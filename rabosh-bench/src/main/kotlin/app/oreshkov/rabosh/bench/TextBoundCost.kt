package app.oreshkov.rabosh.bench

/**
 * What a text bound's width buys in pruning, against what it costs in sidecar bytes.
 *
 * The §10.4 question, aimed at the dial that actually answers it. **There are two text-bound dials and
 * only one of them prunes.** `CatalogOptions.textBoundBytes` truncates the bounds in a `.cat` sketch,
 * which are rendered by `InferredSchema.render` and read by nobody in `rabosh-query`;
 * `IndexOptions.columnTextBoundBytes` truncates a shredded column's segment bounds *and* its
 * per-block text statistics, and those are what `ColumnReader` skips on. Both default to 64, which is
 * how the two came to be argued about as one. This measures the second.
 *
 * ## The model, which is arithmetic before it is a measurement
 *
 * Take a corpus whose values share a prefix of *P* bytes — `type.googleapis.com/` is 20 — and a bound
 * of *B* bytes.
 *
 * **For `B <= P` the bound prunes exactly nothing, and this is a proof rather than a tendency.** Every
 * value truncates to the *same* B-byte prefix, so a block's minimum and its maximum are that one
 * prefix; the maximum is then incremented at its last byte, giving the half-open range
 * `[prefix, prefix⁺)`. Every value beginning with that prefix lies inside it. So `mayContain` is true
 * of every probe the corpus can be asked about, at every block, and the skip rate is **0.0** — not
 * small, zero. [prunesNothing] is that step and `TextBoundCostTest` pins it.
 *
 * For `B > P` the bound retains `B - P` discriminating bytes ([discriminatingBytes]) and pruning
 * becomes possible. *Possible*, not certain: block skipping is a **locality** property, so a column
 * whose values are interleaved with key order prunes nothing at any width however many discriminating
 * bytes it keeps. That is why the sweep runs over two corpora rather than one — see
 * [TextBoundCostMain] — and why the honest headline is the shape that does not benefit.
 *
 * ## Why the cost side is not "bytes per bound"
 *
 * A wider bound is paid for once per block and once per segment, so its cost scales with the *column's
 * block count* rather than with the corpus — and a block is 8192 values. [predictedBoundBytes] is that
 * model: two bounds per block plus one pair for the segment, each B bytes.
 *
 * **It is a model rather than a ceiling wherever the values are longer than the bound**, which is the
 * case the item is about, and the sweep matched it to the byte: widening from 8 to saturation over a
 * 20-block column predicts `2·46·21 − 2·8·21 = 1596` bytes and measured 1596. Below that only where a
 * value is shorter than B, or where a maximum lost trailing `0xFF` bytes to the increment.
 *
 * The pair is what makes this a sweep worth running rather than a before/after: pruning and bytes both
 * rise with B, so there is a crossover to find if there is one. There is not — see [TextBoundCostMain].
 */
class TextBoundCost(
    /** The bound width this row was measured at, in bytes. */
    val boundBytes: Int,
    /** Bytes of prefix every value at the path shares. */
    val sharedPrefixBytes: Int,
    /** Probes the block counts below are summed over. */
    val probes: Int,
    /** Blocks the predicate's bounds ruled out without reading a value, over every probe. */
    val blocksSkipped: Int,
    /** Blocks that had to be read, over every probe. */
    val blocksScanned: Int,
    /** Bytes on disk of the shredded columns this row's store wrote. */
    val columnBytes: Long,
) {
    init {
        require(boundBytes > 0) { "a bound width must be positive, was $boundBytes" }
        require(sharedPrefixBytes >= 0) { "sharedPrefixBytes must not be negative" }
        require(probes > 0) { "a row must be measured over at least one probe, was $probes" }
        require(blocksSkipped >= 0 && blocksScanned >= 0) { "block counts must not be negative" }
        require(columnBytes >= 0) { "columnBytes must not be negative" }
    }

    /** Blocks considered across every probe. */
    val blocksConsidered: Int get() = blocksSkipped + blocksScanned

    /**
     * Blocks the **column** holds, which is what the bytes below are divided by.
     *
     * Every probe considers the same column, so the total above is that number times [probes]. Keeping
     * the two apart is not pedantry: dividing the column's bytes by the probe *total* understates the
     * per-block cost by a factor of [probes], which is exactly the error the first run of this sweep
     * printed.
     */
    val columnBlocks: Int get() = blocksConsidered / probes

    /**
     * The share of blocks ruled out by their bounds. `0.0` when the column has no blocks at all.
     *
     * The quantity the whole item is about: at or below the shared prefix it is provably zero, and
     * what the sweep is looking for is where it stops being zero and how fast it climbs. A ratio, so
     * summing over probes is sound where dividing the bytes by the same total is not.
     */
    val skipRate: Double
        get() = if (blocksConsidered == 0) 0.0 else blocksSkipped.toDouble() / blocksConsidered

    /** Column bytes carried per block, which is what the pruning above is paid for with. */
    val bytesPerBlock: Double
        get() = if (columnBlocks == 0) 0.0 else columnBytes.toDouble() / columnBlocks

    override fun toString(): String =
        "TextBoundCost(bound=$boundBytes, prefix=$sharedPrefixBytes, " +
            "skipped=$blocksSkipped/$blocksConsidered, rate=${"%.3f".format(skipRate)})"

    companion object {
        /**
         * Whether a bound of [boundBytes] can rule anything out on a corpus sharing [sharedPrefixBytes].
         *
         * The step. `true` means the skip rate is **exactly** zero rather than merely poor, because
         * every bound in the column collapses to one prefix and the incremented maximum covers every
         * value that prefix can start. A caller reading this as "probably not much" has misread it.
         */
        fun prunesNothing(boundBytes: Int, sharedPrefixBytes: Int): Boolean {
            require(boundBytes > 0) { "a bound width must be positive, was $boundBytes" }
            require(sharedPrefixBytes >= 0) { "sharedPrefixBytes must not be negative" }
            return boundBytes <= sharedPrefixBytes
        }

        /**
         * Bytes of a bound left to tell two values apart, after the shared prefix has eaten its share.
         *
         * Zero is the step above. One is not much better than zero and the sweep shows it: a single
         * discriminating byte splits the space 256 ways at best and far less on text, which is why the
         * useful widths start well above the prefix rather than just past it.
         */
        fun discriminatingBytes(boundBytes: Int, sharedPrefixBytes: Int): Int =
            (boundBytes - sharedPrefixBytes).coerceAtLeast(0)

        /**
         * The most a bound of [boundBytes] can add to a column of [blocks] blocks.
         *
         * A ceiling rather than an estimate: two bounds per block for the statistics, plus one pair for
         * the segment, each at most [boundBytes] long. Stated as a function so the sweep's byte column
         * can be read against what it *must* be under rather than against a remembered number — the
         * same reason [CorrelationCost.predictedRate] exists.
         */
        fun predictedBoundBytes(boundBytes: Int, blocks: Int): Long {
            require(boundBytes > 0) { "a bound width must be positive, was $boundBytes" }
            require(blocks >= 0) { "blocks must not be negative" }
            return 2L * boundBytes * (blocks + 1)
        }
    }
}
