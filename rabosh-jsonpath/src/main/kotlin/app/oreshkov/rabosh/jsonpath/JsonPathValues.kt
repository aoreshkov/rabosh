package app.oreshkov.rabosh.jsonpath

import app.oreshkov.rabosh.variant.Variant
import app.oreshkov.rabosh.variant.VariantKind
import java.math.BigDecimal

// RFC 9535 §2.3.5.2's comparison, over `Variant` values.
//
// **This is the second definition of comparison in the repository and it is not a defect, because
// the two can never decide the same question.** `ColumnPredicate.matches` in `rabosh-index` decides
// which *documents* a `Query` returns; everything here decides which *nodes* of one document a
// caller is shown. They disagree on purpose and in ways that cannot be reconciled — the engine's
// leaf is existential over every value at a path while `@.a` here *is* the array; the engine's `Not`
// is the complement of the document while `!` here is the complement of the candidate node — and the
// module boundary is what keeps them apart: nothing in the storage chain depends on this module, so
// no plan, no bound and no posting list can reach this file.
//
// Two rules from §2.3.5.2 that look like details and are not:
//
//   * **A comparison never fails.** A number against a string is `false`, not an error, and neither
//     is a missing path. Making either throw would mean every filter had to be written twice.
//   * **`Nothing == Nothing` is true**, which is what makes `$[?@.a == @.b]` select the objects that
//     have neither. It is also the rule most easily lost by an implementation that models absence as
//     `null`, so absence is its own case here and is never a Variant.

/** A `ValueType`: a JSON value, or RFC 9535's `Nothing`. */
internal sealed interface FilterValue {
    /** `Nothing` — the value of a query that selected no node. Not `null`, which is a JSON value. */
    object Absent : FilterValue

    /** A value from the document, or a literal the query carried. */
    class Node(val value: Variant) : FilterValue

    /**
     * An integer a function produced: `length()` and `count()` answer with one.
     *
     * Kept out of [Node] rather than built into a `Variant` so that evaluating a filter allocates
     * nothing per document. It is a number like any other to every comparison below.
     */
    class Integral(val value: Long) : FilterValue
}

/** Applies [operator] to two values. Total: every pair of values has an answer, and none throws. */
internal fun compareValues(left: FilterValue, operator: ComparisonOperator, right: FilterValue): Boolean =
    when (operator) {
        ComparisonOperator.EQUAL -> valuesEqual(left, right)
        ComparisonOperator.NOT_EQUAL -> !valuesEqual(left, right)
        ComparisonOperator.LESS -> valueLess(left, right)
        ComparisonOperator.LESS_OR_EQUAL -> valueLess(left, right) || valuesEqual(left, right)
        ComparisonOperator.GREATER -> valueLess(right, left)
        ComparisonOperator.GREATER_OR_EQUAL -> valueLess(right, left) || valuesEqual(left, right)
    }

private fun valuesEqual(left: FilterValue, right: FilterValue): Boolean {
    if (left is FilterValue.Absent || right is FilterValue.Absent) {
        return left is FilterValue.Absent && right is FilterValue.Absent
    }
    val leftNumber = numberOf(left)
    val rightNumber = numberOf(right)
    if (leftNumber != null || rightNumber != null) {
        return leftNumber != null && rightNumber != null && compareNumbers(leftNumber, rightNumber) == 0
    }
    // Neither is a number and neither is absent, so both are nodes.
    return variantsEqual((left as FilterValue.Node).value, (right as FilterValue.Node).value)
}

/**
 * `left < right`.
 *
 * Only two numbers and two strings are ordered; everything else — including two arrays, two objects,
 * two booleans and anything against `Nothing` — is unordered, which §2.3.5.2 spells as `false` for
 * both `<` and `>`. That is why `<=` is written as "less **or** equal" above rather than as a
 * negated `>`: `null <= null` is true and `null < null` is false.
 */
private fun valueLess(left: FilterValue, right: FilterValue): Boolean {
    val leftNumber = numberOf(left)
    val rightNumber = numberOf(right)
    if (leftNumber != null && rightNumber != null) return compareNumbers(leftNumber, rightNumber) < 0
    if (left !is FilterValue.Node || right !is FilterValue.Node) return false
    if (left.value.kind != VariantKind.STRING || right.value.kind != VariantKind.STRING) return false
    return compareByCodePoints(left.value.stringValue(), right.value.stringValue()) < 0
}

/**
 * Structural equality of two document values.
 *
 * Numbers are compared **by value and across widths**, which is why this is not a byte comparison:
 * `1` stored as an `int8` and `1.0` stored as a decimal are the same JSON number, and an array
 * holding one must equal an array holding the other.
 */
internal fun variantsEqual(left: Variant, right: Variant): Boolean {
    val leftNumber = numberOfVariant(left)
    val rightNumber = numberOfVariant(right)
    if (leftNumber != null || rightNumber != null) {
        return leftNumber != null && rightNumber != null && compareNumbers(leftNumber, rightNumber) == 0
    }

    val kind = left.kind
    if (kind != right.kind) return false
    return when (kind) {
        VariantKind.NULL -> true
        VariantKind.BOOLEAN -> left.booleanValue() == right.booleanValue()
        VariantKind.STRING -> left.stringValue() == right.stringValue()
        VariantKind.ARRAY -> elementsEqual(left, right)
        VariantKind.OBJECT -> fieldsEqual(left, right)

        // Handled above; a number is never reached here.
        VariantKind.INTEGER, VariantKind.FLOAT, VariantKind.DOUBLE, VariantKind.DECIMAL -> false

        // Values the JSON data model has no case for, so RFC 9535 has no rule for them either. The
        // engine can store all five and a query can hold a literal for none, so the only reachable
        // question is one document value against another — and answering it "never equal" would be a
        // silent wrong answer to a caller comparing two timestamps. Exact identity is the strongest
        // thing available that invents nothing: the same primitive type carrying the same bytes.
        VariantKind.BINARY,
        VariantKind.DATE,
        VariantKind.TIME,
        VariantKind.TIMESTAMP,
        VariantKind.UUID,
        -> left.primitiveType == right.primitiveType && left.toByteArray().contentEquals(right.toByteArray())
    }
}

private fun elementsEqual(left: Variant, right: Variant): Boolean {
    val count = left.elementCount
    if (count != right.elementCount) return false
    for (index in 0 until count) {
        if (!variantsEqual(left.element(index), right.element(index))) return false
    }
    return true
}

/**
 * Objects are equal when they carry the same member names and equal values at each.
 *
 * Compared pairwise rather than by lookup because a `Variant` object's fields are ordered by name —
 * the encoding requires it, and `Variant.field` already relies on it for its binary search — so two
 * objects with the same member names present them in the same order.
 */
private fun fieldsEqual(left: Variant, right: Variant): Boolean {
    val count = left.fieldCount
    if (count != right.fieldCount) return false
    for (index in 0 until count) {
        if (left.fieldName(index) != right.fieldName(index)) return false
        if (!variantsEqual(left.fieldValue(index), right.fieldValue(index))) return false
    }
    return true
}

/** Unicode scalar value order, which is what §2.3.5.2 means by comparing strings. */
internal fun compareByCodePoints(left: String, right: String): Int {
    var leftAt = 0
    var rightAt = 0
    while (leftAt < left.length && rightAt < right.length) {
        val leftPoint = left.codePointAt(leftAt)
        val rightPoint = right.codePointAt(rightAt)
        if (leftPoint != rightPoint) return leftPoint.compareTo(rightPoint)
        leftAt += Character.charCount(leftPoint)
        rightAt += Character.charCount(rightPoint)
    }
    return (left.length - leftAt).compareTo(right.length - rightAt)
}

/**
 * A number as it was stored, never widened.
 *
 * `Variant.doubleValue` deliberately refuses an integer, and this keeps that discipline: an `int64`
 * beyond a `double`'s exact range and a decimal with 38 digits both compare exactly, because neither
 * is put through a `Double` on the way. The widening happens once, in [compareNumbers], and only
 * when the two sides are not already the same representation.
 */
private sealed interface NumberValue {
    class Integral(val value: Long) : NumberValue

    class Decimal(val value: BigDecimal) : NumberValue

    class Floating(val value: Double) : NumberValue
}

private fun numberOf(value: FilterValue): NumberValue? = when (value) {
    is FilterValue.Absent -> null
    is FilterValue.Integral -> NumberValue.Integral(value.value)
    is FilterValue.Node -> numberOfVariant(value.value)
}

private fun numberOfVariant(value: Variant): NumberValue? = when (value.kind) {
    VariantKind.INTEGER -> NumberValue.Integral(value.longValue())
    VariantKind.DECIMAL -> NumberValue.Decimal(value.decimalValue())
    VariantKind.DOUBLE, VariantKind.FLOAT -> NumberValue.Floating(value.doubleValue())
    else -> null
}

/**
 * Compares two JSON numbers by value.
 *
 * `BigDecimal` is the widening, not `Double`, because `Double` cannot separate two `int64`s that
 * differ below its 53rd bit and this comparison decides which documents a caller is shown. The one
 * case it cannot take is a non-finite `Double` — reachable from a literal such as `1e1000`, which
 * the encoder stores as an infinity — so that is separated first and compared as doubles, where
 * infinity has the ordering the caller meant.
 */
private fun compareNumbers(left: NumberValue, right: NumberValue): Int {
    if (left is NumberValue.Integral && right is NumberValue.Integral) {
        return left.value.compareTo(right.value)
    }
    val leftDouble = toDouble(left)
    val rightDouble = toDouble(right)
    if (!leftDouble.isFinite() || !rightDouble.isFinite()) return leftDouble.compareTo(rightDouble)
    return toBigDecimal(left).compareTo(toBigDecimal(right))
}

private fun toDouble(value: NumberValue): Double = when (value) {
    is NumberValue.Integral -> value.value.toDouble()
    is NumberValue.Decimal -> value.value.toDouble()
    is NumberValue.Floating -> value.value
}

private fun toBigDecimal(value: NumberValue): BigDecimal = when (value) {
    is NumberValue.Integral -> BigDecimal.valueOf(value.value)
    is NumberValue.Decimal -> value.value
    // `valueOf` goes through `Double.toString`, so a double reads back as the shortest decimal that
    // round-trips to it — the digits the JSON text meant, rather than the binary expansion.
    is NumberValue.Floating -> BigDecimal.valueOf(value.value)
}
