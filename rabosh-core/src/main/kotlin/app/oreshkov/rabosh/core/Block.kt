package app.oreshkov.rabosh.core

import java.lang.foreign.MemorySegment

/**
 * Builds one block of a segment: a run of key-ordered entries, prefix-compressed against restart
 * points.
 *
 * Every entry stores only the part of its key that differs from the one before it. Once every
 * [SegmentFormat.RESTART_INTERVAL] entries a *restart point* stores a whole key and its offset is
 * recorded in an array at the end of the block. That array is what makes the block searchable: a
 * reader bisects it, then walks at most an interval's worth of entries. Without restarts a
 * prefix-compressed block could only be read from the front.
 *
 * The same builder serves data blocks and the index block, because an index entry is only a key
 * whose value happens to be a [BlockHandle].
 */
internal class BlockWriter(private val restartInterval: Int = SegmentFormat.RESTART_INTERVAL) {
    private val out = ByteWriter()
    private val restarts = ArrayList<Int>()
    private var lastKey = ByteArray(0)
    private var lastKeyLength = 0
    private var sinceRestart = 0

    var entryCount: Int = 0
        private set

    fun isEmpty(): Boolean = entryCount == 0

    /**
     * Bytes this block would occupy if finished now, trailer excluded.
     *
     * Used to decide when a block is full, so it has to account for the restart array that has not
     * been written yet — a block sized by its entries alone overshoots by four bytes per restart.
     */
    fun approximateSize(): Int = out.size + (restarts.size + 1) * 4 + 4

    fun add(key: ByteArray, keyLength: Int, value: ByteArray, valueOffset: Int, valueLength: Int) {
        startEntry(key, keyLength, valueLength)
        out.write(value, valueOffset, valueLength)
    }

    /** Adds an entry whose value is still in a mapped segment; used by compaction. */
    fun add(key: ByteArray, keyLength: Int, value: MemorySegment, valueOffset: Long, valueLength: Int) {
        startEntry(key, keyLength, valueLength)
        out.write(value, valueOffset, valueLength)
    }

    private fun startEntry(key: ByteArray, keyLength: Int, valueLength: Int) {
        require(entryCount == 0 || compareEncodedKeys(lastKey, lastKeyLength, key, keyLength) < 0) {
            "block entries must strictly ascend"
        }
        val shared = if (sinceRestart == restartInterval || entryCount == 0) {
            restarts += out.size
            sinceRestart = 0
            0
        } else {
            sharedPrefix(key, keyLength)
        }

        out.writeInt(shared)
        out.writeInt(keyLength - shared)
        out.writeInt(valueLength)
        out.write(key, shared, keyLength - shared)

        if (lastKey.size < keyLength) lastKey = ByteArray(keyLength)
        key.copyInto(lastKey, 0, 0, keyLength)
        lastKeyLength = keyLength
        sinceRestart++
        entryCount++
    }

    private fun sharedPrefix(key: ByteArray, keyLength: Int): Int {
        val limit = minOf(lastKeyLength, keyLength)
        var shared = 0
        while (shared < limit && lastKey[shared] == key[shared]) shared++
        return shared
    }

    /** Closes the block and returns its contents. The builder is reusable afterwards. */
    fun finish(): ByteArray {
        for (restart in restarts) out.writeInt(restart)
        out.writeInt(restarts.size)
        val contents = out.toByteArray()
        reset()
        return contents
    }

    fun reset() {
        out.clear()
        restarts.clear()
        lastKeyLength = 0
        sinceRestart = 0
        entryCount = 0
    }
}

/**
 * Reads a block out of a mapped segment.
 *
 * Values are handed back as an offset and a length into the mapping, never copied — reading one
 * field of a large document should cost the field, and that only holds if the document was not
 * copied to reach it. Keys are the exception and have to be: prefix compression means a key exists
 * only as the concatenation of what came before it, so the iterator rebuilds each one into a buffer
 * it reuses.
 *
 * That is also why key comparison here is `Arrays.compareUnsigned` over a heap buffer rather than
 * `MemorySegment.mismatch` over the mapping: after reconstruction there is nothing in the mapping
 * left to compare against.
 */
internal class BlockReader(
    private val bytes: SegmentBytes,
    private val base: Long,
    private val length: Int,
) {
    private val restartCount: Int
    private val restartsAt: Long

    init {
        if (length < 4) bytes.corrupt("block of $length byte(s) cannot hold a restart count", base)
        restartCount = bytes.length(base + length - 4, "restart count", (length / 4).toLong())
        restartsAt = base + length - 4L - restartCount.toLong() * 4
        if (restartsAt < base) {
            bytes.corrupt("block claims $restartCount restart point(s), more than it can hold", base)
        }
        if (restartCount == 0 && restartsAt != base) {
            bytes.corrupt("an empty block cannot hold entries", base)
        }
    }

    fun iterator(): BlockIterator = BlockIterator(bytes, base, restartsAt, restartsAt, restartCount)

    /** Whether the block holds no entries at all. Only ever true of a segment with no documents. */
    fun isEmpty(): Boolean = restartCount == 0

    /** Number of restart points, which is the number of whole keys the block stores. */
    val restartPoints: Int get() = restartCount
}

/**
 * A cursor over one block's entries.
 *
 * Not an `Iterator<T>`: the whole point is that the current entry is *not* materialised into an
 * object. `key` is a buffer the cursor overwrites and `value` is a region of the mapping, so both
 * are valid only until the next move — which is exactly the contract a merge over several blocks
 * wants, since it compares heads far more often than it consumes them.
 */
internal class BlockIterator(
    private val bytes: SegmentBytes,
    private val base: Long,
    private val entriesEnd: Long,
    private val restartsAt: Long,
    private val restartCount: Int,
) {
    private var position: Long = base
    private var nextPosition: Long = base

    private var keyBuffer = ByteArray(64)

    /** Length of the reconstructed key in [key]; `0` when the cursor is not on an entry. */
    var keyLength: Int = 0
        private set

    var valueOffset: Long = 0
        private set

    var valueLength: Int = 0
        private set

    private var positioned = false

    /** The current key. Valid over `[0, keyLength)` and only until the cursor moves. */
    val key: ByteArray get() = keyBuffer

    val segment: MemorySegment get() = bytes.segment

    fun valid(): Boolean = positioned

    fun seekToFirst() {
        if (restartCount == 0) {
            positioned = false
            return
        }
        seekToRestart(0)
        advance()
    }

    /** Positions the cursor at the first entry whose key is at or after [target]. */
    fun seek(target: ByteArray, targetLength: Int = target.size) {
        if (restartCount == 0) {
            positioned = false
            return
        }
        // Bisect the restart array for the last restart whose key does not exceed the target, then
        // walk. A restart key is stored whole, so reading one costs no reconstruction.
        var low = 0
        var high = restartCount - 1
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            seekToRestart(middle)
            advance()
            if (!positioned) {
                high = middle - 1
                continue
            }
            if (compareEncodedKeys(keyBuffer, keyLength, target, targetLength) <= 0) low = middle else high = middle - 1
        }
        seekToRestart(low)
        advance()
        while (positioned && compareEncodedKeys(keyBuffer, keyLength, target, targetLength) < 0) {
            advance()
        }
    }

    fun next() {
        check(positioned) { "next() on an exhausted block cursor" }
        advance()
    }

    private fun seekToRestart(index: Int) {
        val offset = bytes.length(restartsAt + index.toLong() * 4, "restart offset $index", entriesEnd - base)
        nextPosition = base + offset
        keyLength = 0
        positioned = false
    }

    private fun advance() {
        position = nextPosition
        if (position >= entriesEnd) {
            positioned = false
            keyLength = 0
            return
        }
        val available = entriesEnd - position
        if (available < SegmentFormat.ENTRY_HEADER_BYTES) {
            bytes.corrupt("block entry header runs past the end of the block", position)
        }
        // The shared length counts bytes of the *previous* key, not bytes of this entry, so the
        // remaining-bytes bound that fits the other two fields does not fit this one. No key can be
        // longer than the block that holds it, and the exact check against `keyLength` follows.
        val shared = bytes.length(position, "shared key length", entriesEnd - base)
        val unshared = bytes.length(position + 4, "unshared key length", available)
        val valueSize = bytes.length(position + 8, "value length", available)
        val keyAt = position + SegmentFormat.ENTRY_HEADER_BYTES
        val valueAt = keyAt + unshared
        if (valueAt + valueSize > entriesEnd) {
            bytes.corrupt("block entry runs past the end of the block", position)
        }
        if (shared > keyLength) {
            // A shared length longer than the key it shares with is the shape a bit flip in this
            // field takes, and it would otherwise read uninitialised bytes out of the buffer.
            bytes.corrupt("entry shares $shared byte(s) with a key of $keyLength", position)
        }
        val total = shared + unshared
        if (total < SegmentFormat.TAG_BYTES) {
            bytes.corrupt("block entry key of $total byte(s) is too short to carry a tag", position)
        }
        if (keyBuffer.size < total) keyBuffer = keyBuffer.copyOf(maxOf(total, keyBuffer.size * 2))
        bytes.copyInto(keyAt, keyBuffer, shared, unshared, "entry key")
        keyLength = total
        valueOffset = valueAt
        valueLength = valueSize
        nextPosition = valueAt + valueSize
        positioned = true
    }
}
