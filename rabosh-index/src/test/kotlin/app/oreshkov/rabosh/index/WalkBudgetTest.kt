package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.catalog.CatalogPath
import app.oreshkov.rabosh.variant.Variant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two walks, side by side: what the writer's budget cuts, and what the reader's absence of one
 * does not.
 *
 * `WalkTruncationTest` in `rabosh-query` asserts what this costs a caller — coverage, never an
 * answer. This one asserts the mechanism underneath it, and specifically the **attribution**, which
 * is the part that decides whether the escape is affordable. Reporting every candidate at every
 * truncated container would be sound and would take down every index in a store the moment one
 * document held one wide array; reporting too few would put the silent shortfall back.
 */
class WalkBudgetTest {

    private val bounded = IndexOptions(maxChildren = 4)

    private fun document(json: String): Variant = Variant.fromJson(json)

    /** The paths reported truncated, as indices into the list handed to the extractor. */
    private fun truncatedFor(paths: List<String>, json: String, options: IndexOptions = bounded): List<Int> {
        val reported = ArrayList<Int>()
        TermExtractor(paths.map(CatalogPath::parse), options)
            .extract(document(json), { reported += it }) { _, _ -> }
        return reported.distinct().sorted()
    }

    private fun valuesFor(paths: List<String>, json: String, extractor: TermExtractor): List<String> {
        val values = ArrayList<String>()
        extractor.extract(document(json)) { index, value -> values += "${paths[index]}=${value.stringValue()}" }
        return values
    }

    @Test
    fun `an array cut by the bound reports the paths that go through it`() {
        val json = """{"a":["0","1","2","3","4","5"],"b":["x"]}"""
        assertEquals(listOf(0), truncatedFor(listOf("$.a[*]", "$.b[*]"), json), "only the wide one")
    }

    /**
     * A path that has arrived is not a path the bound can cost.
     *
     * `$.tags` names the array itself; this walk reports scalars, so it contributes nothing from
     * inside the container and a skipped element could not have carried a value for it. Reporting it
     * would stand down an index on the strength of a container the bound never came between.
     */
    @Test
    fun `a path that ends at the truncated container is not reported`() {
        assertEquals(
            emptyList(),
            truncatedFor(listOf("$.tags"), """{"tags":["0","1","2","3","4","5"]}"""),
            "the array is the path's destination, not its route",
        )
    }

    /**
     * An object is reported conservatively, and that asymmetry with the array case is deliberate:
     * deciding which of the candidates a skipped *field* would have matched means reading the names
     * the bound exists to avoid reading.
     */
    @Test
    fun `an object cut by the bound reports every candidate still alive at it`() {
        val json = """{"f0":{"x":"1"},"f1":{"x":"1"},"f2":{"x":"1"},"f3":{"x":"1"},"f4":{"x":"1"},"f5":{"x":"1"}}"""
        assertEquals(
            listOf(0, 1),
            truncatedFor(listOf("$.f0.x", "$.f9.x"), json),
            "both are alive at the truncated root, including the one whose field is not there",
        )
    }

    @Test
    fun `a container the bound does not reach reports nothing`() {
        assertEquals(
            emptyList(),
            truncatedFor(listOf("$.a[*]"), """{"a":["0","1","2","3"]}"""),
            "exactly at the bound is not past it",
        )
    }

    /** The depth budget, counted the same way and by the same rule about arrived paths. */
    @Test
    fun `the depth bound reports the paths that had further to go`() {
        val options = IndexOptions(maxDepth = 2)
        assertEquals(
            listOf(0),
            truncatedFor(listOf("$.a.b.c", "$.a.b"), """{"a":{"b":{"c":"1"}}}""", options),
            "`$.a.b` arrives at the object the walk stopped inside; `$.a.b.c` does not",
        )
    }

    /**
     * The reader's walk sees the whole container and reports nothing, because it has no budget to
     * report on.
     *
     * The second half is what makes the first half safe to rely on: a `reading` extractor that
     * quietly carried a default budget would put the truncation back exactly where no caller checks.
     */
    @Test
    fun `the reader's walk has no budget and no truncation to report`() {
        val paths = listOf("$.a[*]")
        val json = """{"a":["0","1","2","3","4","5"]}"""

        val bounded = valuesFor(paths, json, TermExtractor(paths.map(CatalogPath::parse), this.bounded))
        assertEquals(4, bounded.size, "the writer's walk stops at maxChildren=4")

        val reading = TermExtractor.reading(paths.map(CatalogPath::parse))
        assertEquals(6, valuesFor(paths, json, reading).size, "the reader's walk sees every element")

        val reported = ArrayList<Int>()
        reading.extract(document(json), { reported += it }) { _, _ -> }
        assertTrue(reported.isEmpty(), "and has nothing to report: $reported")
    }

    /** `ElementExtractor` is the same rule one level up, and the composite index depends on it. */
    @Test
    fun `the element walk reports and widens the same way`() {
        val json = """{"items":[{"sku":"0"},{"sku":"1"},{"sku":"2"},{"sku":"3"},{"sku":"4"}]}"""
        val paths = listOf(CatalogPath.parse("$.items[*]"))

        val reported = ArrayList<Int>()
        var bounded = 0
        ElementExtractor(paths, this.bounded).extract(document(json), { reported += it }) { _, _ -> bounded++ }
        assertEquals(4, bounded, "the writer's walk stops at maxChildren=4")
        assertEquals(listOf(0), reported.distinct(), "and says which path it stopped short of")

        var complete = 0
        ElementExtractor.reading(paths).extract(document(json)) { _, _ -> complete++ }
        assertEquals(5, complete, "the reader's walk sees every element")
    }
}
