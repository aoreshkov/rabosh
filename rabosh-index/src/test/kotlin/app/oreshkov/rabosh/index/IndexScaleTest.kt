package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.core.Durability
import app.oreshkov.rabosh.core.Key
import app.oreshkov.rabosh.core.StoreOptions
import app.oreshkov.rabosh.variant.Variant
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTime
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir

/**
 * The phase's acceptance criterion: **index over pre-loaded documents; identical results before,
 * during and after; faster after.**
 *
 * Two sizes, and the split is deliberate. The scaled-down run is part of every build, because
 * "identical results" is a correctness claim and correctness claims belong in the default suite. The
 * ten-million-document run is tagged `scale` and excluded from it, because a criterion worth stating
 * is not worth adding a quarter of an hour to every commit on two CI platforms.
 *
 * ```
 * ./gradlew :rabosh-index:test -Drabosh.index.scale=true --tests '*IndexScaleTest*'
 * ```
 *
 * The "faster after" assertion is a wall-clock ratio with a generous bound, and it is **not** a
 * benchmark. Phase 9 owns those. What this asserts is the thing an unmeasured performance claim
 * cannot: that the index is doing something at all, rather than falling back to a scan and being
 * quietly correct for the wrong reason.
 */
class IndexScaleTest {

    private val teams = List(64) { "team-$it" }

    /**
     * `team` cycles, so an equality index matches documents spread across every segment; `price`
     * rises with the key, so a range matches a contiguous run.
     *
     * The difference is deliberate and worth stating: **block pruning is a locality property.** A
     * column whose values are uniformly interleaved with the key order prunes nothing at all, however
     * selective the predicate, because every block then holds the whole range. Real corpora are
     * usually somewhere between — timestamps and monotone ids cluster, hashes do not — and asserting
     * a skip on interleaved data would be asserting something untrue.
     */
    private fun document(index: Int): Variant = jsonDocument(
        """{"team":"${teams[index % teams.size]}","score":${index % 1000},""" +
            """"price":${index / 100}.${"%02d".format(index % 100)},"filler":"${"x".repeat(24)}"}""",
    )

    /** Bigger segments than the other tests use; here the point is volume, not tree shape. */
    private fun scaleOptions(catalog: IndexCatalog?) = StoreOptions(
        durability = Durability.BUFFERED,
        segmentMaxBytes = 16 * 1024 * 1024,
        memtableMaxBytes = 32L * 1024 * 1024,
        backgroundMaintenance = false,
        segmentObserver = catalog,
    )

    private fun run(root: Path, documentCount: Int, batch: Int) {
        val directory = scratch(root, "scale")
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, scaleOptions(catalog)).use { store ->
                catalog.attach(store)

                // Preloaded, with no index in sight. This is the case the whole project is about: by
                // the time anybody knows which index they want, rewriting the documents is the thing
                // they cannot afford.
                var written = 0
                while (written < documentCount) {
                    repeat(minOf(batch, documentCount - written)) {
                        store.put(keyFor(written), document(written))
                        written++
                    }
                    store.flush()
                }
                store.compact()

                val term = IndexTerm.ofString(teams[3])

                // --- before: no index at all.
                val truth: List<Key>
                val scanTime = measureTime {
                    truth = store.snapshot().use { snapshot ->
                        val evaluator = Evaluator(
                            app.oreshkov.rabosh.catalog.CatalogPath.parse("$.team"),
                            IndexOptions.DEFAULT,
                        )
                        val keys = sortedSetOf<Key>()
                        store.scan(snapshot = snapshot).use { cursor ->
                            while (cursor.next()) {
                                if (evaluator.terms(cursor.document)?.contains(term) == true) keys.add(cursor.key)
                            }
                        }
                        keys.toList()
                    }
                }
                assertTrue(truth.isNotEmpty(), "the fixture must produce matches")
                assertEquals(documentCount / teams.size, truth.size, "one document in ${teams.size} matches")

                // --- during: a build over segments that are already written. No document is rewritten.
                val handle = catalog.createIndex(store, IndexDefinition.inverted("$.team"))
                assertEquals(segmentNumbers(directory).map { it to handle.id }.toSet(), postingFiles(directory))

                // --- after: identical answers, from the index.
                store.snapshot().use { snapshot ->
                    catalog.read(store, handle, snapshot).use { reader ->
                        assertTrue(reader.isAuthoritative, "everything is flushed and covered")
                        val indexed: List<Key>
                        val indexTime = measureTime { indexed = IndexQuery.keysEqualTo(store, reader, term) }
                        assertEquals(truth, indexed, "the index changed the answer")
                        assertTrue(
                            indexTime < scanTime,
                            "the index took $indexTime against a $scanTime scan; it is not being used",
                        )
                    }
                }

                // --- and the column half: a range, answered without opening a document.
                val column = catalog.createIndex(store, IndexDefinition.column("$.price"))
                store.snapshot().use { snapshot ->
                    catalog.readColumn(store, column, snapshot).use { reader ->
                        assertTrue(reader.isAuthoritative)
                        val predicate = ColumnPredicate.numericRange(
                            java.math.BigDecimal("100"),
                            java.math.BigDecimal("120"),
                        )
                        val truth: ColumnScan
                        val columnScan: ColumnScan
                        val truthTime = measureTime { truth = ColumnQuery.scanKeys(store, reader, predicate) }
                        val columnTime = measureTime { columnScan = ColumnQuery.keysMatching(store, reader, predicate) }

                        // The two claims together. Neither means much alone: a zero read count is
                        // trivial for a query that returns nothing, and equality says nothing about
                        // the work done.
                        assertEquals(truth.keys, columnScan.keys, "the column changed the answer")
                        assertTrue(truth.keys.isNotEmpty(), "the fixture must match something")
                        assertEquals(0, columnScan.documentsRead, "the column opened a document")
                        assertTrue(columnScan.blocksSkipped > 0, "no block was pruned: $columnScan")
                        assertTrue(
                            columnTime < truthTime,
                            "the column took $columnTime against a $truthTime scan; it is not being used",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `identical results before, during and after a build`(@TempDir root: Path) {
        run(root, documentCount = 200_000, batch = 50_000)
    }

    @Test
    @Tag("scale")
    fun `identical results over ten million documents`(@TempDir root: Path) {
        run(root, documentCount = 10_000_000, batch = 250_000)
    }
}
