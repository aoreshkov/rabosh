package app.oreshkov.rabosh.index

/**
 * The four set operations, one block at a time.
 *
 * Both operands are materialised and any run list among them is expanded first, so every operation
 * has four cases — array/array, array/bitset, bitset/array, bitset/bitset — rather than sixteen. That
 * is the trade `RunContainer` documents: correctness over nine hand-written pairings per operation,
 * with the cost bounded at one 8 KB block per operand however large the bitmaps are, and a run-native
 * fast path left as an additive change once phase 9 has a benchmark that asks for one.
 *
 * **No operation mutates an operand.** `materialise` returns the receiver itself for a heap container,
 * so a mutating implementation would corrupt the bitmap it was reading — every function here builds
 * its result into fresh storage.
 *
 * A bitset result whose cardinality fits an array is handed back as an array. Not for the file, which
 * `normalise` settles on the way out, but for the heap: a chain of operations that each narrowed the
 * result would otherwise hold 8 KB per block for a handful of values.
 */
internal object ContainerAlgebra {

    fun and(left: ReadableContainer, right: ReadableContainer): Container {
        val a = operand(left)
        val b = operand(right)
        return when {
            a is ArrayContainer && b is ArrayContainer -> andArrays(a, b)
            a is ArrayContainer && b is BitsetContainer -> andArrayBitset(a, b)
            a is BitsetContainer && b is ArrayContainer -> andArrayBitset(b, a)
            else -> andBitsets(a as BitsetContainer, b as BitsetContainer)
        }
    }

    fun or(left: ReadableContainer, right: ReadableContainer): Container {
        val a = operand(left)
        val b = operand(right)
        return when {
            a is ArrayContainer && b is ArrayContainer -> orArrays(a, b)
            a is ArrayContainer && b is BitsetContainer -> orArrayBitset(a, b)
            a is BitsetContainer && b is ArrayContainer -> orArrayBitset(b, a)
            else -> orBitsets(a as BitsetContainer, b as BitsetContainer)
        }
    }

    fun andNot(left: ReadableContainer, right: ReadableContainer): Container {
        val a = operand(left)
        val b = operand(right)
        return when {
            a is ArrayContainer && b is ArrayContainer -> andNotArrays(a, b)
            a is ArrayContainer && b is BitsetContainer -> andNotArrayBitset(a, b)
            a is BitsetContainer && b is ArrayContainer -> andNotBitsetArray(a, b)
            else -> andNotBitsets(a as BitsetContainer, b as BitsetContainer)
        }
    }

    fun xor(left: ReadableContainer, right: ReadableContainer): Container {
        val a = operand(left)
        val b = operand(right)
        return when {
            a is ArrayContainer && b is ArrayContainer -> xorArrays(a, b)
            a is ArrayContainer && b is BitsetContainer -> xorArrayBitset(a, b)
            a is BitsetContainer && b is ArrayContainer -> xorArrayBitset(b, a)
            else -> xorBitsets(a as BitsetContainer, b as BitsetContainer)
        }
    }

    // --- and ------------------------------------------------------------------------------------

    private fun andArrays(a: ArrayContainer, b: ArrayContainer): Container {
        val left = a.backing()
        val right = b.backing()
        val out = CharArray(minOf(a.cardinality, b.cardinality))
        var i = 0
        var j = 0
        var written = 0
        while (i < a.cardinality && j < b.cardinality) {
            val x = left[i].code
            val y = right[j].code
            when {
                x < y -> i++
                x > y -> j++
                else -> {
                    out[written++] = left[i]
                    i++
                    j++
                }
            }
        }
        return ArrayContainer.ofSorted(out, written)
    }

    private fun andArrayBitset(array: ArrayContainer, bitset: BitsetContainer): Container {
        val values = array.backing()
        val out = CharArray(array.cardinality)
        var written = 0
        for (index in 0 until array.cardinality) {
            val value = values[index]
            if (bitset.contains(value.code)) out[written++] = value
        }
        return ArrayContainer.ofSorted(out, written)
    }

    private fun andBitsets(a: BitsetContainer, b: BitsetContainer): Container {
        val words = LongArray(BitmapFormat.BITSET_WORDS)
        var count = 0
        for (index in words.indices) {
            val word = a.word(index) and b.word(index)
            words[index] = word
            count += word.countOneBits()
        }
        return result(words, count)
    }

    // --- or -------------------------------------------------------------------------------------

    private fun orArrays(a: ArrayContainer, b: ArrayContainer): Container {
        if (a.cardinality + b.cardinality > BitmapFormat.ARRAY_MAX_CARDINALITY) {
            val words = LongArray(BitmapFormat.BITSET_WORDS)
            var count = fill(words, a)
            count += fill(words, b)
            return result(words, count)
        }
        val left = a.backing()
        val right = b.backing()
        val out = CharArray(a.cardinality + b.cardinality)
        var i = 0
        var j = 0
        var written = 0
        while (i < a.cardinality && j < b.cardinality) {
            val x = left[i].code
            val y = right[j].code
            when {
                x < y -> out[written++] = left[i++]
                x > y -> out[written++] = right[j++]
                else -> {
                    out[written++] = left[i]
                    i++
                    j++
                }
            }
        }
        while (i < a.cardinality) out[written++] = left[i++]
        while (j < b.cardinality) out[written++] = right[j++]
        return ArrayContainer.ofSorted(out, written)
    }

    private fun orArrayBitset(array: ArrayContainer, bitset: BitsetContainer): Container {
        val words = bitset.backing().copyOf()
        // `fill` reports only the bits it newly set, so the count stays exact without a second pass.
        val count = bitset.cardinality + fill(words, array)
        return result(words, count)
    }

    private fun orBitsets(a: BitsetContainer, b: BitsetContainer): Container {
        val words = LongArray(BitmapFormat.BITSET_WORDS)
        var count = 0
        for (index in words.indices) {
            val word = a.word(index) or b.word(index)
            words[index] = word
            count += word.countOneBits()
        }
        return result(words, count)
    }

    // --- andNot ---------------------------------------------------------------------------------

    private fun andNotArrays(a: ArrayContainer, b: ArrayContainer): Container {
        val left = a.backing()
        val right = b.backing()
        val out = CharArray(a.cardinality)
        var i = 0
        var j = 0
        var written = 0
        while (i < a.cardinality && j < b.cardinality) {
            val x = left[i].code
            val y = right[j].code
            when {
                x < y -> out[written++] = left[i++]
                x > y -> j++
                else -> {
                    i++
                    j++
                }
            }
        }
        while (i < a.cardinality) out[written++] = left[i++]
        return ArrayContainer.ofSorted(out, written)
    }

    private fun andNotArrayBitset(array: ArrayContainer, bitset: BitsetContainer): Container {
        val values = array.backing()
        val out = CharArray(array.cardinality)
        var written = 0
        for (index in 0 until array.cardinality) {
            val value = values[index]
            if (!bitset.contains(value.code)) out[written++] = value
        }
        return ArrayContainer.ofSorted(out, written)
    }

    private fun andNotBitsetArray(bitset: BitsetContainer, array: ArrayContainer): Container {
        val words = bitset.backing().copyOf()
        var count = bitset.cardinality
        val values = array.backing()
        for (index in 0 until array.cardinality) {
            if (clearBit(words, values[index].code)) count--
        }
        return result(words, count)
    }

    private fun andNotBitsets(a: BitsetContainer, b: BitsetContainer): Container {
        val words = LongArray(BitmapFormat.BITSET_WORDS)
        var count = 0
        for (index in words.indices) {
            val word = a.word(index) and b.word(index).inv()
            words[index] = word
            count += word.countOneBits()
        }
        return result(words, count)
    }

    // --- xor ------------------------------------------------------------------------------------

    private fun xorArrays(a: ArrayContainer, b: ArrayContainer): Container {
        if (a.cardinality + b.cardinality > BitmapFormat.ARRAY_MAX_CARDINALITY) {
            val words = LongArray(BitmapFormat.BITSET_WORDS)
            var count = fill(words, a)
            count += flip(words, b)
            return result(words, count)
        }
        val left = a.backing()
        val right = b.backing()
        val out = CharArray(a.cardinality + b.cardinality)
        var i = 0
        var j = 0
        var written = 0
        while (i < a.cardinality && j < b.cardinality) {
            val x = left[i].code
            val y = right[j].code
            when {
                x < y -> out[written++] = left[i++]
                x > y -> out[written++] = right[j++]
                else -> {
                    i++
                    j++
                }
            }
        }
        while (i < a.cardinality) out[written++] = left[i++]
        while (j < b.cardinality) out[written++] = right[j++]
        return ArrayContainer.ofSorted(out, written)
    }

    private fun xorArrayBitset(array: ArrayContainer, bitset: BitsetContainer): Container {
        val words = bitset.backing().copyOf()
        val count = bitset.cardinality + flip(words, array)
        return result(words, count)
    }

    private fun xorBitsets(a: BitsetContainer, b: BitsetContainer): Container {
        val words = LongArray(BitmapFormat.BITSET_WORDS)
        var count = 0
        for (index in words.indices) {
            val word = a.word(index) xor b.word(index)
            words[index] = word
            count += word.countOneBits()
        }
        return result(words, count)
    }

    // --- shared ---------------------------------------------------------------------------------

    /** A heap container that can be read directly and combined: never a run list. */
    private fun operand(container: ReadableContainer): Container {
        val heap = container.materialise()
        return if (heap is RunContainer) heap.expand() else heap
    }

    /**
     * A bitset result, handed back as an array when the values fit one.
     *
     * The encoding a *file* gets is settled by `normalise`; this is about the heap, so that a sequence
     * of narrowing operations does not keep 8 KB a block for a handful of values.
     */
    private fun result(words: LongArray, count: Int): Container {
        val bitset = BitsetContainer.ofWords(words, count)
        return if (count <= BitmapFormat.ARRAY_MAX_CARDINALITY) bitset.toArray() else bitset
    }

    /** Sets every value of [array] in [words], returning how many bits were not already set. */
    private fun fill(words: LongArray, array: ArrayContainer): Int {
        val values = array.backing()
        var added = 0
        for (index in 0 until array.cardinality) {
            if (setBit(words, values[index].code)) added++
        }
        return added
    }

    /** Toggles every value of [array] in [words], returning the change in population. */
    private fun flip(words: LongArray, array: ArrayContainer): Int {
        val values = array.backing()
        var delta = 0
        for (index in 0 until array.cardinality) {
            val low = values[index].code
            if (setBit(words, low)) {
                delta++
            } else {
                clearBit(words, low)
                delta--
            }
        }
        return delta
    }

    private fun setBit(words: LongArray, low: Int): Boolean {
        val at = low ushr 6
        val bit = 1L shl (low and 63)
        if (words[at] and bit != 0L) return false
        words[at] = words[at] or bit
        return true
    }

    private fun clearBit(words: LongArray, low: Int): Boolean {
        val at = low ushr 6
        val bit = 1L shl (low and 63)
        if (words[at] and bit == 0L) return false
        words[at] = words[at] and bit.inv()
        return true
    }
}
