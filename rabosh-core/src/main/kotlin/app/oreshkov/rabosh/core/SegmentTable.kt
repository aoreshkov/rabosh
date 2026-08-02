package app.oreshkov.rabosh.core

import app.oreshkov.rabosh.variant.Variant
import app.oreshkov.rabosh.variant.VariantMetadata
import java.lang.foreign.Arena
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicInteger

/**
 * What a source knows about a key.
 *
 * A `null` [document] is a tombstone — the source knows the key was deleted — and that is a
 * different answer from the source not knowing anything, which is a `null` [Found]. Blurring the
 * two is how a deleted document reappears out of a deeper level.
 */
internal class Found(val document: Variant?)

/**
 * An open sorted segment: a mapped file, its bloom filter, its block index and its dictionary.
 *
 * **The mapping is owned by an [Arena], and the channel is closed the moment the mapping exists.**
 * Both halves matter. A mapping outlives the channel that produced it, so holding the channel would
 * be one file descriptor per segment for no reason. And an arena unmaps *when it is closed* rather
 * than when a collector notices — which is what lets compaction delete a file it has just replaced.
 * On Windows that is not an optimisation: a mapped file cannot be deleted at all, so a leaked
 * mapping is a store that accumulates every segment it ever wrote.
 *
 * **Reference counted, because a reader may be inside a file compaction has finished with.** A
 * [Version] holds a reference for as long as it is installed or a snapshot pins it; the file is
 * unmapped and deleted when the last one goes. Nothing here waits on a lock: a reader that already
 * has a reference is safe by construction.
 */
internal class SegmentTable private constructor(
    val metadata: SegmentMetadata,
    val path: Path,
    private val arena: Arena,
    private val bytes: SegmentBytes,
    private val footer: SegmentFormat.Footer,
    /** The one dictionary every document in this segment is encoded against. */
    val dictionary: VariantMetadata,
    /** Run once the mapping is gone, which is when the file becomes safe to delete. */
    private val onUnmapped: ((SegmentTable) -> Unit)?,
) : AutoCloseable {

    private val references = AtomicInteger(1)

    val number: Long get() = metadata.number

    /**
     * Takes a reference, or returns `false` if the segment has already been released for the last
     * time.
     *
     * The compare-and-set loop rather than a bare increment: a plain increment from zero would
     * resurrect a mapping whose arena is closed, and the read that followed would fault rather than
     * fail.
     */
    fun acquire(): Boolean {
        while (true) {
            val current = references.get()
            if (current == 0) return false
            if (references.compareAndSet(current, current + 1)) return true
        }
    }

    fun release() {
        if (references.decrementAndGet() == 0) {
            // Unmap first, then tell the owner. On Windows the file cannot be deleted while it is
            // mapped, so the order here is what makes deletion possible at all.
            arena.close()
            onUnmapped?.invoke(this)
        }
    }

    override fun close(): Unit = release()

    /** Whether this segment is still mapped. For tests that assert an unmap actually happened. */
    val isOpen: Boolean get() = references.get() > 0

    /**
     * The newest version of [key] at or below [maxSequence], or `null` if this segment has none.
     *
     * Three steps, each cheaper to fail at than the one after it: the bloom filter answers most
     * negative lookups without touching the index; the index block names the one data block that
     * could hold the key; the data block is searched. A returned tombstone stops the search — an
     * older segment may well hold the key, and finding it there would be resurrecting a deletion.
     */
    fun get(key: Key, maxSequence: Long): Found? {
        if (key < metadata.smallestKey || key > metadata.largestKey) return null
        if (footer.bloom.length > BloomFilter.HEADER_BYTES &&
            !BloomFilter.mayContain(bytes, footer.bloom, key)
        ) {
            return null
        }

        val target = SegmentFormat.seekKey(key, maxSequence)
        // Checked when this segment was mapped, and not again here: re-verifying it made every
        // lookup pay a CRC32C over one entry per data block in the segment. See `readBlockCheckedAtOpen`.
        val indexCursor = bytes.readBlockCheckedAtOpen(footer.index).iterator()
        indexCursor.seek(target)
        if (!indexCursor.valid()) return null

        val handle = readHandleAt(indexCursor)
        val dataCursor = bytes.readBlock(handle, "data block").iterator()
        dataCursor.seek(target)
        if (!dataCursor.valid()) return null

        val userKeyLength = dataCursor.keyLength - SegmentFormat.TAG_BYTES
        if (userKeyLength != key.size ||
            !java.util.Arrays.equals(dataCursor.key, 0, userKeyLength, key.raw, 0, key.size)
        ) {
            return null
        }
        return materialise(dataCursor)
    }

    /** A cursor over every version in this segment, in key order. */
    fun cursor(): EntryCursor = SegmentCursor(this, bytes, footer.index)

    internal fun materialise(cursor: BlockIterator): Found {
        val tag = SegmentFormat.readTag(cursor.key, cursor.keyLength - SegmentFormat.TAG_BYTES)
        return when (SegmentFormat.kindOf(tag, bytes.file, cursor.valueOffset)) {
            OperationKind.DELETE -> TOMBSTONE
            OperationKind.PUT -> Found(document(cursor))
        }
    }

    /**
     * The document a cursor is sitting on, as a view over the mapping.
     *
     * The slice is the bound: a `Variant` reads its own extents, and given the whole file it would
     * validate a corrupt length against the file rather than against the entry. Sliced to the
     * entry, a document that claims to be longer than it is cannot reach into its neighbour.
     */
    internal fun document(cursor: BlockIterator): Variant = Variant(
        dictionary,
        bytes.segment.asSlice(cursor.valueOffset, cursor.valueLength.toLong()),
        0,
    )

    internal fun readHandleAt(cursor: BlockIterator): BlockHandle {
        if (cursor.valueLength != SegmentFormat.HANDLE_BYTES) {
            bytes.corrupt(
                "index entry carries ${cursor.valueLength} byte(s), not a ${SegmentFormat.HANDLE_BYTES}-byte handle",
                cursor.valueOffset,
            )
        }
        return SegmentFormat.readHandle(bytes, cursor.valueOffset, "block handle")
    }

    override fun toString(): String = "SegmentTable(${path.fileName}, $metadata)"

    companion object {
        private val TOMBSTONE = Found(null)

        /**
         * Maps [path] and validates everything a later read will trust.
         *
         * The header, the footer and the three metadata blocks are checked once, here, rather than
         * on every lookup. Data blocks are checked as they are read, because there is no "once" for
         * them — a segment may hold thousands and a point lookup touches one.
         */
        fun open(
            path: Path,
            metadata: SegmentMetadata,
            onUnmapped: ((SegmentTable) -> Unit)? = null,
        ): SegmentTable {
            val arena = Arena.ofShared()
            try {
                // The mapping outlives the channel, so the channel is not kept: one descriptor per
                // open segment would be a limit long before memory was.
                val segment = FileChannel.open(path, StandardOpenOption.READ).use { channel ->
                    channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), arena)
                }
                val bytes = SegmentBytes(segment, path.fileName.toString())
                SegmentFormat.checkHeader(bytes)
                val footer = SegmentFormat.readFooter(bytes)
                bytes.verifyBlock(footer.index, "index block")
                bytes.verifyBlock(footer.bloom, "bloom block")
                bytes.verifyBlock(footer.dictionary, "dictionary block")
                // Reading the dictionary validates its whole offset list, so no later field lookup
                // can walk outside it. One pass per segment, paid at open.
                val dictionary = VariantMetadata.read(segment, footer.dictionary.offset)
                return SegmentTable(metadata, path, arena, bytes, footer, dictionary, onUnmapped)
            } catch (failure: Throwable) {
                arena.close()
                throw failure
            }
        }
    }
}
