package app.oreshkov.rabosh.index

/**
 * A heap block held as a list of runs of consecutive values: ascending, non-adjacent
 * `(start, lengthMinusOne)` pairs.
 *
 * Four bytes a run, so a block of consecutive ordinals costs four bytes however many values it holds —
 * and consecutive ordinals are not a corner case for an index sidecar. A predicate that most documents
 * in a segment satisfy produces exactly this, and without a run encoding it would cost the 8 KB a
 * bitset costs.
 *
 * `lengthMinusOne` rather than a length, so a run covering the whole 65 536-value block still fits two
 * 16-bit fields. A length would have needed seventeen bits for the one case this encoding helps most
 * with.
 *
 * **A run list is an encoding, not an algebra.** The read path is native — [ReadableContainer.contains],
 * [ReadableContainer.rank], [ReadableContainer.select] and cursoring all work off the runs, in
 * [RunBlock] — but [add] and [remove] [expand] first, and so does every constructive operation in
 * [ContainerAlgebra]. Two consequences, both deliberate: the four boolean operations have four cases
 * rather than sixteen, and the cost of the shortcut is bounded at one block, because mutating a run
 * container expands only the block that was touched and `normalise` puts the result back into runs on
 * the way out if runs are still smallest. A run-native fast path is a private function away, and phase
 * 9's benchmarks are what would justify writing one.
 */
internal class RunContainer private constructor(
    private val runs: CharArray,
    private val runsCount: Int,
    private val count: Int,
) : RunBlock(), Container {

    override val cardinality: Int get() = count

    override val runCount: Int get() = runsCount

    override fun runStart(index: Int): Int = runs[index * 2].code

    override fun runLengthMinusOne(index: Int): Int = runs[index * 2 + 1].code

    override fun materialise(): Container = this

    /** Itself: nothing here is ever mutated, because every mutation [expand]s into another container. */
    override fun copy(): Container = this

    override fun add(low: Int): Container = expand().add(low)

    override fun remove(low: Int): Container = expand().remove(low)

    override fun addRange(first: Int, last: Int): Container = expand().addRange(first, last)

    override fun removeRange(first: Int, last: Int): Container = expand().removeRange(first, last)

    override fun normalise(): Container = when (BitmapFormat.smallestKind(count, runsCount)) {
        BitmapFormat.KIND_RUN -> this
        BitmapFormat.KIND_ARRAY -> toArray()
        else -> toBitset()
    }

    override fun writeTo(out: IndexWriter) {
        out.writeU32(runsCount)
        for (index in 0 until runsCount * 2) out.writeU16(runs[index].code)
    }

    /** The same values in whichever encoding can be operated on and mutated. */
    fun expand(): Container =
        if (count <= BitmapFormat.ARRAY_MAX_CARDINALITY) toArray() else toBitset()

    fun toArray(): ArrayContainer {
        require(count <= BitmapFormat.ARRAY_MAX_CARDINALITY) {
            "$count value(s) do not fit an array container"
        }
        val values = CharArray(count)
        var written = 0
        for (index in 0 until runsCount) {
            for (value in runStart(index)..runLast(index)) values[written++] = value.toChar()
        }
        return ArrayContainer.ofSorted(values, written)
    }

    fun toBitset(): BitsetContainer {
        val bitset = BitsetContainer.empty()
        for (index in 0 until runsCount) bitset.addRange(runStart(index), runLast(index))
        return bitset
    }

    companion object {
        /**
         * Takes ownership of [runs], whose first [runCount] pairs must be ascending and separated by at
         * least one absent value, with [cardinality] their total length.
         *
         * Unchecked for the reason `ArrayContainer.ofSorted` is: every caller in this module produces
         * that shape as a consequence of how it walks, and a file claiming otherwise is rejected by
         * [BitmapView] before a container is built from it.
         */
        fun ofRuns(runs: CharArray, runCount: Int, cardinality: Int): RunContainer =
            RunContainer(runs, runCount, cardinality)

        /** A single run covering `first..last`, both inclusive. */
        fun ofRange(first: Int, last: Int): RunContainer {
            require(first in 0..last && last <= 0xFFFF) { "$first..$last is not a run within a block" }
            return RunContainer(charArrayOf(first.toChar(), (last - first).toChar()), 1, last - first + 1)
        }
    }
}
