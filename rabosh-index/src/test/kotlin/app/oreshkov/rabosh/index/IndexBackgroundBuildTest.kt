package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.variant.Variant
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Index builds that run on the catalog's own thread, and what stopping one leaves behind.
 *
 * **Every assertion about scheduling sits beside a differential equality against a full scan.** That
 * is the phase-8 rule applied to a phase whose whole subject is *when* work happens: a build that can
 * be started, stopped and resumed is worth nothing if any of those can change an answer. So each test
 * here ends by comparing `IndexQuery.keysEqualTo` against `IndexQuery.scanKeys` over the same reader,
 * whatever state the build reached.
 *
 * **What is asserted is the invariant, never the schedule.** A cancel issued from the test thread may
 * land while the build is running or a moment after it finished, and no amount of arranging makes that
 * deterministic on a machine whose speed the test does not control. `IndexConcurrencyTest` takes the
 * same position for the same reason. The claims that *are* deterministic — that a terminal state is
 * reached, that cancelling a finished build does not rewrite history, that resuming reaches full
 * coverage — are asserted exactly.
 */
class IndexBackgroundBuildTest {

    /** Enough segments that a build has somewhere to be interrupted, at 8 KB a segment. */
    private val documentCount = 6_000

    private fun document(index: Int): Variant = jsonDocument(
        """{"team":"team-${index % 11}","score":${index % 31}}""",
    )

    /**
     * Writes the fixture as many small segments rather than one large one.
     *
     * A flush per round, because the granularity of a build — and therefore of stopping one — is a
     * *segment*. One `flush` at the end would produce a store the build crosses in a couple of steps,
     * which is not a store a cancellation can be observed inside.
     */
    private fun load(store: DocumentStore) {
        for (round in 0 until documentCount / ROUND) {
            for (index in 0 until ROUND) store.put(keyFor(round * ROUND + index), document(round * ROUND + index))
            store.flush()
        }
    }

    /** The differential claim: the index answers what a scan answers, in whatever state it is in. */
    private fun assertAnswersMatchAScan(store: DocumentStore, catalog: IndexCatalog, handle: IndexHandle) {
        store.snapshot().use { snapshot ->
            catalog.read(store, handle, snapshot).use { reader ->
                for (team in listOf("team-0", "team-4", "team-10", "team-absent")) {
                    val term = IndexTerm.ofString(team)
                    assertEquals(
                        IndexQuery.scanKeys(store, reader, matches = { term in it }),
                        IndexQuery.keysEqualTo(store, reader, term),
                        "$team, coverage ${reader.coverage}",
                    )
                }
            }
        }
    }

    private fun coverageOf(store: DocumentStore, catalog: IndexCatalog, handle: IndexHandle): IndexCoverage =
        store.snapshot().use { snapshot ->
            catalog.read(store, handle, snapshot).use { reader -> reader.coverage }
        }

    @Test
    fun `a background build converges and its index is usable throughout`(@TempDir root: Path) {
        val directory = scratch(root)
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, indexStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                load(store)

                val build = catalog.createIndexInBackground(store, IndexDefinition.inverted("$.team"))
                val handle = assertNotNull(build.handle, "a created index has a handle immediately")

                // Usable before the build has finished — no cutover, because the segments the index
                // does not cover yet are scanned. This is the claim the whole design rests on and it
                // is checked here while the build is genuinely still in flight, not only afterwards.
                assertAnswersMatchAScan(store, catalog, handle)

                val progress = build.await()
                assertEquals(IndexBuildState.COMPLETED, progress.state)
                assertNull(build.failure, "a completed build reported a failure")
                assertEquals(progress.segmentsTotal, progress.segmentsVisited, "every segment was visited")
                assertTrue(progress.segmentsBuilt <= progress.segmentsVisited, "built more than it visited")
                assertEquals(1.0, progress.fraction, "a completed build is not at 1.0: $progress")

                assertTrue(catalog.problems.isEmpty(), "problems: ${catalog.problems}")
                val segments = segmentNumbers(directory)
                assertEquals(segments, baseSidecarNumbers(directory))
                assertEquals(segments.map { it to handle.id }.toSet(), postingFiles(directory))
                assertTrue(coverageOf(store, catalog, handle).isComplete, "coverage after the build")
                assertAnswersMatchAScan(store, catalog, handle)
            }
        }
    }

    /**
     * Cancelling **part way through**, and then resuming by asking for the same index again.
     *
     * Both halves are exact, and reaching the first one needs the seam. A cancel issued from this
     * thread lands wherever the machine's speed puts it — on a fast one, reliably after the build has
     * already finished — so a test written that way would assert "cancelled or completed" and pass
     * for years without ever running the cancelled path. `IndexCatalog.backgroundSegmentHook` stops
     * the pass on a known segment instead, so the count below is an equality rather than a hope.
     */
    @Test
    fun `a cancelled build leaves a correct index that a later build finishes`(@TempDir root: Path) {
        val directory = scratch(root)
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, indexStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                load(store)
                val segments = segmentNumbers(directory)
                assertTrue(segments.size > GATE_AT, "the fixture has too few segments to stop inside")

                val reached = CountDownLatch(1)
                val release = CountDownLatch(1)
                val seen = AtomicInteger()
                catalog.backgroundSegmentHook = {
                    if (seen.incrementAndGet() == GATE_AT + 1) {
                        reached.countDown()
                        check(release.await(30, TimeUnit.SECONDS)) { "the test never released the build" }
                    }
                }

                val build = catalog.createIndexInBackground(store, IndexDefinition.inverted("$.team"))
                val handle = assertNotNull(build.handle)
                check(reached.await(30, TimeUnit.SECONDS)) { "the build never reached segment ${GATE_AT + 1}" }
                build.cancel()
                release.countDown()
                val progress = build.await()

                assertEquals(IndexBuildState.CANCELLED, progress.state, "$progress")
                assertEquals(GATE_AT, progress.segmentsVisited, "it stopped somewhere else: $progress")
                assertEquals(GATE_AT, progress.segmentsBuilt, "a visited segment was not built: $progress")
                assertNull(build.failure, "cancelling is not a failure")
                assertTrue(catalog.problems.isEmpty(), "problems: ${catalog.problems}")

                // Partially covered, and correct anyway — which is the whole reason cancelling needs
                // no rollback. A half-built index is a state every query already handles by scanning.
                val stopped = coverageOf(store, catalog, handle)
                assertEquals(GATE_AT, stopped.segmentsCovered, "coverage after the cancel: $stopped")
                assertTrue(stopped.segmentsUncovered > 0, "nothing was left to resume: $stopped")
                assertEquals(0, stopped.segmentsStale, "a build cannot leave a segment stale: $stopped")
                assertAnswersMatchAScan(store, catalog, handle)

                // Resumption: the same call again, and the covered segments are skipped rather than
                // rebuilt. There is no resume verb because there is no state to resume *from*.
                catalog.backgroundSegmentHook = null
                val resumed = catalog.createIndexInBackground(store, IndexDefinition.inverted("$.team")).await()
                assertEquals(IndexBuildState.COMPLETED, resumed.state)
                assertEquals(
                    segments.size - GATE_AT,
                    resumed.segmentsBuilt,
                    "a resumed build rebuilt segments the cancelled one had already covered: $resumed",
                )
                assertEquals(handle.id, catalog.indexes().single().id, "resuming defined a second index")
                assertTrue(coverageOf(store, catalog, handle).isComplete, "coverage after resuming")
                assertAnswersMatchAScan(store, catalog, handle)
                assertEquals(
                    segments.map { it to handle.id }.toSet(),
                    postingFiles(directory),
                    "a resumed build did not finish the posting files",
                )
            }
        }
    }

    /** Cancelling a build that has already finished does not rewrite what it did. */
    @Test
    fun `cancelling a finished build changes nothing`(@TempDir root: Path) {
        val directory = scratch(root)
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, indexStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                store.load(List(200) { document(it) })

                val build = catalog.createIndexInBackground(store, IndexDefinition.inverted("$.team"))
                build.await()
                assertEquals(IndexBuildState.COMPLETED, build.state)

                build.cancel()
                assertEquals(IndexBuildState.COMPLETED, build.state, "a finished build was retroactively cancelled")
            }
        }
    }

    /**
     * The non-blocking half of attaching: take what is on disk, cover the rest afterwards.
     *
     * `attach(backfill = false)` returns immediately over a store with no sidecars at all, and
     * `buildIndexesInBackground` finishes the job. Its build names no index, because covering what the
     * sidecars do not is a fact about the catalog rather than about any one of them.
     */
    @Test
    fun `an uncovered store attaches instantly and is covered in the background`(@TempDir root: Path) {
        val directory = scratch(root)

        // The index is defined and fully covered over a small store...
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, indexStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                store.load(List(200) { document(it) })
                catalog.createIndex(store, IndexDefinition.inverted("$.team"))
            }
        }
        // ...and then the store runs for a while with no catalog attached at all, which is what leaves
        // segments an index is defined over and does not cover.
        DocumentStore.open(directory, indexStoreOptions(null)).use { store ->
            for (index in 200 until documentCount) store.put(keyFor(index), document(index))
            store.flush()
        }

        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, indexStoreOptions(catalog)).use { store ->
                catalog.attach(store, backfill = false)
                val handle = catalog.indexes().single()
                val uncovered = coverageOf(store, catalog, handle)
                assertTrue(uncovered.segmentsUncovered > 0, "the fixture covered everything already")
                // Correct while incomplete, which is what makes deferring the scan a choice about when
                // to pay rather than a mode where something answers from partial data.
                assertAnswersMatchAScan(store, catalog, handle)

                val build = catalog.buildIndexesInBackground(store)
                assertNull(build.handle, "a general pass names no single index")
                assertEquals(IndexBuildState.COMPLETED, build.await().state)

                assertTrue(catalog.problems.isEmpty(), "problems: ${catalog.problems}")
                assertEquals(segmentNumbers(directory), baseSidecarNumbers(directory))
                assertTrue(coverageOf(store, catalog, handle).isComplete, "coverage after the pass")
                assertAnswersMatchAScan(store, catalog, handle)
            }
        }
    }

    /** A build submitted to a catalog that is closing is cancelled, never left running. */
    @Test
    fun `closing the catalog stops a build`(@TempDir root: Path) {
        val directory = scratch(root)
        val catalog = IndexCatalog(directory)
        DocumentStore.open(directory, indexStoreOptions(catalog)).use { store ->
            catalog.attach(store)
            load(store)

            val build = catalog.createIndexInBackground(store, IndexDefinition.inverted("$.team"))
            catalog.close()

            assertTrue(build.await(5, TimeUnit.SECONDS), "the build was still running after close returned")
            assertTrue(build.isDone, "close left a build running: ${build.progress}")
            assertNull(build.failure, "close failed a build rather than cancelling it")
        }
        // And the definition outlived the build, because it was durable before anything was built.
        IndexCatalog(directory).use { reopened ->
            DocumentStore.open(directory, indexStoreOptions(reopened)).use { store ->
                reopened.attach(store, backfill = false)
                assertEquals(1, reopened.indexes().size, "the definition did not survive")
            }
        }
    }

    /** The two builds are serialised, so neither races the other to write the same sidecar. */
    @Test
    fun `two background builds both finish`(@TempDir root: Path) {
        val directory = scratch(root)
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, indexStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                store.load(List(1_000) { document(it) })

                val first = catalog.createIndexInBackground(store, IndexDefinition.inverted("$.team"))
                val second = catalog.createIndexInBackground(store, IndexDefinition.column("$.score"))
                assertEquals(IndexBuildState.COMPLETED, first.await().state)
                assertEquals(IndexBuildState.COMPLETED, second.await().state)

                assertTrue(catalog.problems.isEmpty(), "problems: ${catalog.problems}")
                val segments = segmentNumbers(directory)
                val inverted = assertNotNull(first.handle)
                val column = assertNotNull(second.handle)
                assertEquals(segments.map { it to inverted.id }.toSet(), postingFiles(directory))
                assertEquals(segments.map { it to column.id }.toSet(), columnFiles(directory))
                assertAnswersMatchAScan(store, catalog, inverted)
            }
        }
    }

    private companion object {
        /**
         * Segments the cancellation test lets through before it stops the build.
         *
         * Above one, so the cancelled build has covered something and the assertions can tell "stopped
         * part way" from "never started"; well below the fixture's segment count, so there is
         * definitely work left for the resumed build to do.
         */
        const val GATE_AT = 3

        /** Documents per flush, and therefore roughly per segment. */
        const val ROUND = 200
    }
}
