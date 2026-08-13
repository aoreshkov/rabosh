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

class VariantNodeTest {

    @Test
    fun `a node is a location and the value there`() {
        val document = Variant.fromJson("""{"items":[{"sku":"a"},{"sku":"b"}]}""")
        val location = VariantPath.parse("$.items[1].sku")
        val node = VariantNode(location, checkNotNull(document.select(location)))

        assertEquals(location, node.location)
        assertEquals("b", node.value.stringValue())
        // The inverse, which is the whole reason the location is a VariantPath and not a string.
        assertEquals("b", checkNotNull(document.select(node.location)).stringValue())
    }

    @Test
    fun `a node summarises as its normalized location and an outline of its value`() {
        val document = Variant.fromJson("""{"items":[{"sku":"a","qty":1,"tags":["x","y"]}]}""")
        val location = VariantPath.parse("$.items[0]")
        val node = VariantNode(location, checkNotNull(document.select(location)))

        // Fields come out name-ordered, as they are stored: see `Variant.fieldName`.
        assertEquals("""$['items'][0] {"qty":1,"sku":"a","tags":[…2]}""", node.toJsonSummaryString())
        assertEquals("""$['items'][0] {"qty":1,…2 more}""", node.toJsonSummaryString(limit = 1))
    }

    @Test
    fun `a node summarises nested levels of its value, keeping the location in front`() {
        val document = Variant.fromJson("""{"items":[{"sku":"a","meta":{"lot":7,"bin":"c"}}]}""")
        val location = VariantPath.parse("$.items[0]")
        val node = VariantNode(location, checkNotNull(document.select(location)))

        assertEquals("""$['items'][0] {"meta":{…2},"sku":"a"}""", node.toJsonSummaryString(depth = 1))
        assertEquals("""$['items'][0] {"meta":{"bin":"c","lot":7},"sku":"a"}""", node.toJsonSummaryString(depth = 2))
        // A node's summary is its value's summary with a location in front of it, at every depth —
        // asserted against the value rather than against a second literal, so the two cannot drift.
        assertEquals(
            "${location.toNormalizedPath()} ${node.value.toJsonSummaryString(limit = 1, depth = 2)}",
            node.toJsonSummaryString(limit = 1, depth = 2),
        )
    }

    @Test
    fun `a nested node summary is bounded by its limit, its depth and its location`() {
        forAll(JsonGens.document(), Gen.int(0..8)) { document, limit ->
            val node = VariantNode(LONG_LOCATION, Variant.fromJson(document.toJsonString()))
            for (depth in 1..3) {
                val summary = node.toJsonSummaryString(limit, depth)
                val bound = LONG_LOCATION.toNormalizedPath().length + 1 + maxJsonSummaryLength(limit, depth)

                assertTrue(
                    summary.length <= bound,
                    "limit $limit depth $depth allows $bound chars, got ${summary.length}: $summary",
                )
                assertTrue(summary.startsWith(LONG_LOCATION.toNormalizedPath()), "no location in $summary")
            }
        }
    }

    @Test
    fun `toString names the location the engine's way and cannot throw`() {
        val document = Variant.fromJson("""{"a":{"b":1}}""")
        val location = VariantPath.parse("$.a")
        val node = VariantNode(location, checkNotNull(document.select(location)))

        assertEquals("$.a Variant(object, children=1, bytes=${node.value.byteSize})", node.toString())
    }

    /**
     * The one place a node's summary can fail, and it is the location rather than the value.
     *
     * `toString` is the form that cannot: it renders the location the engine's way, which has a
     * spelling for every string a `VariantPath` can hold.
     */
    @Test
    fun `a location with no normalized spelling is reported by the summary and not by toString`() {
        val node = VariantNode(
            VariantPath(listOf(VariantPathStep.Field(0xD834.toChar().toString()))),
            Variant.fromJson("1"),
        )

        assertFailsWith<IllegalArgumentException> { node.toJsonSummaryString() }
        assertTrue("unreadable" !in node.toString(), "toString should render, was ${node.toString()}")
    }

    @Test
    fun `a summary of a node inside a huge document is short and cheap`() {
        // The unfavourable case arranged rather than hoped for: the node is an object one of whose
        // children is four megabytes, so nothing but the byte gate keeps this bounded.
        val blob = "x".repeat(FOUR_MEGABYTES)
        val document = Variant.fromJson("""{"items":[{"sku":"abc","blob":"$blob"}]}""")
        assertTrue(document.byteSize > FOUR_MEGABYTES, "fixture is not large enough: ${document.byteSize}")

        val location = VariantPath.parse("$.items[0]")
        val node = VariantNode(location, checkNotNull(document.select(location)))
        val summary = node.toJsonSummaryString()

        assertEquals("""$['items'][0] {"blob":…${blob.length + 5} bytes,"sku":"abc"}""", summary)
        assertTrue(summary.length < 100, "summary was ${summary.length} chars: $summary")
    }

    @Test
    fun `a node summary is bounded by its limit, the value limit and its location`() {
        forAll(JsonGens.document(), Gen.int(0..16)) { document, limit ->
            val value = Variant.fromJson(document.toJsonString())
            val node = VariantNode(LONG_LOCATION, value)
            val summary = node.toJsonSummaryString(limit)
            val bound = LONG_LOCATION.toNormalizedPath().length + 1 + maxJsonSummaryLength(limit)

            assertTrue(
                summary.length <= bound,
                "limit $limit allows $bound chars, got ${summary.length}: $summary",
            )
            // Without this the property is satisfied by every summary being empty.
            assertTrue(summary.startsWith(LONG_LOCATION.toNormalizedPath()), "no location in $summary")
        }
    }

    private companion object {
        const val FOUR_MEGABYTES = 4 * 1024 * 1024

        /** A location long enough that leaving it out of the bound would be visible. */
        val LONG_LOCATION = VariantPath(
            List(8) { VariantPathStep.Field("field$it") } + VariantPathStep.Index(1234),
        )
    }
}
