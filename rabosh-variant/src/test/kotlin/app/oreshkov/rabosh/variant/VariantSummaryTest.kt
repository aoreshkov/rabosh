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

    // --- the nested outline -------------------------------------------------------------------

    @Test
    fun `a nested outline expands one more level per depth and elides below the last`() {
        val document = Variant.fromJson("""{"a":{"b":{"c":1,"d":[2,3]}},"e":5}""")

        // The ladder, rather than one depth: each rung must differ from the one above it in exactly
        // the level it added, which is what says `depth` selects a level and not merely more output.
        assertEquals("""{"a":{…1},"e":5}""", document.toJsonSummaryString(depth = 1))
        assertEquals("""{"a":{"b":{…2}},"e":5}""", document.toJsonSummaryString(depth = 2))
        assertEquals("""{"a":{"b":{"c":1,"d":[…2]}},"e":5}""", document.toJsonSummaryString(depth = 3))
        // Past the bottom of the document, where there is nothing left to elide.
        assertEquals("""{"a":{"b":{"c":1,"d":[2,3]}},"e":5}""", document.toJsonSummaryString(depth = 4))
        assertEquals(document.toJsonString(), document.toJsonSummaryString(depth = 4))
    }

    @Test
    fun `a nested outline applies its limit at every level, not only the top`() {
        val document = Variant.fromJson("""{"a":{"x":1,"y":2,"z":3},"b":{"x":1},"c":{"x":1}}""")

        assertEquals("""{"a":{"x":1,"y":2,…1 more},"b":{"x":1},…1 more}""",
            document.toJsonSummaryString(limit = 2, depth = 2))
        // A limit of zero collapses every level onto the root's own count, however deep it is asked
        // to go — the "just tell me the shape" form does not stop being that because depth grew.
        assertEquals("{…3 more}", document.toJsonSummaryString(limit = 0, depth = 4))
    }

    @Test
    fun `a nested outline stops at its depth however far the document runs below it`() {
        // Built rather than parsed, deliberately, and far deeper than JsonParser would accept: the
        // claim is that the walk is bounded by its argument alone, so the fixture has to be a
        // document that any walk following the *value* would not survive. `toJsonString` is the
        // control — it refuses this document, and that refusal is the thing being avoided here.
        val deep = nest(DEEP)

        assertEquals("""{"down":{"down":{"down":{…1}}}}""", deep.toJsonSummaryString(depth = 3))
        assertFailsWith<JsonWriteException> { deep.toJsonString() }
    }

    @Test
    fun `a nested outline of a huge document is short and cheap`() {
        // The unfavourable case for a *nested* outline: the second level is where the bytes are, so
        // nothing the top level does can keep this bounded. Without the byte gate applying below the
        // root as well, showing eight rows here decodes eight kilobytes to print eight counts.
        val blob = "x".repeat(1024)
        val huge = build {
            startArray()
            repeat(ROWS) { index ->
                startObject()
                field("blob"); appendString(blob)
                field("id"); appendLong(index.toLong())
                endObject()
            }
            endArray()
        }
        assertTrue(huge.byteSize > 5_000_000, "fixture is not large enough: ${huge.byteSize}")

        val outline = huge.toJsonSummaryString(depth = 2)
        // 1 header + 4 length + 1024 bytes, the same arithmetic as the top-level case above.
        val expected = (0 until DEFAULT_SUMMARY_LIMIT)
            .joinToString(",", "[", ",…${ROWS - DEFAULT_SUMMARY_LIMIT} more]") {
                """{"blob":…1029 bytes,"id":$it}"""
            }
        assertEquals(expected, outline)
        assertTrue(outline.length < 400, "outline was ${outline.length} chars: $outline")
    }

    @Test
    fun `rejects a depth outside one to the json depth`() {
        val document = Variant.fromJson("""{"a":1}""")

        for (depth in listOf(0, -1, DEFAULT_MAX_JSON_DEPTH + 1)) {
            val failure = assertFailsWith<IllegalArgumentException>("depth $depth") {
                document.toJsonSummaryString(depth = depth)
            }
            assertTrue("$depth" in failure.message.orEmpty(), failure.message.orEmpty())
        }
        // At the ceiling, not near it: the bound is the depth `toJsonString` refuses past, and a
        // summary is allowed to reach it.
        assertEquals("""{"a":1}""", document.toJsonSummaryString(depth = DEFAULT_MAX_JSON_DEPTH))
        // The limit is still the limit, and it is rejected before the depth is looked at.
        assertFailsWith<IllegalArgumentException> { document.toJsonSummaryString(limit = -1, depth = 2) }
    }

    @Test
    fun `appendJsonSummaryTo appends a nested outline rather than replacing`() {
        val out = StringBuilder("value: ")
        Variant.fromJson("""{"a":{"b":1},"c":2}""").appendJsonSummaryTo(out, limit = 1, depth = 2)
        assertEquals("""value: {"a":{"b":1},…1 more}""", out.toString())
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
    private fun maxSummaryLength(limit: Int, depth: Int = 1): Int = maxJsonSummaryLength(limit, depth)

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

    /**
     * The pin that makes the two spellings one outline rather than two that resemble each other.
     *
     * Everything the one-level form's KDoc promises is inherited by the nested one *because* it is
     * the nested one at `depth = 1`; if that ever stops being true, the inheritance is a claim about
     * code that no longer holds, and every other assertion here would still pass.
     */
    @Test
    fun `a depth of one is the top-level outline, for every document and every limit`() {
        forAll(JsonGens.document(), Gen.int(0..16)) { document, limit ->
            val variant = Variant.fromJson(document.toJsonString())
            assertEquals(variant.toJsonSummaryString(limit), variant.toJsonSummaryString(limit, depth = 1))
        }
    }

    @Test
    fun `a nested outline is bounded by its limit and depth, and not by the document`() {
        forAll(JsonGens.document(), Gen.int(0..8)) { document, limit ->
            val variant = Variant.fromJson(document.toJsonString())
            for (depth in 1..3) {
                val summary = variant.toJsonSummaryString(limit, depth)
                val bound = maxSummaryLength(limit, depth)
                assertTrue(
                    summary.length <= bound,
                    "limit $limit depth $depth allows $bound chars, got ${summary.length}: $summary",
                )
                assertTrue(summary.isNotEmpty(), "empty summary for $document")
            }
        }
    }

    @Test
    fun `a nested outline that elides nothing is the JSON`() {
        // The generalisation of the scalar case above, and the reason it can be stated for a whole
        // document: `…` has no JSON production, so its absence from the result is exactly the
        // statement that no cut, no count and no byte gate fired anywhere in the walk. A document
        // holding a literal `…` only ever makes this skip a case, never accept a wrong one.
        var compared = 0
        forAll(JsonGens.document()) { document ->
            val variant = Variant.fromJson(document.toJsonString())
            val summary = variant.toJsonSummaryString(limit = Int.MAX_VALUE, depth = DEFAULT_MAX_JSON_DEPTH)
            if (ELISION !in summary) {
                compared++
                assertEquals(variant.toJsonString(), summary)
            }
        }
        assertTrue(compared > 0, "nothing was small enough to compare; the property proved nothing")
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

    /** A chain of [depth] objects under `down`, with `{"leaf":7}` at the bottom. */
    private fun nest(depth: Int): Variant = build {
        repeat(depth) {
            startObject()
            field("down")
        }
        startObject()
        field("leaf")
        appendLong(7)
        endObject()
        repeat(depth) { endObject() }
    }

    private companion object {
        /** Well above `DEFAULT_MAX_JSON_DEPTH`, and above any stack a value-following walk would have. */
        const val DEEP = 20_000
        const val ROWS = 5_000
    }
}
