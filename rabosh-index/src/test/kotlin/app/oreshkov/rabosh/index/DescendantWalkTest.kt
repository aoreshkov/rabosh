package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.catalog.CatalogPath
import app.oreshkov.rabosh.catalog.nodesIn
import app.oreshkov.rabosh.testkit.json.JsonGens
import app.oreshkov.rabosh.testkit.json.toJsonString
import app.oreshkov.rabosh.variant.toJsonString as variantJson
import app.oreshkov.rabosh.testkit.property.forAll
import app.oreshkov.rabosh.variant.Variant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The writer's walk over a path that never narrows away.
 *
 * A `..` turns the walk from a cursor into an **automaton**: without one, a path's progress is its
 * depth and one integer per path is the whole state; with one, the next step may match here or at
 * any level below, so a path is at several positions at once. That is the change this file exists to
 * hold, and it is checked the way every other walk here is — against a second implementation written
 * over the testkit's JSON model rather than over the encoding, so agreement is evidence.
 *
 * **The failure mode it is aimed at is a *missing* term, not a wrong one.** An automaton that drops a
 * state produces an index that is a subset of the truth, which no recheck can rescue and no coverage
 * counter reports: the documents it forgot are simply never candidates. So the corpora below are
 * self-similar on purpose — the same field name at four depths, inside its own subtree — because a
 * walk that keeps only the *first* match, or only the *deepest*, passes every flat fixture.
 */
class DescendantWalkTest {

    private val selfSimilar = """
        {"@type":"Root","reward":{"@type":"A","reward":{"@type":"B","items":[{"@type":"C"}]}},
         "rewards":[{"@type":"D","nested":{"@type":"E"}},{"@type":"F"}],
         "tags":["x","y"],"plain":1,"empty":[],"nothing":null}
    """.trimIndent()

    private fun termsOf(path: String, document: Variant): Set<IndexTerm>? =
        Evaluator(CatalogPath.parse(path), IndexOptions.DEFAULT).terms(document)

    private fun strings(vararg values: String): Set<IndexTerm> = values.map(IndexTerm::ofString).toSet()

    /**
     * The expectations here are written out by hand rather than derived, because on this fixture that
     * *is* the second implementation: a reader can check each set against the document above by eye,
     * which is not true of anything a program produced.
     */
    @Test
    fun `every descendant spelling finds what the document actually holds`() {
        val document = Variant.fromJson(selfSimilar)

        assertEquals(
            strings("Root", "A", "B", "C", "D", "E", "F"),
            termsOf("""$..["@type"]""", document),
            "one term per tagged node, wherever it sits — the query the whole step exists for",
        )
        assertEquals(
            strings("A", "B"),
            termsOf("""$..reward["@type"]""", document),
            "a descendant followed by two child steps: the two `reward` objects and no others",
        )
        assertEquals(
            strings("D", "F"),
            termsOf("""$..rewards[*]["@type"]""", document),
            "the elements of a `rewards` array anywhere, and not what is nested inside them",
        )
        assertEquals(
            strings("D", "E", "F"),
            termsOf("""$.rewards[*]..["@type"]""", document),
            "a descendant that starts part way down: everything at or under an element",
        )
        assertEquals(strings("x", "y"), termsOf("$..[*]", document), "array elements at any depth")

        // A path arriving at a container reports nothing, exactly as `$.items` does: this walk
        // reaches its sink for scalars, and a descendant does not change what a value is.
        assertNull(termsOf("$..reward", document), "an object is not a term")
        assertNull(termsOf("$..absent", document), "a field nothing has")
        assertNull(termsOf("$..nested..absent", document), "two descendants and still nothing there")
        assertEquals(1, termsOf("$..plain", document)?.size, "a number is a term like any other")
    }

    @Test
    fun `the terms at a descendant path are the ones the model says, on documents nobody shaped`() {
        var found = 0
        forAll(JsonGens.document()) { generated ->
            val document = Variant.fromJson(generated.toJsonString())
            for (path in listOf("$..id", "$..data.id", "$..[*]", "$.data..id", "$..name")) {
                val expected = expectedTerms(generated, CatalogPath.parse(path))
                assertEquals(expected, termsOf(path, document), "$path over ${generated.toJsonString()}")
                found += expected?.size ?: 0
            }
        }
        assertTrue(found > 0, "no term was compared; the property proved nothing")
    }

    /**
     * A descendant is **not** a prefix scan: `$..a` is not `$.a` widened, and the two overlap.
     *
     * The specific confusion this rules out is that a descendant might have been implemented as
     * "match the tail anywhere below", which drops the zero-level case and would answer `$..a` over
     * `{"a":1}` with nothing at all. RFC 9535 is explicit that the root's own member is included.
     */
    @Test
    fun `zero levels counts, which is what makes a descendant contain the plain path`() {
        val document = Variant.fromJson("""{"a":"top","b":{"a":"under"}}""")
        assertEquals(setOf(IndexTerm.ofString("top")), termsOf("$.a", document))
        assertEquals(
            setOf(IndexTerm.ofString("top"), IndexTerm.ofString("under")),
            termsOf("$..a", document),
            "the root's own member and the one below it",
        )
    }

    /**
     * The element walk and the reader's expander agree **exactly** over `$..`, which is the shape an
     * `elemMatch` over a subtree of unknown depth is written as.
     *
     * `NodeExpansionDifferentialTest` can only assert a superset for a *writer's* walk, because that
     * one is bounded and reaches its sink for scalars alone. Neither is true here: `reading` carries
     * no budget and `ElementExtractor` reports containers, so anything short of equality would be
     * leaving the automaton's descent untested at exactly the path where it never prunes.
     */
    @Test
    fun `the element walk over a bare descendant reports every node, exactly as the expander does`() {
        val document = Variant.fromJson(selfSimilar)
        val path = CatalogPath.parse("$..")

        val walked = ArrayList<String>()
        ElementExtractor.reading(listOf(path)).extract(document) { _, element ->
            walked += element.variantJson()
        }

        assertEquals(path.nodesIn(document).map { it.value.variantJson() }, walked)
        assertTrue(walked.size > 20, "the fixture must have enough nodes to be worth comparing: ${walked.size}")
    }
}
