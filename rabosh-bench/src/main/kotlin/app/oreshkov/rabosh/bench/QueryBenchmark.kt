package app.oreshkov.rabosh.bench

import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.core.Durability
import app.oreshkov.rabosh.core.Snapshot
import app.oreshkov.rabosh.core.StoreOptions
import app.oreshkov.rabosh.index.IndexCatalog
import app.oreshkov.rabosh.index.IndexDefinition
import app.oreshkov.rabosh.query.Projection
import app.oreshkov.rabosh.query.Query
import app.oreshkov.rabosh.query.QueryEngine
import app.oreshkov.rabosh.query.and
import app.oreshkov.rabosh.query.path
import app.oreshkov.rabosh.variant.Variant
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown
import java.math.BigDecimal
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * What an index is worth, measured rather than claimed.
 *
 * Two stores over the same corpus — one indexed, one not — and the same predicates run against both.
 * Every test in `rabosh-query` asserts the two return *identical* answers; these say what the
 * difference costs, which is the other half of the sentence the whole engine is built around: an
 * index may change how fast a query runs, never what it returns.
 *
 * The pairs are chosen to separate three effects that are easy to conflate: a posting-list lookup
 * ([indexedEquality]), a bitmap intersection of two indexes ([indexedConjunction], where the win is
 * that the second predicate never decodes a key), and a column's ordered scan with block pruning
 * ([indexedRange]).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
open class QueryBenchmark {

    /**
     * How many documents the fixture holds. See `ReadBenchmark.documentCount` for why it is a
     * parameter: these two suites were excluded from the smoke configuration and so had never started
     * in CI at all, while `CLAUDE.md` claimed the smoke run proves every suite still starts.
     */
    @Param("200000")
    var documentCount: Int = 200_000

    /**
     * Documents per flush: four segments before compaction, whatever the corpus size.
     *
     * Derived rather than the constant 50 000 it replaces, so that shrinking the corpus shrinks the
     * fixture instead of collapsing it to one segment — the level structure is held fixed across sizes,
     * which is the same rule the sweeps follow.
     */
    private val perSegment: Int get() = maxOf(1, documentCount / 4)

    private lateinit var directory: Path
    private lateinit var store: DocumentStore
    private lateinit var indexes: IndexCatalog
    private lateinit var indexed: QueryEngine
    private lateinit var unindexed: QueryEngine
    private lateinit var snapshot: Snapshot

    private val equality = Query.where(path("$.team") eq "search").project(Projection.KEY)
    private val conjunction = Query
        .where(and(path("$.team") eq "search", path("$.score") ge 900L))
        .project(Projection.KEY)
    private val range = Query
        .where(path("$.price").between(BigDecimal("100.00"), BigDecimal("140.00")))
        .project(Projection.KEY)

    @Setup
    fun setUp() {
        directory = Corpus.scratch("query")
        indexes = IndexCatalog(directory)
        store = DocumentStore.open(
            directory,
            StoreOptions(
                durability = Durability.BUFFERED,
                memtableMaxBytes = 32L * 1024 * 1024,
                backgroundMaintenance = false,
                segmentObserver = indexes,
            ),
        )
        indexes.attach(store)
        for (index in 0 until documentCount) {
            store.put(Corpus.key(index), Variant.fromJson(Corpus.json(index)))
            if (index % perSegment == perSegment - 1) store.flush()
        }
        store.flush()
        store.compact()

        indexed = QueryEngine(store, indexes)
        // The comparison engine sees the same store through a catalog with no indexes defined, so
        // the only difference between the two is whether the sidecars exist.
        unindexed = QueryEngine(store, IndexCatalog(directory.resolve("no-indexes")))
        indexes.createIndex(store, IndexDefinition.inverted("$.team"))
        indexes.createIndex(store, IndexDefinition.column("$.score"))
        indexes.createIndex(store, IndexDefinition.column("$.price"))
        snapshot = store.snapshot()
    }

    @TearDown
    fun tearDown() {
        snapshot.close()
        store.close()
        indexes.close()
        Corpus.deleteRecursively(directory)
    }

    @Benchmark
    fun indexedEquality(hole: Blackhole) {
        hole.consume(indexed.keys(equality, snapshot).size)
    }

    @Benchmark
    fun scannedEquality(hole: Blackhole) {
        hole.consume(unindexed.keys(equality, snapshot).size)
    }

    @Benchmark
    fun indexedConjunction(hole: Blackhole) {
        hole.consume(indexed.keys(conjunction, snapshot).size)
    }

    @Benchmark
    fun scannedConjunction(hole: Blackhole) {
        hole.consume(unindexed.keys(conjunction, snapshot).size)
    }

    @Benchmark
    fun indexedRange(hole: Blackhole) {
        hole.consume(indexed.keys(range, snapshot).size)
    }

    @Benchmark
    fun scannedRange(hole: Blackhole) {
        hole.consume(unindexed.keys(range, snapshot).size)
    }

    /**
     * With a projection the columns **cannot** serve, which is the control.
     *
     * `$.team` has an inverted index and no column, and projection push-down is all-or-nothing per
     * row — one unbound field means the document is read, and once it is read every field comes from
     * it. So this measures what a projected query cost before phase 12 and still costs when a
     * projected path is not shredded.
     */
    @Benchmark
    fun indexedEqualityProjected(hole: Blackhole) {
        indexed.execute(equality.project("$.team", "$.score"), snapshot).use { rows ->
            var count = 0
            while (rows.next()) {
                hole.consume(rows.row["$.score"])
                count++
            }
            hole.consume(count)
        }
    }

    /**
     * With a projection every field of which has a column: the one push-down can serve.
     *
     * Read against `indexedEquality` above rather than against a remembered number. Keys-only is the
     * floor — the plan already decided those rows and opened nothing — so this says how much of the
     * gap between "decide the row" and "fill the row in" the columns close. The fixture is deliberately
     * unchanged from the other benchmarks: `$.score` and `$.price` were already shredded, so nothing
     * here was arranged to make the number look better.
     */
    @Benchmark
    fun indexedEqualityProjectedColumns(hole: Blackhole) {
        indexed.execute(equality.project("$.score", "$.price"), snapshot).use { rows ->
            var count = 0
            while (rows.next()) {
                hole.consume(rows.row["$.score"])
                hole.consume(rows.row["$.price"])
                count++
            }
            hole.consume(count)
        }
    }
}
