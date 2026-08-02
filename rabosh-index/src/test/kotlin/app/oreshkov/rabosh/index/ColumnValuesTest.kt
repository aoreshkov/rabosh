package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.testkit.property.Gen
import app.oreshkov.rabosh.testkit.property.RandomSource
import app.oreshkov.rabosh.testkit.property.forAll
import java.lang.foreign.MemorySegment
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The property everything else in the phase rests on: **encoded order is value order.**
 *
 * A shredded column exists to answer range predicates and to prune blocks by their bounds, and both
 * reduce to comparing two stored integers. If that comparison ever disagrees with comparing the
 * values, a range query silently returns the wrong documents and a bound silently skips a block that
 * holds a match. Neither shows up as a fault.
 *
 * This is precisely what the inverted index's term order is *not*: `ValueSignature` sorts
 * `NUMERIC||"10"` before `NUMERIC||"9"`, which is a fine lookup order and a useless value order. The
 * two orderings coexisting in one engine is exactly why this is asserted directly rather than assumed.
 */
class ColumnValuesTest {

    private fun numbers(vararg literals: String): List<ColumnValue> =
        literals.map { ColumnValue.ofNumber(BigDecimal(it)) }

    private fun readerFor(type: ColumnType, values: List<ColumnValue>): ColumnValueReader {
        val encoded = ColumnValues.encode(type, values)
        assertTrue(
            encoded.size <= ColumnValues.maxEncodedSize(type, values),
            "maxEncodedSize must bound what encode writes, or the blob cap is not a cap",
        )
        if (type.id != ColumnFormat.COLUMN_TYPE_BOOLEAN) {
            assertEquals(
                ColumnValues.maxEncodedSize(type, values),
                encoded.size.toLong(),
                "and it must be exact for the types that can overflow",
            )
        }
        return ColumnValueReader(
            type,
            IndexBytes(MemorySegment.ofArray(encoded), 0, encoded.size, "values.col", ::CorruptIndexException),
            values.size,
            "values.col",
        )
    }

    // --- type selection ---------------------------------------------------------------------------

    @Test
    fun `a mixed integer and decimal column shreds as one numeric type`() {
        // The bug the family lattice exists to prevent: choosing by raw VariantKind makes INTEGER win
        // two to one here, and [1, 2.5, 3] — ordinary JSON — becomes entirely residual.
        val type = ColumnType.choose(numbers("1", "2.5", "3"))!!
        assertTrue(type.isNumeric)
        assertEquals(1, type.scale)
        for (literal in listOf("1", "2.5", "3")) {
            assertTrue(type.unscaledOrNull(BigDecimal(literal)) != null, "$literal must be representable")
        }
    }

    @Test
    fun `an all-integral column is INT64 with no scale`() {
        val type = ColumnType.choose(numbers("1", "-9", "1000000"))!!
        assertEquals(ColumnFormat.COLUMN_TYPE_INT64, type.id)
        assertEquals(0, type.scale)
    }

    @Test
    fun `a narrow decimal column is 32-bit and a wide one is 64`() {
        assertEquals(ColumnFormat.COLUMN_TYPE_DECIMAL32, ColumnType.choose(numbers("1.25", "-3.50"))!!.id)
        // One value past 32 bits at the common scale widens the whole column: residual is for values,
        // not for widths.
        assertEquals(
            ColumnFormat.COLUMN_TYPE_DECIMAL64,
            ColumnType.choose(numbers("1.25", "99999999999.99"))!!.id,
        )
    }

    @Test
    fun `the family with the most values wins and the choice is a pure function of the multiset`() {
        val values = numbers("1", "2") + listOf(ColumnValue.ofText("a".encodeToByteArray()))
        assertTrue(ColumnType.choose(values)!!.isNumeric)
        // Order must not matter, or a flush and a backfill could disagree and byte identity breaks.
        assertEquals(ColumnType.choose(values)!!.id, ColumnType.choose(values.reversed())!!.id)

        val textHeavy = numbers("1") + List(3) { ColumnValue.ofText("t$it".encodeToByteArray()) }
        assertEquals(ColumnFormat.COLUMN_TYPE_STRING, ColumnType.choose(textHeavy)!!.id)
    }

    @Test
    fun `a column of nothing shreddable has no type`() {
        assertNull(ColumnType.choose(listOf(ColumnValue.NULL, ColumnValue.OTHER)))
        assertNull(ColumnType.choose(emptyList()))
    }

    @Test
    fun `a value beyond 64 unscaled bits is not representable`() {
        val type = ColumnType.choose(numbers("1.5", "2.5"))!!
        // Residual, not a truncation. Narrowing a value silently would make a bound wrong.
        assertNull(type.unscaledOrNull(BigDecimal("123456789012345678901234567890.5")))
    }

    // --- order preservation -----------------------------------------------------------------------

    private fun assertOrderPreserved(literals: List<String>) {
        val values = literals.map { ColumnValue.ofNumber(BigDecimal(it)) }
        val type = ColumnType.choose(values) ?: return
        val representable = literals.filter { type.unscaledOrNull(BigDecimal(it)) != null }
        for (left in representable) {
            for (right in representable) {
                val expected = BigDecimal(left).compareTo(BigDecimal(right))
                val actual = type.unscaledOrNull(BigDecimal(left))!!.compareTo(type.unscaledOrNull(BigDecimal(right))!!)
                assertEquals(
                    expected.coerceIn(-1, 1),
                    actual.coerceIn(-1, 1),
                    "$type ordered $left against $right the wrong way",
                )
            }
        }
    }

    @Test
    fun `unscaled order is value order across scales and signs`() {
        assertOrderPreserved(listOf("0", "1", "-1", "0.5", "-0.5", "1.5", "1.50", "1.500", "2", "-2"))
        assertOrderPreserved(listOf("-0.0", "0.0", "0"))
        assertOrderPreserved(listOf("9999999999", "-9999999999", "0.0000001"))
        // At the 32-bit boundary, not near it: these are the values that decide the width.
        assertOrderPreserved(listOf("2147483647", "2147483648", "-2147483648", "-2147483649"))
    }

    @Test
    fun `scale differences are one value, not three`() {
        val type = ColumnType.choose(numbers("1.5", "1.50", "1.500"))!!
        val unscaled = listOf("1.5", "1.50", "1.500").map { type.unscaledOrNull(BigDecimal(it)) }
        assertEquals(1, unscaled.toSet().size, "1.5, 1.50 and 1.500 must encode identically")
    }

    @Test
    fun `unscaled order is value order over generated numbers`() {
        forAll(decimalLiterals()) { literals -> assertOrderPreserved(literals) }
    }

    @Test
    fun `text order is unsigned byte order, not UTF-16 order`() {
        // A supplementary-plane character is one UTF-16 surrogate pair whose first unit (0xD83D) is
        // below 0xFFFF, so String.compareTo puts it *before* U+FFFD. In UTF-8 bytes it is after.
        // A bound compared one way and built the other would skip segments that hold a match.
        val astral = "😀".encodeToByteArray()
        val bmp = "�".encodeToByteArray()
        assertTrue(compareText(astral, bmp) > 0, "UTF-8 byte order must put U+1F600 after U+FFFD")
        assertTrue("😀" < "�", "…and Kotlin String order disagrees, which is the point")

        assertTrue(compareText(ByteArray(0), byteArrayOf(0)) < 0, "the empty string is below everything")
        assertTrue(compareText(byteArrayOf(0x7F), byteArrayOf(0x80.toByte())) < 0, "0x7F before 0x80, unsigned")
    }

    // --- round trips ------------------------------------------------------------------------------

    @Test
    fun `every type round-trips including its null slots`() {
        val decimals = listOf(
            ColumnValue.ofNumber(BigDecimal("1.25")),
            ColumnValue.NULL,
            ColumnValue.ofNumber(BigDecimal("-7.00")),
        )
        val decimalType = ColumnType.choose(decimals)!!
        readerFor(decimalType, decimals).let { reader ->
            assertEquals(0, BigDecimal("1.25").compareTo(reader.numberAt(0)))
            assertEquals(0, reader.unscaledAt(1), "a null slot holds the type's zero")
            assertEquals(0, BigDecimal("-7").compareTo(reader.numberAt(2)))
            reader.verify()
        }

        val booleans = listOf(ColumnValue.ofBoolean(true), ColumnValue.NULL, ColumnValue.ofBoolean(false))
        readerFor(ColumnType.choose(booleans)!!, booleans).let { reader ->
            assertTrue(reader.booleanAt(0))
            assertTrue(!reader.booleanAt(1))
            assertTrue(!reader.booleanAt(2))
            reader.verify()
        }

        val strings = listOf(
            ColumnValue.ofText("alpha".encodeToByteArray()),
            ColumnValue.NULL,
            ColumnValue.ofText(ByteArray(0)),
            ColumnValue.ofText("ω".encodeToByteArray()),
        )
        readerFor(ColumnType.choose(strings)!!, strings).let { reader ->
            assertEquals("alpha", reader.textAt(0).decodeToString())
            assertEquals("", reader.textAt(1).decodeToString(), "a null slot is an empty slice")
            assertEquals("", reader.textAt(2).decodeToString())
            assertEquals("ω", reader.textAt(3).decodeToString())
            reader.verify()
        }
    }

    @Test
    fun `an empty column round-trips`() {
        for (type in listOf(
            ColumnType.of(ColumnFormat.COLUMN_TYPE_INT64, 0),
            ColumnType.of(ColumnFormat.COLUMN_TYPE_DECIMAL32, 2),
            ColumnType.of(ColumnFormat.COLUMN_TYPE_STRING, 0),
            ColumnType.of(ColumnFormat.COLUMN_TYPE_BOOLEAN, 0),
        )) {
            readerFor(type, emptyList()).verify()
        }
    }

    @Test
    fun `a truncated values section is reported rather than read past`() {
        val values = (0 until 40).map { ColumnValue.ofNumber(BigDecimal(it)) }
        val type = ColumnType.choose(values)!!
        val encoded = ColumnValues.encode(type, values)
        for (limit in 0 until encoded.size) {
            val truncated = encoded.copyOf(limit)
            val failure = runCatching {
                ColumnValueReader(
                    type,
                    IndexBytes(
                        MemorySegment.ofArray(truncated),
                        0,
                        truncated.size,
                        "truncated.col",
                        ::CorruptIndexException,
                    ),
                    values.size,
                    "truncated.col",
                ).verify()
            }.exceptionOrNull()
            assertTrue(
                failure is IndexException,
                "truncating to $limit gave ${failure?.let { it::class.simpleName }}: ${failure?.message}",
            )
        }
    }
}

/** Decimal literals spanning scales, signs and the width boundaries. Implemented directly so shrinking survives. */
private fun decimalLiterals(): Gen<List<String>> = object : Gen<List<String>> {
    override fun generate(source: RandomSource): List<String> {
        val count = source.nextInt(1..12)
        val scale = source.nextInt(0..6)
        return (0 until count).map {
            val unscaled = source.nextLong(-1_000_000_000L..1_000_000_000L)
            if (scale == 0) unscaled.toString() else BigDecimal.valueOf(unscaled, scale).toPlainString()
        }
    }

    override fun shrink(value: List<String>): Sequence<List<String>> = sequence {
        if (value.size > 1) yield(value.take(1))
        if (value.size > 2) yield(value.take(value.size / 2))
        if (value.size > 1) yield(value.dropLast(1))
    }

    override val edgeCases: List<List<String>> = listOf(
        listOf("0"),
        listOf("0", "-0", "0.0"),
        listOf("1", "1.0", "1.00"),
        listOf("2147483647", "2147483648"),
        listOf("-2147483648", "-2147483649"),
        listOf("9223372036854775807", "-9223372036854775808"),
        listOf("0.000001", "1000000"),
    )

    override fun render(value: List<String>): String = value.joinToString(prefix = "[", postfix = "]")
}
