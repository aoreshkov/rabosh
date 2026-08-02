package app.oreshkov.rabosh.core

/**
 * A 64-bit mixing hash over a byte range.
 *
 * Derived from MurmurHash3's 64-bit mixing and finalisation constants — this is not a claim of
 * byte-compatibility with any reference implementation, and it must not become one: **the values
 * this produces are baked into every bloom filter ever written**, so changing the function changes
 * what old segments mean. It is fixed at the same moment the segment format is.
 *
 * The JDK offers nothing usable here. `Arrays.hashCode` is 32 bits with poor avalanche, and a bloom
 * filter built on it degrades to a much higher false-positive rate than its bit budget promises —
 * which does not corrupt an answer, only quietly removes the reason the filter exists.
 */
internal object Hash {
    private const val C1 = -0x783c846eeebdac2bL // 0x87c37b91114253d5
    private const val C2 = 0x4cf5ad432745937fL
    private const val SEED = -0x61c8864680b583ebL // 0x9e3779b97f4a7c15, the golden-ratio constant

    fun hash64(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Long {
        var h = SEED xor (length.toLong() * C1)
        var index = offset
        val end = offset + length
        while (end - index >= 8) {
            h = mix(h, readLong(bytes, index))
            index += 8
        }
        if (index < end) {
            var tail = 0L
            var shift = 0
            while (index < end) {
                tail = tail or ((bytes[index].toLong() and 0xFF) shl shift)
                shift += 8
                index++
            }
            h = mix(h, tail)
        }
        return finalise(h)
    }

    private fun mix(hash: Long, block: Long): Long {
        var k = block
        k *= C1
        k = java.lang.Long.rotateLeft(k, 31)
        k *= C2
        var h = hash xor k
        h = java.lang.Long.rotateLeft(h, 27)
        return h * 5 + 0x52dce729
    }

    private fun finalise(hash: Long): Long {
        var h = hash
        h = h xor (h ushr 33)
        h *= -0x7ee3623a03d3c83fL // 0xff51afd7ed558ccd
        h = h xor (h ushr 33)
        h *= -0x3b314601e57a13adL // 0xc4ceb9fe1a85ec53
        h = h xor (h ushr 33)
        return h
    }

    private fun readLong(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 0 until 8) {
            value = value or ((bytes[offset + index].toLong() and 0xFF) shl (8 * index))
        }
        return value
    }
}

/**
 * The per-segment bloom filter: does this segment possibly hold this key?
 *
 * ```
 * bloom := bitsPerKey:u32 hashCount:u32 bitCount:u32 keyCount:u32 bits[ceil(bitCount / 8)]
 * ```
 *
 * **It hashes user keys, not internal keys.** Every version of a key therefore costs one entry and
 * a `get` probes once. Hashing internal keys would mean hashing the sequence number too, and a
 * lookup does not know which sequence it is looking for — the filter would answer a question nobody
 * asks.
 *
 * **One filter per segment rather than per block.** The read path's expensive step is *touching the
 * segment at all*: an L0 lookup consults every file in the level, and the filter is what turns most
 * of those into a probe of a few hundred bytes rather than an index search plus a block read. A
 * per-block filter would refine what happens after that decision, which is the cheaper half.
 *
 * **Only false positives are possible, never a false negative.** That asymmetry is what makes the
 * filter safe to consult at all: a "no" skips the segment, so a wrong "no" would lose a document.
 * The property test asserts it directly rather than trusting the arithmetic.
 *
 * The two probes per key come from one hash by double hashing — `h`, then a rotation of it as the
 * stride. Computing k independent hashes would cost k times as much for no measurable difference in
 * the false-positive rate, which is a result old enough to rely on.
 */
internal object BloomFilter {
    const val HEADER_BYTES: Int = 16

    /** Smallest filter written, so an almost-empty segment still has a usable one. */
    private const val MIN_BITS = 64

    /** Ten bits per key is a ~1% false-positive rate: the knee of the curve. */
    const val DEFAULT_BITS_PER_KEY: Int = 10

    private const val LN2 = 0.6931471805599453

    fun hashCountFor(bitsPerKey: Int): Int = (bitsPerKey * LN2).let { Math.round(it).toInt() }.coerceIn(1, 30)

    /**
     * Accumulates key hashes and encodes the filter.
     *
     * Hashes are kept rather than bits, because the bit count depends on how many keys there turn
     * out to be and that is not known until the segment is complete. Eight bytes per distinct key
     * during a flush is a cost the memtable already dwarfs.
     */
    class Builder(private val bitsPerKey: Int = DEFAULT_BITS_PER_KEY) {
        private var hashes = LongArray(1024)
        private var count = 0
        private var lastKey = ByteArray(0)
        private var lastKeyLength = -1

        init {
            require(bitsPerKey in 1..64) { "bitsPerKey must be in 1..64, was $bitsPerKey" }
        }

        val keyCount: Int get() = count

        /**
         * Adds the user key in `[0, length)` of [key], skipping it when it repeats the previous one.
         *
         * Keys arrive in order, so consecutive versions of one key are adjacent and the check is
         * one comparison. Without it a segment holding many versions of few keys would size its
         * filter for the versions and waste most of it.
         *
         * Takes bytes rather than a [Key] because the caller has an encoded internal key in hand and
         * this runs once per entry on every flush and every compaction; extracting a [Key] to hash
         * it would allocate for every version of every document in the store.
         */
        fun add(key: ByteArray, length: Int) {
            if (length == lastKeyLength && java.util.Arrays.equals(lastKey, 0, length, key, 0, length)) return
            if (lastKey.size < length) lastKey = ByteArray(length)
            key.copyInto(lastKey, 0, 0, length)
            lastKeyLength = length
            if (count == hashes.size) hashes = hashes.copyOf(hashes.size * 2)
            hashes[count++] = Hash.hash64(key, 0, length)
        }

        fun add(key: Key): Unit = add(key.raw, key.size)

        fun finish(): ByteArray {
            val bits = maxOf(MIN_BITS, count * bitsPerKey).let { (it + 7) / 8 * 8 }
            val hashCount = hashCountFor(bitsPerKey)
            val out = ByteWriter(HEADER_BYTES + bits / 8)
            out.writeInt(bitsPerKey)
            out.writeInt(hashCount)
            out.writeInt(bits)
            out.writeInt(count)
            val words = ByteArray(bits / 8)
            for (index in 0 until count) {
                setBits(words, bits, hashCount, hashes[index])
            }
            out.write(words)
            return out.toByteArray()
        }
    }

    private fun setBits(words: ByteArray, bits: Int, hashCount: Int, hash: Long) {
        var probe = hash
        val stride = java.lang.Long.rotateLeft(hash, 32)
        for (round in 0 until hashCount) {
            val bit = bitIndex(probe, bits)
            words[bit ushr 3] = (words[bit ushr 3].toInt() or (1 shl (bit and 7))).toByte()
            probe += stride
        }
    }

    /**
     * Which bit a probe selects.
     *
     * One function, called by both the writer and the reader, because these are the two halves of
     * the same decision: a difference between them would show up as a false *negative*, the one
     * failure a bloom filter must never have, and only on the keys where the arithmetic happened to
     * differ. Masking the sign bit rather than shifting keeps 63 bits of the hash in play.
     */
    private fun bitIndex(probe: Long, bits: Int): Int = ((probe and Long.MAX_VALUE) % bits).toInt()

    /**
     * Whether [key] may be in the segment whose filter lives at [handle].
     *
     * `false` is a definitive answer and skips the segment. `true` means only that the block index
     * has to be consulted.
     */
    fun mayContain(bytes: SegmentBytes, handle: BlockHandle, key: Key): Boolean {
        if (handle.length < HEADER_BYTES) {
            bytes.corrupt("bloom filter of ${handle.length} byte(s) is too short for its header", handle.offset)
        }
        val hashCount = bytes.length(handle.offset + 4, "bloom hash count", 30L)
        // The bit count is bounded by the bits its own block can hold, not by the file's bytes —
        // the generic length check would compare a bit count against a byte count and reject every
        // filter denser than one bit per byte.
        val bitLimit = (handle.length - HEADER_BYTES).toLong() * 8
        val bits = bytes.length(handle.offset + 8, "bloom bit count", bitLimit)
        val keyCount = bytes.length(handle.offset + 12, "bloom key count", Int.MAX_VALUE.toLong())
        if (keyCount == 0) return false
        if (bits == 0 || hashCount == 0) {
            bytes.corrupt("bloom filter holds $keyCount key(s) in $bits bit(s)", handle.offset)
        }
        val base = handle.offset + HEADER_BYTES
        var probe = Hash.hash64(key.raw)
        val stride = java.lang.Long.rotateLeft(probe, 32)
        for (round in 0 until hashCount) {
            val bit = bitIndex(probe, bits)
            val byte = bytes.u8(base + (bit ushr 3), "bloom bits")
            if (byte and (1 shl (bit and 7)) == 0) return false
            probe += stride
        }
        return true
    }
}
