package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.variant.Variant
import app.oreshkov.rabosh.variant.VariantKind
import java.math.BigDecimal

/**
 * A test a shredded column can answer without opening a document.
 *
 * **Type bracketing is part of the contract, and it has to be**, because skipping depends on it. A
 * [numericRange] matches values of numeric kind only; a [textRange] matches strings only; a value of
 * any other kind is **not a match and not an error**. Without that rule, a column whose numeric bound
 * misses the predicate could not be skipped merely because the segment also holds strings at the
 * path — and with it, one bound slot is enough to rule a segment out.
 *
 * It is the rule MongoDB and JavaScript both take and SQL/JSON declines; what matters here is less
 * which one than that it is written down and that **the recheck uses the same one**. `CLAUDE.md`
 * requires the recheck to run the same logic that built the index, so [matches] is what both the
 * column scan and the fallback document scan evaluate.
 */
public class ColumnPredicate private constructor(
    internal val kind: Kind,
    internal val numericMin: BigDecimal?,
    internal val numericMax: BigDecimal?,
    internal val textMin: ByteArray?,
    internal val textMax: ByteArray?,
    internal val boolean: Boolean,
    /** Whether the lower bound is `>` rather than `>=`. See [numericRange]. */
    internal val minExclusive: Boolean = false,
    /** Whether the upper bound is `<` rather than `<=`. */
    internal val maxExclusive: Boolean = false,
) {
    internal enum class Kind { NUMERIC, TEXT, BOOLEAN, IS_NULL, EXISTS }

    /** Whether [value] satisfies this predicate. The one definition, used by index and scan alike. */
    public fun matches(value: Variant): Boolean = when (kind) {
        Kind.EXISTS -> true
        Kind.IS_NULL -> value.kind == VariantKind.NULL

        Kind.NUMERIC -> when (value.kind) {
            VariantKind.INTEGER -> matchesNumber(BigDecimal.valueOf(value.longValue()))
            VariantKind.DECIMAL -> matchesNumber(value.decimalValue())
            VariantKind.FLOAT, VariantKind.DOUBLE ->
                value.doubleValue().let { it.isFinite() && matchesNumber(BigDecimal.valueOf(it)) }

            else -> false
        }

        Kind.TEXT ->
            value.kind == VariantKind.STRING && matchesText(value.stringValue().encodeToByteArray())

        Kind.BOOLEAN -> value.kind == VariantKind.BOOLEAN && value.booleanValue() == boolean
    }

    internal fun matchesNumber(value: BigDecimal): Boolean {
        if (numericMin != null && !within(value.compareTo(numericMin), minExclusive, lower = true)) return false
        if (numericMax != null && !within(value.compareTo(numericMax), maxExclusive, lower = false)) return false
        return true
    }

    internal fun matchesText(value: ByteArray): Boolean {
        if (textMin != null && !within(compareText(value, textMin), minExclusive, lower = true)) return false
        if (textMax != null && !within(compareText(value, textMax), maxExclusive, lower = false)) return false
        return true
    }

    /** Whether a value comparing [comparison] against a bound is inside it. */
    private fun within(comparison: Int, exclusive: Boolean, lower: Boolean): Boolean = when {
        comparison == 0 -> !exclusive
        lower -> comparison > 0
        else -> comparison < 0
    }

    /**
     * Whether a segment with these bounds could hold a match.
     *
     * Exclusivity is deliberately *not* applied to either pruning test. A skip test may be generous
     * and may never be narrow: keeping a segment whose only value is the excluded endpoint costs one
     * block scan that finds nothing, while skipping one that holds a match deletes documents from a
     * result. The exactness lives in [matchesNumber] and [matchesText], where a wrong answer is
     * impossible rather than merely unlikely.
     */
    internal fun mayContain(bounds: ColumnSegmentBounds): Boolean = when (kind) {
        Kind.NUMERIC -> bounds.mayContainNumeric(numericMin, numericMax)
        Kind.TEXT -> bounds.mayContainText(textMin, textMax)
        // Nothing in a segment bound speaks to booleans, nulls or existence, so no segment is ruled
        // out by them. A bound that answered a question it does not cover is how over-pruning starts.
        Kind.BOOLEAN, Kind.IS_NULL, Kind.EXISTS -> true
    }

    /** Whether block [block] of [column] could hold a match. */
    internal fun mayContain(column: ColumnFile, block: Int): Boolean = when (kind) {
        Kind.NUMERIC -> column.blockMayContainNumeric(block, numericMin, numericMax)
        Kind.TEXT -> column.blockMayContainText(block, textMin, textMax)
        Kind.BOOLEAN -> column.blockMayContainBoolean(block, boolean)
        // A null occupies a value slot, so any block with one could match; existence needs no test.
        Kind.IS_NULL, Kind.EXISTS -> true
    }

    /** Whether a null value satisfies this. Only [isNull] does; a range never matches a null. */
    internal fun matchesNull(): Boolean = kind == Kind.IS_NULL || kind == Kind.EXISTS

    /** Whether the column's own type can answer this at all, or every ordinal must be rechecked. */
    internal fun answerableBy(type: ColumnType): Boolean = when (kind) {
        Kind.NUMERIC -> type.isNumeric
        Kind.TEXT -> type.id == ColumnFormat.COLUMN_TYPE_STRING
        Kind.BOOLEAN -> type.id == ColumnFormat.COLUMN_TYPE_BOOLEAN
        Kind.IS_NULL, Kind.EXISTS -> true
    }

    private val open: String get() = if (minExclusive) "(" else "["
    private val close: String get() = if (maxExclusive) ")" else "]"

    override fun toString(): String = when (kind) {
        Kind.NUMERIC -> "numeric in $open${numericMin ?: "-inf"}, ${numericMax ?: "+inf"}$close"
        Kind.TEXT -> "text in $open${textMin?.decodeToString() ?: ""}, ${textMax?.decodeToString() ?: "+inf"}$close"
        Kind.BOOLEAN -> "= $boolean"
        Kind.IS_NULL -> "is null"
        Kind.EXISTS -> "exists"
    }

    public companion object {
        /** Numbers between [min] and [max] inclusive. A `null` bound is unbounded on that side. */
        public fun numericRange(min: BigDecimal?, max: BigDecimal?): ColumnPredicate {
            require(min != null || max != null) { "a range needs at least one bound; use exists() otherwise" }
            return ColumnPredicate(Kind.NUMERIC, min, max, null, null, false)
        }

        /**
         * Numbers between [min] and [max], with either bound optionally *exclusive*.
         *
         * The strict form is here rather than in a caller because a caller could only approximate it:
         * an inclusive range plus a separate "and not the endpoint" test is a second definition of
         * matching, and one of the two would eventually disagree with the other about a value of
         * another type. The endpoint of a strict bound is also the one value a caller must not be
         * allowed to admit from the column alone, which is exactly the sort of thing that has to be
         * decided where the matching is.
         */
        public fun numericRange(
            min: BigDecimal?,
            minExclusive: Boolean,
            max: BigDecimal?,
            maxExclusive: Boolean,
        ): ColumnPredicate {
            require(min != null || max != null) { "a range needs at least one bound; use exists() otherwise" }
            return ColumnPredicate(Kind.NUMERIC, min, max, null, null, false, minExclusive, maxExclusive)
        }

        /** Exactly [value], compared as a number whatever width the document stored it at. */
        public fun numericEqualTo(value: BigDecimal): ColumnPredicate =
            ColumnPredicate(Kind.NUMERIC, value, value, null, null, false)

        /** Exactly [value]. */
        public fun numericEqualTo(value: Long): ColumnPredicate = numericEqualTo(BigDecimal.valueOf(value))

        /** Strings between [min] and [max] inclusive, in unsigned UTF-8 byte order. */
        public fun textRange(min: String?, max: String?): ColumnPredicate {
            require(min != null || max != null) { "a range needs at least one bound; use exists() otherwise" }
            return ColumnPredicate(
                Kind.TEXT,
                null,
                null,
                min?.encodeToByteArray(),
                max?.encodeToByteArray(),
                false,
            )
        }

        /** Strings between [min] and [max] in unsigned UTF-8 byte order, either bound exclusive. */
        public fun textRange(
            min: String?,
            minExclusive: Boolean,
            max: String?,
            maxExclusive: Boolean,
        ): ColumnPredicate {
            require(min != null || max != null) { "a range needs at least one bound; use exists() otherwise" }
            return ColumnPredicate(
                Kind.TEXT,
                null,
                null,
                min?.encodeToByteArray(),
                max?.encodeToByteArray(),
                false,
                minExclusive,
                maxExclusive,
            )
        }

        /** Exactly [value], as a string. */
        public fun textEqualTo(value: String): ColumnPredicate {
            val bytes = value.encodeToByteArray()
            return ColumnPredicate(Kind.TEXT, null, null, bytes, bytes, false)
        }

        /** Exactly [value], as a boolean. */
        public fun booleanEqualTo(value: Boolean): ColumnPredicate =
            ColumnPredicate(Kind.BOOLEAN, null, null, null, null, value)

        /** The path is present and its value is the JSON null. */
        public fun isNull(): ColumnPredicate = ColumnPredicate(Kind.IS_NULL, null, null, null, null, false)

        /** The path is present, whatever it holds. */
        public fun exists(): ColumnPredicate = ColumnPredicate(Kind.EXISTS, null, null, null, null, false)
    }
}
