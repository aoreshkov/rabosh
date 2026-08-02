package app.oreshkov.rabosh.catalog

/**
 * A 64-bit mixing hash over a byte range.
 *
 * Derived from MurmurHash3's 64-bit mixing and finalisation constants. As with the bloom filter's
 * hash in `rabosh-core`, **this is permanent**: a cardinality estimator's registers are a function
 * of the values this returns, and those registers are written into every `.cat` sidecar. Changing
 * the function would change what an existing sidecar means, and the failure would be silent — an
 * estimate that is merely wrong rather than a file that will not read.
 *
 * It is a copy of the core's rather than a call into it because `Hash` there is `internal`, and
 * `internal` does not cross a module boundary. Publishing it out of the storage core to save thirty
 * lines here would put a hashing utility in the ABI of the module that owns the LSM tree, which is
 * the worse trade — the same reasoning the core itself records for duplicating the byte accessors
 * out of `rabosh-variant`. The two copies are independently permanent and need not stay equal.
 *
 * The JDK offers nothing usable. `Arrays.hashCode` is 32 bits with poor avalanche, and a
 * HyperLogLog built on a hash with weak high bits does not degrade gracefully — the register index
 * comes from the top bits, so a biased hash puts everything in a few registers and the estimate is
 * wrong by an order of magnitude rather than by a percent.
 */
internal object SketchHash {
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
