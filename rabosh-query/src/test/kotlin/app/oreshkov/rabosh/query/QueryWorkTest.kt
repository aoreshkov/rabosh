package app.oreshkov.rabosh.query

import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.index.IndexCatalog
import app.oreshkov.rabosh.index.IndexDefinition
import java.math.BigDecimal
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * What a plan **costs**, which a result set cannot demonstrate.
 *
 * Two rules govern every test here, both from `.claude/rules/testing.md`.
 *
 * **An assertion about work never stands alone.** `documentsRead == 0` passes trivially for a query
 * that returned nothing, so every counter asserted below sits in the same test as the differential
 * equality against a full scan. Together they say something; separately neither does.
 *
 * **Block pruning is a locality property.** A column whose values are interleaved with key order
 * prunes nothing at all, however selective the predicate, because every block then holds the whole
 * range. The pruning fixture is monotone in key order deliberately, and the fixture that is not says
 * so and asserts nothing about skipping.
 */
class QueryWorkTest {

    /** Monotone in key order: `score` ascends with the key, so a range is a contiguous run. */
    private fun monotone(index: Int) = jsonDocument(
        """{"team":"team-${index % 7}","score":$index,"price":$index.50}""",
    )

    /** Cycling: the same values, interleaved with key order, so no block can be skipped. */
    private fun cycling(index: Int) = jsonDocument(
        """{"team":"team-${index % 7}","score":${index % 50},"price":${index % 50}.50}""",
    )

    /**
     * The phase's sharpest claim, with its conditions attached: a compacted, write-once, fully
     * covered store, a plan the index decides outright, and a projection that asks for no document.
     */
    @Test
    fun `a covered plan projecting keys opens no document`(@TempDir root: Path) {
        withStore(root, ::monotone, compact = true) { store, catalog, engine ->
            catalog.createIndex(store, IndexDefinition.inverted("$.team"))
            catalog.createIndex(store, IndexDefinition.column("$.score"))

            store.snapshot().use { snapshot ->
                val query = Query.where(path("$.team") eq "team-3")
                val stats = assertMatchesScan(engine, store, snapshot, query, "equality")
                assertTrue(stats.rowsReturned > 0, "the fixture must match something")
                assertEquals(0, stats.documentsRead, "the index decided this and opened nothing")
                assertEquals(0, stats.segmentsScanned)
                assertFalse(stats.scannedUnflushed)

                val range = Query.where(path("$.score").between(BigDecimal("100"), BigDecimal("200")))
                val rangeStats = assertMatchesScan(engine, store, snapshot, range, "range")
                assertTrue(rangeStats.rowsReturned > 0)
                assertEquals(0, rangeStats.documentsRead, "a column answers a range without a document")

                val both = Query.where(
                    and(path("$.team") eq "team-3", path("$.score") le 300L),
                )
                val bothStats = assertMatchesScan(engine, store, snapshot, both, "conjunction")
                assertTrue(bothStats.rowsReturned > 0)
                assertEquals(0, bothStats.documentsRead, "two indexes intersect before a document is touched")
            }
        }
    }

    /** A projection is an honest read: asking for the document means opening it, once per row. */
    @Test
    fun `projecting fields reads one document per row`(@TempDir root: Path) {
        withStore(root, ::monotone, compact = true) { store, catalog, engine ->
            catalog.createIndex(store, IndexDefinition.inverted("$.team"))

            store.snapshot().use { snapshot ->
                val query = Query.where(path("$.team") eq "team-3")
                val expected = scanKeys(store, snapshot, query.predicate)

                engine.execute(query.project("$.team", "$.score"), snapshot).use { cursor ->
                    val keys = ArrayList<app.oreshkov.rabosh.core.Key>()
                    while (cursor.next()) {
                        keys.add(cursor.key)
                        assertEquals("team-3", cursor.row["$.team"]?.stringValue())
                    }
                    assertEquals(expected, keys, "the projection changed the answer")
                    assertEquals(keys.size, cursor.stats.documentsRead, "one document per projected row")
                }
            }
        }
    }

    /** Bounds prune, and the counters say so — over a fixture where pruning is possible at all. */
    @Test
    fun `a column skips segments and blocks its bounds rule out`(@TempDir root: Path) {
        withStore(root, ::monotone, compact = false) { store, catalog, engine ->
            catalog.createIndex(store, IndexDefinition.column("$.score"))

            store.snapshot().use { snapshot ->
                assertTrue(snapshot.segmentNumbers.size > 2, "the fixture should span several segments")
                val query = Query.where(path("$.score").between(BigDecimal("10"), BigDecimal("60")))
                val stats = assertMatchesScan(engine, store, snapshot, query, "narrow range")
                assertTrue(stats.rowsReturned > 0, "the range must match something")
                assertTrue(stats.segmentsSkipped > 0, "a segment whose bound misses must be skipped whole")
                assertTrue(stats.blocksSkipped > 0, "and its blocks with it")
                assertEquals(0, stats.documentsRead)
            }
        }
    }

    /**
     * The converse, stated rather than hidden: values interleaved with key order prune nothing, so
     * this asserts only that the answer is right and that nothing was skipped for the wrong reason.
     */
    @Test
    fun `an interleaved column is still correct, and prunes nothing`(@TempDir root: Path) {
        withStore(root, ::cycling, compact = false) { store, catalog, engine ->
            catalog.createIndex(store, IndexDefinition.column("$.score"))

            store.snapshot().use { snapshot ->
                val query = Query.where(path("$.score").between(BigDecimal("10"), BigDecimal("12")))
                val stats = assertMatchesScan(engine, store, snapshot, query, "interleaved range")
                assertTrue(stats.rowsReturned > 0)
                assertEquals(0, stats.segmentsSkipped, "every segment holds the whole range here")
            }
        }
    }

    /**
     * **A range is never answered by an inverted index**, pinned as a test rather than a comment.
     *
     * Its terms are ordered for lookup — `NUMERIC || "10"` before `NUMERIC || "9"` — so there is no
     * interval of the dictionary that is an interval of values. A planner reaching for it would
     * return a subset of the answer with nothing reporting a problem.
     */
    @Test
    fun `a range over a path with only an inverted index is not answered by it`(@TempDir root: Path) {
        withStore(root, ::monotone, compact = true) { store, catalog, engine ->
            catalog.createIndex(store, IndexDefinition.inverted("$.score"))

            store.snapshot().use { snapshot ->
                val query = Query.where(path("$.score") lt 40L)
                val stats = assertMatchesScan(engine, store, snapshot, query, "range on an inverted index")
                assertTrue(stats.rowsReturned > 0)
                assertTrue(stats.indexes.isEmpty(), "no index may answer this")
                assertEquals(snapshot.segmentNumbers.size, stats.segmentsScanned, "so it is scanned")

                // The same index answers the equality it *is* for, over the same data.
                val equality = Query.where(path("$.score") eq 12L)
                val equalityStats = assertMatchesScan(engine, store, snapshot, equality, "equality")
                assertTrue(equalityStats.indexes.isNotEmpty(), "equality is exactly what it is for")
            }
        }
    }

    /**
     * Conjuncts are intersected cheapest first, read off the plan rather than off a clock.
     *
     * The cardinalities `Explain` reports are measured — it reads the sources it would use — which is
     * what makes this an assertion about the plan rather than about how fast the machine is today.
     */
    @Test
    fun `explain orders conjuncts by what they actually admit`(@TempDir root: Path) {
        withStore(root, ::monotone, compact = true) { store, catalog, engine ->
            catalog.createIndex(store, IndexDefinition.inverted("$.team"))
            catalog.createIndex(store, IndexDefinition.column("$.score"))

            store.snapshot().use { snapshot ->
                // `score < 5` admits five documents; `team = team-3` admits a seventh of the store.
                val query = Query.where(and(path("$.team") eq "team-3", path("$.score") lt 5L))
                val explain = engine.explain(query, snapshot)

                assertTrue(explain.usesIndexes, "both leaves have an index")
                assertEquals(2, explain.sources.size)
                assertTrue(
                    explain.sources.first().candidates < explain.sources.last().candidates,
                    "cheapest first: ${explain.render()}",
                )
                assertTrue(explain.sources.first().describes.contains("score"), explain.render())
                assertEquals(0, explain.segmentsScanned, explain.render())
                assertMatchesScan(engine, store, snapshot, query, "ordered conjunction")
            }
        }
    }

    private fun withStore(
        root: Path,
        document: (Int) -> app.oreshkov.rabosh.variant.Variant,
        compact: Boolean,
        body: (DocumentStore, IndexCatalog, QueryEngine) -> Unit,
    ) {
        val directory = scratch(root, "work")
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, queryStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                for (round in 0 until 4) {
                    store.load((round * 100 until round * 100 + 100).map(document), round * 100)
                }
                if (compact) store.compact()
                body(store, catalog, QueryEngine(store, catalog))
            }
        }
    }
}
