package app.oreshkov.rabosh.samples

import app.oreshkov.rabosh.api.Rabosh
import app.oreshkov.rabosh.catalog.CatalogPath
import app.oreshkov.rabosh.core.Key
import app.oreshkov.rabosh.index.IndexDefinition
import app.oreshkov.rabosh.query.Query
import app.oreshkov.rabosh.query.QueryStats
import app.oreshkov.rabosh.query.and
import app.oreshkov.rabosh.query.path
import app.oreshkov.rabosh.variant.toJsonString
import java.nio.file.Path

/**
 * The whole of rabosh's argument, as a program that runs:
 * **write blind → model later → index later.**
 *
 * ```
 * ./gradlew :rabosh-samples:runThreeSteps
 * ```
 *
 * The README opens with a fourteen-line version of this. What a snippet cannot show, and what this
 * exists for, is the third step's *evidence*: the same query is run **before** the index is created
 * and again after, and both the rows and the counters are printed. The rows are identical and the
 * work is not. That is the invariant the entire project is built around —
 * *an index may change how fast a query runs, never what it returns* — and it is the one claim a
 * reader should not have to take on trust.
 *
 * Nothing here declares a schema, and nothing re-ingests a document to acquire an index. The events
 * are written first, as raw JSON of a shape nobody stated; the model is worked out afterwards from
 * statistics that flush and compaction collected anyway; and the indexes are built last, against
 * bytes already on disk.
 */
object ThreeStepsMain {

    private const val DOCUMENT_COUNT = 4_000
    private const val BATCH_SIZE = 500

    /** The service the sample filters on, and the path it filters by. */
    private const val SERVICE = "billing"
    private const val SERVICE_PATH = "\$.service"
    private const val LATENCY_PATH = "\$.latencyMs"
    private const val SLOW_MILLIS = 300L

    @JvmStatic
    fun main(arguments: Array<String>) {
        SampleRun.entryPoint(arguments, "three-steps", ::run)
    }

    /** The sample itself. Takes a directory so the suite can run it against a temporary one. */
    fun run(directory: Path) {
        Rabosh.open(directory, SampleRun.options()).use { db ->
            writeBlind(db)
            modelLater(db)
            indexLater(db)
        }
    }

    // --- 1. write blind ---------------------------------------------------------------------------

    /**
     * Accept the JSON. All of it, whatever shape it is in, without being told the shape first.
     *
     * There is no schema to declare, so none is declared. The corpus is deliberately ragged — a field
     * missing from a third of the documents, a field that is sometimes explicitly null, a field that
     * one producer in fifty sends as a string instead of a number — and none of that is rejected,
     * repaired or flattened. See [SampleCorpus].
     */
    private fun writeBlind(db: Rabosh) {
        SampleRun.heading("1.", "Write blind")

        println("writing $DOCUMENT_COUNT events, in batches of $BATCH_SIZE, with no schema anywhere")
        SampleRun.load(db, DOCUMENT_COUNT, BATCH_SIZE)

        val stats = db.stats
        println("stored: $stats")
        SampleRun.note("a batch is one commit: one log append and one force, whatever it carries")
        SampleRun.note("each flush sealed segments; an index will later be a sidecar beside each one")
    }

    // --- 2. model later ---------------------------------------------------------------------------

    /**
     * Ask what was actually written.
     *
     * Nothing scanned the store to answer this. The path statistics behind it were collected during
     * the flushes step 1 already paid for, so the model is a by-product of storage maintenance rather
     * than a job somebody has to schedule — and it is never stale for the segments it covers.
     */
    private fun modelLater(db: Rabosh) {
        SampleRun.heading("2.", "Model later")

        println("the model, derived rather than declared:")
        println()
        println(db.schema().render().trimEnd().prependIndent("   "))

        SampleRun.note("presence below 100% is a field that is genuinely absent, not one defaulted to null")
        SampleRun.note("$LATENCY_PATH shows two types: one producer in fifty sends a string")
        SampleRun.note("\$.tags[*] exceeds 100% because it is a repeated path, and that is the honest number")

        println()
        println("paths worth an index, best first (a report: nothing is built yet):")
        for (candidate in db.indexCandidates()) println("   $candidate")
    }

    // --- 3. index later ---------------------------------------------------------------------------

    /**
     * Build indexes against data already on disk, and show what changed and what did not.
     *
     * The measurement is the point. `createIndex` rewrites no document — an index is a set of
     * immutable per-segment sidecar files — so the only thing it can possibly change is how a query
     * is answered, and the before/after comparison below is what says so out loud.
     */
    private fun indexLater(db: Rabosh) {
        SampleRun.heading("3.", "Index later")

        val query = Query.where(path(SERVICE_PATH) eq SERVICE)
        val expected = SampleCorpus.countOf(SERVICE, DOCUMENT_COUNT)

        val (keysBefore, statsBefore) = execute(db, query)
        println("before any index exists:")
        report(keysBefore, statsBefore)

        // Act on the recommendation from step 2 where there is one, rather than reaching past it.
        val recommended = db.indexCandidates().firstOrNull { it.path == CatalogPath.parse(SERVICE_PATH) }
        val definition = recommended?.let(IndexDefinition::of) ?: IndexDefinition.inverted(SERVICE_PATH)
        println()
        println("creating an index over $SERVICE_PATH, against the segments already written")
        db.createIndex(definition)
        db.createIndex(IndexDefinition.column(LATENCY_PATH))

        val (keysAfter, statsAfter) = execute(db, query)
        println()
        println("after the index is built:")
        report(keysAfter, statsAfter)

        println()
        check(keysBefore == keysAfter) {
            "the index changed the answer, which is the one thing it may never do: " +
                "${keysBefore.size} rows before, ${keysAfter.size} after"
        }
        check(keysAfter.size == expected) { "expected $expected rows, got ${keysAfter.size}" }
        println("the same ${keysAfter.size} keys, in the same order, from a different amount of work:")
        println(
            "   documents read %d -> %d, segments scanned %d -> %d, segments from sidecars %d -> %d"
                .format(
                    statsBefore.documentsRead, statsAfter.documentsRead,
                    statsBefore.segmentsScanned, statsAfter.segmentsScanned,
                    statsBefore.segmentsIndexed, statsAfter.segmentsIndexed,
                ),
        )

        println()
        println("how the planner answers it, and why:")
        println()
        println(db.explain(query).render().trimEnd().prependIndent("   "))

        rangeAndProjection(db)
    }

    /**
     * The second index kind, and the two things about it a reader will otherwise get wrong.
     *
     * An inverted index answers equality and cannot answer `<` at all; a **shredded column** answers
     * ranges, and answers them without opening a document — its projected values are read straight
     * out of the column. And a numeric predicate matches numeric values *only*, so the one document
     * in fifty whose latency arrived as a string is not a match here. Not an error, and not a silent
     * coercion: type bracketing is part of what the query means.
     */
    private fun rangeAndProjection(db: Rabosh) {
        val query = Query.where(and(path(SERVICE_PATH) eq SERVICE, path(LATENCY_PATH) ge SLOW_MILLIS))
            .project(LATENCY_PATH)

        var rows = 0
        var sample = ""
        db.query(query).use { cursor ->
            while (cursor.next()) {
                if (rows == 0) sample = "${cursor.key} -> ${cursor.row[LATENCY_PATH]?.toJsonString()}"
                rows++
            }
            println()
            println("a range over the same events, answered by the shredded column:")
            println("   $rows rows, first: $sample")
            println(
                "   rows served from columns without opening a document: %d of %d"
                    .format(cursor.stats.rowsProjectedFromColumns, cursor.stats.rowsReturned),
            )
        }
        SampleRun.note("an inverted index cannot answer this: its terms sort for lookup, not by value")
        SampleRun.note("the string-valued latencies are not matched: a numeric predicate matches numbers only")
    }

    // --- plumbing ---------------------------------------------------------------------------------

    /** Runs [query] to exhaustion, returning the keys it found and what it cost to find them. */
    private fun execute(db: Rabosh, query: Query): Pair<List<Key>, QueryStats> {
        val keys = ArrayList<Key>()
        db.query(query).use { cursor ->
            while (cursor.next()) keys.add(cursor.key)
            return keys to cursor.stats
        }
    }

    private fun report(keys: List<Key>, stats: QueryStats) {
        println("   ${keys.size} rows, first ${keys.firstOrNull()}, last ${keys.lastOrNull()}")
        println("   $stats")
    }
}
