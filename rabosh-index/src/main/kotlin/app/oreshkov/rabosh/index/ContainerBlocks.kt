package app.oreshkov.rabosh.index

/**
 * The read algorithms of the three encodings, written once and given their bytes by a subclass.
 *
 * A block exists in two places — on the heap while a sidecar is being built, and inside a mapped file
 * afterwards — and the algorithms over it are identical in both. Writing them twice is the shape this
 * would naturally take, and it is exactly the shape that fails silently: a heap `select` and a mapped
 * `select` that disagreed would make a query return different documents depending on whether the
 * bitmap it used had been flushed yet. The same argument put `BloomFilter.bitIndex` in one place, and
 * it applies harder here because there are more algorithms to get subtly wrong.
 *
 * So each encoding gets an abstract block that implements everything in terms of one or two accessors,
 * and the two subclasses supply nothing but the bytes: an array on the heap, or reads through
 * [IndexBytes] against a mapped file.
 */

/**
 * A block encoded as ascending, distinct 16-bit remainders.
 *
 * Every question about it is a binary search — [searchRange] is the only one in the module, so a
 * cursor's leapfrog and a plain `contains` cannot disagree about what "the first value at or above"
 * means.
 */
internal abstract class ArrayBlock : ReadableContainer {

    /** The remainder at [index], which is below [ReadableContainer.cardinality]. */
    protected abstract fun valueAt(index: Int): Int

    final override val kind: Int get() = BitmapFormat.KIND_ARRAY

    final override val first: Int get() = valueAt(0)

    final override val last: Int get() = valueAt(cardinality - 1)

    final override val runCount: Int
        get() {
            if (cardinality == 0) return 0
            var runs = 1
            var previous = valueAt(0)
            for (index in 1 until cardinality) {
                val value = valueAt(index)
                if (value != previous + 1) runs++
                previous = value
            }
            return runs
        }

    final override fun contains(low: Int): Boolean = searchRange(low, 0, cardinality) >= 0

    final override fun rank(low: Int): Int {
        val at = searchRange(low, 0, cardinality)
        return if (at >= 0) at + 1 else -at - 1
    }

    final override fun select(index: Int): Int = valueAt(index)

    final override fun cursor(): ContainerCursor = ArrayCursor()

    /**
     * Binary search over `[from, to)`, returning the index of [low] or `-(insertionPoint) - 1`.
     *
     * The `Arrays.binarySearch` encoding, so the two negative-result idioms in the module are one
     * idiom. Comparisons are on `Int` remainders and are therefore unsigned by construction — the
     * mistake this encoding invites is a signed 16-bit comparison, which sorts every remainder above
     * 32767 before every remainder below it.
     */
    protected fun searchRange(low: Int, from: Int, to: Int): Int {
        var lower = from
        var upper = to - 1
        while (lower <= upper) {
            val middle = (lower + upper) ushr 1
            val value = valueAt(middle)
            if (value < low) {
                lower = middle + 1
            } else if (value > low) {
                upper = middle - 1
            } else {
                return middle
            }
        }
        return -(lower + 1)
    }

    private inner class ArrayCursor : ContainerCursor {
        private var position = -1

        override val low: Int get() = valueAt(position)

        override fun next(): Boolean {
            if (position >= cardinality) return false
            position++
            return position < cardinality
        }

        override fun advanceTo(low: Int): Boolean {
            // From the current position *inclusive*, so a cursor already sitting on a value at or
            // above the target stays. A leapfrog join asks the same cursor about a value it may
            // already be on, and searching from `position + 1` would step over it.
            val from = maxOf(position, 0)
            if (from >= cardinality || low > 0xFFFF) {
                position = cardinality
                return false
            }
            val at = searchRange(low, from, cardinality)
            position = if (at >= 0) at else -at - 1
            return position < cardinality
        }
    }
}

/**
 * A block encoded as [BitmapFormat.BITSET_WORDS] little-endian words of bits.
 *
 * The cardinality is *not* derived here: a population count over 1024 words on every read would be
 * paid by `rank`, by the directory that has to write the number, and by every planner decision that
 * asks how selective a block is. The heap subclass carries it and the mapped one reads it from the
 * directory.
 */
internal abstract class BitsetBlock : ReadableContainer, BitsetSource {

    final override val kind: Int get() = BitmapFormat.KIND_BITSET

    final override val first: Int get() = nextSetBit(0)

    final override val last: Int
        get() {
            for (index in BitmapFormat.BITSET_WORDS - 1 downTo 0) {
                val word = word(index)
                if (word != 0L) return (index shl 6) + (63 - word.countLeadingZeroBits())
            }
            return -1
        }

    /**
     * Runs of consecutive values, counted by counting run *starts*.
     *
     * A value starts a run when its predecessor is absent, and `word shl 1` is exactly "the
     * predecessor of each bit" — except at bit zero, whose predecessor is the previous word's top bit
     * and arrives as the carry. One pass, no branch inside it.
     */
    final override val runCount: Int
        get() {
            var runs = 0
            var carry = 0L
            for (index in 0 until BitmapFormat.BITSET_WORDS) {
                val word = word(index)
                val predecessors = (word shl 1) or carry
                runs += (word and predecessors.inv()).countOneBits()
                carry = word ushr 63
            }
            return runs
        }

    final override fun contains(low: Int): Boolean =
        word(low ushr 6) and (1L shl (low and 63)) != 0L

    final override fun rank(low: Int): Int {
        val lastWord = low ushr 6
        var total = 0
        for (index in 0 until lastWord) total += word(index).countOneBits()
        return total + (word(lastWord) and maskUpTo(low and 63)).countOneBits()
    }

    /**
     * The [index]-th smallest value.
     *
     * A word at a time, then the bit within it: clearing the lowest set bit [index] times leaves the
     * wanted bit lowest, which is one instruction a step and needs no table. Up to 1024 word reads in
     * the worst case, which is the price of not storing a per-word prefix count — 4 KB of index over a
     * structure whose whole point is that it is 8 KB.
     */
    final override fun select(index: Int): Int {
        var remaining = index
        for (position in 0 until BitmapFormat.BITSET_WORDS) {
            var word = word(position)
            val available = word.countOneBits()
            if (remaining < available) {
                repeat(remaining) { word = word and (word - 1) }
                return (position shl 6) + word.countTrailingZeroBits()
            }
            remaining -= available
        }
        throw IndexOutOfBoundsException("select($index) in a block of $cardinality value(s)")
    }

    final override fun cursor(): ContainerCursor = BitsetCursor()

    /** The smallest value at or above [from], or `-1` when there is none. */
    fun nextSetBit(from: Int): Int {
        if (from > 0xFFFF) return -1
        var index = from ushr 6
        var word = word(index) and (-1L shl (from and 63))
        while (true) {
            if (word != 0L) return (index shl 6) + word.countTrailingZeroBits()
            index++
            if (index == BitmapFormat.BITSET_WORDS) return -1
            word = word(index)
        }
    }

    /** The smallest *absent* value at or above [from], or [BitmapFormat.CONTAINER_VALUES]. */
    protected fun nextClearBit(from: Int): Int {
        if (from > 0xFFFF) return BitmapFormat.CONTAINER_VALUES
        var index = from ushr 6
        var word = word(index).inv() and (-1L shl (from and 63))
        while (true) {
            if (word != 0L) return (index shl 6) + word.countTrailingZeroBits()
            index++
            if (index == BitmapFormat.BITSET_WORDS) return BitmapFormat.CONTAINER_VALUES
            word = word(index).inv()
        }
    }

    private inner class BitsetCursor : ContainerCursor {
        private var position = -1

        override val low: Int get() = position

        override fun next(): Boolean = seek(position + 1)

        override fun advanceTo(low: Int): Boolean = seek(maxOf(position, low))

        private fun seek(from: Int): Boolean {
            val found = if (from > 0xFFFF) -1 else nextSetBit(from)
            position = if (found < 0) BitmapFormat.CONTAINER_VALUES else found
            return found >= 0
        }
    }

    protected companion object {
        /** Bits `bit..63`. */
        fun maskFrom(bit: Int): Long = -1L shl bit

        /** Bits `0..bit`. */
        fun maskUpTo(bit: Int): Long = if (bit == 63) -1L else (1L shl (bit + 1)) - 1
    }
}

/**
 * A block encoded as ascending, non-adjacent `(start, lengthMinusOne)` runs.
 *
 * [rank] and [select] walk the runs rather than binary-searching a prefix sum, and that is a decision
 * rather than an omission: a run encoding is only ever chosen when its runs cost less than the 8 KB a
 * bitset would, which caps the walk at 2047 steps, and four bytes of prefix per run would spend a
 * quarter of the encoding's whole advantage on it.
 */
internal abstract class RunBlock : ReadableContainer {

    /** The first value of run [index]. */
    protected abstract fun runStart(index: Int): Int

    /** One less than the length of run [index], as stored. */
    protected abstract fun runLengthMinusOne(index: Int): Int

    /** The last value of run [index]. */
    protected fun runLast(index: Int): Int = runStart(index) + runLengthMinusOne(index)

    final override val kind: Int get() = BitmapFormat.KIND_RUN

    final override val first: Int get() = runStart(0)

    final override val last: Int get() = runLast(runCount - 1)

    final override fun contains(low: Int): Boolean {
        val index = runAtOrBefore(low)
        return index >= 0 && low <= runLast(index)
    }

    final override fun rank(low: Int): Int {
        var total = 0
        for (index in 0 until runCount) {
            val start = runStart(index)
            if (start > low) break
            val last = runLast(index)
            total += if (low >= last) last - start + 1 else low - start + 1
        }
        return total
    }

    final override fun select(index: Int): Int {
        var remaining = index
        for (position in 0 until runCount) {
            val length = runLengthMinusOne(position) + 1
            if (remaining < length) return runStart(position) + remaining
            remaining -= length
        }
        throw IndexOutOfBoundsException("select($index) in a block of $cardinality value(s)")
    }

    final override fun cursor(): ContainerCursor = RunCursor()

    /**
     * Adds the one claim the shared check cannot see: runs are separated by at least one absent value.
     *
     * Two runs written adjacently describe the right values in the wrong number of runs, so a cursor
     * over them ascends and counts correctly and the generic check passes. It is still not the encoding
     * this writer produces, and letting it through would mean two files holding the same values whose
     * bytes differ — the property this format is built to keep.
     */
    final override fun verify(report: (String) -> Nothing) {
        for (index in 1 until runCount) {
            if (runStart(index) <= runLast(index - 1) + 1) {
                report(
                    "run block has ${runStart(index)}..${runLast(index)} following " +
                        "${runStart(index - 1)}..${runLast(index - 1)}, which are not separated",
                )
            }
        }
        verifyContainerContents(this, report)
    }

    /** The last run beginning at or below [low], or `-1` when every run begins above it. */
    protected fun runAtOrBefore(low: Int): Int {
        var lower = 0
        var upper = runCount - 1
        while (lower <= upper) {
            val middle = (lower + upper) ushr 1
            if (runStart(middle) > low) upper = middle - 1 else lower = middle + 1
        }
        return upper
    }

    private inner class RunCursor : ContainerCursor {
        private var index = 0
        private var current = -1

        override val low: Int get() = current

        override fun next(): Boolean {
            if (index >= runCount) return false
            if (current < 0) {
                current = runStart(index)
                return true
            }
            if (current < runLast(index)) {
                current++
                return true
            }
            index++
            if (index >= runCount) return false
            current = runStart(index)
            return true
        }

        override fun advanceTo(low: Int): Boolean {
            val target = maxOf(current, low)
            while (index < runCount && runLast(index) < target) index++
            if (index >= runCount) return false
            current = maxOf(runStart(index), target)
            return true
        }
    }
}
