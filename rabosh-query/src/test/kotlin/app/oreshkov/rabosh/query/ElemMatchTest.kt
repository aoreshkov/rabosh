package app.oreshkov.rabosh.query

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
 * **`elemMatch` is the correlated question, and a composite index answers it exactly.**
 *
 * The counterpart to `CorrelationSemanticsTest`, over the same two documents, and it exists for the
 * mirror-image reason. That suite pins that a *conjunction* stays uncorrelated, because a refactor
 * making it correlated would look like an improvement and change answers; this one pins that
 * `elemMatch` **is** correlated, and that adding an index for it does not move the answer by a single
 * key.
 *
 * The measurement behind the feature is in `CorrelationCost`: on corpora whose element fields vary
 * independently the uncorrelated conjunction returns 5-6x the documents a caller keeps, and on
 * corpora whose fields move together it returns exactly the right ones. That is why the index is
 * asked for by name rather than recommended, and it is why the last test here matters as much as the
 * first — a plan that cannot spell the query has to fall back rather than answer badly.
 */
class ElemMatchTest {

    @Test
    fun `only the document with one element satisfying both matches`(@TempDir root: Path) {
        withCorpus(root) { _, engine, snapshot ->
            assertEquals(
                listOf(TOGETHER_KEY),
                engine.keys(Query.where(CORRELATED), snapshot),
                "the split document satisfies each leaf from a different element, which is not this question",
            )
            assertEquals(
                listOf(SPLIT_KEY, TOGETHER_KEY),
                engine.keys(Query.where(UNCORRELATED), snapshot),
                "and the conjunction still means what it has always meant",
            )
        }
    }

    /**
     * The negation is the document's, exactly as it is for every other leaf.
     *
     * `not(elemMatch(…))` holds for a document where **no** element satisfies the operand — which
     * includes the split document, a document whose `items` is a string, and one with no `items` at
     * all. It is emphatically not "some element fails it", and the third fixture is what tells the two
     * readings apart: a document with one matching element and one failing element satisfies the
     * second and not the first.
     */
    @Test
    fun `a negated elemMatch is the document-level complement`(@TempDir root: Path) {
        withCorpus(root) { _, engine, snapshot ->
            assertEquals(
                listOf(ABSENT_KEY, SCALAR_KEY, SPLIT_KEY),
                engine.keys(Query.where(not(CORRELATED)), snapshot),
                "no element satisfying it — including the documents with no elements to try",
            )
        }
    }

    /**
     * **An index changes the speed and not the answer**, and it decides this one outright.
     *
     * The composite term is the whole tuple stored whole, so an ordinal in the posting list *is* a
     * document with a satisfying element rather than a candidate for one. That is what lets
     * `documentsRead` reach zero — and the assertion never stands alone: the answer is compared
     * against the un-indexed one in the same test, so a plan that read nothing because it returned
     * nothing fails.
     */
    @Test
    fun `a composite index answers it without opening a document`(@TempDir root: Path) {
        val directory = scratch(root)
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, queryStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                val engine = QueryEngine(store, catalog)
                load(store)

                val query = Query.where(CORRELATED)
                val before = store.snapshot().use { engine.keys(query, it) }

                catalog.createIndex(store, IndexDefinition.composite("$.items[*]", "$.sku", "$.qty"))

                store.snapshot().use { snapshot ->
                    assertEquals(0, engine.explain(query, snapshot).segmentsScanned, "the index must cover this")
                    engine.execute(query, snapshot).use { cursor ->
                        val keys = ArrayList<Key>()
                        while (cursor.next()) keys.add(cursor.key)
                        assertEquals(before, keys, "an index may change the speed and never the answer")
                        assertTrue(keys.isNotEmpty(), "the fixture must match something")
                        assertEquals(
                            0,
                            cursor.stats.documentsRead,
                            "a composite term is exact, so the plan decides without a recheck",
                        )
                        assertEquals(0, cursor.stats.segmentsScanned)
                    }
                }
            }
        }
    }

    /**
     * Everything the composite index cannot spell falls back, and still answers correctly.
     *
     * Four shapes that must **not** be answered from the tuple — a range inside the element, a
     * conjunct over a field the index does not declare, a subset of the declared fields, and a
     * disjunction — each checked against the answer the same store gives with no index at all. A
     * planner that reached for the term dictionary for any of them would return a subset of the
     * answer with nothing anywhere reporting a problem, which is the failure this whole layer's
     * kind-matching rules exist to prevent.
     */
    @Test
    fun `a query the tuple cannot spell falls back to the walk`(@TempDir root: Path) {
        val directory = scratch(root)
        val unspellable = mapOf(
            "a range inside the element" to elemMatch("$.items[*]", path("$.qty") gt 3L),
            "a field the index does not declare" to elemMatch(
                "$.items[*]",
                and(path("$.sku") eq "A", path("$.note") eq "x"),
            ),
            "only some of the declared fields" to elemMatch("$.items[*]", path("$.sku") eq "A"),
            "a disjunction inside the element" to elemMatch(
                "$.items[*]",
                or(path("$.sku") eq "A", path("$.qty") eq 5L),
            ),
            "a negated element node" to not(CORRELATED),
        )

        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, queryStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                val engine = QueryEngine(store, catalog)
                load(store)

                val expected = store.snapshot().use { snapshot ->
                    unspellable.mapValues { (_, predicate) -> engine.keys(Query.where(predicate), snapshot) }
                }
                catalog.createIndex(store, IndexDefinition.composite("$.items[*]", "$.sku", "$.qty"))

                store.snapshot().use { snapshot ->
                    for ((name, predicate) in unspellable) {
                        assertEquals(
                            expected.getValue(name),
                            engine.keys(Query.where(predicate), snapshot),
                            "$name: the index must not change this answer",
                        )
                    }
                    // And the one it *can* spell is still answered from the index, so the fallbacks
                    // above are not passing because the index was never usable.
                    assertEquals(0, engine.explain(Query.where(CORRELATED), snapshot).segmentsScanned)
                }
            }
        }
    }

    /**
     * **What a composite index cannot spell, ordinary indexes still narrow — and a single leaf is
     * exact.**
     *
     * The two identities this rests on, and they are not the same identity:
     *
     * - `elemMatch(p, L)` for one leaf **is** `leaf(p + r, L)`. There is nothing to correlate, so this
     *   is an equality and the plan decides it — `documentsRead == 0`, from an ordinary inverted index
     *   nobody built for this purpose.
     * - `elemMatch(p, A and B)` is only a **superset** of `leaf(p+ra, A) and leaf(p+rb, B)` — that gap
     *   is the correlation gap itself — so it narrows and the element walk decides. Fewer documents
     *   opened than a scan, and not zero.
     *
     * Both assertions sit beside the answer, because a plan that read nothing by returning nothing
     * would satisfy either on its own.
     */
    @Test
    fun `ordinary indexes narrow an elemMatch a tuple cannot spell`(@TempDir root: Path) {
        val directory = scratch(root)
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, queryStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                val engine = QueryEngine(store, catalog)
                load(store)

                val single = Query.where(elemMatch("$.items[*]", path("$.sku") eq "A"))
                val ranged = Query.where(
                    elemMatch("$.items[*]", and(path("$.sku") eq "A", path("$.qty") ge 5L)),
                )
                val expected = store.snapshot().use { snapshot ->
                    listOf(engine.keys(single, snapshot), engine.keys(ranged, snapshot))
                }

                // Nothing composite here: two ordinary indexes over the concatenated paths, which is
                // what a caller filtering on those fields would already have.
                catalog.createIndex(store, IndexDefinition.inverted("$.items[*].sku"))
                catalog.createIndex(store, IndexDefinition.column("$.items[*].qty"))

                store.snapshot().use { snapshot ->
                    engine.execute(single, snapshot).use { cursor ->
                        val keys = ArrayList<Key>()
                        while (cursor.next()) keys.add(cursor.key)
                        assertEquals(expected[0], keys, "an index may change the speed and never the answer")
                        assertTrue(keys.isNotEmpty(), "the fixture must match something")
                        assertEquals(
                            0,
                            cursor.stats.documentsRead,
                            "one leaf inside an elemMatch is not correlated, so the index decides it",
                        )
                    }

                    engine.execute(ranged, snapshot).use { cursor ->
                        val keys = ArrayList<Key>()
                        while (cursor.next()) keys.add(cursor.key)
                        assertEquals(expected[1], keys, "and the correlated answer is still the right one")
                        assertEquals(0, cursor.stats.segmentsScanned, "the decomposition must narrow, not scan")
                        assertTrue(
                            cursor.stats.documentsRead in 1..3,
                            "a conjunction decomposed this way narrows without deciding, so it opens " +
                                "fewer than the four documents a scan would and more than none — was " +
                                "${cursor.stats.documentsRead}",
                        )
                    }
                }
            }
        }
    }

    /** The declared fields survive a reopen, because a definition that lost them is a different index. */
    @Test
    fun `a composite definition round-trips through the registry`(@TempDir root: Path) {
        val directory = scratch(root)
        val definition = IndexDefinition.composite("$.items[*]", "$.sku", "$.qty")

        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, queryStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                load(store)
                catalog.createIndex(store, definition)
            }
        }

        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, queryStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                val reopened = catalog.indexes().single()
                assertEquals(definition, reopened.definition, "the fields are part of what the index is")
                assertEquals(
                    listOf(TOGETHER_KEY),
                    store.snapshot().use { QueryEngine(store, catalog).keys(Query.where(CORRELATED), it) },
                    "and the reopened index answers the same question",
                )
            }
        }
    }

    private fun withCorpus(root: Path, body: (DocumentStore, QueryEngine, app.oreshkov.rabosh.core.Snapshot) -> Unit) {
        val directory = scratch(root)
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, queryStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                load(store)
                store.snapshot().use { snapshot -> body(store, QueryEngine(store, catalog), snapshot) }
            }
        }
    }

    private fun load(store: DocumentStore) {
        store.put(SPLIT_KEY, jsonDocument(SPLIT))
        store.put(TOGETHER_KEY, jsonDocument(TOGETHER))
        store.put(SCALAR_KEY, jsonDocument(SCALAR))
        store.put(ABSENT_KEY, jsonDocument(ABSENT))
        store.flush()
    }

    private companion object {
        val SPLIT_KEY = Key.of("doc:split")
        val TOGETHER_KEY = Key.of("doc:together")
        val SCALAR_KEY = Key.of("doc:scalar")
        val ABSENT_KEY = Key.of("doc:absent")

        /** The two leaves are satisfied by *different* elements. */
        const val SPLIT = """{"items":[{"sku":"A","qty":1},{"sku":"B","qty":5}]}"""

        /** One element satisfies both — and a second element satisfies neither, deliberately. */
        const val TOGETHER = """{"items":[{"sku":"A","qty":5},{"sku":"B","qty":1}]}"""

        /** `items` is not an array at all: nothing to walk, and not an error. */
        const val SCALAR = """{"items":"not-an-array"}"""

        /** No `items`. The document a negation must include and an existential must not. */
        const val ABSENT = """{"other":1}"""

        val CORRELATED = elemMatch("$.items[*]", and(path("$.sku") eq "A", path("$.qty") eq 5L))
        val UNCORRELATED = and(path("$.items[*].sku") eq "A", path("$.items[*].qty") eq 5L)
    }
}
