package app.oreshkov.rabosh.index

/**
 * A heap block held as 1024 words of bits: the encoding a dense block wants.
 *
 * Fixed at [BitmapFormat.BITSET_BYTES] whatever it holds, which makes it smallest above 4096 values
 * and — because it is the only encoding whose cost does not grow with cardinality — the one that puts
 * a ceiling on what a block can cost at all. `contains` is a single word read, and two of these
 * intersect in 1024 word operations however many values they hold.
 *
 * The read algorithms are in [BitsetBlock]; what is here is the words, the cardinality that travels
 * with them, and the mutation a sidecar build needs.
 */
internal class BitsetContainer private constructor(
    private val words: LongArray,
    private var count: Int,
) : BitsetBlock(), Container {

    override val cardinality: Int get() = count

    override fun word(index: Int): Long = words[index]

    override fun materialise(): Container = this

    override fun copy(): Container = BitsetContainer(words.copyOf(), count)

    override fun add(low: Int): Container {
        val at = low ushr 6
        val bit = 1L shl (low and 63)
        if (words[at] and bit == 0L) {
            words[at] = words[at] or bit
            count++
        }
        return this
    }

    override fun remove(low: Int): Container {
        val at = low ushr 6
        val bit = 1L shl (low and 63)
        if (words[at] and bit != 0L) {
            words[at] = words[at] and bit.inv()
            count--
        }
        return this
    }

    override fun addRange(first: Int, last: Int): Container {
        forEachWordInRange(first, last) { index, mask ->
            count += (mask and words[index].inv()).countOneBits()
            words[index] = words[index] or mask
        }
        return this
    }

    override fun removeRange(first: Int, last: Int): Container {
        forEachWordInRange(first, last) { index, mask ->
            count -= (mask and words[index]).countOneBits()
            words[index] = words[index] and mask.inv()
        }
        return this
    }

    override fun normalise(): Container = when (BitmapFormat.smallestKind(count, runCount)) {
        BitmapFormat.KIND_BITSET -> this
        BitmapFormat.KIND_ARRAY -> toArray()
        else -> toRun()
    }

    override fun writeTo(out: IndexWriter) {
        for (word in words) out.writeLong(word)
    }

    fun toArray(): ArrayContainer {
        require(count <= BitmapFormat.ARRAY_MAX_CARDINALITY) {
            "$count value(s) do not fit an array container"
        }
        val values = CharArray(count)
        var written = 0
        var bit = nextSetBit(0)
        while (bit >= 0) {
            values[written++] = bit.toChar()
            bit = if (bit == 0xFFFF) -1 else nextSetBit(bit + 1)
        }
        return ArrayContainer.ofSorted(values, written)
    }

    fun toRun(): RunContainer {
        val runs = CharArray(runCount * 2)
        var written = 0
        var start = nextSetBit(0)
        while (start >= 0) {
            val afterRun = nextClearBit(start)
            runs[written++] = start.toChar()
            runs[written++] = (afterRun - 1 - start).toChar()
            start = if (afterRun >= BitmapFormat.CONTAINER_VALUES) -1 else nextSetBit(afterRun)
        }
        return RunContainer.ofRuns(runs, written / 2, count)
    }

    /** The backing words, for the algebra, which combines them directly. */
    fun backing(): LongArray = words

    private inline fun forEachWordInRange(first: Int, last: Int, apply: (Int, Long) -> Unit) {
        if (first > last) return
        val firstWord = first ushr 6
        val lastWord = last ushr 6
        if (firstWord == lastWord) {
            apply(firstWord, maskFrom(first and 63) and maskUpTo(last and 63))
            return
        }
        apply(firstWord, maskFrom(first and 63))
        for (index in firstWord + 1 until lastWord) apply(index, -1L)
        apply(lastWord, maskUpTo(last and 63))
    }

    companion object {
        fun empty(): BitsetContainer = BitsetContainer(LongArray(BitmapFormat.BITSET_WORDS), 0)

        /**
         * Takes ownership of [words], trusting [cardinality] to be their population count.
         *
         * Unchecked here and checked where it matters: every caller in this module derives the count as
         * it sets the bits, and a count read from a file is confirmed by `BitmapView.verify` rather
         * than on the read path — see [BitmapView] for why that division is deliberate.
         */
        fun ofWords(words: LongArray, cardinality: Int): BitsetContainer {
            require(words.size == BitmapFormat.BITSET_WORDS) {
                "a bitset container is ${BitmapFormat.BITSET_WORDS} words, not ${words.size}"
            }
            return BitsetContainer(words, cardinality)
        }
    }
}
