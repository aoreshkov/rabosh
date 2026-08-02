package app.oreshkov.rabosh.variant

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Byte-exact checks against the [Variant binary encoding specification][spec].
 *
 * These exist because every other test in this module is self-consistent: encode, decode, compare.
 * A misreading of the specification — a header bit in the wrong place, an offset relative to the
 * wrong origin — survives a roundtrip test perfectly happily and only surfaces years later when
 * another implementation reads the file. The vectors below are derived by hand from the
 * specification's own diagrams and are the anchor that keeps the format honest.
 *
 * [spec]: https://github.com/apache/parquet-format/blob/master/VariantEncoding.md
 */
class VariantEncodingTest {

    @Test
    fun `metadata of a single name`() {
        val variant = Variant.fromJson("""{"a":1}""")
        assertBytes(
            variant.metadata.toByteArray(),
            // header: version 1, sorted_strings 1, offset_size_minus_one 0
            0x11,
            // dictionary_size
            0x01,
            // offsets: [0, 1]
            0x00, 0x01,
            // bytes: "a"
            0x61,
        )
    }

    @Test
    fun `object of one int8 field`() {
        val variant = Variant.fromJson("""{"a":1}""")
        assertBytes(
            variant.toByteArray(),
            // basic_type 2 (object), value_header 0: 1-byte ids, 1-byte offsets, not large
            0x02,
            // num_elements
            0x01,
            // field_id list: ["a" -> 0]
            0x00,
            // field_offset list: [0, 2]
            0x00, 0x02,
            // values: int8 1
            0x0C, 0x01,
        )
    }

    /**
     * The specification's own worked example: `{"c":3,"b":2,"a":1}`.
     *
     * Field *ids* must be written in lexicographic order of their names, but the values may stay
     * where they were written — so the offset list here runs `[4, 2, 0]`, backwards. An encoder
     * that sorts the values instead of the ids passes every roundtrip test and still writes a file
     * this vector rejects.
     */
    @Test
    fun `field ids are sorted by name while values stay in place`() {
        val variant = Variant.fromJson("""{"c":3,"b":2,"a":1}""")

        assertBytes(
            variant.metadata.toByteArray(),
            // sorted_strings is 0: the dictionary is in interning order, c then b then a
            0x01,
            0x03,
            0x00, 0x01, 0x02, 0x03,
            0x63, 0x62, 0x61,
        )
        assertBytes(
            variant.toByteArray(),
            0x02,
            0x03,
            // ids in name order: a=2, b=1, c=0
            0x02, 0x01, 0x00,
            // offsets follow the ids, so they run backwards through the value region
            0x04, 0x02, 0x00, 0x06,
            0x0C, 0x03,
            0x0C, 0x02,
            0x0C, 0x01,
        )
        assertEquals(listOf("a", "b", "c"), (0 until variant.fieldCount).map(variant::fieldName))
        assertEquals("""{"a":1,"b":2,"c":3}""", variant.toJsonString())
    }

    @Test
    fun `array of mixed values`() {
        val variant = Variant.fromJson("""[1,"x"]""")
        assertBytes(
            variant.toByteArray(),
            // basic_type 3 (array), value_header 0: 1-byte offsets, not large
            0x03,
            0x02,
            0x00, 0x02, 0x04,
            0x0C, 0x01,
            // short string of length 1
            0x05, 0x78,
        )
    }

    @Test
    fun `scalars at the top level`() {
        assertBytes(Variant.fromJson("null").toByteArray(), 0x00)
        assertBytes(Variant.fromJson("true").toByteArray(), 0x04)
        assertBytes(Variant.fromJson("false").toByteArray(), 0x08)
        assertBytes(Variant.fromJson("\"hi\"").toByteArray(), 0x09, 0x68, 0x69)
        assertBytes(Variant.fromJson("[]").toByteArray(), 0x03, 0x00, 0x00)
        assertBytes(Variant.fromJson("{}").toByteArray(), 0x02, 0x00, 0x00)
    }

    @Test
    fun `strings switch to the long form at 64 bytes`() {
        val short = "x".repeat(63)
        val long = "x".repeat(64)

        val shortBytes = Variant.fromJson("\"$short\"").toByteArray()
        assertEquals(VariantBasicType.SHORT_STRING, VariantBasicType.ofHeader(shortBytes[0]))
        assertEquals(63, (shortBytes[0].toInt() and 0xFF) ushr 2)
        assertEquals(1 + 63, shortBytes.size)

        val longBytes = Variant.fromJson("\"$long\"").toByteArray()
        assertEquals(VariantBasicType.PRIMITIVE, VariantBasicType.ofHeader(longBytes[0]))
        assertEquals(VariantPrimitiveType.STRING, VariantPrimitiveType.ofHeader(longBytes[0]))
        // 1 header byte + 4 length bytes + payload
        assertEquals(1 + 4 + 64, longBytes.size)
        assertEquals(long, Variant.fromJson("\"$long\"").stringValue())
    }

    /**
     * A multi-byte string is measured in *bytes*, not characters — the 63/64 boundary is about the
     * six header bits, which count bytes.
     */
    @Test
    fun `short string length counts bytes not characters`() {
        // U+00E9 is two bytes in UTF-8, so 32 of them are 64 bytes: one past the short form.
        val text = "é".repeat(32)
        val bytes = Variant.fromJson("\"$text\"").toByteArray()
        assertEquals(VariantBasicType.PRIMITIVE, VariantBasicType.ofHeader(bytes[0]))
        assertEquals(text, Variant.fromJson("\"$text\"").stringValue())

        val justUnder = "é".repeat(31)
        assertEquals(
            VariantBasicType.SHORT_STRING,
            VariantBasicType.ofHeader(Variant.fromJson("\"$justUnder\"").toByteArray()[0]),
        )
    }

    @Test
    fun `is_large switches on above 255 elements`() {
        fun arrayHeaderOf(count: Int): Int {
            val json = (0 until count).joinToString(",", "[", "]") { "1" }
            return (Variant.fromJson(json).toByteArray()[0].toInt() and 0xFF) ushr 2
        }

        // value_header for an array is (is_large << 2 | field_offset_size_minus_one).
        assertEquals(0, arrayHeaderOf(255) ushr 2, "255 elements still fit a one-byte count")
        assertEquals(1, arrayHeaderOf(256) ushr 2, "256 elements need the four-byte count")

        val large = Variant.fromJson((0 until 300).joinToString(",", "[", "]") { it.toString() })
        assertEquals(300, large.elementCount)
        assertEquals(299, large.element(299).longValue())
    }

    @Test
    fun `offset width grows with the size of the value region`() {
        // Each element is a 300-byte string, so the region passes 64 KiB and offsets need 3 bytes.
        val element = "\"${"x".repeat(300)}\""
        val variant = Variant.fromJson((0 until 250).joinToString(",", "[", "]") { element })
        val valueHeader = (variant.toByteArray()[0].toInt() and 0xFF) ushr 2
        assertEquals(3, (valueHeader and 0x03) + 1, "offsets should have widened to three bytes")
        assertEquals(250, variant.elementCount)
        assertEquals("x".repeat(300), variant.element(249).stringValue())
    }

    /**
     * Field order is UTF-8 byte order, which is not `String.compareTo`.
     *
     * `U+FF21` and `U+10000` are the counterexample: in UTF-16 the astral character sorts first
     * (its high surrogate `D800` is below `FF21`), in UTF-8 it sorts second (`F0…` is above `EF…`).
     * An encoder that sorts field names as Kotlin strings gets this pair backwards and every
     * binary search that trusts the specification then misses.
     */
    @Test
    fun `field order is UTF-8 byte order not UTF-16`() {
        val fullWidth = "Ａ"
        val astral = "𐀀"
        assertTrue(astral < fullWidth, "the two disagree in UTF-16 order, which is the point")

        val variant = Variant.fromJson("""{"$astral":1,"$fullWidth":2}""")
        assertEquals(listOf(fullWidth, astral), (0 until variant.fieldCount).map(variant::fieldName))
        assertEquals(1, variant.field(astral)?.longValue())
        assertEquals(2, variant.field(fullWidth)?.longValue())
    }

    @Test
    fun `field id width grows with the dictionary`() {
        val dictionary = VariantDictionaryBuilder()
        // Push the ids that this document uses past 255 so two-byte field ids are required.
        repeat(300) { dictionary.intern("filler$it") }

        val builder = VariantBuilder(dictionary)
        builder.startObject()
        builder.field("late")
        builder.appendLong(1)
        builder.endObject()

        val variant = builder.buildVariant()
        val valueHeader = (variant.toByteArray()[0].toInt() and 0xFF) ushr 2
        assertEquals(2, ((valueHeader ushr 2) and 0x03) + 1, "field ids should have widened")
        assertEquals(1, variant.field("late")?.longValue())
    }

    @Test
    fun `nested containers keep their parent's offsets correct`() {
        val json = """{"a":{"b":[1,{"c":"deep"}]},"d":2}"""
        val variant = Variant.fromJson(json)
        assertEquals("deep", variant.select("$.a.b[1].c")?.stringValue())
        assertEquals(2, variant.select("$.d")?.longValue())
        assertEquals(json, variant.toJsonString())
    }

    @Test
    fun `byte size matches the encoded length for every nested value`() {
        val variant = Variant.fromJson("""{"a":{"b":[1,"two",{"c":null}]},"d":[[[]]]}""")
        assertEquals(variant.toByteArray().size.toLong(), variant.byteSize)

        // Every nested view must also report exactly its own extent, since that is what lets a
        // value be copied out of a segment without decoding it.
        fun check(value: Variant) {
            assertEquals(value.toByteArray().size.toLong(), value.byteSize)
            when (value.kind) {
                VariantKind.OBJECT -> (0 until value.fieldCount).forEach { check(value.fieldValue(it)) }
                VariantKind.ARRAY -> (0 until value.elementCount).forEach { check(value.element(it)) }
                else -> Unit
            }
        }
        check(variant)
    }
}

internal fun assertBytes(actual: ByteArray, vararg expected: Int) {
    val expectedBytes = ByteArray(expected.size) { expected[it].toByte() }
    assertContentEquals(
        expectedBytes,
        actual,
        "expected ${expectedBytes.toHex()}\n            but was ${actual.toHex()}",
    )
}

internal fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }
