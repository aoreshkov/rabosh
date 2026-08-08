package app.oreshkov.rabosh.bench

import app.oreshkov.rabosh.variant.Variant
import kotlin.random.Random

/**
 * The gate on a composite-term index: how much the uncorrelated conjunction over-returns.
 *
 * ```
 * ./gradlew :rabosh-bench:runCorrelationCost
 * ```
 *
 * A sweep rather than a before/after, over the axis the model in [CorrelationCost] says decides
 * everything — **elements per document** — and run twice over shapes that move the answer in opposite
 * directions:
 *
 * - **independent** — an element's two fields are drawn separately. The unfavourable shape for the
 *   engine as it stands, and the one the model predicts `1 - 1/n` for.
 * - **correlated** — the second field is a function of the first, so any element matching one leaf
 *   matches the other. The favourable shape: the two answers coincide and the rate is zero.
 *
 * Neither is a guess about what real data looks like; between them they bracket it, which is the
 * point. A corpus's own position between the two is the thing to measure when there is one, and that
 * is what a decision to build the index has to rest on rather than on either endpoint.
 *
 * Everything printed is ASCII: `System.out` encodes to the console codepage on Windows, and for a
 * diagnostic the output *is* the deliverable.
 */
object CorrelationCostMain {

    private const val DOCUMENTS = 20_000
    private const val SKU_VALUES = 40
    private const val QTY_VALUES = 8
    private val ELEMENT_COUNTS = intArrayOf(1, 2, 3, 4, 6, 8, 12, 16, 24, 32)

    @JvmStatic
    fun main(arguments: Array<String>) {
        val seed = arguments.firstOrNull()?.toLongOrNull() ?: 20260808L
        println("false positives of the uncorrelated conjunction")
        println("  documents per row : $DOCUMENTS")
        println("  question          : some element has sku == 'sku-0' and qty == 'qty-0'")
        println("  asked as          : and(\$.items[*].sku eq 'sku-0', \$.items[*].qty eq 'qty-0')")
        println("  seed              : $seed")
        println()

        val probability = 1.0 / SKU_VALUES
        println("independent fields - the shape that argues for a composite term")
        header()
        for (elements in ELEMENT_COUNTS) {
            val corpus = corpus(elements, seed, correlated = false)
            val measured = CorrelationCost.measure(corpus, "items", SKU, QTY)
            val predicted = CorrelationCost.predictedRate(elements, probability, 1.0 / QTY_VALUES)
            row(elements, measured, predicted)
        }

        println()
        println("correlated fields - the shape that argues against it")
        header()
        for (elements in ELEMENT_COUNTS) {
            val corpus = corpus(elements, seed, correlated = true)
            val measured = CorrelationCost.measure(corpus, "items", SKU, QTY)
            row(elements, measured, predicted = 0.0)
        }

        println()
        println("the honest headline is the smaller number: a corpus whose fields move together")
        println("costs the caller nothing, and one whose fields are independent costs 1 - 1/n.")
    }

    private fun header() {
        println("  elements  correlated  uncorrelated  false-positive rate  predicted  amplification")
    }

    private fun row(elements: Int, measured: CorrelationCost, predicted: Double) {
        println(
            "  %8d  %10d  %12d  %19.4f  %9.4f  %13s".format(
                elements,
                measured.correlatedMatches,
                measured.uncorrelatedMatches,
                measured.falsePositiveRate,
                predicted,
                if (measured.amplification.isNaN()) "-" else "%.2fx".format(measured.amplification),
            ),
        )
    }

    /**
     * A corpus of [DOCUMENTS] documents, each with [elements] items.
     *
     * The two regimes differ in one line — whether `qty` is drawn or derived — so the sweep compares
     * shapes rather than corpora, which is what keeps the two rows readable against each other.
     */
    private fun corpus(elements: Int, seed: Long, correlated: Boolean): List<Variant> {
        val random = Random(seed)
        return List(DOCUMENTS) {
            val items = (0 until elements).joinToString(",") {
                val sku = random.nextInt(SKU_VALUES)
                val qty = if (correlated) sku % QTY_VALUES else random.nextInt(QTY_VALUES)
                """{"sku":"sku-$sku","qty":"qty-$qty"}"""
            }
            Variant.fromJson("""{"items":[$items]}""")
        }
    }

    private val SKU = ElementMatch("sku", "sku-0")
    private val QTY = ElementMatch("qty", "qty-0")
}
