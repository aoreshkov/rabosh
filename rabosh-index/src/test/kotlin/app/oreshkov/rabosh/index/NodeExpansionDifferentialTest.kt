package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.catalog.CatalogPath
import app.oreshkov.rabosh.catalog.catalogPathOf
import app.oreshkov.rabosh.catalog.forEachNodeIn
import app.oreshkov.rabosh.catalog.nodesIn
import app.oreshkov.rabosh.testkit.json.JsonGens
import app.oreshkov.rabosh.testkit.json.toJsonString
import app.oreshkov.rabosh.testkit.property.forAll
import app.oreshkov.rabosh.variant.Variant
import app.oreshkov.rabosh.variant.toJsonString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The expander's nodes are a superset of the extractor's terms, never a subset.**
 *
 * It lives in `rabosh-index` because this is the lowest module that can see both: the extractor is
 * here and the expander is in `rabosh-catalog`, one below.
 *
 * The direction is the content. Only one of the two failures is silent — an expander returning
 * *fewer* nodes than the index matched means a caller who narrowed by the index and then expanded
 * finds nothing, with no error anywhere; an expander returning more costs a caller a re-check it was
 * doing anyway. So this asserts containment and not equality, and the last two tests arrange the
 * documents where the containment is **strict**, because a containment that is always an equality is
 * a claim nothing has tested.
 */
class NodeExpansionDifferentialTest {

    @Test
    fun `every term the extractor emits has a node the expander reports`() {
        var total = 0
        for (options in listOf(IndexOptions.DEFAULT, WIDENED)) {
            for (corpus in corpora()) {
                total += assertSuperset(corpus.paths, corpus.document, options, corpus.name)
            }
        }
        assertTrue(total > 0, "no term was compared; the differential proved nothing")
    }

    @Test
    fun `the same holds over generated documents`() {
        var total = 0
        forAll(JsonGens.document()) { document ->
            total += assertSuperset(
                GENERATED_PATHS,
                Variant.fromJson(document.toJsonString()),
                IndexOptions.DEFAULT,
                document.toJsonString(),
            )
        }
        assertTrue(total > 0, "no term was compared; the property proved nothing")
    }

    /**
     * The gap on breadth, arranged rather than hoped for.
     *
     * Five thousand elements is above `IndexOptions.maxChildren`, so the index recorded a term for
     * the first 1024 of them and the expander must still report all five thousand. An expander that
     * inherited *any* child budget fails here, which is what makes the superset direction a tested
     * claim rather than a stated one — the containment above would still hold if both walks
     * truncated alike.
     */
    @Test
    fun `the extractor stops at maxChildren and the expander does not`() {
        val path = CatalogPath.parse("$.items[*].sku")
        val elements = (0 until WIDE).joinToString(",") { """{"sku":"s$it"}""" }
        val document = Variant.fromJson("""{"items":[$elements]}""")

        val terms = countTerms(listOf(path), document, IndexOptions.DEFAULT)
        val nodes = path.nodesIn(document).size

        assertEquals(IndexOptions.DEFAULT.maxChildren, terms, "the extractor should stop at its budget")
        assertEquals(WIDE, nodes, "the expander should report every element")
        assertTrue(nodes > terms, "the fixture must make the containment strict")
    }

    /** The same gap on depth, which is the other bound the writers' walk carries. */
    @Test
    fun `the extractor stops at maxDepth and the expander does not`() {
        var json = """{"leaf":1}"""
        repeat(DEEP - 1) { json = """{"a":$json}""" }
        val document = Variant.fromJson(json)
        val path = catalogPathOf(*(List(DEEP - 1) { "a" } + "leaf").toTypedArray())

        assertTrue(DEEP > IndexOptions.DEFAULT.maxDepth, "the fixture must be deeper than the budget")
        assertEquals(0, countTerms(listOf(path), document, IndexOptions.DEFAULT))
        assertEquals(1, countTerms(listOf(path), document, WIDENED))
        assertEquals(1, path.nodesIn(document).size, "the expander has no depth budget")
    }

    /**
     * Every term, as JSON, must be found among the values the expander reports at the same path —
     * and *removed*, so that a path firing twice needs two nodes rather than one node twice.
     *
     * @return how many terms were compared, so a caller can refuse a vacuous pass.
     */
    private fun assertSuperset(
        paths: List<CatalogPath>,
        document: Variant,
        options: IndexOptions,
        hint: String,
    ): Int {
        val available = paths.map { path ->
            val counts = HashMap<String, Int>()
            path.forEachNodeIn(document) { node -> counts.merge(node.value.toJsonString(), 1, Int::plus) }
            counts
        }

        var terms = 0
        TermExtractor(paths, options).extract(document) { pathIndex, value ->
            val json = value.toJsonString()
            val remaining = available[pathIndex][json] ?: 0
            assertTrue(
                remaining > 0,
                "$hint: ${paths[pathIndex]} indexed $json, which the expander does not report there",
            )
            available[pathIndex][json] = remaining - 1
            terms++
        }
        return terms
    }

    private fun countTerms(paths: List<CatalogPath>, document: Variant, options: IndexOptions): Int {
        var terms = 0
        TermExtractor(paths, options).extract(document) { _, _ -> terms++ }
        return terms
    }

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
                CatalogPath.parse("$.items[*].qty"),
                CatalogPath.parse("$.meta.nested.deep"),
                CatalogPath.parse("$.nothing"),
                CatalogPath.parse("$.absent[*].deeper"),
            ),
        ),
        // A path whose steps do not apply, in each of the ways they can fail to.
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
    )

    private companion object {
        const val WIDE = 5000
        const val DEEP = 40

        /** Wide enough that any budget an expander might inherit is below it. */
        val WIDENED = IndexOptions(maxDepth = 64, maxChildren = 8192)

        /**
         * Paths over the field names [JsonGens] draws from, so a generated document hits them often
         * enough for the property to be about something.
         */
        val GENERATED_PATHS = listOf(
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
