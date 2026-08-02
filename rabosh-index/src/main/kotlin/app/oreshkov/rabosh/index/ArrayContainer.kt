package app.oreshkov.rabosh.index

/**
 * A heap block held as a sorted array of remainders: the encoding a sparse block wants.
 *
 * Two bytes a value, so it is the smallest of the three up to and including
 * [BitmapFormat.ARRAY_MAX_CARDINALITY] values — at 4096 an array and a bitset are both 8192 bytes and
 * the tie goes to the array. Above that the block promotes itself to a bitset, which is where "present
 * in most documents" lives.
 *
 * Remainders are `Char` rather than `Short` because `Char` is the JVM's unsigned 16-bit type, and this
 * array is *sorted* — a `ShortArray` would have put 0x8000 before 0x0001 and every search over a block
 * holding a remainder above 32767 would have been quietly wrong. The read algorithms are in
 * [ArrayBlock], which compares `Int` remainders and so cannot make that mistake either.
 */
internal class ArrayContainer private constructor(
    private var values: CharArray,
    private var size: Int,
) : ArrayBlock(), Container {

    override val cardinality: Int get() = size

    override fun valueAt(index: Int): Int = values[index].code

    override fun materialise(): Container = this

    override fun copy(): Container = ArrayContainer(values.copyOf(size), size)

    override fun add(low: Int): Container {
        val at = searchRange(low, 0, size)
        if (at >= 0) return this
        if (size == BitmapFormat.ARRAY_MAX_CARDINALITY) return toBitset().add(low)
        val insertAt = -at - 1
        reserve(size + 1)
        values.copyInto(values, insertAt + 1, insertAt, size)
        values[insertAt] = low.toChar()
        size++
        return this
    }

    override fun remove(low: Int): Container {
        val at = searchRange(low, 0, size)
        if (at < 0) return this
        values.copyInto(values, at, at + 1, size)
        size--
        return this
    }

    override fun addRange(first: Int, last: Int): Container {
        if (first > last) return this
        val added = last - first + 1
        // The bound is on what the array *could* hold rather than on what it will: computing the exact
        // overlap first would be a second pass to save an occasional promotion, and a bitset that
        // turns out to be over-sized is put back into an array by `normalise`.
        if (size + added > BitmapFormat.ARRAY_MAX_CARDINALITY) return toBitset().addRange(first, last)

        val merged = CharArray(size + added)
        var source = 0
        var written = 0
        while (source < size && values[source].code < first) merged[written++] = values[source++]
        for (value in first..last) merged[written++] = value.toChar()
        while (source < size && values[source].code <= last) source++
        while (source < size) merged[written++] = values[source++]
        values = merged
        size = written
        return this
    }

    override fun removeRange(first: Int, last: Int): Container {
        if (first > last) return this
        val from = lowerBound(first)
        val to = lowerBound(last + 1)
        if (from == to) return this
        values.copyInto(values, from, to, size)
        size -= to - from
        return this
    }

    override fun normalise(): Container = when (BitmapFormat.smallestKind(size, runCount)) {
        BitmapFormat.KIND_ARRAY -> this
        BitmapFormat.KIND_RUN -> toRun()
        else -> toBitset()
    }

    override fun writeTo(out: IndexWriter) {
        for (index in 0 until size) out.writeU16(values[index].code)
    }

    /** A bitset holding the same values. */
    fun toBitset(): BitsetContainer {
        val words = LongArray(BitmapFormat.BITSET_WORDS)
        for (index in 0 until size) {
            val low = values[index].code
            words[low ushr 6] = words[low ushr 6] or (1L shl (low and 63))
        }
        return BitsetContainer.ofWords(words, size)
    }

    /** A run list holding the same values. */
    fun toRun(): RunContainer {
        val runs = CharArray(runCount * 2)
        var written = 0
        var index = 0
        while (index < size) {
            val start = values[index].code
            var end = start
            index++
            while (index < size && values[index].code == end + 1) {
                end++
                index++
            }
            runs[written++] = start.toChar()
            runs[written++] = (end - start).toChar()
        }
        return RunContainer.ofRuns(runs, written / 2, size)
    }

    /** The backing array, valid over `[0, cardinality)`. For the algebra, which reads it directly. */
    fun backing(): CharArray = values

    /** The first index holding a value at or above [low], which is [size] when there is none. */
    private fun lowerBound(low: Int): Int {
        if (low > 0xFFFF) return size
        val at = searchRange(low, 0, size)
        return if (at >= 0) at else -at - 1
    }

    private fun reserve(required: Int) {
        require(required <= BitmapFormat.ARRAY_MAX_CARDINALITY) {
            "an array container holds at most ${BitmapFormat.ARRAY_MAX_CARDINALITY} values, not $required"
        }
        if (required <= values.size) return
        var capacity = maxOf(values.size * 2, INITIAL_CAPACITY)
        while (capacity < required) capacity *= 2
        values = values.copyOf(capacity.coerceAtMost(BitmapFormat.ARRAY_MAX_CARDINALITY))
    }

    companion object {
        private const val INITIAL_CAPACITY = 8

        fun empty(): ArrayContainer = ArrayContainer(CharArray(INITIAL_CAPACITY), 0)

        /**
         * Takes ownership of [values], whose first [size] entries must be ascending and distinct.
         *
         * Unchecked, and used only by the algebra and the decoder — both of which produce that order
         * as a consequence of how they walk rather than by sorting afterwards. A file claiming an
         * unsorted array is rejected by [BitmapView] before it reaches here.
         */
        fun ofSorted(values: CharArray, size: Int): ArrayContainer = ArrayContainer(values, size)

        fun of(low: Int): ArrayContainer = ArrayContainer(charArrayOf(low.toChar()), 1)
    }
}
