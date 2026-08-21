package app.oreshkov.rabosh.variant

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * The third reader: RFC 9535's `singular-query`, read as a location.
 *
 * `VariantPathTest` covers the engine's spelling and `NormalizedPathTest` covers §2.7's. This covers
 * the one a person types — and, more to the point, the question a consumer actually has, which is
 * whether an expression names one location at all.
 */
class SingularJsonPathTest {

    @Test
    fun `one location has many spellings and all of them answer`() {
        val expected = variantPathOf("response", "body") + VariantPathStep.Index(0)
        for (expression in listOf(
            """$['response']['body'][0]""",
            """$["response"]["body"][0]""",
            "$.response.body[0]",
            """$.response['body'][0]""",
            """$[ 'response' ][ 'body' ][ 0 ]""",
        )) {
            assertEquals(expected, VariantPath.parseJsonPathOrNull(expression), expression)
        }
    }

    @Test
    fun `the normalized path the engine emits reads back, which it could not before`() {
        // The round trip §3(d) of the alignment study says is missing: a location comes back from a
        // JSONPath evaluation as §2.7's single-quoted form, and the only reader that took it was
        // `parseNormalized`. Now the general reader takes it too, so a caller holding one spelling
        // does not have to know which of the three it is.
        val path = variantPathOf("content") + VariantPathStep.Index(108)
        val normalized = path.toNormalizedPath()

        assertEquals("""$['content'][108]""", normalized)
        assertEquals(path, VariantPath.parseJsonPathOrNull(normalized))
        assertEquals(path, VariantPath.parseJsonPathOrNull(path.toString()))
    }

    @Test
    fun `the bracket form no longer costs a caller its projection`() {
        // The defect R3 exists for, stated as the check a consumer writes without it. `toString`
        // renders a simple name in dot form, so a perfectly valid bracketed expression fails the
        // equality and the caller silently falls back to reading whole documents.
        val expression = """$["response"]["body"]"""
        val parsed = VariantPath.parse(expression)

        assertNotEquals(expression, parsed.toString(), "the string check this replaces fails here")
        assertEquals(parsed, VariantPath.parseJsonPathOrNull(expression))
    }

    @Test
    fun `escaping is RFC 9535's, and the engine's reader still means what it meant`() {
        assertEquals(variantPathOf("a\nb"), VariantPath.parseJsonPathOrNull("""$['a\nb']"""))
        assertEquals(variantPathOf("anb"), VariantPath.parse("""$["a\nb"]"""))

        assertEquals(variantPathOf("a\tb"), VariantPath.parseJsonPathOrNull("""$['a\tb']"""))
        assertEquals(variantPathOf("a/b"), VariantPath.parseJsonPathOrNull("""$['a\/b']"""))
        assertEquals(variantPathOf("aAb"), VariantPath.parseJsonPathOrNull("""$['a\u0041b']"""))
        assertEquals(variantPathOf("😀"), VariantPath.parseJsonPathOrNull("""$['😀']"""))
    }

    @Test
    fun `it is more lenient than parseNormalized and says so by answering where that throws`() {
        // §2.7 is one spelling per location: no dot form, no double quotes, no `A` for a
        // character that can stand raw. All three are ordinary JSONPath and all three answer here.
        for (expression in listOf("$.a", """$["a"]""", """$['\u0041']""")) {
            assertEquals(1, VariantPath.parseJsonPathOrNull(expression)?.steps?.size, expression)
        }
    }

    @Test
    fun `an expression naming more than one location is null`() {
        for (expression in listOf(
            "$.items[*]",
            "$.items.*",
            "$..sku",
            "$.items[1:3]",
            "$.items[:]",
            """$.items[?@.sku == 'a']""",
            """$['a','b']""",
        )) {
            assertNull(VariantPath.parseJsonPathOrNull(expression), expression)
        }
    }

    @Test
    fun `an index this engine cannot hold is null rather than rounded`() {
        // `[-1]` is a singular query and names one location *per document*; `[9999999999]` is a
        // location RFC 9535 allows and `VariantPathStep.Index` cannot hold. Neither may be
        // approximated, and a leading zero is not a spelling of an index at all.
        for (expression in listOf("$.a[-1]", "$.a[9999999999]", "$.a[01]", "$.a[+1]")) {
            assertNull(VariantPath.parseJsonPathOrNull(expression), expression)
        }
        assertEquals(variantPathOf("a") + VariantPathStep.Index(0), VariantPath.parseJsonPathOrNull("$.a[0]"))
    }

    @Test
    fun `a malformed expression is null and never an exception`() {
        // The whole contract in one test: this reader throws nothing, so a caller needs no `catch`
        // — and in particular no `runCatching`, which catches `Throwable` and would swallow a
        // cancellation along with the typo.
        for (expression in listOf(
            "",
            "a",
            "$.",
            "$[",
            "$[*",
            "$.a.",
            """$['unterminated""",
            "$.@type",
            "$[a]",
            """$['a'}""",
            """$['a\qb']""",
            """$['a\"b']""",
            """$["a\'b"]""",
            """$['\uD800']""",
        )) {
            assertNull(VariantPath.parseJsonPathOrNull(expression), expression)
        }
    }

    @Test
    fun `the root is a location and is not confused with a failure`() {
        assertEquals(VariantPath.ROOT, VariantPath.parseJsonPathOrNull("$"))
    }
}
