package app.oreshkov.rabosh.query

import app.oreshkov.rabosh.catalog.CatalogPath
import app.oreshkov.rabosh.catalog.nodesIn
import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.core.Key
import app.oreshkov.rabosh.index.IndexCatalog
import app.oreshkov.rabosh.index.IndexDefinition
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * **A conjunction over the same `[*]` is not correlated**, and the engine has that semantics on
 * purpose.
 *
 * `DocumentMatcher` settles each leaf from *any* value at its own path and then conjoins the per-leaf
 * answers, so `and($.items[*].sku eq "A", $.items[*].qty eq 5)` holds for a document whose `sku`
 * matches in one element and whose `qty` matches in another. That is a defined semantics being
 * applied correctly rather than a defect — and the indexed and the unindexed answers being identical
 * is the invariant holding exactly.
 *
 * **Why it is committed rather than left to the comment that used to hold it.** A future refactor
 * that made a conjunction correlated would look like an improvement, would change answers, and
 * *neither* differential suite would catch it: both of `rabosh-query`'s oracles evaluate leaves the
 * same way, so all three would agree on the new answer. Whichever semantics the engine has, it should
 * have on purpose, which means something has to fail when it changes.
 *
 * The last test is the other half of the same story: the question "which element satisfied both" is
 * answerable, by expanding the path within the document rather than by a predicate.
 */
class CorrelationSemanticsTest {

    @Test
    fun `both documents match, before an index and after one`(@TempDir root: Path) {
        val directory = scratch(root)
        val query = Query.where(
            and(path("$.items[*].sku") eq "A", path("$.items[*].qty") eq 5L),
        )

        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, queryStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                val engine = QueryEngine(store, catalog)

                store.put(SPLIT_KEY, jsonDocument(SPLIT))
                store.put(TOGETHER_KEY, jsonDocument(TOGETHER))
                store.flush()

                store.snapshot().use { snapshot ->
                    assertEquals(
                        listOf(SPLIT_KEY, TOGETHER_KEY),
                        engine.keys(query, snapshot),
                        "each leaf is existential over its own path, independently of the other",
                    )
                    // The unfavourable half arranged: with no index this is a full scan, so the
                    // answer below cannot be the same answer arrived at the same way.
                    assertEquals(0, engine.explain(query, snapshot).segmentsIndexed)
                }

                catalog.createIndex(store, IndexDefinition.inverted("$.items[*].sku"))
                catalog.createIndex(store, IndexDefinition.inverted("$.items[*].qty"))

                store.snapshot().use { snapshot ->
                    assertEquals(
                        listOf(SPLIT_KEY, TOGETHER_KEY),
                        engine.keys(query, snapshot),
                        "an index may change the speed of this and never the answer",
                    )
                    val explain = engine.explain(query, snapshot)
                    // Without this the equality above is satisfied by the index never being used.
                    assertEquals(snapshot.segmentNumbers.size, explain.segmentsIndexed)
                    assertEquals(0, explain.segmentsScanned)
                }
            }
        }
    }

    /**
     * The question `and(...)` cannot answer, answered where it can be: inside the document.
     *
     * This is the recommendation §3a of the phase document settles on, in miniature — and note that
     * the split document has *no* element satisfying both, which is exactly the fact the predicate
     * above is unable to see.
     */
    @Test
    fun `expanding the path says which element satisfied both, and whether any did`() {
        assertEquals(emptyList(), elementsMatchingBoth(SPLIT))
        assertEquals(listOf("$['items'][0]"), elementsMatchingBoth(TOGETHER))
        // The predicate matched both documents; the expander separates them. That difference is the
        // whole reason the walk exists.
        assertTrue(elementsMatchingBoth(SPLIT).isEmpty() && elementsMatchingBoth(TOGETHER).isNotEmpty())
    }

    private fun elementsMatchingBoth(json: String): List<String> =
        CatalogPath.parse("$.items[*]").nodesIn(jsonDocument(json))
            .filter { node ->
                node.value.field("sku")?.stringValue() == "A" && node.value.field("qty")?.longValue() == 5L
            }
            .map { it.location.toNormalizedPath() }

    private companion object {
        val SPLIT_KEY: Key = Key.of("doc:split")
        val TOGETHER_KEY: Key = Key.of("doc:together")

        /** The `sku` comes from element 0 and the `qty` from element 1. */
        const val SPLIT = """{"items":[{"sku":"A","qty":1},{"sku":"B","qty":5}]}"""

        /** Both come from element 0. */
        const val TOGETHER = """{"items":[{"sku":"A","qty":5},{"sku":"B","qty":1}]}"""
    }
}
