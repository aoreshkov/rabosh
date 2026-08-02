package app.oreshkov.rabosh.variant

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VariantPathTest {

    private val document = Variant.fromJson(
        """
        {
          "id": 7,
          "user": { "name": "ada", "tags": ["x", "y"], "address": { "city": "London" } },
          "items": [ { "sku": "a" }, { "sku": "b" } ],
          "odd name": 1,
          "a.b": 2,
          "": 3
        }
        """.trimIndent(),
    )

    @Test
    fun `paths parse and render back to the same text`() {
        val expressions = listOf(
            "$",
            "$.id",
            "$.user.name",
            "$.items[0].sku",
            "$[\"odd name\"]",
            "$[\"a.b\"]",
            "$[\"\"]",
            "$.a[0][1].b",
        )
        for (expression in expressions) {
            assertEquals(expression, VariantPath.parse(expression).toString(), expression)
        }
    }

    @Test
    fun `a quoted step and a dotted step are different locations`() {
        // The whole reason paths are parsed rather than compared as strings.
        assertTrue(VariantPath.parse("$[\"a.b\"]") != VariantPath.parse("$.a.b"))
        assertEquals(2, document.select("$[\"a.b\"]")?.longValue())
        assertNull(document.select("$.a.b"))
    }

    @Test
    fun `navigation reaches every kind of location`() {
        assertEquals(7, document.select("$.id")?.longValue())
        assertEquals("ada", document.select("$.user.name")?.stringValue())
        assertEquals("y", document.select("$.user.tags[1]")?.stringValue())
        assertEquals("London", document.select("$.user.address.city")?.stringValue())
        assertEquals("b", document.select("$.items[1].sku")?.stringValue())
        assertEquals(1, document.select("$[\"odd name\"]")?.longValue())
        assertEquals(3, document.select("$[\"\"]")?.longValue())
        assertEquals(document.toJsonString(), document.select("$")?.toJsonString())
    }

    /**
     * A path that does not match is absent, not an error. Over schemaless data, "this document has
     * no such field" is the ordinary case, and making it throw would double the length of every
     * predicate above this module.
     */
    @Test
    fun `a path that does not match yields null`() {
        assertNull(document.select("$.missing"))
        assertNull(document.select("$.user.missing.deeper"))
        assertNull(document.select("$.items[9]"))
        assertNull(document.select("$.id[0]"), "an index into a scalar")
        assertNull(document.select("$.items.sku"), "a field of an array")
        assertNull(document.select("$.user.tags[0].x"), "a field of a string")
    }

    @Test
    fun `paths compose and compare structurally`() {
        val user = variantPathOf("user")
        assertEquals(VariantPath.parse("$.user.name"), user + VariantPathStep.Field("name"))
        assertEquals(VariantPath.parse("$.user.tags[0]"), user + VariantPath.parse("$.tags[0]"))
        assertEquals(VariantPath.parse("$.user"), user)
        assertEquals(VariantPath.parse("$.user").hashCode(), user.hashCode())
        assertTrue(VariantPath.ROOT.isRoot)
        assertEquals("$", VariantPath.ROOT.toString())
    }

    @Test
    fun `malformed path expressions are rejected with a position`() {
        fun rejects(expression: String, expectedMessage: String) {
            val failure = assertFailsWith<IllegalArgumentException>(expression) {
                VariantPath.parse(expression)
            }
            assertTrue(expectedMessage in failure.message.orEmpty(), failure.message.orEmpty())
            assertTrue("position" in failure.message.orEmpty(), failure.message.orEmpty())
        }

        rejects("user.name", "must start with '$'")
        rejects("$.", "expected a field name")
        rejects("$..a", "expected a field name")
        rejects("$[", "unterminated '['")
        rejects("$[0", "expected ']'")
        rejects("$[\"a", "unterminated quoted field name")
        rejects("$[a]", "expected an array index")
        rejects("\$user", "expected '.' or '['")
    }

    @Test
    fun `a negative array index is not a path`() {
        assertFailsWith<IllegalArgumentException> { VariantPathStep.Index(-1) }
        // '-' is not an index character, so the parser rejects it before the step is built.
        assertFailsWith<IllegalArgumentException> { VariantPath.parse("$[-1]") }
    }
}
