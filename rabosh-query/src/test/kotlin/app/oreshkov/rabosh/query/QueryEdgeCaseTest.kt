package app.oreshkov.rabosh.query

import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.index.IndexCatalog
import app.oreshkov.rabosh.index.IndexDefinition
import app.oreshkov.rabosh.index.IndexOptions
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * The corners: empty stores, empty answers, bounds that cross, and the two limits an index has.
 *
 * The last two are the interesting ones. A value too long to key on and a segment whose term budget
 * overflowed both leave an index that cannot answer for something — and in both cases the query has
 * to be *right anyway*, by not using the index rather than by using it and missing documents.
 */
class QueryEdgeCaseTest {

    @Test
    fun `an empty store answers nothing rather than failing`(@TempDir root: Path) {
        val directory = scratch(root, "edge")
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, queryStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                val engine = QueryEngine(store, catalog)
                store.snapshot().use { snapshot ->
                    assertContentEquals(emptyList(), engine.keys(Query.where(path("$.a") eq 1L), snapshot))
                    assertContentEquals(emptyList(), engine.keys(Query.all(), snapshot))
                    assertContentEquals(emptyList(), engine.keys(Query.where(Predicate.False), snapshot))
                }
            }
        }
    }

    @Test
    fun `a store of nothing but tombstones answers nothing`(@TempDir root: Path) {
        withStore(root) { store, catalog, engine ->
            catalog.createIndex(store, IndexDefinition.inverted("$.team"))
            for (index in 0 until 60) store.delete(keyFor(index))
            store.flush()
            catalog.attach(store)

            store.snapshot().use { snapshot ->
                assertMatchesScan(engine, store, snapshot, Query.where(path("$.team").exists()), "all deleted")
                assertContentEquals(emptyList(), engine.keys(Query.all(), snapshot))
            }
        }
    }

    @Test
    fun `constants, empty limits and crossed bounds are answered without a plan`(@TempDir root: Path) {
        withStore(root) { store, catalog, engine ->
            store.snapshot().use { snapshot ->
                assertEquals(60, engine.keys(Query.where(Predicate.True), snapshot).size)
                assertContentEquals(emptyList(), engine.keys(Query.where(Predicate.False), snapshot))
                assertContentEquals(emptyList(), engine.keys(Query.all().limit(0), snapshot))
                assertContentEquals(
                    emptyList(),
                    engine.keys(Query.all().range(keyFor(50), keyFor(10)), snapshot),
                )
                assertFailsWith<IllegalArgumentException> { Query.all().limit(-2) }
            }
        }
    }

    @Test
    fun `a path nothing has matches nothing, and its absence matches everything`(@TempDir root: Path) {
        withStore(root) { store, catalog, engine ->
            store.snapshot().use { snapshot ->
                assertContentEquals(emptyList(), engine.keys(Query.where(path("$.nope").exists()), snapshot))
                assertEquals(60, engine.keys(Query.where(not(path("$.nope").exists())), snapshot).size)
            }
        }
    }

    /**
     * A value above `maxTermBytes` is *present* and not keyed, so an index that dropped it must not
     * be asked about it. The query falls back and is right; the bound costs space, not correctness.
     */
    @Test
    fun `a term too long for the dictionary is answered by a scan`(@TempDir root: Path) {
        val directory = scratch(root, "edge")
        val options = IndexOptions(maxTermBytes = 16)
        IndexCatalog(directory, options).use { catalog ->
            DocumentStore.open(directory, queryStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                val engine = QueryEngine(store, catalog)
                val long = "x".repeat(64)
                store.load(
                    (0 until 40).map { index ->
                        jsonDocument("""{"team":"${if (index % 5 == 0) long else "short-$index"}"}""")
                    },
                )
                catalog.createIndex(store, IndexDefinition.inverted("$.team"))

                store.snapshot().use { snapshot ->
                    val query = Query.where(path("$.team") eq long)
                    val stats = assertMatchesScan(engine, store, snapshot, query, "an unkeyable term")
                    assertEquals(8, stats.rowsReturned, "the documents are found anyway")
                    assertTrue(stats.indexes.isEmpty(), "no index may claim to answer this")

                    // A term the dictionary *can* spell still goes through the index.
                    val short = Query.where(path("$.team") eq "short-1")
                    assertTrue(assertMatchesScan(engine, store, snapshot, short, "a keyable term").indexes.isNotEmpty())
                }
            }
        }
    }

    /**
     * A segment whose term budget overflowed carries no sidecar at all, so it reads as uncovered and
     * is scanned. The same rule as a missing sidecar, reached a different way.
     */
    @Test
    fun `a segment whose index overflowed is scanned rather than half-answered`(@TempDir root: Path) {
        val directory = scratch(root, "edge")
        IndexCatalog(directory, IndexOptions(maxTermsPerSegment = 4)).use { catalog ->
            DocumentStore.open(directory, queryStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                val engine = QueryEngine(store, catalog)
                store.load((0 until 60).map { jsonDocument("""{"team":"team-$it"}""") })
                catalog.createIndex(store, IndexDefinition.inverted("$.team"))

                store.snapshot().use { snapshot ->
                    val query = Query.where(path("$.team") eq "team-7")
                    val stats = assertMatchesScan(engine, store, snapshot, query, "an overflowed index")
                    assertEquals(1, stats.rowsReturned)
                    assertEquals(snapshot.segmentNumbers.size, stats.segmentsScanned)
                }
            }
        }
    }

    @Test
    fun `a closed cursor refuses to be used again`(@TempDir root: Path) {
        withStore(root) { store, catalog, engine ->
            store.snapshot().use { snapshot ->
                val cursor = engine.execute(Query.all(), snapshot)
                assertTrue(cursor.next())
                cursor.close()
                cursor.close()
                assertFailsWith<IllegalStateException> { cursor.next() }
            }
        }
    }

    @Test
    fun `a query without a snapshot takes one and closes it with itself`(@TempDir root: Path) {
        withStore(root) { store, _, engine ->
            val before = store.stats.liveSnapshots
            engine.execute(Query.all()).use { cursor ->
                assertTrue(cursor.next())
                assertTrue(store.stats.liveSnapshots > before, "a cursor must pin a view while it reads")
            }
            assertEquals(before, store.stats.liveSnapshots, "and release it when it closes")
            assertEquals(60, engine.keys(Query.all()).size)
        }
    }

    private fun withStore(root: Path, body: (DocumentStore, IndexCatalog, QueryEngine) -> Unit) {
        val directory = scratch(root, "edge")
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, queryStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                store.load((0 until 60).map { jsonDocument("""{"team":"team-${it % 4}","score":$it}""") })
                body(store, catalog, QueryEngine(store, catalog))
            }
        }
    }
}
