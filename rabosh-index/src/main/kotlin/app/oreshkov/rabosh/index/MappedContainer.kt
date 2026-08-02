package app.oreshkov.rabosh.index

/**
 * The three encodings read straight out of a mapped file.
 *
 * Each is a handful of lines, because the algorithms are not here: they are in [ArrayBlock],
 * [BitsetBlock] and [RunBlock], and what these classes add is where the bytes come from. That is the
 * point of the split — a mapped `select` and a heap `select` are the *same* code, so they cannot come to
 * disagree about which document a bitmap position names.
 *
 * Nothing is copied and nothing is allocated per query. A `contains` against a mapped array block is a
 * binary search of `u16` reads against the mapping; against a bitset block it is one `u64` read. The
 * only allocation is [ReadableContainer.materialise], which the four constructive operations ask for
 * because they have to build a result anyway, and it is bounded at 8 KB a block.
 *
 * The cardinality is not stored in a container — it comes from the directory entry, as
 * [BitmapFormat] describes, which is why every one of these takes it as an argument.
 */
internal class MappedArrayContainer(
    private val bytes: IndexBytes,
    private val offset: Int,
    override val cardinality: Int,
) : ArrayBlock() {

    override fun valueAt(index: Int): Int = bytes.u16(offset + index * 2, "array block value")

    override fun materialise(): Container {
        val values = CharArray(cardinality)
        for (index in 0 until cardinality) values[index] = valueAt(index).toChar()
        return ArrayContainer.ofSorted(values, cardinality)
    }
}

internal class MappedBitsetContainer(
    private val bytes: IndexBytes,
    private val offset: Int,
    override val cardinality: Int,
) : BitsetBlock() {

    override fun word(index: Int): Long = bytes.i64(offset + index * 8, "bitset block word")

    override fun materialise(): Container {
        val words = LongArray(BitmapFormat.BITSET_WORDS)
        bytes.words(offset, words, BitmapFormat.BITSET_WORDS, "bitset block")
        return BitsetContainer.ofWords(words, cardinality)
    }
}

internal class MappedRunContainer(
    private val bytes: IndexBytes,
    private val offset: Int,
    override val cardinality: Int,
    /** Read by [BitmapView] before this exists, because the block's extent depends on it. */
    override val runCount: Int,
) : RunBlock() {

    override fun runStart(index: Int): Int = bytes.u16(offset + 4 + index * 4, "run block start")

    override fun runLengthMinusOne(index: Int): Int =
        bytes.u16(offset + 4 + index * 4 + 2, "run block length")

    override fun materialise(): Container {
        val runs = CharArray(runCount * 2)
        for (index in 0 until runCount) {
            runs[index * 2] = runStart(index).toChar()
            runs[index * 2 + 1] = runLengthMinusOne(index).toChar()
        }
        return RunContainer.ofRuns(runs, runCount, cardinality)
    }
}
