package app.oreshkov.rabosh.jsonpath

import app.oreshkov.rabosh.variant.Variant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Why a filter selector cannot be lifted into the query language, pinned rather than asserted.
 *
 * The module's `CLAUDE.md` and the README both say `rabosh-jsonpath` sits *beside* the storage chain
 * so that RFC 9535's comparison rules can never reach the planner, and both call the two semantics
 * "genuinely disagreeing". That is a claim about behaviour and it is checkable here, in this module
 * alone, with no dependency on `rabosh-query` — **which is itself the point**: the differential that
 * would compare the two directly cannot be written anywhere without acquiring the edge the layout
 * exists to forbid, so what can be checked is the half that decides the question.
 *
 * ## The finding: a filter is not a predicate, and the difference is not a matter of degree
 *
 * A `Predicate` is a **boolean over a document**. A filter selector is a **selector over a nodelist**:
 * it takes the children of the node it is applied to and keeps those satisfying a test. The only way
 * to read a selector as a predicate is "the document matches when the nodelist is non-empty" — the
 * obvious bridge, and the one anybody adding §10.1b would reach for.
 *
 * **That bridge admits a document satisfying a condition and its negation at once**, which no boolean
 * over documents may do. `$.tags[?@ == 'b']` and `$.tags[?!(@ == 'b')]` both select from
 * `{"tags":["a","b"]}` — the first the `b`, the second the `a`. Non-empty and non-empty, so `P` and
 * `¬P` are both true of the same document. The law of the excluded middle is not a subtlety in a query
 * language: it is what `not(…)` means.
 *
 * The reason is not a defect in either design. A filter's `!` negates a test **about one node**, and
 * the selector is still existential over the others; a `Predicate`'s `not` negates the answer **for
 * the document**, after the existential has been folded. Both are correct about what they quantify
 * over, and they quantify over different things.
 *
 * `.claude/rules/index-and-query.md` already states the storage-side half of this — *a negated leaf is
 * never a flipped operator* — and notes it is the most natural-looking simplification in the layer and
 * that it deletes documents from a result silently. §10.1b is that same trap arriving from outside, in
 * a syntax users already know, which is what makes it worth a test rather than a sentence.
 */
class FilterIsNotAPredicateTest {

    private val tags = Variant.fromJson("""{"tags":["a","b"]}""")

    private fun selects(expression: String): List<String> =
        JsonPathQuery.compile(expression).nodesIn(tags).map { it.location.toNormalizedPath() }

    /**
     * The decisive one: a filter and its negation both select, so "non-empty" is not a predicate.
     *
     * Read as a document test through the only bridge available, this document both has and has not a
     * tag equal to `b`. A query language built on that would answer `where(f)` and `where(not(f))` with
     * the same row, and no planner change or index could repair it, because the defect is in the
     * meaning rather than in the evaluation.
     */
    @Test
    fun `a filter and its negation both select from the same document`() {
        val matching = selects("$.tags[?@ == 'b']")
        val negated = selects("$.tags[?!(@ == 'b')]")

        assertEquals(listOf("$['tags'][1]"), matching, "the filter keeps the tag that is b")
        assertEquals(listOf("$['tags'][0]"), negated, "and its negation keeps the tag that is not")

        assertTrue(matching.isNotEmpty() && negated.isNotEmpty(), "so both are 'true' under the bridge")
        assertTrue(
            matching.intersect(negated.toSet()).isEmpty(),
            "and they are not even the same nodes: this is one document answering yes to both",
        )
    }

    /**
     * The second disagreement, and the one a user would hit first: an operand is a **node**, not the
     * values under it.
     *
     * `@.tags == 'b'` compares the array itself with a string and is therefore false, where the
     * storage side's `path("$.tags[*]") eq "b"` is existential over the elements and is true.
     * `DocumentMatcherTest` pins that side; this pins this one. Same document, same apparent question,
     * opposite answers — and the RFC's answer is the correct one *for RFC 9535*, which is exactly why
     * neither can be quietly adopted by the other.
     */
    @Test
    fun `comparing a path selects nothing where the engine's leaf is existential`() {
        assertEquals(emptyList(), selects("$[?@.tags == 'b']"), "an array is not equal to a string")

        // The RFC spelling that *is* existential says so out loud, with a second filter.
        assertEquals(listOf("$['tags'][1]"), selects("$.tags[?@ == 'b']"))
    }

    /**
     * An absent path makes a comparison false rather than an error, and its negation then selects
     * everything — which is the third way the bridge misleads.
     *
     * Under "non-empty means matched", `!(@.missing == 1)` would report that this document matches a
     * condition about a field it does not have. The engine's `not(path("$.missing") eq 1)` also holds
     * for such a document, so the two agree *here* — and that is worth pinning precisely because
     * agreement on one shape is what makes the disagreement on the others dangerous. A bridge that is
     * right most of the time is the shape that ships.
     */
    @Test
    fun `an absent field compares false and its negation selects every child`() {
        val document = Variant.fromJson("""{"a":1,"b":2}""")
        val query = { expression: String ->
            JsonPathQuery.compile(expression).nodesIn(document).map { it.location.toNormalizedPath() }
        }

        assertEquals(emptyList(), query("$[?@.missing == 1]"))
        assertEquals(listOf("$['a']", "$['b']"), query("$[?!(@.missing == 1)]"))
    }
}
