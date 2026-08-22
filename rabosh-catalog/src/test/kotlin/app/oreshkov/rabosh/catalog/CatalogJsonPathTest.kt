package app.oreshkov.rabosh.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The interchange direction: what `CatalogPath` renders as RFC 9535, and what it reads back.
 *
 * `CatalogPathTest` covers the engine's own spelling — the one written to disk. This covers the
 * second one, and in particular the three places the two grammars disagree about the same
 * characters: the wildcard, the quote and the backslash.
 */
class CatalogJsonPathTest {

    @Test
    fun `every step has an RFC 9535 rendering`() {
        assertEquals("$", CatalogPath.ROOT.toJsonPath())
        assertEquals("""$['user']['name']""", CatalogPath.parse("$.user.name").toJsonPath())
        assertEquals("""$['items'][:]['sku']""", CatalogPath.parse("$.items[*].sku").toJsonPath())
        assertEquals("""$['@type']""", CatalogPath.parse("""$["@type"]""").toJsonPath())
    }

    @Test
    fun `AnyElement renders as the slice and never as the wildcard`() {
        // The finding this whole pair exists for: `[*]` selects every child of an object as well as
        // an array, and `AnyElement` does not. `NodeWalkDifferentialTest` is where the difference is
        // pinned against a real evaluator; here it is pinned as text so a "tidy-up" has to edit an
        // assertion that says why.
        val path = CatalogPath.parse("$.items[*]")
        assertEquals("""$['items'][:]""", path.toJsonPath())
        assertNotEquals(path.toString(), path.toJsonPath())
    }

    @Test
    fun `a name is escaped as RFC 9535 escapes it, and a control character always as hex`() {
        assertEquals("""$['a\'b']""", catalogPathOf("a'b").toJsonPath())
        assertEquals("""$['a\\b']""", catalogPathOf("a\\b").toJsonPath())
        // A control character is written in the `\uXXXX` form even where a named escape exists,
        // because both are `escapable` and one form is fewer branches. That is allowed in a query
        // and would not be in a Normalized Path, which gives each name exactly one spelling.
        assertEquals("""$['a\u000ab']""", catalogPathOf("a\nb").toJsonPath())
        // A double quote and a solidus are ordinary inside single quotes; §2.7 says so and so does
        // §2.3.1.1. Escaping them would still parse and would make one name have two spellings.
        assertEquals("""$['a"b/c']""", catalogPathOf("""a"b/c""").toJsonPath())
    }

    @Test
    fun `an unpaired surrogate has no rendering and is reported rather than emitted`() {
        val failure = assertFailsWith<IllegalArgumentException> { catalogPathOf("a\uD800b").toJsonPath() }
        assertTrue(failure.message!!.contains("unpaired surrogate"), failure.message!!)
    }

    @Test
    fun `the rendering round-trips through the reader`() {
        for (expression in listOf(
            "$",
            "$.user.name",
            "$.items[*].sku",
            "$[*][*]",
            """$["odd name"]""",
            """$["a.b"].c""",
            """$["@type"]""",
        )) {
            val path = CatalogPath.parse(expression)
            assertEquals(path, CatalogPath.parseJsonPath(path.toJsonPath()), path.toJsonPath())
        }
    }

    @Test
    fun `both quote styles read as the same path, which is the point of the reader`() {
        val expected = CatalogPath.parse("""$.response.body["@type"]""")
        for (expression in listOf(
            """$['response']['body']['@type']""",
            """$["response"]["body"]["@type"]""",
            """$.response['body']["@type"]""",
            """$.response.body['@type']""",
        )) {
            assertEquals(expected, CatalogPath.parseJsonPath(expression), expression)
        }
    }

    @Test
    fun `a wildcard is accepted on input in every spelling it has`() {
        // Postel, deliberately: `[*]` is what a consumer types and `[:]` is what the writer emits.
        // Accepting both on input while emitting one is the asymmetry, and it is not sloppiness.
        val expected = CatalogPath.parse("$.items[*].sku")
        for (expression in listOf("$.items[*].sku", """$['items'][:]['sku']""", "$.items.*.sku")) {
            assertEquals(expected, CatalogPath.parseJsonPath(expression), expression)
        }
    }

    @Test
    fun `escaping is RFC 9535's and not the engine's, and the two name different fields`() {
        // `$["a\nb"]` is `anb` to `parse` and `a`, newline, `b` to `parseJsonPath`. Both are right
        // for their own grammar; what must never happen is one reader quietly answering for both.
        val engine = CatalogPath.parse("""$["a\nb"]""")
        val rfc = CatalogPath.parseJsonPath("""$["a\nb"]""")

        assertEquals(catalogPathOf("anb"), engine)
        assertEquals(catalogPathOf("a\nb"), rfc)
        assertNotEquals(engine, rfc)
    }

    @Test
    fun `an escape RFC 9535 spells is read, and one it does not is malformed`() {
        assertEquals(catalogPathOf("a\tb"), CatalogPath.parseJsonPath("""$['a\tb']"""))
        assertEquals(catalogPathOf("a/b"), CatalogPath.parseJsonPath("""$['a\/b']"""))
        assertEquals(catalogPathOf("aAb"), CatalogPath.parseJsonPath("""$['a\u0041b']"""))
        assertEquals(catalogPathOf("😀"), CatalogPath.parseJsonPath("""$['😀']"""))

        // A quote may be escaped only by the quoting that opened the literal.
        assertFailsWith<IllegalArgumentException> { CatalogPath.parseJsonPath("""$['a\"b']""") }
        assertFailsWith<IllegalArgumentException> { CatalogPath.parseJsonPath("""$["a\'b"]""") }
        // A lone high surrogate escape is not a character.
        assertFailsWith<IllegalArgumentException> { CatalogPath.parseJsonPath("""$['\uD800']""") }
    }

    @Test
    fun `blanks are allowed exactly where RFC 9535 allows them`() {
        assertEquals(catalogPathOf("a"), CatalogPath.parseJsonPath("""$[ 'a' ]"""))
        assertEquals(CatalogPath.parse("$[*]"), CatalogPath.parseJsonPath("$[ * ]"))
        // None between a dot and the name it introduces: `member-name-shorthand` follows directly.
        assertFailsWith<IllegalArgumentException> { CatalogPath.parseJsonPath("$. a") }
    }

    @Test
    fun `an unrepresentable construct is refused by name and never approximated`() {
        for ((expression, construct) in listOf(
            "$.items[0]" to PathConstruct.INDEX,
            "$.items[-1]" to PathConstruct.INDEX,
            """$.items[?@.sku == 'a']""" to PathConstruct.FILTER,
            "$.items[1:3]" to PathConstruct.SLICE,
            "$.items[::2]" to PathConstruct.SLICE,
            "$.items[::]" to PathConstruct.SLICE,
            """$['a','b']""" to PathConstruct.MULTIPLE_SELECTORS,
        )) {
            val failure = assertFailsWith<PathNotRepresentableException>(expression) {
                CatalogPath.parseJsonPath(expression)
            }
            assertEquals(construct, failure.construct, expression)
        }
    }

    /**
     * `..` was on that list until this type had a step for it, and the row it left behind is worth
     * keeping: `PathConstruct.DESCENDANT` is still an entry nobody raises, because removing a value
     * from an enum a caller may `when` over buys nothing and breaks a build.
     *
     * The bare `$..` is the asymmetry to know about. It is a catalog path — every node, root
     * included — and it is **not** a JSONPath query, because RFC 9535's descendant segment must
     * carry a selector. So it is *malformed* to this reader rather than unrepresentable, which is
     * the same fact `toJsonPath` states from the other side by refusing to render one.
     */
    @Test
    fun `a descendant is read where it used to be refused, and the bare one is still not a query`() {
        assertEquals(
            CatalogPath(listOf(CatalogStep.AnyDescendant, CatalogStep.Field("sku"))),
            CatalogPath.parseJsonPath("$..sku"),
        )
        assertEquals(CatalogPath.parseJsonPath("$..sku"), CatalogPath.parseJsonPath("""$..['sku']"""))
        assertEquals(
            CatalogPath.parse("""$..["@type"]"""),
            CatalogPath.parseJsonPath("""$..["@type"]"""),
            "the two grammars agree about a descendant, which is why one expression can serve both",
        )

        val malformed = assertFailsWith<IllegalArgumentException> { CatalogPath.parseJsonPath("$..") }
        assertTrue(
            malformed !is PathNotRepresentableException,
            "'\$..' is a path this type can hold and a query the RFC has no production for: ${malformed.message}",
        )
    }

    @Test
    fun `a refusal is catchable as the type every caller already catches`() {
        // The compatibility property R4 rests on: an existing `catch (IllegalArgumentException)`
        // around a path parse keeps working, and a caller wanting the distinction catches the
        // narrower type in front of it.
        val failure = assertFailsWith<IllegalArgumentException> { CatalogPath.parseJsonPath("$.items[0]") }
        assertTrue(failure is PathNotRepresentableException, "must stay a subclass of IllegalArgumentException")
    }

    @Test
    fun `a typo is malformed and not a refusal, which is the distinction that matters`() {
        for (expression in listOf(
            "",
            "items",
            "$.",
            "$[",
            "$[*",
            "$.a.",
            """$['unterminated""",
            "$.@type",
            "$[a]",
            """$['a'}""",
        )) {
            val failure = assertFailsWith<IllegalArgumentException>("'$expression' should not parse") {
                CatalogPath.parseJsonPath(expression)
            }
            assertTrue(
                failure !is PathNotRepresentableException,
                "'$expression' is a typo, not a construct this grammar declines",
            )
        }
    }

    @Test
    fun `the engine's reader is untouched by the RFC one`() {
        // Both readers stay, and neither learns the other's escapes: a path is persisted as
        // `toString` and read back by `parse`, so widening `parse` would change what stored bytes
        // mean.
        assertFailsWith<IllegalArgumentException> { CatalogPath.parse("""$['a']""") }
        assertEquals(catalogPathOf("anb"), CatalogPath.parse("""$["a\nb"]"""))
    }
}
