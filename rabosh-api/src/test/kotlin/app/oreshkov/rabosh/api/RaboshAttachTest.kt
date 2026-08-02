package app.oreshkov.rabosh.api

import app.oreshkov.rabosh.catalog.SchemaCatalog
import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.index.CompositeSegmentObserver
import app.oreshkov.rabosh.index.IndexCatalog
import app.oreshkov.rabosh.index.IndexDefinition
import app.oreshkov.rabosh.query.Query
import app.oreshkov.rabosh.query.path
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * The one place the facade is faster rather than merely tidier: **one backfill pass, not one per
 * layer.**
 *
 * `DocumentStore.backfill` takes a single observer and each layer decides for itself which segments
 * it still needs, so two `attach` calls walk every segment neither covers twice. The facade attaches
 * both layers *without* backfilling and then runs one pass through the composite.
 *
 * Counted, never timed. The comparison is built in the same test rather than asserted against a
 * remembered number, so the claim is "half the walks of the manual wiring" rather than "a number that
 * looked right on the day".
 *
 * The manual side is reproduced as `attach(backfill = false)` plus an explicit
 * `DocumentStore.backfill` through a counting decorator, which is *the same work* `attach` does
 * internally — the decomposition exists only because a count is wanted, and each layer's own
 * `beginSegment` still decides whether its segment is read at all.
 */
class RaboshAttachTest {

    @Test
    fun `attaching through the facade walks each segment once where manual wiring walks it twice`(
        @TempDir root: Path,
    ) {
        val documents = 400

        // Stores written with no observer at all, so nothing on disk is covered and both layers have
        // the whole store to build. That is the state "model later" is about.
        val manual = writeUnobservedStore(scratch(root, "manual"), documents)
        val facade = writeUnobservedStore(scratch(root, "facade"), documents)

        val schema = SchemaCatalog(manual)
        val indexes = IndexCatalog(manual)
        val schemaSpy = CountingObserver(schema)
        val indexSpy = CountingObserver(indexes)
        val composite = CompositeSegmentObserver(listOf(schema, indexes))
        DocumentStore.open(manual, apiStoreOptions().withSegmentObserver(composite)).use { store ->
            indexes.use {
                schema.attach(store, backfill = false)
                store.backfill(schemaSpy)
                indexes.attach(store, backfill = false)
                store.backfill(indexSpy)
            }
        }

        val facadeSpy = CountingObserver()
        Rabosh.open(
            facade,
            RaboshOptions(store = apiStoreOptions(), segmentObserver = facadeSpy),
        ).use { }

        val segments = namesEndingIn(facade, ".seg").size
        assertTrue(segments > 1, "the fixture should produce several segments, not one")
        assertEquals(
            segments,
            namesEndingIn(manual, ".seg").size,
            "the two stores must hold the same segments or the counts below compare nothing",
        )

        val manualWalks = schemaSpy.segmentsBegun.get() + indexSpy.segmentsBegun.get()
        assertEquals(
            2 * segments,
            manualWalks,
            "each layer attaches on its own, so every segment is walked once per layer",
        )
        assertEquals(
            segments,
            facadeSpy.segmentsBegun.get(),
            "the facade should walk each segment exactly once",
        )

        val manualDocuments = schemaSpy.documentsObserved.get() + indexSpy.documentsObserved.get()
        assertEquals(
            manualDocuments,
            2 * facadeSpy.documentsObserved.get(),
            "one pass over the documents against two",
        )
    }

    @Test
    fun `backfill false opens without a scan and a later attach finishes the job`(@TempDir root: Path) {
        val directory = writeUnobservedStore(scratch(root), 300)

        val spy = CountingObserver()
        Rabosh.open(
            directory,
            RaboshOptions(store = apiStoreOptions(), backfill = false, segmentObserver = spy),
        ).use { db ->
            assertEquals(0, spy.segmentsBegun.get(), "backfill = false must not scan anything")

            // Answers are already correct — an uncovered segment is scanned, which is the same
            // mechanism a half-built index uses.
            val expected = (0 until 300).filter { it % 7 == 2 }.map(::keyFor)
            assertEquals(expected, db.keys(Query.where(path("$.team") eq "team-2")))
            assertTrue(db.schema().coverage.segmentsCovered == 0, "nothing should be covered yet")

            db.attach()
            assertTrue(spy.segmentsBegun.get() > 0, "attach should have scanned")
            assertTrue(db.schema().coverage.isComplete, "attach should have completed the model")
            assertEquals(expected, db.keys(Query.where(path("$.team") eq "team-2")))
        }
    }

    @Test
    fun `a repeated attach costs nothing once everything is covered`(@TempDir root: Path) {
        val directory = writeUnobservedStore(scratch(root), 300)
        val spy = CountingObserver()
        Rabosh.open(
            directory,
            RaboshOptions(store = apiStoreOptions(), segmentObserver = spy),
        ).use { db ->
            db.createIndex(IndexDefinition.inverted("$.team"))
            val afterOpen = spy.segmentsBegun.get()
            assertTrue(afterOpen > 0)

            // The spy always opens an observation, so it is counted; what must not happen is the
            // *catalogs* rebuilding what they already have. Their sidecars are the evidence.
            val sidecars = namesEndingIn(directory, ".idx").size + namesEndingIn(directory, ".cat").size
            db.attach()
            assertEquals(
                sidecars,
                namesEndingIn(directory, ".idx").size + namesEndingIn(directory, ".cat").size,
                "a repeated attach must not change what is on disk",
            )
        }
    }

    /**
     * A store nobody was modelling: the case attaching later has to work for.
     *
     * Flushed in rounds rather than once at the end, because a flush writes one segment per memtable —
     * so a single flush would make "each segment is walked once" a statement about one segment, which
     * is not a statement about passes at all.
     */
    private fun writeUnobservedStore(directory: Path, documents: Int): Path {
        DocumentStore.open(directory, apiStoreOptions()).use { store ->
            for (index in 0 until documents) {
                store.put(keyFor(index), documentOf(index))
                if (index % 100 == 99) store.flush()
            }
            store.flush()
        }
        return directory
    }
}
