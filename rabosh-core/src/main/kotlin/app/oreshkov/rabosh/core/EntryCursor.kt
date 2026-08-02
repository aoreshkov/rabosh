package app.oreshkov.rabosh.core

import app.oreshkov.rabosh.variant.Variant
import app.oreshkov.rabosh.variant.VariantMetadata

/**
 * A cursor over key-ordered versions, whatever holds them.
 *
 * Deliberately not `Iterator<T>`. An `Iterator` has to build an object per step, and a merge asks
 * "which of these heads is smallest" far more often than it consumes one — so the head is exposed
 * as a key buffer and a lazily-decoded document instead. Both are valid only until the cursor
 * moves, which is the contract every caller here already wants.
 *
 * One implementation per source — memtable, segment, merge — and they compose, which is what lets
 * compaction, a range scan and the read path be the same walk over different inputs.
 */
internal interface EntryCursor : AutoCloseable {
    fun valid(): Boolean

    /** The current internal key. Valid over `[0, keyLength)`, and only until the cursor moves. */
    val key: ByteArray

    val keyLength: Int

    /** The current document, or `null` for a tombstone. */
    fun document(): Variant?

    fun seekToFirst()

    /** Positions at the first entry at or after [target]. */
    fun seek(target: ByteArray)

    fun next()

    override fun close(): Unit = Unit
}

/** The user key the cursor is on. Allocates, so it is for callers that keep the key, not comparisons. */
internal fun EntryCursor.userKey(): Key = SegmentFormat.userKeyOf(key, keyLength)

internal fun EntryCursor.tag(): Long = SegmentFormat.readTag(key, keyLength - SegmentFormat.TAG_BYTES)

internal fun EntryCursor.sequence(): Long = SegmentFormat.sequenceOf(tag())

/**
 * A cursor over a memtable.
 *
 * The memtable is a skip list of `(key, sequence)` objects and a segment is a run of encoded key
 * bytes; this is where the two meet. Encoding the key per step allocates, which is the price of
 * having one merge implementation rather than two — and a flush copies every value anyway.
 */
internal class MemtableCursor(private val memtable: Memtable) : EntryCursor {
    private var entries = memtable.entries().iterator()
    private var current: Map.Entry<InternalKey, MemtableValue>? = null
    private var encoded = ByteArray(0)

    override val key: ByteArray get() = encoded
    override var keyLength: Int = 0
        private set

    override fun valid(): Boolean = current != null

    override fun document(): Variant? = when (val value = current?.value) {
        null, MemtableValue.Deleted -> null
        is MemtableValue.Present -> Variant(VariantMetadata.of(value.metadata), value.value)
    }

    override fun seekToFirst() {
        entries = memtable.entries().iterator()
        step()
    }

    override fun seek(target: ByteArray) {
        // A skip list can be positioned directly, but a flush and a compaction both start at the
        // beginning, and a point lookup uses `Memtable.get`. Walking is honest for what remains.
        seekToFirst()
        while (valid() && compareEncodedKeys(encoded, keyLength, target, target.size) < 0) step()
    }

    override fun next() {
        check(valid()) { "next() on an exhausted memtable cursor" }
        step()
    }

    private fun step() {
        if (!entries.hasNext()) {
            current = null
            keyLength = 0
            return
        }
        val entry = entries.next()
        current = entry
        val kind = if (entry.value == MemtableValue.Deleted) OperationKind.DELETE else OperationKind.PUT
        encoded = SegmentFormat.encodeKey(entry.key.userKey, entry.key.sequence, kind)
        keyLength = encoded.size
    }
}

/**
 * A cursor over a whole segment: the index block outside, one data block at a time inside.
 *
 * Blocks are opened as they are reached rather than up front, so a scan of a 8 MiB segment holds
 * one 4 KiB block's worth of reader state at a time and a seek that lands in the last block never
 * touches the others.
 */
internal class SegmentCursor(
    private val table: SegmentTable,
    private val bytes: SegmentBytes,
    indexHandle: BlockHandle,
) : EntryCursor {

    // Checked when the segment was mapped; see `SegmentBytes.readBlockCheckedAtOpen`. Once per cursor
    // rather than once per key, so this is not the hot one — but a scan of a compacted store opens a
    // cursor per segment, and paying for the same checksum a second time is not free either.
    private val index = bytes.readBlockCheckedAtOpen(indexHandle).iterator()
    private var data: BlockIterator? = null

    override val key: ByteArray get() = checkNotNull(data).key
    override val keyLength: Int get() = data?.keyLength ?: 0

    override fun valid(): Boolean = data?.valid() == true

    override fun document(): Variant? {
        val cursor = checkNotNull(data) { "document() on an exhausted segment cursor" }
        return table.materialise(cursor).document
    }

    override fun seekToFirst() {
        index.seekToFirst()
        openBlock()
        skipEmptyBlocks()
    }

    override fun seek(target: ByteArray) {
        index.seek(target)
        openBlock()
        data?.seek(target)
        skipEmptyBlocks()
    }

    override fun next() {
        val cursor = checkNotNull(data) { "next() on an exhausted segment cursor" }
        cursor.next()
        if (!cursor.valid()) {
            if (index.valid()) index.next()
            openBlock()
            skipEmptyBlocks()
        }
    }

    private fun openBlock() {
        data = if (index.valid()) bytes.readBlock(table.readHandleAt(index), "data block").iterator().also {
            it.seekToFirst()
        } else {
            null
        }
    }

    /** A block with no entries cannot be written, but a corrupt index could name one. */
    private fun skipEmptyBlocks() {
        while (data?.valid() == false && index.valid()) {
            index.next()
            openBlock()
        }
    }
}

/**
 * A cursor over several cursors, in one merged key order.
 *
 * The children are consulted linearly rather than through a heap: a merge here has at most a
 * memtable or two plus the files of two adjacent levels, so the constant factor of a linear scan
 * beats the bookkeeping of a heap at every size that occurs. If a level ever holds hundreds of
 * inputs this is the thing to revisit, with a measurement.
 *
 * Order comes from [compareEncodedKeys], so the newest version of a key is emitted first and older
 * versions of it follow — which is exactly what both a reader (stop at the first) and a compaction
 * (drop the rest) need.
 */
internal class MergingCursor(private val children: List<EntryCursor>) : EntryCursor {
    private var currentIndex = -1

    override val key: ByteArray get() = children[currentIndex].key
    override val keyLength: Int get() = children[currentIndex].keyLength

    override fun valid(): Boolean = currentIndex >= 0

    override fun document(): Variant? = children[currentIndex].document()

    override fun seekToFirst() {
        children.forEach(EntryCursor::seekToFirst)
        pickSmallest()
    }

    override fun seek(target: ByteArray) {
        children.forEach { it.seek(target) }
        pickSmallest()
    }

    override fun next() {
        check(valid()) { "next() on an exhausted merging cursor" }
        children[currentIndex].next()
        pickSmallest()
    }

    private fun pickSmallest() {
        var best = -1
        for (index in children.indices) {
            val child = children[index]
            if (!child.valid()) continue
            if (best < 0) {
                best = index
                continue
            }
            val winner = children[best]
            if (compareEncodedKeys(child.key, child.keyLength, winner.key, winner.keyLength) < 0) {
                best = index
            }
        }
        currentIndex = best
    }

    override fun close() {
        var failure: Throwable? = null
        for (child in children) {
            try {
                child.close()
            } catch (thrown: Throwable) {
                if (failure == null) failure = thrown else failure.addSuppressed(thrown)
            }
        }
        failure?.let { throw it }
    }
}
