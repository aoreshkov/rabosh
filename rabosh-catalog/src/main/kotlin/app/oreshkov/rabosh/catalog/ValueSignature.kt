package app.oreshkov.rabosh.catalog

import app.oreshkov.rabosh.variant.Variant
import app.oreshkov.rabosh.variant.VariantKind
import java.math.BigDecimal

/**
 * The canonical byte string a scalar is identified by.
 *
 * This is the single answer to "when are two values the same value", and it is shared rather than
 * reimplemented for a reason that is worth stating plainly: the catalog counts distinct values with
 * it to decide whether a path is **worth** an inverted index, and `rabosh-index` keys its term
 * dictionary with it to **build** one. An estimator that disagreed with the index it recommended
 * would not crash — it would recommend against indexing a column whose real cardinality is small, or
 * build an index whose terms a query could never spell. That is the same argument that put the
 * container read algorithms in one place in `rabosh-index`, and it applies harder here, because the
 * failure is silent on both sides.
 *
 * **Canonical across widths.** Every numeric width shares [NUMERIC] and goes through
 * `stripTrailingZeros().toPlainString()`, so `1` written as an `int8`, `1.0` written as a `double`
 * and `1.00` written as a decimal are **one** value. Scale is a property of how a value was written,
 * not of what it is, and a query asking for `1` means all three.
 *
 * **This is a lookup order, not a value order.** Signatures sort by tag and then by bytes, so
 * `NUMERIC || "10"` sorts before `NUMERIC || "9"`. That is exactly what a term dictionary needs — a
 * total order to binary search — and exactly what a range predicate must not be answered with. An
 * inverted index answers equality, `IN` and existence; ordered skipping is a shredded column's job.
 *
 * **The tags are permanent.** Every HyperLogLog register ever written is a function of them, and so
 * is every term in every posting file. Add, never renumber.
 */
public object ValueSignature {
    /** `true` or `false`. One payload byte, `1` or `0`. */
    public const val BOOLEAN: Int = 0

    /** Any number of any width. Payload is the plain-string form with trailing zeros stripped. */
    public const val NUMERIC: Int = 1

    /** A string. Payload is its UTF-8 bytes. */
    public const val TEXT: Int = 2

    /** A byte string. Payload is the bytes themselves. */
    public const val BINARY: Int = 3

    /** A date, time or timestamp. Payload is the decimal form of its epoch-relative value. */
    public const val TEMPORAL: Int = 4

    /** A UUID. Payload is its canonical hyphenated text. */
    public const val UUID: Int = 5

    /**
     * The signature of [value], or `null` if it does not have one.
     *
     * `null` is returned for a JSON null and for containers, and the two are `null` for different
     * reasons. A null is *present*, not absent — `PathSketch.nullObservations` already says so — and
     * counting it as a distinct value would make a column of nothing but nulls look worth indexing.
     * A container is a place to look *inside*: hashing a subtree would cost the whole subtree per
     * document, and the paths within it are being considered in their own right anyway.
     *
     * Exhaustive with no `else`, so a kind added to [VariantKind] must be given a signature here
     * rather than silently inheriting one.
     */
    public fun of(value: Variant): ByteArray? = when (value.kind) {
        VariantKind.BOOLEAN -> ofBoolean(value.booleanValue())

        VariantKind.INTEGER -> ofNumber(BigDecimal.valueOf(value.longValue()))

        VariantKind.DECIMAL -> ofNumber(value.decimalValue())

        VariantKind.FLOAT, VariantKind.DOUBLE -> {
            val number = value.doubleValue()
            // NaN and the infinities have no place in an ordered bound — they would make a range
            // meaningless — but they are still perfectly good distinct values, and a query can ask
            // for one. They keep the numeric tag and carry their Java text form.
            if (number.isFinite()) ofNumber(BigDecimal.valueOf(number)) else tagged(NUMERIC, number.toString())
        }

        VariantKind.STRING -> ofText(value.stringValue())

        VariantKind.BINARY -> tagged(BINARY, value.binaryValue())

        VariantKind.DATE -> tagged(TEMPORAL, value.epochDay().toString())

        VariantKind.TIME, VariantKind.TIMESTAMP -> tagged(TEMPORAL, value.temporalValue().toString())

        VariantKind.UUID -> tagged(UUID, value.uuidValue().toString())

        VariantKind.NULL, VariantKind.ARRAY, VariantKind.OBJECT -> null
    }

    /** The signature of a boolean. */
    public fun ofBoolean(value: Boolean): ByteArray = byteArrayOf(BOOLEAN.toByte(), if (value) 1 else 0)

    /**
     * The signature of a number, whatever width it was stored at.
     *
     * Trailing zeros are stripped so that scale does not become part of a value's identity.
     */
    public fun ofNumber(value: BigDecimal): ByteArray =
        tagged(NUMERIC, value.stripTrailingZeros().toPlainString())

    /** The signature of a number given as an integer. */
    public fun ofNumber(value: Long): ByteArray = ofNumber(BigDecimal.valueOf(value))

    /**
     * The signature of a number given as a double.
     *
     * NaN and the infinities are signed the way [of] signs them, so a term built here and a term
     * extracted from a document agree.
     */
    public fun ofNumber(value: Double): ByteArray =
        if (value.isFinite()) ofNumber(BigDecimal.valueOf(value)) else tagged(NUMERIC, value.toString())

    /** The signature of a string. */
    public fun ofText(value: String): ByteArray = tagged(TEXT, value.encodeToByteArray())

    /** The signature of a byte string. */
    public fun ofBinary(value: ByteArray): ByteArray = tagged(BINARY, value)

    /** The name of a tag, or `null` if this build does not know it. Never a default. */
    public fun tagName(tag: Int): String? = when (tag) {
        BOOLEAN -> "boolean"
        NUMERIC -> "numeric"
        TEXT -> "text"
        BINARY -> "binary"
        TEMPORAL -> "temporal"
        UUID -> "uuid"
        else -> null
    }

    private fun tagged(tag: Int, text: String): ByteArray = tagged(tag, text.encodeToByteArray())

    private fun tagged(tag: Int, payload: ByteArray): ByteArray {
        val bytes = ByteArray(payload.size + 1)
        bytes[0] = tag.toByte()
        payload.copyInto(bytes, 1)
        return bytes
    }
}
