package app.oreshkov.rabosh.bench

import app.oreshkov.rabosh.variant.Variant
import app.oreshkov.rabosh.variant.VariantBasicType
import app.oreshkov.rabosh.variant.VariantKind

/**
 * How many documents an **uncorrelated** conjunction returns that no single element justifies.
 *
 * This is the number three open items have been waiting on. A conjunction over an array path is
 * existential in each leaf *independently*, so
 *
 * ```
 * and($.items[*].sku eq "A", $.items[*].qty eq 5)
 * ```
 *
 * matches `{"items":[{"sku":"A","qty":1},{"sku":"B","qty":5}]}` — the `sku` from element 0 and the
 * `qty` from element 1. That is a defined semantics and the indexed and unindexed answers agree
 * exactly, so nothing is wrong. What is unknown is how much it *costs* a caller who wanted the
 * correlated question: every extra document is one the engine opened, decoded and handed back for the
 * caller's own walk to reject.
 *
 * Two counts over the same corpus, so the answer is a ratio rather than a magnitude:
 *
 * - [correlatedMatches] — documents with **one element** satisfying both. What the caller wanted.
 * - [uncorrelatedMatches] — documents the conjunction returns. What the engine gives them.
 *
 * **The model, which was arithmetic before it was a measurement.** Take *n* elements per document and
 * per-element probabilities *p* and *q*, independent. A document matches the correlated question
 * unless no element satisfies both, so `1 - (1 - pq)^n`; it matches the uncorrelated one when each
 * leaf finds *some* element, so `(1 - (1-p)^n)(1 - (1-q)^n)`. For small *p* and *q* those are
 * approximately `npq` and `n²pq`, so the false-positive rate approaches **`1 - 1/n`** — a property of
 * how many elements a document has, and almost nothing else. [predictedRate] is that formula, and
 * `CorrelationCostTest` checks the measurement against it rather than against a remembered number.
 *
 * **So the shape of the data decides everything, and the sweep therefore runs twice.** Fields that
 * move together in the data — a `sku` that determines its `qty` — make the two counts coincide and
 * the rate collapse to zero. Independent fields make it `1 - 1/n`. Reporting only the second would be
 * arranging the favourable case for a feature; `CorrelationCostMain` reports both, and the honest
 * headline is the **smaller** number, because that is the one that has to justify the bytes.
 */
class CorrelationCost(
    /** Documents examined. */
    val documents: Long,
    /** Documents where one element satisfies both leaves. */
    val correlatedMatches: Long,
    /** Documents the uncorrelated conjunction returns — always at least [correlatedMatches]. */
    val uncorrelatedMatches: Long,
    /** Array elements visited, so a rate can be read against the work that produced it. */
    val elementsScanned: Long,
) {
    init {
        require(uncorrelatedMatches >= correlatedMatches) {
            "the uncorrelated answer is a superset by construction, so $uncorrelatedMatches cannot be " +
                "below $correlatedMatches — the measurement is wrong, not the engine"
        }
    }

    /** Documents returned that no single element justifies. */
    val falsePositives: Long get() = uncorrelatedMatches - correlatedMatches

    /** Of the documents returned, the share the caller has to discard. `0.0` when nothing matched. */
    val falsePositiveRate: Double
        get() = if (uncorrelatedMatches == 0L) 0.0 else falsePositives.toDouble() / uncorrelatedMatches

    /**
     * Documents read per document wanted.
     *
     * The form the cost is actually paid in: a caller asking the correlated question through the
     * uncorrelated conjunction opens this many documents for each one they keep. `1.0` when the two
     * answers coincide, and unbounded as the elements per document grow.
     */
    val amplification: Double
        get() = if (correlatedMatches == 0L) Double.NaN else uncorrelatedMatches.toDouble() / correlatedMatches

    override fun toString(): String =
        "CorrelationCost(documents=$documents, correlated=$correlatedMatches, " +
            "uncorrelated=$uncorrelatedMatches, rate=${"%.4f".format(falsePositiveRate)})"

    companion object {
        /**
         * Counts both answers over [documents].
         *
         * An element is an object under `$.<arrayField>[*]`; a leaf is satisfied by an element whose
         * [ElementMatch.field] holds [ElementMatch.value] as a string. Deliberately the narrowest
         * predicate shape that can express the question — the point is the *shape of the answer set*,
         * and a richer predicate would only move both counts together.
         */
        fun measure(
            documents: Iterable<Variant>,
            arrayField: String,
            first: ElementMatch,
            second: ElementMatch,
        ): CorrelationCost {
            var count = 0L
            var correlated = 0L
            var uncorrelated = 0L
            var elements = 0L

            for (document in documents) {
                count++
                var anyFirst = false
                var anySecond = false
                var anyBoth = false

                val items = document.field(arrayField)
                if (items != null && items.basicType == VariantBasicType.ARRAY) {
                    val size = items.elementCount
                    elements += size
                    for (index in 0 until size) {
                        val element = items.element(index)
                        val matchesFirst = matches(element, first)
                        val matchesSecond = matches(element, second)
                        anyFirst = anyFirst || matchesFirst
                        anySecond = anySecond || matchesSecond
                        anyBoth = anyBoth || (matchesFirst && matchesSecond)
                    }
                }

                if (anyBoth) correlated++
                if (anyFirst && anySecond) uncorrelated++
            }

            return CorrelationCost(count, correlated, uncorrelated, elements)
        }

        /**
         * The false-positive rate the model above predicts for independent fields.
         *
         * Stated as a function so the measurement can be checked against it rather than against a
         * number somebody wrote down. A disagreement means either the corpus is not independent or
         * the counting is wrong, and both are worth finding out.
         */
        fun predictedRate(elementsPerDocument: Int, firstProbability: Double, secondProbability: Double): Double {
            require(elementsPerDocument >= 0) { "elementsPerDocument must not be negative" }
            require(firstProbability in 0.0..1.0 && secondProbability in 0.0..1.0) {
                "probabilities must lie in 0..1"
            }
            val n = elementsPerDocument
            val correlated = 1.0 - power(1.0 - firstProbability * secondProbability, n)
            val uncorrelated = (1.0 - power(1.0 - firstProbability, n)) * (1.0 - power(1.0 - secondProbability, n))
            if (uncorrelated == 0.0) return 0.0
            // Clamped at zero because the quantity *is* a rate: the uncorrelated answer is a superset
            // by construction, so the true value cannot be negative, and at one element per document
            // the two expressions are algebraically equal and differ only in the last bits.
            return (1.0 - correlated / uncorrelated).coerceAtLeast(0.0)
        }

        private fun power(base: Double, exponent: Int): Double {
            var result = 1.0
            repeat(exponent) { result *= base }
            return result
        }

        private fun matches(element: Variant, match: ElementMatch): Boolean {
            if (element.basicType != VariantBasicType.OBJECT) return false
            val value = element.field(match.field) ?: return false
            return value.kind == VariantKind.STRING && value.stringValue() == match.value
        }
    }
}

/** One leaf of the conjunction: the element field to look at, and the value it must carry. */
class ElementMatch(val field: String, val value: String) {
    override fun toString(): String = "$field == '$value'"
}
