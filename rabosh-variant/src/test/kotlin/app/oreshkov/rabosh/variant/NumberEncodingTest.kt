package app.oreshkov.rabosh.variant

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Number promotion, at every boundary where the chosen physical type changes.
 *
 * These boundaries are where a JSON codec quietly loses data — a value one past `Long.MAX_VALUE`
 * becoming a `double`, a 39-digit decimal being rounded — so each one is asserted both for the
 * type it lands on and for the value coming back unchanged.
 */
class NumberEncodingTest {

    private fun typeOf(literal: String): VariantPrimitiveType? = Variant.fromJson(literal).primitiveType

    private fun roundtrip(literal: String): String = Variant.fromJson(literal).toJsonString()

    @Test
    fun `integers take the narrowest width that holds them`() {
        assertEquals(VariantPrimitiveType.INT8, typeOf("0"))
        assertEquals(VariantPrimitiveType.INT8, typeOf("127"))
        assertEquals(VariantPrimitiveType.INT8, typeOf("-128"))
        assertEquals(VariantPrimitiveType.INT16, typeOf("128"))
        assertEquals(VariantPrimitiveType.INT16, typeOf("-129"))
        assertEquals(VariantPrimitiveType.INT16, typeOf("32767"))
        assertEquals(VariantPrimitiveType.INT32, typeOf("32768"))
        assertEquals(VariantPrimitiveType.INT32, typeOf("2147483647"))
        assertEquals(VariantPrimitiveType.INT64, typeOf("2147483648"))
        assertEquals(VariantPrimitiveType.INT64, typeOf("9223372036854775807"))
        assertEquals(VariantPrimitiveType.INT64, typeOf("-9223372036854775808"))
    }

    @Test
    fun `integers beyond Long become exact decimals, not doubles`() {
        // The value that catches an encoder using `Double` as its fallback: 9223372036854775808
        // has no `double` representation and would come back as 9223372036854775808 only by luck.
        assertEquals(VariantPrimitiveType.DECIMAL16, typeOf("9223372036854775808"))
        assertEquals("9223372036854775808", roundtrip("9223372036854775808"))
        assertEquals("-9223372036854775809", roundtrip("-9223372036854775809"))

        val thirtyEightDigits = "1".repeat(38)
        assertEquals(VariantPrimitiveType.DECIMAL16, typeOf(thirtyEightDigits))
        assertEquals(thirtyEightDigits, roundtrip(thirtyEightDigits))
    }

    @Test
    fun `decimals take the narrowest exact width`() {
        assertEquals(VariantPrimitiveType.DECIMAL4, typeOf("1.23"))
        assertEquals(VariantPrimitiveType.DECIMAL4, typeOf("0.999999999"), "precision 9 is the decimal4 limit")
        assertEquals(VariantPrimitiveType.DECIMAL8, typeOf("0.9999999999"), "precision 10 needs decimal8")
        assertEquals(VariantPrimitiveType.DECIMAL4, typeOf("0.000000000000000001"), "scale 18, precision 1")
        assertEquals(VariantPrimitiveType.DECIMAL8, typeOf("123456789.123456789"))
        assertEquals(VariantPrimitiveType.DECIMAL16, typeOf("1.2345678901234567890123456789"))
        // An integer is never a decimal, however many digits it has, as long as it fits Long.
        assertEquals(VariantPrimitiveType.INT32, typeOf("999999999"))

        assertEquals(BigDecimal("1.23"), Variant.fromJson("1.23").decimalValue())
        assertEquals(BigDecimal("-0.5"), Variant.fromJson("-0.5").decimalValue())
    }

    @Test
    fun `trailing zeros and exponents are normalised away`() {
        // 1.500 and 1.5 are the same number and must not encode differently.
        assertContentEqualsHex(Variant.fromJson("1.5").toByteArray(), Variant.fromJson("1.500").toByteArray())
        // A negative scale has no encoding, so an exponent folds back into the value.
        assertEquals(VariantPrimitiveType.INT64, typeOf("1.0e10"))
        assertEquals("10000000000", roundtrip("1.0e10"))
        assertEquals(VariantPrimitiveType.INT8, typeOf("1e2"))
        assertEquals("100", roundtrip("1e2"))
    }

    /**
     * The codec's one lossy path, and the reason it exists: JSON's number grammar is unbounded and
     * the encoding is not, so a literal that fits neither `Long` nor 38 digits has to widen.
     */
    @Test
    fun `what fits nothing exact becomes a double`() {
        assertEquals(VariantPrimitiveType.DOUBLE, typeOf("1e400"), "beyond every exact type")
        assertEquals(VariantPrimitiveType.DOUBLE, typeOf("1.0e308"))
        assertEquals(VariantPrimitiveType.DOUBLE, typeOf("0." + "0".repeat(38) + "1"), "scale 39")
        assertEquals(VariantPrimitiveType.DOUBLE, typeOf("1".repeat(39)), "precision 39")

        assertTrue(Variant.fromJson("1e400").doubleValue().isInfinite())
        assertEquals(1.0e308, Variant.fromJson("1.0e308").doubleValue(), absoluteTolerance = 0.0)
    }

    @Test
    fun `negative zero is a zero`() {
        // JSON has -0; the exact-integer path does not, and the two are numerically equal.
        assertEquals("0", roundtrip("-0"))
        assertEquals(0, Variant.fromJson("-0").longValue())
    }

    @Test
    fun `floats print through their stored width`() {
        val variant = VariantBuilder().apply { appendFloat(0.1f) }.buildVariant()
        // 0.1f widened to a double prints as 0.10000000149011612; through Float it stays 0.1.
        assertEquals("0.1", variant.toJsonString())
        assertEquals(VariantPrimitiveType.FLOAT, variant.primitiveType)
    }

    @Test
    fun `a decimal the encoding cannot hold is refused rather than rounded`() {
        val builder = VariantBuilder()
        val failure = runCatching { builder.appendDecimal(BigDecimal("1".repeat(39))) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException, "expected a rejection, got $failure")
        assertTrue("precision" in failure.message.orEmpty(), failure.message.orEmpty())

        val negativeScale = runCatching { VariantBuilder().appendDecimal(BigDecimal("1E+10")) }.exceptionOrNull()
        assertTrue(negativeScale is IllegalArgumentException, "a negative scale has no encoding")
    }

    @Test
    fun `long integer literals are parsed without losing digits`() {
        // 19 digits: past the parser's inline accumulator, still inside Long.
        assertEquals(VariantPrimitiveType.INT64, typeOf("1234567890123456789"))
        assertEquals("1234567890123456789", roundtrip("1234567890123456789"))
        assertEquals(9_223_372_036_854_775_807L, Variant.fromJson("9223372036854775807").longValue())
    }
}
