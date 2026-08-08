package app.oreshkov.rabosh.jsonpath

import app.oreshkov.rabosh.variant.Variant
import app.oreshkov.rabosh.variant.VariantPath
import app.oreshkov.rabosh.variant.VariantPathStep
import app.oreshkov.rabosh.variant.toJsonString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What `compile` accepts, what it refuses, and what it says when it refuses.
 *
 * The compliance suite already asserts that 241 invalid selectors are rejected — this is the part of
 * the contract the suite has no opinion about: that the failure carries a *position*, that the two
 * limits on a query exist and are the query's rather than the document's, that an unimplemented
 * function is refused rather than answered, and that the engine's own bracket grammar is untouched by
 * any of it.
 */
class JsonPathSyntaxTest {

    @Test
    fun `a failure names the offending position and the query`() {
        val failure = assertFailsWith<IllegalArgumentException> { JsonPathQuery.compile("$.a[01]") }
        assertTrue("at position 4" in failure.message.orEmpty(), failure.message.orEmpty())
        assertTrue("$.a[01]" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `the position points at the construct that is wrong, not at wherever parsing reached`() {
        // The left operand is what is not comparable; the operator is fine and so is the literal.
        val failure = assertFailsWith<IllegalArgumentException> { JsonPathQuery.compile("$[?@.a[*] == 1]") }
        assertTrue("at position 3" in failure.message.orEmpty(), failure.message.orEmpty())
        assertTrue("singular" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    /**
     * `match` and `search` are refused at compile time, and the message says why.
     *
     * The alternative — compiling them into something that returns a nodelist — is the failure this
     * module's whole exclusion discipline exists to prevent: a query that quietly answers a question
     * this build cannot answer. Rejected at *compile* rather than at evaluation, so a caller learns
     * once rather than per document.
     */
    @Test
    fun `an unimplemented function is refused, and the message says which and why`() {
        for (query in listOf("$[?match(@.a, 'a.*')]", "$[?search(@.a, 'b')]")) {
            val failure = assertFailsWith<IllegalArgumentException> { JsonPathQuery.compile(query) }
            assertTrue("I-Regexp" in failure.message.orEmpty(), failure.message.orEmpty())
            assertTrue("not implemented" in failure.message.orEmpty(), failure.message.orEmpty())
        }
    }

    @Test
    fun `an unregistered function is refused as a name, not as a regular expression`() {
        val failure = assertFailsWith<IllegalArgumentException> { JsonPathQuery.compile("$[?matches(@.a, 'x')]") }
        assertTrue("not one of the function extensions" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    /**
     * The three functions this build does evaluate, over a document that exercises each answer.
     *
     * `length` of a number is `Nothing` rather than an error, `count` of an empty nodelist is zero
     * rather than `Nothing`, and `value` of a nodelist with two nodes is `Nothing` rather than the
     * first — three rules that a plausible implementation gets wrong in three different ways.
     */
    @Test
    fun `length, count and value answer as RFC 9535 section 2_4 defines them`() {
        val document = Variant.fromJson(
            """{"rows":[{"tags":["a","b"],"n":1},{"tags":[],"n":22},{"n":333},{"tags":"ab","n":4}]}""",
        )

        assertEquals(
            listOf("$['rows'][0]", "$['rows'][3]"),
            JsonPathQuery.compile("$.rows[?length(@.tags) == 2]").nodesIn(document).map {
                it.location.toNormalizedPath()
            },
            "length counts elements of an array and code points of a string, and nothing else",
        )
        assertEquals(
            listOf("$['rows'][0]"),
            JsonPathQuery.compile("$.rows[?count(@.tags[*]) == 2]").nodesIn(document).map {
                it.location.toNormalizedPath()
            },
        )
        assertEquals(
            listOf("$['rows'][1]", "$['rows'][2]", "$['rows'][3]"),
            JsonPathQuery.compile("$.rows[?count(@.tags[*]) == 0]").nodesIn(document).map {
                it.location.toNormalizedPath()
            },
            "count of a nodelist with no nodes is zero — for an empty array, for an absent path, and " +
                "for a wildcard over a string alike, none of which is Nothing",
        )
        assertEquals(
            listOf("$['rows'][3]"),
            JsonPathQuery.compile("$.rows[?value(@.tags) == 'ab']").nodesIn(document).map {
                it.location.toNormalizedPath()
            },
        )
        assertEquals(
            0,
            JsonPathQuery.compile("$.rows[?length(@.n) == 1]").nodesIn(document).size,
            "the length of a number is Nothing, which equals nothing at all",
        )
    }

    /**
     * A number literal means what the same literal means in a document.
     *
     * Both go through `VariantBuilder.appendNumberLiteral`, which is what makes `1e2` and `100` the
     * same number without a comparison rule that knows about exponents. Written as a test because the
     * alternative — parsing query literals with a second number reader — is the obvious refactoring
     * and would break exactly this.
     */
    @Test
    fun `a number literal compares by value across spellings`() {
        val document = Variant.fromJson("""{"a":[100, 1.5, 0, -0.0]}""")
        for (spelling in listOf("100", "1e2", "1.0e2", "100.000")) {
            assertEquals(
                listOf("100"),
                JsonPathQuery.compile("$.a[?@ == $spelling]").nodesIn(document).map { it.value.toJsonString() },
                "the literal $spelling",
            )
        }
        assertEquals(2, JsonPathQuery.compile("$.a[?@ == 0]").nodesIn(document).size, "0 and -0.0 are one number")
    }

    /**
     * A `singular-query` is a spelling, not a cardinality — including the blanks.
     *
     * `@[ 0 ]` selects at most one node and is still not a `singular-query`, because
     * `index-segment` is `"[" index-selector "]"` with no `S` in it. The compliance suite does not
     * probe this corner, so it is pinned here: the ABNF is the authority, and a reader who "fixes"
     * the whitespace handling would otherwise find nothing failing.
     */
    @Test
    fun `a comparison operand must be spelled as a singular query`() {
        val document = Variant.fromJson("""{"a":[{"b":1}]}""")
        assertEquals(1, JsonPathQuery.compile("$.a[?@['b'] == 1]").nodesIn(document).size)
        assertEquals(1, JsonPathQuery.compile("$.a[?@[0] == 1 || @.b == 1]").nodesIn(document).size)

        assertFailsWith<IllegalArgumentException> { JsonPathQuery.compile("$.a[?@[ 'b' ] == 1]") }
        assertFailsWith<IllegalArgumentException> { JsonPathQuery.compile("$.a[?@['b','b'] == 1]") }
        assertFailsWith<IllegalArgumentException> { JsonPathQuery.compile("$.a[?@..b == 1]") }
    }

    @Test
    fun `a bare query is a test and a bare literal is not`() {
        val document = Variant.fromJson("""{"a":[{"b":1},{"c":2}]}""")
        assertEquals(1, JsonPathQuery.compile("$.a[?@.b]").nodesIn(document).size)
        assertEquals(1, JsonPathQuery.compile("$.a[?!@.b]").nodesIn(document).size)

        assertFailsWith<IllegalArgumentException> { JsonPathQuery.compile("$.a[?1]") }
        assertFailsWith<IllegalArgumentException> { JsonPathQuery.compile("$.a[?@.b && 1]") }
        // `!` attaches to a test or to a parenthesised expression, never to a comparison.
        assertFailsWith<IllegalArgumentException> { JsonPathQuery.compile("$.a[?!@.b == 1]") }
        assertEquals(1, JsonPathQuery.compile("$.a[?!(@.b == 1)]").nodesIn(document).size)
    }

    /**
     * Both limits are on the *query*, and both are reachable only by writing one.
     *
     * A bound on the walk would truncate a nodelist, and a truncated nodelist is a wrong answer with
     * nothing to say so. A bound on what the caller wrote costs no answer at all — which is the whole
     * reason the limits are here rather than there.
     */
    @Test
    fun `a query is bounded in selectors and in nesting`() {
        val wide = "$" + "[0,1]".repeat(1)
        assertEquals(2, JsonPathQuery.compile(wide).nodesIn(Variant.fromJson("[1,2,3]")).size)

        val tooManySelectors = "$" + ".a".repeat(2000)
        val selectorFailure = assertFailsWith<IllegalArgumentException> { JsonPathQuery.compile(tooManySelectors) }
        assertTrue("selectors" in selectorFailure.message.orEmpty(), selectorFailure.message.orEmpty())

        val tooDeep = "$[?" + "(".repeat(200) + "@.a" + ")".repeat(200) + "]"
        val nestingFailure = assertFailsWith<IllegalArgumentException> { JsonPathQuery.compile(tooDeep) }
        assertTrue("nest" in nestingFailure.message.orEmpty(), nestingFailure.message.orEmpty())
    }

    /**
     * **The engine's own grammar is untouched, and the two disagree where they always did.**
     *
     * `VariantPath.parse` reads a backslash as escaping the next character *literally*, so
     * `$["a\nb"]` is the three-character name `anb`; RFC 9535's string literal reads the same text as
     * `a`, newline, `b`. Both are right for their own question. This is the assertion that says
     * nothing here has drifted toward the other — a path is persisted as `VariantPath.toString()` in
     * `.cat` sidecars and in the index registry of every golden store, so "improving" that parser
     * toward JSONPath would be a *format* change wearing an ergonomics hat.
     */
    @Test
    fun `the engine's bracket grammar and RFC 9535's remain different languages`() {
        val text = """$["a\nb"]"""
        assertEquals(VariantPath(listOf(VariantPathStep.Field("anb"))), VariantPath.parse(text))

        val document = Variant.fromJson("""{"anb":1,"a\nb":2}""")
        assertContentEquals(
            listOf("2"),
            JsonPathQuery.compile(text).nodesIn(document).map { it.value.toJsonString() },
            "RFC 9535 reads the escape as a newline, and the engine's own parser does not",
        )
    }
}
