package app.oreshkov.rabosh.core

import app.oreshkov.rabosh.variant.Variant
import app.oreshkov.rabosh.variant.VariantBuilder
import app.oreshkov.rabosh.variant.VariantDictionaryBuilder
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * What the manifest records about one sorted segment.
 *
 * The key range is here rather than only in the segment's own footer because the version set uses it
 * to decide which file a lookup could be in, and that decision has to be made without mapping
 * anything: at level 1 and below the ranges do not overlap, so one binary search over this metadata
 * replaces opening every file in the level.
 */
internal class SegmentMetadata(
    val number: Long,
    val fileBytes: Long,
    val smallestKey: Key,
    val largestKey: Key,
    val smallestSequence: Long,
    val largestSequence: Long,
    val entryCount: Long,
) {
    /** Whether this segment's key range overlaps `[from, to]`, either bound absent meaning open. */
    fun overlaps(from: Key?, to: Key?): Boolean {
        if (from != null && largestKey < from) return false
        if (to != null && smallestKey > to) return false
        return true
    }

    override fun toString(): String =
        "SegmentMetadata(#$number, $entryCount entries, $fileBytes bytes, " +
            "$smallestKey..$largestKey, seq $smallestSequence..$largestSequence)"
}

/**
 * Writes one sorted segment.
 *
 * Entries arrive in key order and are packed into blocks of about [StoreOptions.blockSize]; each
 * closed block contributes its last key and its handle to an index block, and every distinct user
 * key contributes to a bloom filter. Index, bloom and dictionary are written after the data, and a
 * footer at the tail says where all three are.
 *
 * **[add] is the funnel.** Every document that enters a segment — from a flush of a memtable or from
 * a compaction of other segments — passes through this one method. That is where the shared
 * dictionary is built, and it is where a [SegmentObserver] is fed: statistics that ride along on a
 * pass the engine already makes cost nothing, and there is exactly one pass to ride on.
 *
 * **The dictionary is one per segment.** Documents arrive carrying dictionaries of their own, and
 * each is re-expressed against this segment's through [VariantBuilder.append]. For the repetitive
 * JSON real systems produce, paying for a field name once per segment instead of once per document
 * is the largest space saving in the design.
 */
internal class SegmentWriter(
    private val path: Path,
    private val number: Long,
    options: StoreOptions,
) : AutoCloseable {

    private val blockSize = options.blockSize
    private val channel = FileChannel.open(
        path,
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE,
    )

    private val dictionary = VariantDictionaryBuilder()
    private val documents = VariantBuilder(dictionary)
    private val data = BlockWriter()
    private val index = BlockWriter()
    private val bloom = if (options.bloomBitsPerKey > 0) BloomFilter.Builder(options.bloomBitsPerKey) else null

    /**
     * The observer's per-segment state, opened here rather than by each caller.
     *
     * A flush and a compaction both produce segments through this class, so hanging the observation
     * off the writer means there is one place where a segment's observation begins and ends, and no
     * way for a new caller to forget it.
     */
    private val observation = Observers.begin(options.segmentObserver, number)
    private val observedKeys = DistinctKeyFilter()

    private var position = 0L
    private var entries = 0L
    private var smallestKey: Key? = null
    private var largestKey: Key? = null
    private var smallestSequence = Long.MAX_VALUE
    private var largestSequence = Long.MIN_VALUE
    private var pendingIndexKey: ByteArray? = null
    private var pendingIndexKeyLength = 0
    private var finished = false

    init {
        write(SegmentFormat.encodeHeader())
    }

    /** Approximate bytes written so far. Used to decide when a compaction should cut its output. */
    val approximateBytes: Long get() = position + data.approximateSize()

    val entryCount: Long get() = entries

    /**
     * Appends one version of one key.
     *
     * @param encodedKey the internal key — user key followed by its tag — as [SegmentFormat.encodeKey]
     *   builds it. Valid over `[0, keyLength)` only; the writer copies what it keeps.
     * @param document the value, or `null` for a tombstone. A tombstone is a stored fact, not an
     *   absence: without one, a key deleted here would still be found in a deeper level.
     */
    fun add(encodedKey: ByteArray, keyLength: Int, document: Variant?) {
        check(!finished) { "segment $number is already finished" }

        val userKeyLength = keyLength - SegmentFormat.TAG_BYTES
        val tag = SegmentFormat.readTag(encodedKey, userKeyLength)
        val kind = SegmentFormat.kindOf(tag, path.fileName.toString(), position)
        require((document != null) == (kind == OperationKind.PUT)) {
            "a $kind entry ${if (document == null) "must" else "must not"} carry a document"
        }

        val value = if (document == null) {
            EMPTY
        } else {
            // Re-expressed against this segment's dictionary. `build` copies, which is one copy per
            // document on a path that already copies it out of the memtable; if it ever matters,
            // it is measurable rather than a guess.
            documents.reset()
            documents.append(document)
            documents.build()
        }

        if (data.approximateSize() + SegmentFormat.ENTRY_HEADER_BYTES + keyLength + value.size > blockSize &&
            !data.isEmpty()
        ) {
            flushDataBlock()
        }

        data.add(encodedKey, keyLength, value, 0, value.size)
        bloom?.add(encodedKey, userKeyLength)

        // The last key of the block is what the index entry is keyed by, so it is remembered here
        // and only written when the block closes.
        pendingIndexKey = rememberKey(encodedKey, keyLength)
        pendingIndexKeyLength = keyLength

        val userKey = SegmentFormat.userKeyOf(encodedKey, keyLength)
        if (smallestKey == null) smallestKey = userKey
        largestKey = userKey
        // Versions of a key are contiguous and newest first, so the first one seen is the one that
        // survives. Observing every version instead would make a document written three times look
        // like three documents.
        if (observation != null && observedKeys.isNewKey(encodedKey, userKeyLength)) {
            observation.observe(userKey, SegmentFormat.sequenceOf(tag), document)
        }
        val sequence = SegmentFormat.sequenceOf(tag)
        smallestSequence = minOf(smallestSequence, sequence)
        largestSequence = maxOf(largestSequence, sequence)
        entries++
    }

    /**
     * Closes the segment and forces it to stable storage.
     *
     * Nothing records a segment until this returns, and this does not return until the bytes are on
     * the medium — a manifest entry pointing at a file the platter does not have would survive a
     * power loss as a store that cannot open.
     */
    fun finish(): SegmentMetadata {
        check(!finished) { "segment $number is already finished" }
        check(entries > 0) { "a segment with no entries must not be written" }
        finished = true

        flushDataBlock()

        val indexHandle = writeBlock(index.finish())
        val bloomHandle = writeBlock(bloom?.finish() ?: ByteArray(BloomFilter.HEADER_BYTES))
        val dictionaryHandle = writeBlock(dictionary.toByteArray())

        write(
            SegmentFormat.encodeFooter(
                SegmentFormat.Footer(
                    dictionary = dictionaryHandle,
                    index = indexHandle,
                    bloom = bloomHandle,
                    entryCount = entries,
                    smallestSequence = smallestSequence,
                    largestSequence = largestSequence,
                ),
            ),
        )
        channel.force(true)
        channel.close()

        // After the force, before the manifest names it: what the observer is told is complete is a
        // file that is actually on the medium.
        observation?.complete(
            SegmentSummary(
                segmentNumber = number,
                entryCount = entries,
                distinctKeyCount = observedKeys.count,
                fileBytes = position,
            ),
        )

        return SegmentMetadata(
            number = number,
            fileBytes = position,
            smallestKey = checkNotNull(smallestKey),
            largestKey = checkNotNull(largestKey),
            smallestSequence = smallestSequence,
            largestSequence = largestSequence,
            entryCount = entries,
        )
    }

    /**
     * Abandons a segment that was never finished, deleting the file.
     *
     * Idempotent, and safe after [finish], where it does nothing. A half-written segment on disk is
     * harmless — no manifest names it, so nothing will read it — but leaving one behind for every
     * failed compaction would fill the directory with rubble.
     */
    override fun close() {
        if (finished) return
        finished = true
        // Before the file goes, so an observer never keeps state for a segment nothing will name.
        observation?.abandon()
        try {
            channel.close()
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private fun flushDataBlock() {
        if (data.isEmpty()) return
        val handle = writeBlock(data.finish())
        val key = checkNotNull(pendingIndexKey)
        val out = ByteWriter(SegmentFormat.HANDLE_BYTES)
        SegmentFormat.writeHandle(out, handle)
        val encoded = out.toByteArray()
        index.add(key, pendingIndexKeyLength, encoded, 0, encoded.size)
        pendingIndexKey = null
    }

    private fun writeBlock(contents: ByteArray): BlockHandle {
        val handle = BlockHandle(position, contents.size)
        write(contents)
        val trailer = ByteWriter(SegmentFormat.BLOCK_TRAILER_BYTES)
        trailer.writeByte(SegmentFormat.BLOCK_TYPE_PLAIN)
        // The checksum covers the type byte as well as the contents: the byte that decides how a
        // block is to be interpreted must not be the one field left unprotected.
        trailer.writeInt(blockChecksum(contents, SegmentFormat.BLOCK_TYPE_PLAIN))
        write(trailer.toByteArray())
        return handle
    }

    private fun write(bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            val written = channel.write(buffer)
            if (written <= 0) throw java.io.IOException("segment write made no progress at $position")
        }
        position += bytes.size
    }

    private fun rememberKey(key: ByteArray, length: Int): ByteArray {
        var buffer = pendingIndexKey
        if (buffer == null || buffer.size < length) buffer = ByteArray(length)
        key.copyInto(buffer, 0, 0, length)
        return buffer
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}

/** CRC32C over a block's contents and its type byte, which is what the trailer stores. */
internal fun blockChecksum(contents: ByteArray, blockType: Int): Int {
    val crc = java.util.zip.CRC32C()
    crc.update(contents, 0, contents.size)
    crc.update(blockType)
    return crc.value.toInt()
}
