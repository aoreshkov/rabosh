package app.oreshkov.rabosh.variant

import java.lang.foreign.MemorySegment
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class VariantValueTest {

    private fun build(block: VariantBuilder.() -> Unit): Variant =
        VariantBuilder().apply(block).buildVariant()

    private companion object {
        /** 2026-07-25, worked out by hand: 20454 days to 2026-01-01 plus 205 more. */
        const val EPOCH_DAY = 20_659
        const val NOON_MICROS = 12L * 60 * 60 * 1_000_000

        /** 2026-07-25T12:34:56.123456Z — `EPOCH_DAY * 86400 + 45296` seconds, then the fraction. */
        const val MICROS = 1_784_982_896_123_456L
        const val NANOS = 1_784_982_896_123_456_789L
    }

    @Test
    fun `every primitive type survives an encode and decode`() {
        val uuid = Uuid.parse("0191d3c7-2c9a-7a1e-9b3f-6a1c2d3e4f50")
        val variant = build {
            startObject()
            field("null"); appendNull()
            field("true"); appendBoolean(true)
            field("false"); appendBoolean(false)
            field("int8"); appendLong(127)
            field("int16"); appendLong(32_767)
            field("int32"); appendLong(2_147_483_647)
            field("int64"); appendLong(Long.MAX_VALUE)
            field("double"); appendDouble(0.1)
            field("float"); appendFloat(0.5f)
            field("decimal4"); appendDecimal(BigDecimal("1.23"))
            field("decimal8"); appendDecimal(BigDecimal("123456789.123456789"))
            field("decimal16"); appendDecimal(BigDecimal("-1.2345678901234567890123456789012345678"))
            field("date"); appendDate(EPOCH_DAY)
            field("timeNtz"); appendTimeNtz(NOON_MICROS)
            field("timestampTz"); appendTimestampMicros(MICROS, adjustedToUtc = true)
            field("timestampNtz"); appendTimestampMicros(MICROS, adjustedToUtc = false)
            field("timestampNanosTz"); appendTimestampNanos(NANOS, adjustedToUtc = true)
            field("timestampNanosNtz"); appendTimestampNanos(NANOS, adjustedToUtc = false)
            field("binary"); appendBinary(byteArrayOf(1, 2, 3, -1))
            field("uuid"); appendUuid(uuid)
            field("string"); appendString("x".repeat(100))
            field("shortString"); appendString("short")
            endObject()
        }

        assertTrue(variant.field("null")!!.isNull)
        assertTrue(variant.field("true")!!.booleanValue())
        assertFalse(variant.field("false")!!.booleanValue())
        assertEquals(127, variant.field("int8")!!.longValue())
        assertEquals(32_767, variant.field("int16")!!.longValue())
        assertEquals(2_147_483_647, variant.field("int32")!!.longValue())
        assertEquals(Long.MAX_VALUE, variant.field("int64")!!.longValue())
        assertEquals(0.1, variant.field("double")!!.doubleValue(), absoluteTolerance = 0.0)
        assertEquals(0.5, variant.field("float")!!.doubleValue(), absoluteTolerance = 0.0)
        assertEquals(BigDecimal("1.23"), variant.field("decimal4")!!.decimalValue())
        assertEquals(BigDecimal("123456789.123456789"), variant.field("decimal8")!!.decimalValue())
        assertEquals(
            BigDecimal("-1.2345678901234567890123456789012345678"),
            variant.field("decimal16")!!.decimalValue(),
        )
        assertEquals(EPOCH_DAY, variant.field("date")!!.epochDay())
        assertEquals(NOON_MICROS, variant.field("timeNtz")!!.temporalValue())
        assertEquals(MICROS, variant.field("timestampTz")!!.temporalValue())
        assertContentEquals(byteArrayOf(1, 2, 3, -1), variant.field("binary")!!.binaryValue())
        assertEquals(uuid, variant.field("uuid")!!.uuidValue())
        assertEquals("x".repeat(100), variant.field("string")!!.stringValue())
        assertEquals("short", variant.field("shortString")!!.stringValue())

        // The narrowest width that holds the value, every time.
        assertEquals(VariantPrimitiveType.INT8, variant.field("int8")!!.primitiveType)
        assertEquals(VariantPrimitiveType.INT16, variant.field("int16")!!.primitiveType)
        assertEquals(VariantPrimitiveType.INT32, variant.field("int32")!!.primitiveType)
        assertEquals(VariantPrimitiveType.INT64, variant.field("int64")!!.primitiveType)
        assertEquals(VariantPrimitiveType.DECIMAL4, variant.field("decimal4")!!.primitiveType)
        assertEquals(VariantPrimitiveType.DECIMAL8, variant.field("decimal8")!!.primitiveType)
        assertEquals(VariantPrimitiveType.DECIMAL16, variant.field("decimal16")!!.primitiveType)
        assertEquals(VariantBasicType.SHORT_STRING, variant.field("shortString")!!.basicType)
        assertEquals(VariantPrimitiveType.STRING, variant.field("string")!!.primitiveType)
    }

    /**
     * Types JSON has no syntax for are rendered as strings. This is the codec's only asymmetry and
     * it is deliberate — see [toJsonString].
     */
    @Test
    fun `types outside JSON render as ISO-8601 and base64 strings`() {
        val variant = build {
            startObject()
            field("date"); appendDate(EPOCH_DAY)
            field("time"); appendTimeNtz(NOON_MICROS)
            field("tz"); appendTimestampMicros(MICROS, adjustedToUtc = true)
            field("ntz"); appendTimestampMicros(MICROS, adjustedToUtc = false)
            field("nanos"); appendTimestampNanos(NANOS, adjustedToUtc = true)
            field("binary"); appendBinary(byteArrayOf(0, 1, 2, 3))
            endObject()
        }
        assertEquals("2026-07-25", variant.field("date")!!.toJsonString().trim('"'))
        assertEquals("12:00", variant.field("time")!!.toJsonString().trim('"'))
        assertEquals("2026-07-25T12:34:56.123456Z", variant.field("tz")!!.toJsonString().trim('"'))
        assertEquals("2026-07-25T12:34:56.123456", variant.field("ntz")!!.toJsonString().trim('"'))
        assertEquals("2026-07-25T12:34:56.123456789Z", variant.field("nanos")!!.toJsonString().trim('"'))
        assertEquals("AAECAw==", variant.field("binary")!!.toJsonString().trim('"'))
    }

    @Test
    fun `a non-finite double has no JSON form and says so`() {
        val variant = build { appendDouble(Double.NaN) }
        assertTrue(variant.doubleValue().isNaN())
        assertFailsWith<JsonWriteException> { variant.toJsonString() }
        assertFailsWith<JsonWriteException> { build { appendDouble(Double.POSITIVE_INFINITY) }.toJsonString() }
    }

    @Test
    fun `asking a value for the wrong type is a type error, not a decode error`() {
        val variant = Variant.fromJson("""{"text":"hello","number":1}""")
        assertFailsWith<VariantTypeException> { variant.field("text")!!.longValue() }
        assertFailsWith<VariantTypeException> { variant.field("number")!!.stringValue() }
        assertFailsWith<VariantTypeException> { variant.field("number")!!.doubleValue() }
        assertFailsWith<VariantTypeException> { variant.elementCount }
        assertFailsWith<VariantTypeException> { Variant.fromJson("[1]").fieldCount }
    }

    @Test
    fun `field lookup finds every field and reports absent ones as null`() {
        // Enough fields that the binary search actually branches.
        val names = (0 until 200).map { "field${it.toString().padStart(3, '0')}" }
        val json = names.withIndex().joinToString(",", "{", "}") { (index, name) -> "\"$name\":$index" }
        val variant = Variant.fromJson(json)

        names.forEachIndexed { index, name ->
            assertEquals(index.toLong(), variant.field(name)?.longValue(), name)
        }
        assertNull(variant.field("missing"))
        assertNull(variant.field(""))
        // Field order is the encoding's order, which for these names is also insertion order.
        assertEquals(names, (0 until variant.fieldCount).map(variant::fieldName))
    }

    @Test
    fun `values can be read from anywhere inside a segment`() {
        // The shape a mapped SSTable has: a dictionary somewhere, values somewhere else.
        val source = Variant.fromJson("""{"a":{"b":[1,2,3]}}""")
        val metadataBytes = source.metadata.toByteArray()
        val valueBytes = source.toByteArray()
        val file = ByteArray(16) + metadataBytes + ByteArray(8) + valueBytes + ByteArray(4)
        val segment = MemorySegment.ofArray(file)

        val metadata = VariantMetadata.read(segment, 16)
        val variant = Variant(metadata, segment, 16L + metadataBytes.size + 8)
        assertEquals("""{"a":{"b":[1,2,3]}}""", variant.toJsonString())
        assertEquals(2, variant.select("$.a.b[1]")?.longValue())
    }

    // --- data that does not decode ----------------------------------------------------------

    @Test
    fun `an unknown primitive type id is unreadable, not absent`() {
        // Type id 21: one past the last id this specification version defines.
        val failure = assertFailsWith<VariantFormatException> {
            Variant(VariantMetadata.EMPTY, byteArrayOf((21 shl 2).toByte())).kind
        }
        assertTrue("21" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `a truncated value is refused`() {
        // int8 header with no payload byte behind it.
        assertFailsWith<VariantFormatException> {
            Variant(VariantMetadata.EMPTY, byteArrayOf(0x0C)).longValue()
        }
        // An object header claiming one element, with nothing after it.
        assertFailsWith<VariantFormatException> {
            Variant(VariantMetadata.EMPTY, byteArrayOf(0x02, 0x01)).fieldCount
        }
    }

    @Test
    fun `a field id outside the dictionary is refused`() {
        val bytes = byteArrayOf(0x02, 0x01, 0x09, 0x00, 0x02, 0x0C, 0x01)
        val failure = assertFailsWith<VariantFormatException> {
            Variant(VariantMetadata.EMPTY, bytes).fieldName(0)
        }
        assertTrue("field id 9" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `a string whose bytes are not UTF-8 is refused`() {
        // Short string of length 2 holding a truncated two-byte sequence.
        val bytes = byteArrayOf((VariantBasicType.SHORT_STRING.id or (2 shl 2)).toByte(), 0xC3.toByte(), 0x28)
        assertFailsWith<VariantFormatException> {
            Variant(VariantMetadata.EMPTY, bytes).stringValue()
        }
    }

    @Test
    fun `a value region shorter than its offsets claim is refused`() {
        // Array of one element whose last offset says two bytes, with one byte present.
        val bytes = byteArrayOf(0x03, 0x01, 0x00, 0x02, 0x0C)
        assertFailsWith<VariantFormatException> {
            Variant(VariantMetadata.EMPTY, bytes).byteSize
        }
    }

    /**
     * An element offset inside the segment but outside its own container would otherwise read
     * whatever value happens to sit there — a wrong answer rather than a reported failure.
     */
    @Test
    fun `an element offset past the end of its container is refused`() {
        // Array of one element: the element's offset is 4, but the region is only 2 bytes long.
        val bytes = byteArrayOf(0x03, 0x01, 0x04, 0x02, 0x0C, 0x01, 0x0C, 0x02)
        val failure = assertFailsWith<VariantFormatException> {
            Variant(VariantMetadata.EMPTY, bytes).element(0)
        }
        assertTrue("past the container" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `a decimal scale beyond the specification is refused`() {
        val bytes = byteArrayOf((VariantPrimitiveType.DECIMAL4.id shl 2).toByte(), 39, 1, 0, 0, 0)
        val failure = assertFailsWith<VariantFormatException> {
            Variant(VariantMetadata.EMPTY, bytes).decimalValue()
        }
        assertTrue("39" in failure.message.orEmpty(), failure.message.orEmpty())
    }
}
