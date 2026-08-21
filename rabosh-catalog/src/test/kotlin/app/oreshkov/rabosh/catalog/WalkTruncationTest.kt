package app.oreshkov.rabosh.catalog

import app.oreshkov.rabosh.core.DocumentStore
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * A bound that truncates says so.
 *
 * `maxPaths` has counted what it dropped since the format was written; `maxChildren` and `maxDepth`
 * did not, and a model built from the first *n* children of a container was indistinguishable from a
 * model built from all of them. That is the half of this the catalog owns: an understated count is a
 * recommendation that ranks low, which is a different — and much smaller — failure than the index
 * half, where the same bound was costing documents. The index half is pinned in
 * `rabosh-query`'s `WalkTruncationTest`, which is where an answer can be compared.
 *
 * **What is asserted here is a count, and the count has to be exact.** A test that only asked
 * "something was reported" would pass for a report that fired on every container, which would make
 * the signal worthless in exactly the corpus it exists for. So the fixtures name how many children
 * went unvisited and where.
 */
class WalkTruncationTest {

    /** Above the bound the fixtures set, and a literal so the assertions can do arithmetic on it. */
    private val wide = 20

    private fun wideArray(elements: Int): String =
        (0 until elements).joinToString(prefix = """{"tags":[""", postfix = "]}") { """"tag-$it"""" }

    @Test
    fun `an array wider than the bound is counted, with what it skipped and where`() {
        val options = CatalogOptions(maxChildren = 8)
        val builder = SegmentSketchBuilder(options)
        builder.add(jsonDocument(wideArray(wide)))

        val truncation = assertNotNull(builder.truncation(), "a container of $wide under maxChildren=8 must be reported")
        assertEquals(1, truncation.containers, "one container was cut, not the document")
        assertEquals((wide - 8).toLong(), truncation.skippedChildren, "twelve elements were never visited")
        assertEquals(CatalogPath.parse("$.tags"), truncation.example, "the path of the container, not of its values")
    }

    /**
     * The presence case's opposite, and it is not optional: an assertion that truncation is reported
     * is satisfied by a reporter that fires always, which would be worse than the silence it replaced.
     */
    @Test
    fun `a container the bound does not reach reports nothing`() {
        val builder = SegmentSketchBuilder(CatalogOptions(maxChildren = wide))
        builder.add(jsonDocument(wideArray(wide)))
        assertNull(builder.truncation(), "exactly at the bound is not past it")
    }

    /** The other budget, counted the same way: the container was seen, its children were not. */
    @Test
    fun `the depth bound is counted as the children it never reached`() {
        val builder = SegmentSketchBuilder(CatalogOptions(maxDepth = 2))
        builder.add(jsonDocument("""{"a":{"b":{"c":1,"d":2}}}"""))

        val truncation = assertNotNull(builder.truncation(), "the walk stopped inside a document that continues")
        assertEquals(2, truncation.skippedChildren, "`c` and `d` were never visited")
        assertEquals(CatalogPath.parse("$.a.b"), truncation.example)
    }

    /** Every container, and every document: a per-segment report of what the whole pass cost. */
    @Test
    fun `the counts are over the segment and not over one document`() {
        val builder = SegmentSketchBuilder(CatalogOptions(maxChildren = 4))
        repeat(3) { builder.add(jsonDocument(wideArray(10))) }

        val truncation = assertNotNull(builder.truncation())
        assertEquals(3, truncation.containers, "one array per document")
        assertEquals(3L * (10 - 4), truncation.skippedChildren)
    }

    /**
     * The report reaches a caller, and the model is still written.
     *
     * Dropping the sketch would be the index half's answer — *not covered rather than partly
     * covered* — and it is the wrong one here. A partial model is a usable model: it names the paths
     * it saw and understates their counts, which moves a recommendation rather than deleting a
     * document. So the sidecar is written, the segment is covered, and the shortfall is a fact in
     * `problems` beside it.
     */
    @Test
    fun `a truncated segment is reported, covered, and modelled`(@TempDir root: Path) {
        val directory = scratch(root, "truncation")
        val catalog = SchemaCatalog(directory, CatalogOptions(maxChildren = 8))
        DocumentStore.open(directory, catalogStoreOptions(catalog)).use { store ->
            catalog.attach(store)
            store.put(keyFor(0), jsonDocument(wideArray(wide)))
            store.flush()

            val reported = catalog.problems.filterIsInstance<TruncatedWalkException>()
            assertEquals(1, reported.size, "one segment, one report: ${catalog.problems}")
            assertEquals((wide - 8).toLong(), reported.single().skippedChildren)
            assertEquals(CatalogPath.parse("$.tags"), reported.single().example)
            assertTrue(
                reported.single().message!!.contains("$.tags"),
                "the message points at the caller's own data: ${reported.single().message}",
            )

            val schema = catalog.inferSchema()
            assertTrue(schema.coverage.isComplete, "a partial model is still a model, and still covers")
            val tags = schema.fields.single { it.path == CatalogPath.parse("$.tags[*]") }
            assertEquals(8L, tags.observations, "understated on purpose: the walk saw eight")
        }

    }

    @Test
    fun `a segment the bound does not reach reports nothing at all`(@TempDir root: Path) {
        val directory = scratch(root, "truncation")
        val catalog = SchemaCatalog(directory, CatalogOptions(maxChildren = wide))
        DocumentStore.open(directory, catalogStoreOptions(catalog)).use { store ->
            catalog.attach(store)
            store.put(keyFor(0), jsonDocument(wideArray(wide)))
            store.flush()
            assertTrue(catalog.problems.isEmpty(), "nothing fired: ${catalog.problems}")
        }

    }
}
