package app.oreshkov.rabosh.query

import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.index.IndexCatalog
import app.oreshkov.rabosh.index.IndexDefinition
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * The half of a plan that reads documents, and the reason phase 8 asked `rabosh-core` for a seam.
 *
 * Before it, a store with *any* uncovered segment scanned everything — correct and blunt. What the
 * partial scan buys is that "usable while it is still building, with no cutover" becomes a property
 * of the *work* rather than only of the answer: an index covering three segments of four does
 * three-quarters of the job, and the counters here are what say so.
 */
class QueryPartialScanTest {

    private fun document(index: Int) = jsonDocument("""{"team":"team-${index % 5}","score":$index}""")

    @Test
    fun `an uncovered segment is scanned and the rest is answered from sidecars`(@TempDir root: Path) {
        val directory = scratch(root, "partial")

        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, queryStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                for (round in 0 until 3) {
                    store.load((round * 100 until round * 100 + 100).map(::document), round * 100)
                }
                catalog.createIndex(store, IndexDefinition.inverted("$.team"))
            }
        }

        // A fourth segment written with no catalog attached: three covered, one not.
        DocumentStore.open(directory, queryStoreOptions(null)).use { store ->
            store.load((300 until 400).map(::document), 300)
        }

        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, queryStoreOptions(catalog)).use { store ->
                catalog.attach(store, backfill = false)
                val engine = QueryEngine(store, catalog)

                store.snapshot().use { snapshot ->
                    assertEquals(4, snapshot.segmentNumbers.size, "the fixture should hold four segments")
                    val stats = assertMatchesScan(
                        engine,
                        store,
                        snapshot,
                        Query.where(path("$.team") eq "team-2"),
                        "three covered, one not",
                    )
                    assertEquals(1, stats.segmentsScanned, "only the uncovered segment is read")
                    assertEquals(3, stats.segmentsIndexed, "the rest is answered from sidecars")
                    assertTrue(stats.rowsReturned > 0)
                    // One segment's documents, plus a recheck for each candidate the index found.
                    // The recheck is not optional here and the reason is worth pinning: no reader
                    // covers the whole snapshot, so none of them can say a key is absent from the
                    // segment it does not cover — and a newer version there would change the answer.
                    // What the seam buys is the first number, which used to be the whole store.
                    assertTrue(
                        stats.documentsRead in 100..(100 + stats.rowsReturned),
                        "one segment scanned plus a recheck per indexed candidate: ${stats.documentsRead}",
                    )
                    assertTrue(stats.documentsRead < 400, "and not the whole store, which is the point")
                }

                // Once the build catches up, nothing is scanned and nothing is read.
                catalog.attach(store)
                store.snapshot().use { snapshot ->
                    val stats = assertMatchesScan(
                        engine,
                        store,
                        snapshot,
                        Query.where(path("$.team") eq "team-2"),
                        "fully covered",
                    )
                    assertEquals(0, stats.segmentsScanned)
                    assertEquals(0, stats.documentsRead)
                }
            }
        }
    }

    /** Unflushed writes are merged in and reported — no sidecar can ever cover a memtable. */
    @Test
    fun `a memtable is merged into a fully covered plan`(@TempDir root: Path) {
        val directory = scratch(root, "partial")
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, queryStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                store.load((0 until 200).map(::document))
                catalog.createIndex(store, IndexDefinition.inverted("$.team"))
                val engine = QueryEngine(store, catalog)
                val query = Query.where(path("$.team") eq "team-2")

                store.snapshot().use { flushed ->
                    val before = assertMatchesScan(engine, store, flushed, query, "before the unflushed writes")
                    assertEquals(0, before.segmentsScanned)
                    assertEquals(0, before.documentsRead)
                    assertTrue(!before.scannedUnflushed)
                }

                for (index in 200 until 240) store.put(keyFor(index), document(index))

                store.snapshot().use { unflushed ->
                    val after = assertMatchesScan(engine, store, unflushed, query, "with unflushed writes")
                    assertTrue(after.scannedUnflushed, "a memtable must be merged, and said to be")
                    assertEquals(0, after.segmentsScanned, "the segments are still all covered")
                    assertTrue(after.rowsReturned > 0)
                }
            }
        }
    }
}
