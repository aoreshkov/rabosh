package app.oreshkov.rabosh.samples

import app.oreshkov.rabosh.api.Rabosh
import app.oreshkov.rabosh.core.Key
import app.oreshkov.rabosh.index.IndexBuildState
import app.oreshkov.rabosh.index.IndexDefinition
import app.oreshkov.rabosh.query.Query
import app.oreshkov.rabosh.query.QueryStats
import app.oreshkov.rabosh.query.path
import java.nio.file.Path

/**
 * Indexing a store that is already full, without stopping it — and the state in the middle.
 *
 * ```
 * ./gradlew :rabosh-samples:runIndexLater
 * ```
 *
 * [ThreeStepsMain] builds its index in one blocking call, which is the right thing for four thousand
 * documents and the wrong thing for four hundred million. This is the other shape: the build runs on
 * a thread of the database's own, and the store keeps answering the whole time.
 *
 * **What is worth watching is the middle.** Because an index is a set of per-segment sidecar files,
 * a half-built one is not a broken one — it covers the segments it has reached, and every query
 * scans the segments it has not. There is no cutover, no rebuild-and-swap, and no window in which an
 * answer is wrong. This sample reaches that half-built state *deliberately*, by cancelling the build
 * after it starts, and then queries from it: the rows are the same as before the index existed and
 * the same as after it is finished, and only the counters move.
 *
 * Cancelling is safe for the same reason, and it is why there is no rollback and no resume verb. A
 * cancelled build, a crashed build and a running build all leave exactly one thing behind — an index
 * that is defined and partly covered — so finishing the job is just asking for it again, which is
 * what the last step does.
 */
object IndexLaterMain {

    private const val DOCUMENT_COUNT = 20_000
    private const val BATCH_SIZE = 500

    private const val SERVICE = "billing"
    private const val SERVICE_PATH = "\$.service"

    @JvmStatic
    fun main(arguments: Array<String>) {
        SampleRun.entryPoint(arguments, "index-later", ::run)
    }

    /** The sample itself. Takes a directory so the suite can run it against a temporary one. */
    fun run(directory: Path) {
        Rabosh.open(directory, SampleRun.options()).use { db ->
            val expected = SampleCorpus.countOf(SERVICE, DOCUMENT_COUNT)
            val query = Query.where(path(SERVICE_PATH) eq SERVICE)

            val uncovered = load(db, query)
            val partial = buildAndStop(db, query)
            val complete = resume(db, query)

            SampleRun.heading("4.", "The answer never moved")
            check(uncovered == partial && partial == complete) {
                "the three states disagreed: ${uncovered.size}, ${partial.size}, ${complete.size} rows -- " +
                    "an index changed an answer, which is the one thing it may never do"
            }
            check(complete.size == expected) { "expected $expected rows, got ${complete.size}" }
            println("no index, half an index, a whole index: the same ${complete.size} keys every time.")
            println("only the counters above moved, and moving those is all an index is allowed to do.")
        }
    }

    // --- 1. a store that is already full ------------------------------------------------------------

    private fun load(db: Rabosh, query: Query): List<Key> {
        SampleRun.heading("1.", "A store with data already in it")

        println("writing $DOCUMENT_COUNT events -- no index is defined, and none will be until step 2")
        SampleRun.load(db, DOCUMENT_COUNT, BATCH_SIZE)
        println("${db.stats.segmentCount} segments on disk, none of them with a sidecar")

        val (keys, stats) = execute(db, query)
        println()
        println("the query, answered by scanning:")
        report(keys, stats)
        return keys
    }

    // --- 2. a build, started and stopped -----------------------------------------------------------

    /**
     * Starts the build, stops it, and queries from what is left.
     *
     * The cancel is issued immediately rather than after a wait, because the half-built state is the
     * subject and hoping to catch it by timing would make the sample demonstrate something different
     * on every machine. `cancel` stops the pass at the next **segment** boundary — the segment in
     * flight is finished rather than abandoned, since an observation writes nothing until it
     * completes and abandoning would throw away a scan that is nearly done.
     */
    private fun buildAndStop(db: Rabosh, query: Query): List<Key> {
        SampleRun.heading("2.", "Start the build, then stop it")

        val build = db.createIndexInBackground(IndexDefinition.inverted(SERVICE_PATH))
        println("createIndexInBackground returned at once, with a usable handle: ${build.handle}")
        SampleRun.note("the definition is durable before a single posting file exists, so this is not a promise")

        build.cancel()
        val progress = build.await()
        println("after cancelling: $progress")
        if (progress.state != IndexBuildState.CANCELLED) {
            SampleRun.note("this build finished before the cancel reached it; the state below is complete, not partial")
        }

        val (keys, stats) = execute(db, query)
        println()
        println("the query, from a half-built index:")
        report(keys, stats)
        stats.indexes.firstOrNull()?.let { use ->
            println("   coverage: ${use.coverage}")
            SampleRun.note("covered segments came from sidecars; the rest were scanned, and nothing was lost")
        }
        return keys
    }

    // --- 3. finish it ------------------------------------------------------------------------------

    /**
     * Finishes the job by asking for it again.
     *
     * There is no resume verb because there is no separate state to resume from. Calling
     * `createIndexInBackground` with the same definition skips every segment already covered without
     * reading it, so `segmentsBuilt` below is exactly the remainder.
     */
    private fun resume(db: Rabosh, query: Query): List<Key> {
        SampleRun.heading("3.", "Finish it, by asking for the same thing again")

        val build = db.createIndexInBackground(IndexDefinition.inverted(SERVICE_PATH))
        while (!build.isDone) {
            println("   ${build.progress}  ${"%.0f%%".format(build.progress.fraction * 100)}")
            // The store is answering queries the entire time this loop runs.
            execute(db, query)
        }
        val progress = build.await()
        println("done: $progress")
        SampleRun.note("segmentsBuilt is the remainder: covered segments were skipped without being read")

        val (keys, stats) = execute(db, query)
        println()
        println("the query, fully indexed:")
        report(keys, stats)
        return keys
    }

    // --- plumbing -----------------------------------------------------------------------------------

    private fun execute(db: Rabosh, query: Query): Pair<List<Key>, QueryStats> {
        val keys = ArrayList<Key>()
        db.query(query).use { cursor ->
            while (cursor.next()) keys.add(cursor.key)
            return keys to cursor.stats
        }
    }

    private fun report(keys: List<Key>, stats: QueryStats) {
        println("   ${keys.size} rows, first ${keys.firstOrNull()}, last ${keys.lastOrNull()}")
        println(
            "   documents read %d, segments indexed %d, scanned %d"
                .format(stats.documentsRead, stats.segmentsIndexed, stats.segmentsScanned),
        )
    }
}
