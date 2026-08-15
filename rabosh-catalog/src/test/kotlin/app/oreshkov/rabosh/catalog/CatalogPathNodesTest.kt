package app.oreshkov.rabosh.catalog

import app.oreshkov.rabosh.testkit.json.JsonGens
import app.oreshkov.rabosh.testkit.json.JsonValue
import app.oreshkov.rabosh.testkit.json.toJsonString
import app.oreshkov.rabosh.testkit.property.forAll
import app.oreshkov.rabosh.variant.Variant
import app.oreshkov.rabosh.variant.VariantPath
import app.oreshkov.rabosh.variant.toJsonString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The expander: which locations a `CatalogPath` stands for inside one document.
 *
 * Two instruments, and they answer different questions. The **inverse** property says a node's
 * location resolves back to that node's value, which is the whole contract of pairing a location
 * with a value and the reason the location is a `VariantPath` rather than a string. The
 * **differential** says the set of nodes is the right set, against a second walk over the testkit's
 * JSON model — the same instrument `SketchCollectorTest` uses, for the same reason.
 */
class CatalogPathNodesTest {

    @Test
    fun `a node's location resolves to that node's value`() {
        var checked = 0
        forAll(JsonGens.document()) { document ->
            val encoded = Variant.fromJson(document.toJsonString())
            for (path in pathsIn(document)) {
                path.forEachNodeIn(encoded) { node ->
                    val selected = encoded.select(node.location)
                    assertTrue(selected != null, "$path expanded to ${node.location}, which selects nothing")
                    assertEquals(
                        node.value.toJsonString(),
                        selected.toJsonString(),
                        "$path: ${node.location} does not lead back to the value reported there",
                    )
                    checked++
                }
            }
        }
        assertTrue(checked > 0, "no node was expanded; the property proved nothing")
    }

    @Test
    fun `the expander agrees with a second implementation over the same document`() {
        forAll(JsonGens.document()) { document ->
            val encoded = Variant.fromJson(document.toJsonString())
            for (path in pathsIn(document)) {
                val expected = expectedNodes(path, document)
                val actual = path.nodesIn(encoded)

                assertEquals(
                    expected.map { it.first },
                    actual.map { it.location },
                    "$path: the locations differ, in $document",
                )
                for ((index, node) in actual.withIndex()) {
                    assertEquals(
                        Variant.fromJson(expected[index].second.toJsonString()).toJsonString(),
                        node.value.toJsonString(),
                        "$path: the value at ${node.location} differs, in $document",
                    )
                }
            }
        }
    }

    @Test
    fun `nodes come out in document order`() {
        val document = jsonDocument("""{"items":[{"sku":"a"},{"sku":"b"},{"sku":"c"}]}""")
        val nodes = CatalogPath.parse("$.items[*].sku").nodesIn(document)

        assertEquals(
            listOf("$['items'][0]['sku']", "$['items'][1]['sku']", "$['items'][2]['sku']"),
            nodes.map { it.location.toNormalizedPath() },
        )
        assertEquals(listOf("a", "b", "c"), nodes.map { it.value.stringValue() })
    }

    @Test
    fun `a container is a node, and so is the document`() {
        val document = jsonDocument("""{"items":[{"sku":"a"}]}""")

        assertEquals(listOf(VariantPath.ROOT), CatalogPath.ROOT.nodesIn(document).map { it.location })
        assertEquals(
            """{"items":[{"sku":"a"}]}""",
            CatalogPath.ROOT.nodesIn(document).single().value.toJsonString(),
        )
        assertEquals("""[{"sku":"a"}]""", catalogPathOf("items").nodesIn(document).single().value.toJsonString())
        assertEquals("""{"sku":"a"}""", CatalogPath.parse("$.items[*]").nodesIn(document).single().value.toJsonString())
    }

    /**
     * A step that does not apply is an answer, not a failure — the rule `Variant.select` already
     * follows. Each of these is one of the shapes a hand-written walk throws on: `elementCount` on a
     * non-array, `field` on a non-object, an index past the end.
     */
    @Test
    fun `a step that does not apply yields nothing rather than throwing`() {
        val document = jsonDocument("""{"items":[],"count":7,"nested":{"a":1}}""")

        assertEquals(emptyList(), CatalogPath.parse("$.items[*]").nodesIn(document).map { it.location })
        assertEquals(emptyList(), CatalogPath.parse("$.absent").nodesIn(document).map { it.location })
        assertEquals(emptyList(), CatalogPath.parse("$.count[*]").nodesIn(document).map { it.location })
        assertEquals(emptyList(), CatalogPath.parse("$.count.deeper").nodesIn(document).map { it.location })
        assertEquals(emptyList(), CatalogPath.parse("$.nested[*]").nodesIn(document).map { it.location })
        assertEquals(emptyList(), CatalogPath.parse("$.items[*].sku").nodesIn(document).map { it.location })
    }

    /**
     * The bound the writers' walks carry and this one must not.
     *
     * Arranged rather than hoped for: the fixture is above `CatalogOptions.maxChildren`, which
     * `IndexOptions.maxChildren` now shares, so an expander that inherited either budget would stop
     * early here and a caller who narrowed by the index would find nothing. The
     * differential against `TermExtractor` in `rabosh-index` is the other half of this; this is the
     * half that can be stated without leaving the module.
     */
    @Test
    fun `a wide array is expanded past every writer's budget`() {
        val elements = (0 until WIDE).joinToString(",") { """{"sku":"s$it"}""" }
        val document = jsonDocument("""{"items":[$elements]}""")
        val nodes = CatalogPath.parse("$.items[*].sku").nodesIn(document)

        assertEquals(WIDE, nodes.size)
        assertTrue(WIDE > CatalogOptions.DEFAULT.maxChildren, "the fixture must exceed the writer's budget")
        assertEquals("s${WIDE - 1}", nodes.last().value.stringValue())
        assertEquals("$['items'][${WIDE - 1}]['sku']", nodes.last().location.toNormalizedPath())
    }

    /**
     * A deep document costs a deep *path* and nothing else: the walk descends one step per frame, so
     * nesting the path does not follow is never entered.
     */
    @Test
    fun `nesting the path does not name is not walked`() {
        val deep = generateSequence("""{"leaf":1}""") { """{"a":$it}""" }.take(DEEP).last()
        val document = jsonDocument("""{"shallow":1,"deep":$deep}""")

        assertEquals(listOf(VariantPath.parse("$.shallow")), catalogPathOf("shallow").nodesIn(document).map { it.location })
    }

    @Test
    fun `nodesIn is forEachNodeIn materialised`() {
        forAll(JsonGens.document()) { document ->
            val encoded = Variant.fromJson(document.toJsonString())
            for (path in pathsIn(document)) {
                val sunk = ArrayList<VariantPath>()
                path.forEachNodeIn(encoded) { sunk += it.location }
                assertEquals(sunk, path.nodesIn(encoded).map { it.location }, "for $path")
            }
        }
    }

    /**
     * Every path the document actually contains, plus two it does not.
     *
     * Taken from [expectedObservations] rather than from a generator, so the property runs over the
     * paths this document is a witness for instead of over paths that expand to nothing almost
     * always — and the two absent ones keep the empty answer covered.
     */
    private fun pathsIn(document: JsonValue): List<CatalogPath> =
        expectedObservations(document).map { it.first }.distinct() +
            listOf(catalogPathOf("absent"), CatalogPath.parse("$.absent[*].deeper"))

    private companion object {
        /**
         * Above every writer budget in the engine, and a **literal** rather than derived from
         * `DEFAULT_MAX_CHILDREN` on purpose: derived, the assertion that this exceeds the budget
         * could not fail, and the point of it is to stop somebody raising the default without
         * looking at what the expander is being asked to prove.
         */
        const val WIDE = 70_000
        const val DEEP = 64
    }
}
