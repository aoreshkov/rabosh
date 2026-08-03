package app.oreshkov.rabosh.variant

import app.oreshkov.rabosh.testkit.json.JsonGens
import app.oreshkov.rabosh.testkit.json.toJsonString
import app.oreshkov.rabosh.testkit.property.Gen
import app.oreshkov.rabosh.testkit.property.forAll
import app.oreshkov.rabosh.testkit.property.int
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VariantSummaryTest {

    private fun build(block: VariantBuilder.() -> Unit): Variant =
        VariantBuilder().apply(block).buildVariant()

    // --- childCount ---------------------------------------------------------------------------

    @Test
    fun `childCount is the top-level count whatever the shape`() {
        val obj = Variant.fromJson("""{"a":1,"b":2,"c":3}""")
        val array = Variant.fromJson("[1,2,3,4]")

        assertEquals(3, obj.childCount)
        assertEquals(4, array.childCount)
        // The two typed counters stay the definition; this one must not be a third.
        assertEquals(obj.fieldCount, obj.childCount)
        assertEquals(array.elementCount, array.childCount)

        assertEquals(0, Variant.fromJson("{}").childCount)
        assertEquals(0, Variant.fromJson("[]").childCount)
        assertEquals(0, Variant.fromJson(""""text"""").childCount)
        assertEquals(0, Variant.fromJson("42").childCount)
        assertEquals(0, Variant.fromJson("null").childCount)
    }

    @Test
    fun `childCount answers for a primitive id this build does not know`() {
        // Type id 21: one past the last id this specification version defines.
        val unknown = Variant(VariantMetadata.EMPTY, byteArrayOf((21 shl 2).toByte()))

        // Zero is an answer here rather than a default invented for unknown data: whatever that
        // byte means, a primitive has no children. The failure is still available from `kind`.
        assertEquals(0, unknown.childCount)
        assertFailsWith<VariantFormatException> { unknown.kind }
    }

    // --- the one-line summary -----------------------------------------------------------------

    @Test
    fun `summarises a container in one line`() {
        // Literal byte counts, deliberately: if the encoder moves, this should fail, and
        // EncodingPinTest is what says whether that was intended.
        assertEquals(
            "Variant(object, children=3, bytes=15)",
            Variant.fromJson("""{"a":1,"b":2,"c":3}""").toSummaryString(),
        )
        assertEquals(
            "Variant(array, children=3, bytes=12)",
            Variant.fromJson("[1,2,3]").toSummaryString(),
        )
        assertEquals("Variant(object, children=0, bytes=3)", Variant.fromJson("{}").toSummaryString())
    }

    @Test
    fun `summarises a scalar without a child count`() {
        assertEquals("Variant(string, bytes=6)", Variant.fromJson(""""hello"""").toSummaryString())
        assertEquals("Variant(integer, bytes=2)", Variant.fromJson("42").toSummaryString())
        assertEquals("Variant(null, bytes=1)", Variant.fromJson("null").toSummaryString())
    }

    @Test
    fun `a summary never throws, and says the same thing toString does`() {
        // int8 header with no payload byte behind it.
        val damaged = Variant(VariantMetadata.EMPTY, byteArrayOf(0x0C))

        val summary = damaged.toSummaryString()
        assertTrue("unreadable" in summary, summary)
        // One failure spelling, two callers — and equal rather than merely alike, because both
        // reach the failure through `byteSize`: toString asks it before it decides which renderer
        // to use, so a value that cannot be read describes itself identically either way.
        assertEquals(damaged.toString(), summary)
    }

    // --- the top-level outline ----------------------------------------------------------------

    @Test
    fun `an outline shows the first children and counts the rest`() {
        val document = Variant.fromJson("""{"a":1,"b":2,"c":3,"d":4}""")
        assertEquals("""{"a":1,"b":2,…2 more}""", document.toJsonSummaryString(limit = 2))
        assertEquals("""{"a":1,"b":2,"c":3,"d":4}""", document.toJsonSummaryString(limit = 4))
        assertEquals("[1,2,…3 more]", Variant.fromJson("[1,2,3,4,5]").toJsonSummaryString(limit = 2))
    }

    @Test
    fun `an outline elides a container child by shape and count`() {
        val document = Variant.fromJson("""{"a":[1,2,3],"b":{"x":1},"c":[],"d":{}}""")
        // The empty forms are in the same case on purpose: `[]` must mean empty and nothing else,
        // which is exactly what a JSON-valid elision would have taken away.
        assertEquals("""{"a":[…3],"b":{…1},"c":[],"d":{}}""", document.toJsonSummaryString())
    }

    @Test
    fun `an outline describes a value too large to decode`() {
        val document = build {
            startObject()
            field("a"); appendString("x".repeat(1_000))
            endObject()
        }
        // 1 header + 4 length + 1000 bytes.
        assertEquals("""{"a":…1005 bytes}""", document.toJsonSummaryString())
    }

    @Test
    fun `an outline truncates a long field name without splitting a surrogate pair`() {
        // One ASCII character then astral pairs, so the cut at SUMMARY_VALUE_LIMIT lands on a high
        // surrogate and has to step back. An even prefix would never exercise the adjustment.
        val name = "a" + "😀".repeat(40)
        val document = build {
            startObject()
            field(name); appendLong(1)
            endObject()
        }

        val summary = document.toJsonSummaryString()
        // Cut at 64 would split the 32nd pair, so it steps back to 63: 'a' plus 31 whole emoji.
        assertEquals("""{"a${"😀".repeat(31)}…":1}""", summary)
        // Encoding a lone surrogate substitutes '?', so a UTF-8 round trip is what says the cut
        // landed on a code-point boundary.
        assertEquals(summary, String(summary.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
    }

    @Test
    fun `an outline of a scalar is the scalar`() {
        assertEquals("7", Variant.fromJson("7").toJsonSummaryString())
        assertEquals(""""hello"""", Variant.fromJson(""""hello"""").toJsonSummaryString())
    }

    @Test
    fun `a limit of zero shows only the count`() {
        assertEquals("{…4 more}", Variant.fromJson("""{"a":1,"b":2,"c":3,"d":4}""").toJsonSummaryString(limit = 0))
        assertEquals("[…2 more]", Variant.fromJson("[1,2]").toJsonSummaryString(limit = 0))
        assertEquals("{}", Variant.fromJson("{}").toJsonSummaryString(limit = 0))
    }

    @Test
    fun `rejects a negative limit`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            Variant.fromJson("""{"a":1}""").toJsonSummaryString(limit = -1)
        }
        assertTrue("-1" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `an outline reports unreadable bytes rather than eliding them`() {
        // An object header claiming one element, with nothing after it.
        val damaged = Variant(VariantMetadata.EMPTY, byteArrayOf(0x02, 0x01))
        assertFailsWith<VariantFormatException> { damaged.toJsonSummaryString() }
        // A non-finite double is the other reason it refuses, exactly as toJsonString does.
        val nonFinite = build {
            startArray()
            appendDouble(Double.NaN)
            endArray()
        }
        assertFailsWith<JsonWriteException> { nonFinite.toJsonSummaryString() }
        // And the one-liner is the escape hatch in both cases.
        assertTrue("unreadable" in damaged.toSummaryString())
        assertEquals("Variant(array, children=1, bytes=13)", nonFinite.toSummaryString())
    }

    @Test
    fun `a summary is not JSON, deliberately`() {
        val elided = Variant.fromJson("""{"a":[1,2,3]}""").toJsonSummaryString()
        assertEquals("""{"a":[…3]}""", elided)
        // Pinned so that a later change cannot quietly make the output parse into something that
        // is readable and wrong — an elided array rendered `[]` is indistinguishable from an empty
        // one, which is the failure this shape exists to avoid.
        assertFailsWith<JsonParseException> { Variant.fromJson(elided) }
    }

    @Test
    fun `appendJsonSummaryTo appends rather than replacing`() {
        val out = StringBuilder("value: ")
        Variant.fromJson("""{"a":1,"b":2}""").appendJsonSummaryTo(out, limit = 1)
        assertEquals("""value: {"a":1,…1 more}""", out.toString())
    }

    @Test
    fun `a summary of a huge document is short and cheap`() {
        // The unfavourable case arranged rather than hoped for: every element is a scalar, so
        // nothing here is bounded by a container placeholder. Without the byte gate this decodes
        // twenty megabytes of UTF-8 in order to print a few hundred characters.
        val element = "x".repeat(1024)
        val huge = build {
            startArray()
            repeat(20_000) { appendString(element) }
            endArray()
        }
        assertTrue(huge.byteSize > 20_000_000, "fixture is not large enough: ${huge.byteSize}")

        val outline = huge.toJsonSummaryString()
        assertTrue(outline.length < 800, "outline was ${outline.length} chars: $outline")
        assertEquals("[…1029 bytes,…1029 bytes,…1029 bytes,…1029 bytes,…1029 bytes,…1029 bytes," +
            "…1029 bytes,…1029 bytes,…19992 more]", outline)
        assertEquals("Variant(array, children=20000, bytes=${huge.byteSize})", huge.toSummaryString())
    }

    @Test
    fun `toString falls back to a summary above the byte limit and not below it`() {
        // A long string is 1 header + 4 length + n bytes, so n picks byteSize exactly.
        val atLimit = build { appendString("a".repeat((Variant.TO_STRING_BYTE_LIMIT - 5).toInt())) }
        val overLimit = build { appendString("a".repeat((Variant.TO_STRING_BYTE_LIMIT - 4).toInt())) }

        assertEquals(Variant.TO_STRING_BYTE_LIMIT, atLimit.byteSize)
        assertEquals(Variant.TO_STRING_BYTE_LIMIT + 1, overLimit.byteSize)

        // At the value, not near it.
        assertEquals(atLimit.toJsonString(), atLimit.toString())
        assertEquals("Variant(string, bytes=${Variant.TO_STRING_BYTE_LIMIT + 1})", overLimit.toString())
    }

    // --- properties ---------------------------------------------------------------------------

    /** See [maxJsonSummaryLength], which `VariantNodeTest` bounds a node against too. */
    private fun maxSummaryLength(limit: Int): Int = maxJsonSummaryLength(limit)

    @Test
    fun `an outline is bounded by its limit and not by the document`() {
        forAll(JsonGens.document(), Gen.int(0..16)) { document, limit ->
            val summary = Variant.fromJson(document.toJsonString()).toJsonSummaryString(limit)
            assertTrue(
                summary.length <= maxSummaryLength(limit),
                "limit $limit allows ${maxSummaryLength(limit)} chars, got ${summary.length}: $summary",
            )
            // Without this the property is satisfied by every summary being empty.
            assertTrue(summary.isNotEmpty(), "empty summary for $document")
        }
    }

    @Test
    fun `an outline that elides nothing is the JSON`() {
        // If the full rendering fits in SUMMARY_VALUE_LIMIT characters then no cut can have
        // happened: the character limit is applied to unescaped content, which is never longer
        // than the rendering, and the byte gate cannot fire either — a value over its 256 bytes
        // renders as at least 125 characters, whether as UTF-16 text or as base64.
        var compared = 0
        forAll(JsonGens.scalar) { scalar ->
            val variant = Variant.fromJson(scalar.toJsonString())
            val json = variant.toJsonString()
            if (json.length <= SUMMARY_VALUE_LIMIT) {
                compared++
                // This is what makes the shared scalar renderer checked rather than merely intended:
                // a document and a summary of it must not spell one scalar two ways.
                assertEquals(json, variant.toJsonSummaryString(), "for $scalar")
            }
        }
        assertTrue(compared > 0, "no scalar was small enough to compare; the property proved nothing")
    }

    @Test
    fun `childCount agrees with the typed counters everywhere`() {
        forAll(JsonGens.document()) { document ->
            checkChildCount(Variant.fromJson(document.toJsonString()))
        }
    }

    private fun checkChildCount(variant: Variant) {
        when (variant.basicType) {
            VariantBasicType.OBJECT -> {
                assertEquals(variant.fieldCount, variant.childCount)
                for (index in 0 until variant.fieldCount) checkChildCount(variant.fieldValue(index))
            }

            VariantBasicType.ARRAY -> {
                assertEquals(variant.elementCount, variant.childCount)
                for (index in 0 until variant.elementCount) checkChildCount(variant.element(index))
            }

            VariantBasicType.PRIMITIVE, VariantBasicType.SHORT_STRING ->
                assertEquals(0, variant.childCount)
        }
    }
}
