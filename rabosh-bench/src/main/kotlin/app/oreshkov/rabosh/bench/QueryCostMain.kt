package app.oreshkov.rabosh.bench

import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.core.Durability
import app.oreshkov.rabosh.core.Snapshot
import app.oreshkov.rabosh.core.StoreOptions
import app.oreshkov.rabosh.index.IndexCatalog
import app.oreshkov.rabosh.index.IndexDefinition
import app.oreshkov.rabosh.index.IndexReader
import app.oreshkov.rabosh.index.IndexTerm
import app.oreshkov.rabosh.query.Projection
import app.oreshkov.rabosh.query.Query
import app.oreshkov.rabosh.query.QueryEngine
import app.oreshkov.rabosh.query.path
import app.oreshkov.rabosh.variant.Variant
import java.nio.file.Path

/**
 * Where an indexed query's time goes, per row it returns.
 *
 * A `main` rather than a JMH suite, for the reason `ReadCostMain`, `AmplificationMain` and
 * `PageCacheMain` are: each of these is one run of a **curve**, and a harness built to repeat one
 * operation has nowhere to put one.
 *
 * ```
 * ./gradlew :rabosh-bench:runQueryCost
 * ```
 *
 * ### Why this exists
 *
 * `QueryBenchmark.indexedEquality` returns ~28 600 keys from a store its index fully covers, and it
 * costs about 2.5 µs per row. `ReadBenchmark.getPresent` — bloom filter, block bisect, block read,
 * document decode — costs **1.00 µs**. Emitting a key an index has already decided should be a
 * fraction of a point lookup and is two and a half times one, and no benchmark in the project could
 * say which mechanism that is: a throughput figure over a whole query is a total, and a total
 * attributes nothing.
 *
 * ### The two sweeps, and why they are two
 *
 * [decomposition] adds **exactly one mechanism per stage**, so a difference between two rows names a
 * cost rather than observing one. That is the same instrument choice phase 13 made when it varied
 * block size to decompose a point get into a per-entry and a per-KiB term, and it is why this reports
 * deltas beside totals.
 *
 * [segmentSweep] moves two costs in opposite directions, which is the shape
 * `.claude/rules/testing.md` asks for whenever a cost can be attributed to two mechanisms. Splitting
 * the same documents across more segments makes every *other* segment a place the executor has to
 * prove a key is absent from — more probes — while making each of those segments' key blocks
 * smaller, so each probe is cheaper. If the uniqueness probe is the cost, the curve rises with
 * segment count. If it is not, the curve is flat. A before/after on one fixture could not tell those
 * apart.
 *
 * Every row prints the rows it produced, because a stage that quietly returned nothing would
 * otherwise be the fastest one on the page — the "assertions about work never stand alone" rule,
 * applied to a diagnostic.
 */
object QueryCostMain {

    private const val DOCUMENT_COUNT = 200_000

    /** `$.team` is one of seven, so this matches about a seventh of the corpus. */
    private const val TERM = "search"

    @JvmStatic
    fun main(args: Array<String>) {
        val documentCount = args.firstOrNull()?.toIntOrNull() ?: DOCUMENT_COUNT
        decomposition(documentCount)
        println()
        segmentSweep(documentCount)
    }

    // --- sweep 1: what one row costs, mechanism by mechanism ---------------------------------

    private fun decomposition(documentCount: Int) {
        // One segment on purpose: it is the only fixture where each stage's delta is the mechanism it
        // added and nothing else. What it cannot show is the cost that grows with segment count, which
        // is why there is a second sweep and why this one alone would have missed the phase's largest
        // finding entirely.
        println("== per-row decomposition: $documentCount documents, one segment ==")
        Fixture.open(documentCount, segments = 1).use { fixture ->
            val reader = fixture.reader()
            try {
                val term = IndexTerm.ofString(TERM)
                val segments = reader.usableSegments
                println("  segments: ${segments.size}, index covers ${reader.coverage}")

                val stages = listOf(
                    Stage("bitmap iteration only") {
                        var rows = 0
                        for (segment in segments) {
                            val cursor = reader.candidateOrdinals(segment, term).cursor()
                            while (cursor.next()) rows++
                        }
                        rows
                    },
                    Stage("+ keyAt") {
                        var rows = 0
                        for (segment in segments) {
                            val cursor = reader.candidateOrdinals(segment, term).cursor()
                            while (cursor.next()) {
                                if (reader.keyAt(segment, cursor.value).size > 0) rows++
                            }
                        }
                        rows
                    },
                    Stage("+ isUniqueKey") {
                        var rows = 0
                        var unique = 0
                        for (segment in segments) {
                            val cursor = reader.candidateOrdinals(segment, term).cursor()
                            while (cursor.next()) {
                                val key = reader.keyAt(segment, cursor.value)
                                if (reader.isUniqueKey(key, segment)) unique++
                                rows++
                            }
                        }
                        // Every key is unique in a one-segment store, and saying so does two jobs: the
                        // probe's result is consumed, so it cannot become dead code, and a fixture that
                        // quietly stopped exercising the probe would fail here rather than look fast.
                        check(unique == rows) { "expected $rows unique key(s) in one segment, got $unique" }
                        rows
                    },
                    Stage("IndexReader.candidates(term)") { reader.candidates(term).toKeyList().size },
                    Stage("QueryEngine.keys, keys only") { fixture.engine.keys(fixture.equality, fixture.snapshot).size },
                )

                var previous = 0.0
                println("  %-34s %10s %10s %9s".format("stage", "us/row", "delta", "rows"))
                for (stage in stages) {
                    val (nanos, rows) = measure(stage.body)
                    val perRow = nanos / rows / 1000.0
                    println(
                        "  %-34s %10.3f %10.3f %9d".format(
                            stage.label,
                            perRow,
                            perRow - previous,
                            rows,
                        ),
                    )
                    previous = perRow
                }

                fixture.engine.execute(fixture.equality, fixture.snapshot).use { rows ->
                    var drained = 0
                    while (rows.next()) drained++
                    println("  stats after $drained row(s): ${rows.stats}")
                }
            } finally {
                reader.close()
            }
        }
    }

    // --- sweep 2: the same documents, spread over more segments -------------------------------

    private fun segmentSweep(documentCount: Int) {
        for (overlapping in listOf(false, true)) {
            println(
                "== segment sweep: $documentCount documents, held constant, " +
                    "${if (overlapping) "overlapping" else "disjoint"} key ranges ==",
            )
            println("  %9s %12s %10s %9s".format("segments", "query us", "us/row", "rows"))
            for (segments in listOf(1, 2, 4, 8, 16)) {
                Fixture.open(documentCount, segments, overlapping).use { fixture ->
                    val (nanos, rows) = measure { fixture.engine.keys(fixture.equality, fixture.snapshot).size }
                    println(
                        "  %9d %12.2f %10.3f %9d".format(
                            fixture.snapshot.segmentNumbers.size,
                            nanos / 1000.0,
                            nanos / rows / 1000.0,
                            rows,
                        ),
                    )
                }
            }
            println()
        }
    }

    // --- plumbing ------------------------------------------------------------------------------

    private class Stage(val label: String, val body: () -> Int)

    /**
     * Median of nine timed runs after three warm-ups, and the rows the last one produced.
     *
     * The median rather than the mean because one garbage collection in nine runs should not move a
     * figure this is going to attribute a mechanism to, and rather than the minimum because the
     * minimum of a small sample reports the luckiest run rather than the ordinary one.
     */
    private fun measure(body: () -> Int): Pair<Double, Int> {
        val samples = DoubleArray(9)
        var rows = 0
        repeat(3) { rows = body() }
        for (index in samples.indices) {
            val start = System.nanoTime()
            rows = body()
            samples[index] = (System.nanoTime() - start).toDouble()
        }
        samples.sort()
        check(rows > 0) { "a stage produced no rows, so its timing measures nothing" }
        return samples[samples.size / 2] to rows
    }

    /**
     * A store of [documentCount] documents in [segments] segments, with an inverted index on `$.team`.
     *
     * The segment count is arranged by flush cadence and *not* by compaction, so every row of the
     * sweep holds the same documents, the same index definition and the same level structure — the
     * hold-everything-else-fixed rule, without which the sweep would be measuring the fixture.
     */
    private class Fixture(
        private val directory: Path,
        private val store: DocumentStore,
        private val indexes: IndexCatalog,
        val engine: QueryEngine,
        val snapshot: Snapshot,
    ) : AutoCloseable {

        val equality: Query = Query.where(path("$.team") eq TERM).project(Projection.KEY)

        fun reader(): IndexReader =
            indexes.read(store, indexes.indexes().first(), snapshot)

        override fun close() {
            snapshot.close()
            store.close()
            indexes.close()
            Corpus.deleteRecursively(directory)
        }

        companion object {
            fun open(documentCount: Int, segments: Int, overlapping: Boolean = false): Fixture {
                val directory = Corpus.scratch("query-cost")
                val indexes = IndexCatalog(directory)
                val store = DocumentStore.open(
                    directory,
                    StoreOptions(
                        durability = Durability.BUFFERED,
                        // Large enough that only the explicit flushes below decide the segment count.
                        memtableMaxBytes = 512L * 1024 * 1024,
                        backgroundMaintenance = false,
                        segmentObserver = indexes,
                    ),
                )
                indexes.attach(store)
                if (overlapping) {
                    // Every segment gets every n-th key, so all of them span the whole key space. This
                    // is the case a key-range rejection cannot help with, and it has to be arranged
                    // deliberately: writing a corpus in order produces disjoint segments, which is the
                    // *favourable* shape, and a sweep that only ever saw it would be reporting a
                    // property of the fixture as a property of the engine.
                    for (batch in 0 until segments) {
                        for (index in batch until documentCount step segments) {
                            store.put(Corpus.key(index), Variant.fromJson(Corpus.json(index)))
                        }
                        store.flush()
                    }
                } else {
                    val perSegment = documentCount / segments
                    for (index in 0 until documentCount) {
                        store.put(Corpus.key(index), Variant.fromJson(Corpus.json(index)))
                        if (index % perSegment == perSegment - 1) store.flush()
                    }
                }
                store.flush()
                indexes.createIndex(store, IndexDefinition.inverted("$.team"))
                val snapshot = store.snapshot()
                return Fixture(directory, store, indexes, QueryEngine(store, indexes), snapshot)
            }
        }
    }
}
