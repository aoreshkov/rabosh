package app.oreshkov.rabosh.bench

import app.oreshkov.rabosh.variant.Variant
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The arithmetic behind the gate on a composite-term index.
 *
 * `CorrelationCostMain` prints a sweep and nothing checks its numbers; this checks the counting that
 * produces them, in the ordinary build, which is the same split `ExplodeCostTest` takes. The
 * load-bearing test is the third: it compares the measurement against a **closed-form model** rather
 * than against a remembered figure, so a counting bug and a corpus that is not what it claims are
 * both caught, and neither can be absorbed by adjusting an expectation.
 */
class CorrelationCostTest {

    @Test
    fun `the false positive is the document no single element justifies`() {
        val documents = listOf(
            // Element 0 has the sku, element 1 has the qty: the conjunction matches, no element does.
            document("""[{"sku":"A","qty":"1"},{"sku":"B","qty":"5"}]"""),
            // One element has both. Wanted, and returned.
            document("""[{"sku":"A","qty":"5"},{"sku":"B","qty":"1"}]"""),
            // Neither leaf is satisfied at all.
            document("""[{"sku":"B","qty":"1"}]"""),
            // Only one leaf is satisfied, so the conjunction does not return it.
            document("""[{"sku":"A","qty":"1"}]"""),
        )

        val cost = CorrelationCost.measure(documents, "items", ElementMatch("sku", "A"), ElementMatch("qty", "5"))

        assertEquals(4, cost.documents)
        assertEquals(1, cost.correlatedMatches)
        assertEquals(2, cost.uncorrelatedMatches)
        assertEquals(1, cost.falsePositives)
        assertEquals(0.5, cost.falsePositiveRate)
        assertEquals(2.0, cost.amplification)
        assertEquals(6, cost.elementsScanned)
    }

    @Test
    fun `a document with no array, an empty one, and a scalar there all contribute nothing`() {
        val documents = listOf(
            Variant.fromJson("""{"other":1}"""),
            document("[]"),
            Variant.fromJson("""{"items":"not an array"}"""),
            Variant.fromJson("""{"items":[1,2,3]}"""),
        )

        val cost = CorrelationCost.measure(documents, "items", ElementMatch("sku", "A"), ElementMatch("qty", "5"))

        assertEquals(4, cost.documents)
        assertEquals(0, cost.correlatedMatches)
        assertEquals(0, cost.uncorrelatedMatches)
        assertEquals(0.0, cost.falsePositiveRate, "a rate over an empty answer is zero, not a division by zero")
        assertTrue(cost.amplification.isNaN(), "an amplification over no wanted documents has no value")
    }

    /**
     * The measurement agrees with the model, across the axis the model says decides the answer.
     *
     * This is the assertion the sweep's headline rests on. It runs at four element counts rather than
     * one, because a counting bug that happens to be right for a single element — the case where the
     * two answers coincide by construction — is exactly the bug this is here to catch.
     *
     * **The bound is three standard errors, derived from the sample rather than chosen.** The rate is
     * a ratio of two counts over a random corpus, so a fixed tolerance is either loose enough to hide
     * a real disagreement or tight enough to fail on sampling noise — the first draft of this test
     * picked 0.03 and failed at two elements per document on a deviation of 0.036, which was well
     * inside one standard error. Same rule as the HyperLogLog's: a bound over an estimate is stated in
     * standard errors, never as a round number.
     */
    @Test
    fun `independent fields match the closed-form prediction`() {
        val skus = 20
        val quantities = 5
        for (elements in listOf(1, 2, 4, 8)) {
            val random = Random(seed = 20260808L + elements)
            val documents = List(DOCUMENTS) {
                val items = (0 until elements).joinToString(",") {
                    """{"sku":"sku-${random.nextInt(skus)}","qty":"qty-${random.nextInt(quantities)}"}"""
                }
                document("[$items]")
            }

            val cost = CorrelationCost.measure(
                documents,
                "items",
                ElementMatch("sku", "sku-0"),
                ElementMatch("qty", "qty-0"),
            )
            val predicted = CorrelationCost.predictedRate(elements, 1.0 / skus, 1.0 / quantities)
            val bound = 3 * standardError(predicted, cost.uncorrelatedMatches) + EXACT

            assertTrue(cost.uncorrelatedMatches > 0, "at $elements element(s) nothing matched at all")
            assertTrue(
                abs(cost.falsePositiveRate - predicted) < bound,
                "at $elements element(s) the measured rate ${cost.falsePositiveRate} is not the " +
                    "predicted $predicted within $bound; either the corpus is not independent, or the " +
                    "counting is wrong",
            )
        }
    }

    /** How much a rate over [samples} documents is expected to wander. Zero when the rate is exact. */
    private fun standardError(rate: Double, samples: Long): Double =
        if (samples == 0L) 0.0 else sqrt(rate * (1.0 - rate) / samples)

    /** One element per document cannot produce a false positive: the two questions are the same one. */
    @Test
    fun `a single element per document makes the two answers identical`() {
        assertEquals(0.0, CorrelationCost.predictedRate(1, 0.1, 0.2), EXACT)
        assertTrue(CorrelationCost.predictedRate(2, 0.01, 0.01) > 0.4, "two elements already lose half")
        assertTrue(CorrelationCost.predictedRate(32, 0.01, 0.01) > 0.9, "and 32 lose almost everything")
    }

    /**
     * Fields that move together cost the caller nothing — the endpoint that argues *against* the
     * feature, arranged rather than assumed.
     */
    @Test
    fun `a field determined by another produces no false positives at all`() {
        val random = Random(seed = 20260808L)
        val documents = List(DOCUMENTS) {
            val items = (0 until 8).joinToString(",") {
                val sku = random.nextInt(20)
                """{"sku":"sku-$sku","qty":"qty-${sku % 5}"}"""
            }
            document("[$items]")
        }

        val cost = CorrelationCost.measure(
            documents,
            "items",
            ElementMatch("sku", "sku-0"),
            ElementMatch("qty", "qty-0"),
        )

        assertTrue(cost.correlatedMatches > 0, "the fixture must match something")
        assertEquals(cost.correlatedMatches, cost.uncorrelatedMatches)
        assertEquals(0.0, cost.falsePositiveRate)
    }

    private fun document(items: String): Variant = Variant.fromJson("""{"items":$items}""")

    private companion object {
        const val DOCUMENTS = 20_000

        /** Not a tolerance on a measurement — the slack a `Double` needs to say "algebraically zero". */
        const val EXACT = 1e-9
    }
}
