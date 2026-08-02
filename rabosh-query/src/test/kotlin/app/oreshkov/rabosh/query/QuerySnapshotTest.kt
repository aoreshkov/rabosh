package app.oreshkov.rabosh.query

import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.core.Snapshot
import app.oreshkov.rabosh.index.IndexCatalog
import app.oreshkov.rabosh.index.IndexDefinition
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Queries at a snapshot the store has since moved past.
 *
 * Two different failures live here and neither is reachable by a differential test taken at the
 * current sequence, because in both cases the thing that makes the index wrong is the same thing that
 * keeps the old data alive.
 *
 * **The index records only the newest version of each key.** A segment holds an older version exactly
 * when a snapshot pinned it, so a reader below a segment's largest sequence is entitled to a version
 * the index never saw. That segment must read as *stale* and be scanned.
 *
 * **A snapshot's universe is its own pinned version, not the store's live set.** A compaction
 * installs new segments and retires the old, and the retired ones are still what this view reads
 * through. A plan that partitioned the live set instead would scan segments this snapshot cannot see
 * and skip every one it can — documents missing from an answer, with nothing anywhere failing.
 */
class QuerySnapshotTest {

    @Test
    fun `a snapshot straddling an overwrite is answered, and the index declares itself stale`(
        @TempDir root: Path,
    ) {
        val directory = scratch(root, "snapshot")
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, queryStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                val engine = QueryEngine(store, catalog)
                store.load((0 until 60).map { jsonDocument("""{"team":"team-${it % 4}"}""") })
                catalog.createIndex(store, IndexDefinition.inverted("$.team"))

                store.snapshot().use { pinned ->
                    // The overwrite the snapshot must not see, and the compaction that keeps both
                    // versions alive precisely *because* the snapshot exists.
                    for (index in 0 until 60 step 4) store.put(keyFor(index), jsonDocument("""{"team":"moved"}"""))
                    store.flush()
                    store.compact()
                    catalog.attach(store)

                    val query = Query.where(path("$.team") eq "team-0")
                    val stats = assertMatchesScan(engine, store, pinned, query, "at the pinned snapshot")
                    assertTrue(stats.rowsReturned > 0, "the old version must still be visible")

                    val stale = stats.indexes.sumOf { it.coverage.segmentsStale }
                    assertEquals(1, stale, "the index must declare itself unusable rather than be lucky")

                    // And what happens to that segment is worth pinning, because it is not what the
                    // phrase "stale segments are scanned" suggests: the compaction's output is not in
                    // this snapshot's universe at all. The view still reads the retired inputs, which
                    // the index covers soundly — every version in them is visible at this sequence.
                    // Two mechanisms, one answer, and the guard is what keeps them from overlapping.
                    assertEquals(
                        pinned.segmentNumbers.size,
                        stats.segmentsIndexed + stats.segmentsScanned,
                        "the plan partitions the snapshot's own segments",
                    )

                    // And at the current sequence the same query sees the new value.
                    store.snapshot().use { now ->
                        assertMatchesScan(engine, store, now, query, "at the current sequence")
                        assertEquals(
                            emptyList(),
                            engine.keys(Query.where(path("$.team") eq "moved"), pinned),
                            "the pinned view must not see the overwrite at all",
                        )
                        assertTrue(engine.keys(Query.where(path("$.team") eq "moved"), now).isNotEmpty())
                    }
                }
            }
        }
    }

    @Test
    fun `a snapshot straddling a delete still sees what it deleted`(@TempDir root: Path) {
        val directory = scratch(root, "snapshot")
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, queryStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                val engine = QueryEngine(store, catalog)
                store.load((0 until 60).map { jsonDocument("""{"team":"team-${it % 4}"}""") })
                catalog.createIndex(store, IndexDefinition.inverted("$.team"))

                store.snapshot().use { pinned ->
                    for (index in 0 until 60 step 4) store.delete(keyFor(index))
                    store.flush()
                    catalog.attach(store)

                    val query = Query.where(path("$.team") eq "team-0")
                    assertMatchesScan(engine, store, pinned, query, "before the delete")
                    assertTrue(engine.keys(query, pinned).isNotEmpty())

                    store.snapshot().use { now ->
                        assertMatchesScan(engine, store, now, query, "after the delete")
                        assertTrue(
                            engine.keys(query, now).size < engine.keys(query, pinned).size,
                            "the delete must be visible to the newer view",
                        )
                    }
                }
            }
        }
    }

    /**
     * The regression for the finding that shaped the phase.
     *
     * After a compaction, the segments a reader reports as uncovered are the compaction's *output* —
     * numbers this snapshot's version has never held — while the segments it actually reads through
     * are the retired inputs. Partitioning anything but [Snapshot.segmentNumbers] loses every
     * document in them.
     */
    @Test
    fun `a snapshot whose version a compaction has replaced is answered in full`(@TempDir root: Path) {
        val directory = scratch(root, "snapshot")
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, queryStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                val engine = QueryEngine(store, catalog)
                for (round in 0 until 4) {
                    store.load(
                        (round * 60 until round * 60 + 60).map { jsonDocument("""{"team":"team-${it % 5}"}""") },
                        round * 60,
                    )
                }
                catalog.createIndex(store, IndexDefinition.inverted("$.team"))

                store.snapshot().use { pinned ->
                    val universe = pinned.segmentNumbers
                    store.compact()
                    catalog.attach(store)
                    assertTrue(
                        store.liveSegmentNumbers.none { it in universe },
                        "the fixture must replace every segment the snapshot pinned",
                    )
                    assertEquals(universe, pinned.segmentNumbers, "a snapshot's universe does not move")

                    val query = Query.where(path("$.team") eq "team-2")
                    val stats = assertMatchesScan(engine, store, pinned, query, "across a compaction")
                    assertTrue(stats.rowsReturned > 0, "every document must still be found")
                    assertEquals(
                        universe.size,
                        stats.segmentsIndexed + stats.segmentsScanned,
                        "the plan must partition the snapshot's own segments and nothing else",
                    )
                }
            }
        }
    }

    /** Every snapshot ever taken stays answerable through a script of writes and compactions. */
    @Test
    fun `every snapshot in a script is still answerable at the end`(@TempDir root: Path) {
        val directory = scratch(root, "snapshot")
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, queryStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                val engine = QueryEngine(store, catalog)
                val snapshots = ArrayList<Snapshot>()
                try {
                    for (round in 0 until 5) {
                        store.load(
                            (round * 40 until round * 40 + 40).map {
                                jsonDocument("""{"team":"team-${it % 5}","score":${it % 30}}""")
                            },
                            round * 40,
                        )
                        if (round == 1) catalog.createIndex(store, IndexDefinition.inverted("$.team"))
                        if (round == 3) catalog.createIndex(store, IndexDefinition.column("$.score"))
                        snapshots.add(store.snapshot())
                        if (round % 2 == 1) store.compact()
                    }
                    catalog.attach(store)

                    for ((round, snapshot) in snapshots.withIndex()) {
                        assertMatchesScan(
                            engine,
                            store,
                            snapshot,
                            Query.where(and(path("$.team") eq "team-2", path("$.score") le 15L)),
                            "snapshot from round $round",
                        )
                    }
                } finally {
                    snapshots.forEach { it.close() }
                }
            }
        }
    }
}
