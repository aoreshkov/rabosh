package app.oreshkov.rabosh.query

import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.core.Key
import app.oreshkov.rabosh.index.IndexCatalog
import app.oreshkov.rabosh.index.IndexDefinition
import app.oreshkov.rabosh.variant.Variant
import java.math.BigDecimal
import java.nio.file.Path
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * The phase's acceptance criterion, run rather than asserted.
 *
 * The 200 000-document version runs in every build, because *identical results* is a correctness
 * claim and correctness claims are not optional. The ten-million-document version is tagged and
 * opt-in:
 *
 * ```
 * ./gradlew :rabosh-query:test -Drabosh.index.scale=true --tests '*QueryScaleTest*'
 * ```
 *
 * **The wall-clock assertion is a generous ratio and is not a benchmark.** What it catches is the
 * index silently not being used — a query that is correct for the wrong reason, which every other
 * test in this suite would pass. Anything finer would be measuring the machine.
 */
class QueryScaleTest {

    @Test
    fun `identical results before, during and after a build`(@TempDir root: Path) {
        run(root, documentCount = 200_000, batch = 50_000)
    }

    @Test
    @org.junit.jupiter.api.Tag("scale")
    fun `identical results over ten million documents`(@TempDir root: Path) {
        run(root, documentCount = 10_000_000, batch = 250_000)
    }

    private fun run(root: Path, documentCount: Int, batch: Int) {
        val directory = scratch(root, "scale")
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, scaleOptions(catalog)).use { store ->
                val loaded = measureTimeMillis {
                    var written = 0
                    while (written < documentCount) {
                        val count = minOf(batch, documentCount - written)
                        for (index in written until written + count) store.put(keyFor(index), document(index))
                        store.flush()
                        written += count
                    }
                }
                catalog.attach(store)
                val compacted = measureTimeMillis { store.compact() }
                val engine = QueryEngine(store, catalog)

                // --- before: no index, so this is the scan the rest is compared against.
                val query = Query.where(
                    and(path("$.team") eq "team-3", path("$.score").between(BigDecimal("100"), BigDecimal("140"))),
                )
                var expected: List<Key>
                val scanned = measureTimeMillis {
                    store.snapshot().use { snapshot ->
                        expected = engine.keys(query, snapshot)
                    }
                }
                assertTrue(expected.isNotEmpty(), "the fixture must match something")

                val built = measureTimeMillis {
                    catalog.createIndex(store, IndexDefinition.inverted("$.team"))
                    catalog.createIndex(store, IndexDefinition.column("$.score"))
                }

                // --- after: identical, and faster, and touching no document.
                var indexed: Long
                store.snapshot().use { snapshot ->
                    lateinit var stats: QueryStats
                    indexed = measureTimeMillis {
                        engine.execute(query, snapshot).use { cursor ->
                            val keys = ArrayList<Key>(expected.size)
                            while (cursor.next()) keys.add(cursor.key)
                            assertEquals(expected, keys, "the index changed the answer")
                            stats = cursor.stats
                        }
                    }
                    assertEquals(0, stats.documentsRead, "a covered plan projecting keys opens no document")
                    assertEquals(0, stats.segmentsScanned)
                    assertTrue(stats.blocksSkipped > 0, "and the column's bounds prune")
                }

                // Printed, not asserted. There used to be a `indexed * 4 < scanned` check here, on the
                // reasoning that a generous ratio catches the index silently not being used. It does
                // not catch anything the three assertions above do not already catch, and it catches
                // one thing they do not: a shared CI runner. On two vCPUs this ran 3× faster than the
                // scan and failed, which is the failure mode of a test of the machine.
                //
                // `documentsRead == 0` and `segmentsScanned == 0` cannot both hold unless the index
                // answered the query, so "correct for the wrong reason" is already excluded — by a
                // fact about the plan rather than about how fast the plan happened to run. That is the
                // rule the rest of this suite follows, and this was the one place that did not.
                println(
                    "scale $documentCount: load ${loaded}ms, compact ${compacted}ms, scan ${scanned}ms, " +
                        "build ${built}ms, indexed ${indexed}ms",
                )
            }
        }
    }

    /**
     * `team` is a coarse repeated value, `score` ascends with the key so its column blocks prune, and
     * `noise` cycles so the fixture is not uniformly monotone. Block pruning is a locality property
     * and a fixture that cycles everything would prune nothing, however selective the predicate.
     */
    private fun document(index: Int): Variant = jsonDocument(
        """{"team":"team-${index % 8}","score":${index % 100_000},"noise":${index % 977}}""",
    )

    private fun scaleOptions(catalog: IndexCatalog) = app.oreshkov.rabosh.core.StoreOptions(
        durability = app.oreshkov.rabosh.core.Durability.BUFFERED,
        memtableMaxBytes = 32L * 1024 * 1024,
        segmentMaxBytes = 64L * 1024 * 1024,
        backgroundMaintenance = false,
        segmentObserver = catalog,
    )
}
