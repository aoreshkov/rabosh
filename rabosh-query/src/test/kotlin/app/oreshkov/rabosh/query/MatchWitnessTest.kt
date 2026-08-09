package app.oreshkov.rabosh.query

import app.oreshkov.rabosh.catalog.CatalogPath
import app.oreshkov.rabosh.catalog.forEachNodeIn
import app.oreshkov.rabosh.index.IndexOptions
import app.oreshkov.rabosh.variant.Variant
import app.oreshkov.rabosh.variant.VariantNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whether "the locations that matched" is a question a row can answer. It is not, and this says why.
 *
 * §10.2 proposes that `QueryCursor` hand back the row **and the locations that matched it**, so a
 * caller does not re-expand a path per row. The entry records it as blocked on a *ruling* — whether
 * the executor may know which leaf matched. This file is the check that precedes the ruling, in the
 * shape `CompositeTermPrefixTest` took for R.2: drive the premises through the engine's own evaluator
 * and see which of them survive.
 *
 * **The finding is not that the feature is unsound. It is that the question is *partial*,** and the
 * partiality falls exactly along the line the engine already draws between a predicate that is
 * satisfied by something and one that is satisfied by the *absence* of something.
 *
 * | shape | is there a location that justifies the match? |
 * |---|---|
 * | a positive leaf | **yes**, and possibly several — the row names none of them |
 * | a positive `elemMatch` | **yes**, the element — and possibly several |
 * | a disjunction | yes, but only from the operands that hold |
 * | **a negated leaf** | **no. There is nothing to point at** |
 * | **a negated `elemMatch`** | **no**, for the same reason |
 * | `not(exists())` | **no**, and most obviously: it matches documents where the path is not there |
 *
 * A witness set that is empty for the whole bottom half of that table is indistinguishable from a
 * witness set that is empty because nothing matched — which is the failure mode this engine already
 * refuses by name in `.claude/rules/index-and-query.md`, where an ordinal source must never answer
 * emptily because "no matches here" and "I cannot say" are different facts.
 *
 * And for the top half the row still does not know: `DocumentMatcher` settles a leaf on the **first**
 * value that satisfies it and then folds booleans, so by the time a row exists the engine holds one
 * bit per leaf and no locations at all. Reporting them would mean either abandoning that
 * short-circuit — on the per-row path of every query, including the ones that never ask — or walking
 * the document a second time, which is what the caller already does, with the same public function,
 * in two lines.
 */
class MatchWitnessTest {

    private fun matches(predicate: Predicate, document: Variant): Boolean =
        DocumentMatcher(predicate.normalise().lower(IndexOptions.DEFAULT), IndexOptions.DEFAULT)
            .matches(document)

    private fun nodesAt(expression: String, document: Variant): List<VariantNode> =
        buildList { CatalogPath.parse(expression).forEachNodeIn(document) { add(it) } }

    /**
     * The bottom half of the table, pinned on two documents that match for **different reasons and
     * neither of them a location**.
     *
     * `not($.a eq 10)` holds for a document whose `a` is a string — there is a node at `$.a`, and it
     * is not a witness, because it does not satisfy the leaf — and for one with no `a` at all, where
     * there is no node to be a witness. A feature reporting "the locations that matched" has to answer
     * something for both rows, and the only honest answer is nothing.
     */
    @Test
    fun `a negated leaf matches documents that hold no location justifying it`() {
        val wrongType = jsonDocument("""{"a":"x"}""")
        val absent = jsonDocument("""{"other":1}""")
        val predicate = not(path("$.a") eq 10L)

        assertTrue(matches(predicate, wrongType), "a value of the wrong type satisfies the negation")
        assertTrue(matches(predicate, absent), "and so does no value at all")

        // The document that *has* a node there: it exists and does not satisfy the leaf, so it cannot
        // be the witness for a match justified by the leaf failing.
        val present = nodesAt("$.a", wrongType)
        assertEquals(1, present.size)
        assertFalse(matches(path("$.a") eq 10L, wrongType), "the only candidate node is not a witness")

        // And the document that has none: there is nothing at the path to report at all.
        assertEquals(0, nodesAt("$.a", absent).size, "a negated match can rest on an empty location set")
    }

    /** `not(exists())` is the same finding with nothing left to argue about. */
    @Test
    fun `a document matching not-exists has no location at the path by construction`() {
        val document = jsonDocument("""{"other":1}""")

        assertTrue(matches(not(path("$.a").exists()), document))
        assertEquals(0, nodesAt("$.a", document).size, "the match *is* the absence")
    }

    /**
     * The top half, and the reason it is not the easy half either: a witness exists and is **plural**.
     *
     * Two tags satisfy the leaf. The predicate is existential, so the row is one row; "the locations
     * that matched" is a two-element set the engine never assembled, because the matcher settles the
     * leaf on the first value and skips the rest. Any answer the cursor gave would be a *choice* —
     * first, all, or arbitrary — presented as a fact.
     */
    @Test
    fun `a positive leaf can be satisfied by several locations and the row names none`() {
        val document = jsonDocument("""{"tags":["t1","t2","t1"]}""")

        assertTrue(matches(path("$.tags[*]") eq "t1", document))

        val all = nodesAt("$.tags[*]", document)
        assertEquals(3, all.size, "three locations at the path")
        assertEquals(
            2,
            all.count { it.value.stringValue() == "t1" },
            "two of them satisfy the leaf, and the boolean the matcher folds distinguishes neither",
        )
    }

    /**
     * A disjunction's witnesses belong to the operands that hold, so a witness set cannot be read off
     * the predicate — it has to be attributed during the fold.
     *
     * Reporting the union of every operand's locations would name `$.b` as a reason this document
     * matched, which it is not. That is a second design decision the feature would have to take, on
     * top of the plural one above and the empty one below.
     */
    @Test
    fun `a disjunction is justified only by the operands that hold`() {
        val document = jsonDocument("""{"a":1,"b":99}""")

        assertTrue(matches(or(path("$.a") eq 1L, path("$.b") eq 2L), document))
        assertTrue(matches(path("$.a") eq 1L, document), "the first operand holds")
        assertFalse(matches(path("$.b") eq 2L, document), "the second does not, and its node is no witness")
        assertEquals(1, nodesAt("$.b", document).size, "though it does have a location, which is the trap")
    }

    /**
     * `elemMatch` is the shape the feature is really wanted for, and it inherits both problems.
     *
     * Two elements satisfy the operand, so the witness is plural; and the negation of the same
     * question is satisfied by a document with **no elements at all**, so the witness is absent. The
     * most-wanted case is not a case where the question becomes total.
     */
    @Test
    fun `an elemMatch witness is plural, and its negation has none`() {
        val twoMatching = jsonDocument(
            """{"items":[{"sku":"A","qty":5},{"sku":"B","qty":1},{"sku":"A","qty":5}]}""",
        )
        val correlated = elemMatch("$.items[*]", and(path("$.sku") eq "A", path("$.qty") eq 5L))

        assertTrue(matches(correlated, twoMatching))
        assertEquals(3, nodesAt("$.items[*]", twoMatching).size, "three elements, two of them witnesses")

        val noItems = jsonDocument("""{"other":1}""")
        assertTrue(matches(not(correlated), noItems), "no element satisfies it, including vacuously")
        assertEquals(0, nodesAt("$.items[*]", noItems).size, "and there is no element to point at")
    }

    /**
     * The alternative that already shipped, asserted so the refusal rests on something that works.
     *
     * This is what a caller writes today, and it is the whole of what §10.2 would have saved them: one
     * `CatalogPath`, parsed once, walked per row. It answers **all** the locations rather than the
     * first, it is the same walk `rabosh-catalog` uses, and it does not have to decide what a negation
     * witnesses because the caller asked about a path rather than about a predicate.
     */
    @Test
    fun `the shipped alternative answers the question the cursor cannot`() {
        val document = jsonDocument("""{"items":[{"sku":"A"},{"sku":"B"},{"sku":"A"}]}""")
        val items = CatalogPath.parse("$.items[*]")

        val matching = buildList {
            items.forEachNodeIn(document) { node -> if (node.value.field("sku")?.stringValue() == "A") add(node) }
        }

        assertEquals(2, matching.size, "both of them, not the first")
        assertEquals(
            listOf("$['items'][0]", "$['items'][2]"),
            matching.map { it.location.toNormalizedPath() },
            "and each carries where it was, in RFC 9535's form",
        )
    }
}
