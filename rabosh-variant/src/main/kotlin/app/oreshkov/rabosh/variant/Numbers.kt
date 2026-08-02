package app.oreshkov.rabosh.variant

import java.math.BigDecimal

/** Widest decimal the encoding can hold: `decimal16` carries an int128 unscaled value. */
public const val MAX_DECIMAL_PRECISION: Int = 38

/** The specification fixes the scale byte's range at `[0, 38]`. */
public const val MAX_DECIMAL_SCALE: Int = 38

/**
 * Maps a JSON number literal onto a physical type. The rule and its rationale are documented on
 * [VariantBuilder.appendNumberLiteral], which is the public face of this decision.
 */
internal fun decideNumber(literal: String): NumberEncoding {
    val decimal = try {
        BigDecimal(literal)
    } catch (failure: NumberFormatException) {
        throw IllegalArgumentException("'$literal' is not a JSON number", failure)
    }
    val stripped = decimal.stripTrailingZeros()
    // `stripTrailingZeros` can leave a negative scale (1.0e10 -> 1E+10); the encoding has no such
    // thing, so the exponent is folded back into the unscaled value before the widths are chosen.
    val normalised = if (stripped.scale() < 0) stripped.setScale(0) else stripped
    if (normalised.scale() == 0) {
        val asLong = normalised.toBigIntegerExact().let { if (it.bitLength() < Long.SIZE_BITS) it.toLong() else null }
        if (asLong != null) return NumberEncoding.Integer(asLong)
    }
    if (normalised.scale() in 0..MAX_DECIMAL_SCALE && normalised.precision() <= MAX_DECIMAL_PRECISION) {
        return NumberEncoding.Decimal(normalised)
    }
    return NumberEncoding.Double(literal.toDouble())
}

/** The outcome of [decideNumber]. */
internal sealed interface NumberEncoding {
    @JvmInline
    value class Integer(val value: Long) : NumberEncoding

    @JvmInline
    value class Decimal(val value: BigDecimal) : NumberEncoding

    @JvmInline
    value class Double(val value: kotlin.Double) : NumberEncoding
}
