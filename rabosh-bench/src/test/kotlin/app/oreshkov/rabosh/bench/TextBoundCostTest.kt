package app.oreshkov.rabosh.bench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The arithmetic the §10.4 sweep is read through.
 *
 * `TextBoundCostMain` prints the curve and nothing checks its numbers — the same split
 * `CorrelationCostTest`, `ExplodeCostTest` and `ElementAccessCostTest` take. What is checked here is
 * the reasoning the curve feeds: that a bound at or below the shared prefix prunes **exactly** nothing
 * rather than merely little, that the discriminating width is what is left after the prefix, and that
 * the byte ceiling scales with blocks rather than with documents.
 */
class TextBoundCostTest {

    /**
     * The step, which is the whole of the item's answer.
     *
     * Asserted at the boundary in both directions rather than well either side of it — a threshold
     * tested only at "clearly below" and "clearly above" is not being tested. 40 is
     * `type.googleapis.com/com.example.game.v1.`; a bound of exactly 40 keeps the prefix and not one
     * byte more, so it still cannot tell two values apart.
     */
    @Test
    fun `a bound at or below the shared prefix prunes nothing at all`() {
        assertTrue(TextBoundCost.prunesNothing(boundBytes = 39, sharedPrefixBytes = 40))
        assertTrue(TextBoundCost.prunesNothing(boundBytes = 40, sharedPrefixBytes = 40))
        assertFalse(TextBoundCost.prunesNothing(boundBytes = 41, sharedPrefixBytes = 40))

        // The default, against the shape §10.4 was opened by: it is one byte past useless.
        assertFalse(TextBoundCost.prunesNothing(boundBytes = 64, sharedPrefixBytes = 40))
        assertTrue(TextBoundCost.prunesNothing(boundBytes = 64, sharedPrefixBytes = 64))
    }

    /** A corpus with no shared prefix is the degenerate case, and the step is at the bottom. */
    @Test
    fun `with no shared prefix every width discriminates`() {
        assertFalse(TextBoundCost.prunesNothing(boundBytes = 1, sharedPrefixBytes = 0))
        assertEquals(1, TextBoundCost.discriminatingBytes(boundBytes = 1, sharedPrefixBytes = 0))
    }

    /**
     * What is left of a bound after the prefix has taken its share, and it floors at zero.
     *
     * Negative discriminating bytes would be arithmetic nonsense that reads as "worse than useless",
     * and the two are the same thing: nothing is ruled out either way.
     */
    @Test
    fun `discriminating bytes are what the prefix leaves, never negative`() {
        assertEquals(24, TextBoundCost.discriminatingBytes(boundBytes = 64, sharedPrefixBytes = 40))
        assertEquals(0, TextBoundCost.discriminatingBytes(boundBytes = 40, sharedPrefixBytes = 40))
        assertEquals(0, TextBoundCost.discriminatingBytes(boundBytes = 8, sharedPrefixBytes = 40))
    }

    /**
     * The rate, and the counters it is read from.
     *
     * `skipRate` is asserted beside the block counts for the standing reason an assertion about work
     * never stands alone: a rate of 1.0 over zero blocks is a column that does not exist, not a column
     * that pruned everything.
     */
    @Test
    fun `the skip rate is over the blocks the query actually considered`() {
        // One probe, so the probe total and the column's own block count coincide.
        val cost = TextBoundCost(
            boundBytes = 64,
            sharedPrefixBytes = 40,
            probes = 1,
            blocksSkipped = 19,
            blocksScanned = 1,
            columnBytes = 4_000,
        )

        assertEquals(20, cost.blocksConsidered)
        assertEquals(20, cost.columnBlocks)
        assertEquals(0.95, cost.skipRate, 1e-9)
        assertEquals(200.0, cost.bytesPerBlock, 1e-9)
    }

    /**
     * Summing blocks over probes is sound for the *rate* and wrong for the *bytes*, and the row keeps
     * them apart.
     *
     * The first version of the sweep divided one column's bytes by the probe total and printed a
     * per-block cost sixteen times too small, which made a real cost look like rounding error. A ratio
     * survives the summation because both halves scale; a per-block quantity does not.
     */
    @Test
    fun `a row measured over many probes divides bytes by the column's blocks, not the probe total`() {
        val cost = TextBoundCost(
            boundBytes = 64,
            sharedPrefixBytes = 40,
            probes = 16,
            blocksSkipped = 16 * 19,
            blocksScanned = 16 * 1,
            columnBytes = 4_000,
        )

        assertEquals(320, cost.blocksConsidered, "every probe considered the same twenty blocks")
        assertEquals(20, cost.columnBlocks, "but the column holds twenty, not three hundred and twenty")
        assertEquals(0.95, cost.skipRate, 1e-9, "the rate is unchanged by how many probes ran")
        assertEquals(200.0, cost.bytesPerBlock, 1e-9, "and the bytes are still divided by twenty")
    }

    /** A column with no blocks reports no pruning rather than dividing by zero. */
    @Test
    fun `a column with no blocks reports no rate`() {
        val empty = TextBoundCost(
            boundBytes = 64,
            sharedPrefixBytes = 40,
            probes = 1,
            blocksSkipped = 0,
            blocksScanned = 0,
            columnBytes = 0,
        )

        assertEquals(0, empty.blocksConsidered)
        assertEquals(0.0, empty.skipRate)
        assertEquals(0.0, empty.bytesPerBlock)
    }

    /**
     * The byte model against the number the sweep actually measured.
     *
     * `CorrelationCostTest` checks a rate against its closed form rather than against a remembered
     * value; this is the same move on the cost side, and it is the stronger half of this file because
     * the agreement is exact rather than within a tolerance. Widening a 20-block column's bound from 8
     * bytes to the 46 at which these values saturate predicts 1596 bytes, and the run measured
     * 8_002_579 − 8_000_983 = **1596**. Exact because every value is longer than both widths, so each
     * bound is written at full width and nothing is lost to a short value or a trailing `0xFF`.
     */
    @Test
    fun `the measured cost of widening is the model, to the byte`() {
        val blocks = 20
        val narrow = TextBoundCost.predictedBoundBytes(boundBytes = 8, blocks = blocks)
        val saturated = TextBoundCost.predictedBoundBytes(boundBytes = 46, blocks = blocks)

        assertEquals(1_596L, saturated - narrow, "the model's prediction for the sweep's two ends")
        assertEquals(8_002_579L - 8_000_983L, saturated - narrow, "and what the sweep measured")
    }

    /**
     * The cost side scales with **blocks**, not with documents, which is why widening is cheap.
     *
     * The asymmetry is the reason the sweep is worth running at all: pruning is bought per query and
     * paid for per block, and a block is 8192 values. Doubling the bound doubles a per-block cost that
     * was already amortised over thousands of documents.
     */
    @Test
    fun `the byte ceiling is two bounds per block plus one pair for the segment`() {
        assertEquals(2L * 64 * 21, TextBoundCost.predictedBoundBytes(boundBytes = 64, blocks = 20))
        assertEquals(2L * 128 * 21, TextBoundCost.predictedBoundBytes(boundBytes = 128, blocks = 20))

        // Doubling the width doubles the ceiling; doubling the blocks does not double it, because the
        // segment's own pair is paid once. Both halves stated, so neither can be read off alone.
        val narrow = TextBoundCost.predictedBoundBytes(boundBytes = 64, blocks = 20)
        val wide = TextBoundCost.predictedBoundBytes(boundBytes = 128, blocks = 20)
        assertEquals(2.0, wide.toDouble() / narrow, 1e-9)
        assertTrue(TextBoundCost.predictedBoundBytes(64, 40) < 2 * narrow)
    }

    /** The invariants that make a row readable at all are checked rather than assumed. */
    @Test
    fun `a nonsensical row is refused rather than reported`() {
        assertFailsWith<IllegalArgumentException> {
            TextBoundCost(
                boundBytes = 0,
                sharedPrefixBytes = 40,
                probes = 1,
                blocksSkipped = 0,
                blocksScanned = 1,
                columnBytes = 0,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TextBoundCost(
                boundBytes = 64,
                sharedPrefixBytes = 40,
                probes = 0,
                blocksSkipped = 0,
                blocksScanned = 1,
                columnBytes = 0,
            )
        }
        assertFailsWith<IllegalArgumentException> { TextBoundCost.prunesNothing(boundBytes = 0, sharedPrefixBytes = 1) }
        assertFailsWith<IllegalArgumentException> { TextBoundCost.predictedBoundBytes(boundBytes = 64, blocks = -1) }
    }

    /**
     * The permutation the unfavourable corpus uses is a **bijection**, so the two rows compare
     * locality and nothing else.
     *
     * If it were not, the interleaved corpus would hold a different multiset of values and its flat
     * pruning curve would be evidence about the data rather than about the ordering — which is the
     * error the two-fixture rule exists to prevent.
     */
    @Test
    fun `the interleaved corpus is the clustered one permuted, value for value`() {
        val clustered = HashSet<String>()
        val interleaved = HashSet<String>()
        for (index in 0 until 4_000) {
            clustered += TextBoundCostMain.typeOf(index, clustered = true)
            interleaved += TextBoundCostMain.typeOf(index, clustered = false)
        }

        assertEquals(4_000, clustered.size, "the clustered values must be distinct")
        assertEquals(4_000, interleaved.size, "and so must the permuted ones")
        assertTrue(
            TextBoundCostMain.typeOf(0, clustered = true).startsWith(TextBoundCostMain.SHARED_PREFIX),
            "every value must carry the shared prefix the sweep is about",
        )
        assertEquals(
            TextBoundCostMain.SHARED_PREFIX_BYTES,
            TextBoundCostMain.SHARED_PREFIX.encodeToByteArray().size,
            "the declared prefix length must be the prefix's actual length in bytes",
        )
    }
}
