package app.oreshkov.rabosh.variant

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The bytes, written down.
 *
 * Every other test here round-trips: it encodes with this build and decodes with this build, so a
 * change that is *self-consistent* — a renumbered primitive type, a reordered header, a different
 * offset width — passes all of them while making every file ever written unreadable. These are the
 * ones that stop.
 *
 * The golden store in `rabosh-query` catches the same class of break end to end. This catches it
 * where the format is defined and reports it as a byte string a reader can check against the Apache
 * Open Variant specification, rather than as a store that will not open.
 *
 * **A failure is not fixed by updating the expectation.** Either the change is wrong, or the engine
 * has moved to a new format version — and the second is a decision with a paper trail, not an edit.
 */
class EncodingPinTest {

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private fun value(json: String): String = Variant.fromJson(json).toByteArray().hex()

    private fun metadata(json: String): String = Variant.fromJson(json).metadata.toByteArray().hex()

    /** header = basicType PRIMITIVE (0) | primitiveType shl 2; payloads little-endian throughout. */
    @Test
    fun `primitives encode to the specification's bytes`() {
        assertEquals("00", value("null"))
        assertEquals("04", value("true"))
        assertEquals("08", value("false"))
        assertEquals("0c07", value("7"), "int8 = type 3")
        assertEquals("102c01", value("300"), "int16 = type 4")
        assertEquals("14a0860100", value("100000"), "int32 = type 5")
        assertEquals("180000c16ff2862300", value("10000000000000000"), "int64 = type 6")
    }

    /** decimal4 = type 8: a scale byte, then a little-endian unscaled int32. */
    @Test
    fun `decimals carry a scale and an unscaled integer`() {
        assertEquals("20027b000000", value("1.23"))
        assertEquals("2002d2040000", value("12.34"))
        assertEquals("2001fbffffff", value("-0.5"), "the unscaled value is signed")
    }

    /**
     * A trailing zero is not part of the value: `10.00` is the integer ten.
     *
     * The parser decides the type from the *value*, so scale that carries no information is gone
     * before anything is stored. That is what makes a query written `10` find a document written
     * `10.00` — the two are one value here, not two spellings compared leniently later.
     */
    @Test
    fun `trailing zeros are normalised at parse, not compared away at read`() {
        assertEquals("0c0a", value("10"))
        assertEquals("0c0a", value("10.00"))
        assertEquals("0c0a", value("1.0e1"))
    }

    /** A short string is basicType 1 with its length in the header; a long one is type 16. */
    @Test
    fun `strings encode short and long by the same rule they always have`() {
        assertEquals("0561", value("\"a\""))
        assertEquals("156162636465", value("\"abcde\""))
        assertEquals("40" + "40000000" + "78".repeat(64), value("\"${"x".repeat(64)}\""))
    }

    @Test
    fun `an empty object and an empty array are not the same bytes`() {
        assertEquals("020000", value("{}"))
        assertEquals("030000", value("[]"))
    }

    /**
     * **Object entries are ordered by field name, not by field id**, and the two coincide only when
     * the dictionary happens to be sorted.
     *
     * The dictionary keeps the order names were interned in and sets a *sorted* flag when that order
     * is lexicographic — `11…` below versus `01…`. So `{"b":1,"a":2}` stores `a` first, with id 1,
     * and its entry list is `[1, 0]`. That is the specification's rule and it is the one
     * `Variant.field` depends on: it bisects by comparing *names* in place, which is correct under
     * either dictionary order and would be wrong if it bisected by id.
     */
    @Test
    fun `an object is ordered by name, whatever order its dictionary is in`() {
        assertEquals("1102000103616263", metadata("""{"a":1,"bc":2}"""), "sorted dictionary: flag 0x11")
        assertEquals("020200010002040c010c02", value("""{"a":1,"bc":2}"""))

        assertEquals("11020001026162", metadata("""{"a":2,"b":1}"""), "a, b: already sorted")
        assertEquals("01020001026261", metadata("""{"b":1,"a":2}"""), "b, a: interned unsorted, flag 0x01")
        assertNotEquals(
            metadata("""{"a":2,"b":1}"""),
            metadata("""{"b":1,"a":2}"""),
            "the dictionary records the order names arrived in",
        )

        val unsorted = Variant.fromJson("""{"b":1,"a":2}""")
        assertEquals(listOf("a", "b"), (0 until unsorted.fieldCount).map(unsorted::fieldName))
        assertEquals(listOf(1, 0), (0 until unsorted.fieldCount).map(unsorted::fieldId))
        assertEquals(2L, unsorted.field("a")?.longValue(), "and lookup follows names, not ids")
        assertEquals(1L, unsorted.field("b")?.longValue())
    }

    /** The other half of a byte pin: what a reader must still see. */
    @Test
    fun `the pinned bytes decode to the values they came from`() {
        val document = Variant.fromJson("""{"a":1.23,"b":"abcde","c":[true,null]}""")
        assertEquals("1.23", document.select("$.a")?.decimalValue()?.toPlainString())
        assertEquals("abcde", document.select("$.b")?.stringValue())
        assertEquals(true, document.select("$.c[0]")?.booleanValue())
        assertEquals(VariantKind.NULL, document.select("$.c[1]")?.kind)
    }
}
