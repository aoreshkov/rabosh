package app.oreshkov.rabosh.jsonpath

import app.oreshkov.rabosh.catalog.CatalogPath
import app.oreshkov.rabosh.catalog.CatalogStep
import app.oreshkov.rabosh.catalog.catalogPathOf
import app.oreshkov.rabosh.catalog.nodesIn
import app.oreshkov.rabosh.testkit.json.JsonGens
import app.oreshkov.rabosh.testkit.json.toJsonString
import app.oreshkov.rabosh.testkit.property.forAll
import app.oreshkov.rabosh.variant.Variant
import app.oreshkov.rabosh.variant.VariantNode
import app.oreshkov.rabosh.variant.toJsonString
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The two walks agree, node for node, on the sub-language both can spell.**
 *
 * This is the instrument the §10.1 review demanded before a second expression language was allowed
 * into the repository. The objection was that a JSONPath evaluator arrives with "its own definition
 * of what a path means"; the answer is that the definition is *checkable* — every `CatalogPath` of
 * `Field` and `AnyElement` steps has an RFC 9535 rendering, and the nodelist that rendering produces
 * must be identical, in order, location for location and value for value, to
 * `CatalogPath.forEachNodeIn`'s nodes. The day the two definitions drift, the run that introduced
 * the drift says so.
 *
 * **It is an equality and not a containment**, which is what distinguishes it from
 * `NodeExpansionDifferentialTest` one module over. That one pairs a *writer's* walk against a
 * reader's and can only assert a superset, because `TermExtractor` reaches its sink for scalars
 * alone and carries budgets this walk must not inherit. Both walks here report containers and
 * neither carries a budget, so anything less than equality would be leaving something untested.
 *
 * It lives in `rabosh-jsonpath` because the module making the claim should be the one that fails
 * when it stops being true, and because the dependency has to point this way: `rabosh-catalog`
 * knowing about a query grammar is the edge `settings.gradle.kts` exists to prevent.
 */
class NodeWalkDifferentialTest {

    @Test
    fun `both walks report the same nodes over a fixed corpus`() {
        var compared = 0
        for (corpus in corpora()) {
            for (path in corpus.paths) {
                assertNull(mismatch(path, renderAsJsonPath(path), corpus.document), "${corpus.name}: $path")
                compared += path.nodesIn(corpus.document).size
            }
        }
        assertTrue(compared > 0, "no node was compared; the differential proved nothing")
    }

    @Test
    fun `both walks report the same nodes over generated documents`() {
        var compared = 0
        forAll(JsonGens.document()) { generated ->
            val document = Variant.fromJson(generated.toJsonString())
            for (path in GENERATED_PATHS) {
                assertNull(mismatch(path, renderAsJsonPath(path), document), generated.toJsonString())
                compared += path.nodesIn(document).size
            }
        }
        assertTrue(compared > 0, "no node was compared; the property proved nothing")
    }

    /**
     * The differential can fail, demonstrated rather than assumed.
     *
     * An equality between two walks is worth exactly what its comparison is worth, and a comparison
     * that always answered "same" would pass every test above while proving nothing. So each of the
     * three ways the two could disagree — a different value at the same position, a different
     * location, a different *number* of nodes — is arranged here and asserted to be caught.
     */
    @Test
    fun `the comparison catches a walk that disagrees`() {
        val document = Variant.fromJson("""{"items":[{"sku":"a"},{"sku":"b"}],"other":[{"sku":"c"}]}""")
        val path = CatalogPath.parse("$.items[*].sku")

        assertNotNull(mismatch(path, "$['other'][*]['sku']", document), "a different value went unnoticed")
        assertNotNull(mismatch(path, "$['items'][*]", document), "a different location went unnoticed")
        assertNotNull(mismatch(path, "$['items'][0]['sku']", document), "a shorter nodelist went unnoticed")
        assertNull(mismatch(path, "$['items'][*]['sku']", document), "the rendering itself must agree")
    }

    /**
     * **`CatalogPath.toString()` renders a valid JSONPath query that does not always mean the same
     * thing — and that is now checked rather than suspected.**
     *
     * Phase 20 left this as "a documentation question with a scope trap in it" and declined to answer
     * it. With a parser in the repository it stops being a question: `$.items[*]` parses under both
     * grammars, and the engine's `AnyElement` selects **array elements** while RFC 9535's `*` selects
     * **every child**, of an object as well as an array. Over a document where `items` is an object
     * the two nodelists differ, with nothing to say so.
     *
     * So the rendering is a coincidence and not an interchange spelling, and this test is the reason
     * to stop calling it one. It also names the selector that *does* mean `AnyElement` — the slice
     * `[:]`, which RFC 9535 defines over arrays alone — which is what the differential above renders
     * and why that one can be an equality.
     */
    @Test
    fun `the catalog's rendering is a valid JSONPath query with a different meaning`() {
        val path = CatalogPath.parse("$.items[*]")
        val overArray = Variant.fromJson("""{"items":[{"sku":"a"},{"sku":"b"}]}""")
        val overObject = Variant.fromJson("""{"items":{"sku":"a"}}""")

        assertTrue(path.steps.any { it is CatalogStep.AnyElement }, "the fixture must carry the step in question")
        assertNull(mismatch(path, path.toString(), overArray), "over an array the two do agree")
        assertNotNull(
            mismatch(path, path.toString(), overObject),
            "over an object the wildcard means something else, and that is the finding",
        )
        assertNull(
            mismatch(path, renderAsJsonPath(path), overObject),
            "the slice is the RFC 9535 selector that means AnyElement, over every shape",
        )
    }

    /**
     * Where the two walks differ, or `null` when they agree.
     *
     * Returned rather than asserted so that the test above can demand a *non*-null answer; an
     * assertion helper that can only fail is a helper nothing can verify.
     */
    private fun mismatch(path: CatalogPath, query: String, document: Variant): String? {
        val expander = path.nodesIn(document)
        val jsonPath = JsonPathQuery.compile(query).nodesIn(document)
        if (expander.size != jsonPath.size) {
            return "$path reports ${expander.size} nodes and '$query' reports ${jsonPath.size}"
        }
        for (index in expander.indices) {
            val left = expander[index]
            val right = jsonPath[index]
            if (left.location != right.location) {
                return "node $index is at ${left.location} and ${right.location}"
            }
            if (describe(left) != describe(right)) {
                return "node $index at ${left.location} is ${describe(left)} and ${describe(right)}"
            }
        }
        return null
    }

    private fun describe(node: VariantNode): String = JsonCanonical.of(node.value.toJsonString())

    /**
     * `$['items'][:]['sku']` — the RFC 9535 spelling of a catalog path, escaping and all.
     *
     * **This was a private helper here and is now `CatalogPath.toJsonPath`**, which is where the
     * repository's only correct interchange rendering belongs: a consumer that has to hand a shape
     * to something outside the engine cannot reach into a test source set for it.
     *
     * Promoting it does not weaken this differential, and the reason is worth stating because it
     * looks as though it should. What is compared here is two *evaluators* over a shared rendering —
     * `CatalogPathNodes` and `JsonPathQuery` — so the rendering is the input to the comparison and
     * never its answer. A wrong rendering makes the two walks disagree and fails the test, exactly
     * as it did when the string was built three lines further down. What would have hollowed the
     * differential out is rendering the query *with* the evaluator that reads it, and that is not
     * what moved.
     *
     * **`AnyElement` renders as the slice `[:]` and not as the wildcard `[*]`**, and getting that
     * backwards is what turns this differential from an equality into an approximation. A wildcard
     * selects every child of an object *and* of an array; `AnyElement` selects array elements, and so
     * does a slice — RFC 9535 defines the slice selector over arrays alone, so a slice applied to an
     * object yields nothing, which is exactly what `CatalogPathNodes` does. See the test above, which
     * is where that difference is pinned rather than merely noted.
     */
    private fun renderAsJsonPath(path: CatalogPath): String = path.toJsonPath()

    private class Corpus(val name: String, val document: Variant, val paths: List<CatalogPath>)

    private fun corpora(): List<Corpus> = listOf(
        Corpus(
            "mixed",
            Variant.fromJson(
                """
                {"team":"analytics","tags":["x","y","x"],
                 "items":[{"sku":"s1","qty":1},{"sku":"s2","qty":2},{"sku":"s1","qty":3}],
                 "meta":{"id":7,"nested":{"deep":true}},"nothing":null}
                """.trimIndent(),
            ),
            listOf(
                CatalogPath.ROOT,
                catalogPathOf("team"),
                CatalogPath.parse("$.tags[*]"),
                CatalogPath.parse("$.items"),
                CatalogPath.parse("$.items[*]"),
                CatalogPath.parse("$.items[*].sku"),
                CatalogPath.parse("$.meta.nested.deep"),
                CatalogPath.parse("$.nothing"),
                CatalogPath.parse("$.absent[*].deeper"),
            ),
        ),
        // Every way a step can fail to apply: a field step into an array, an element step into an
        // object, a step into a scalar. Both walks must answer "nothing here" rather than throwing.
        Corpus(
            "mismatched shapes",
            Variant.fromJson("""{"items":{"sku":"a"},"tags":"not-an-array","count":7}"""),
            listOf(
                CatalogPath.parse("$.items[*]"),
                CatalogPath.parse("$.tags[*]"),
                CatalogPath.parse("$.count.deeper"),
                CatalogPath.parse("$.items.sku"),
            ),
        ),
        Corpus(
            "empty containers",
            Variant.fromJson("""{"items":[],"meta":{},"nested":[[],[{}]]}"""),
            listOf(
                CatalogPath.parse("$.items[*]"),
                CatalogPath.parse("$.meta"),
                CatalogPath.parse("$.nested[*][*]"),
            ),
        ),
        // The names the two bracket grammars disagree about, which is the half a generator rarely
        // reaches. These paths are *built* rather than parsed on purpose: to the engine's own parser
        // `$["a\nb"]` is the three-character name `anb`, and to RFC 9535's it is a newline.
        Corpus(
            "awkward names",
            Variant.fromJson(AWKWARD_DOCUMENT),
            listOf(
                catalogPathOf("a'b"),
                catalogPathOf("a" + BACKSLASH + "b"),
                catalogPathOf("a\nb"),
                catalogPathOf(""),
                CatalogPath(listOf(CatalogStep.Field("日本語"), CatalogStep.AnyElement)),
                catalogPathOf("@type"),
            ),
        ),
    )

    private companion object {
        const val BACKSLASH: Char = '\\'

        val AWKWARD_DOCUMENT: String = """{"a'b":1,"a\\b":2,"a\nb":3,"":4,"日本語":[5,6],"@type":"x"}"""

        /** Over the field names [JsonGens] draws from, so a generated document hits them often. */
        val GENERATED_PATHS = listOf(
            CatalogPath.ROOT,
            catalogPathOf("id"),
            catalogPathOf("name"),
            catalogPathOf("data"),
            CatalogPath.parse("$.items[*]"),
            CatalogPath.parse("$.tags[*]"),
            CatalogPath.parse("$.data.id"),
            CatalogPath.parse("$.items[*].name"),
            CatalogPath.parse("$.meta.status"),
        )
    }
}
