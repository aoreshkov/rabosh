package app.oreshkov.rabosh.bench

import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.core.Durability
import app.oreshkov.rabosh.core.Snapshot
import app.oreshkov.rabosh.core.StoreOptions
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
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Reading a compacted store: point lookups, misses, and scans.
 *
 * **The miss is the one worth having.** A lookup for a key that is not there is what the bloom
 * filters exist for, and the ratio between [getMissing] and [getPresent] is the only direct evidence
 * that they are doing their job — every other test only shows that the answer is right, which it
 * would be with no filter at all.
 *
 * [scanKeysOnly] against [scanDocuments] is the other pair: the cursor's document is a view over a
 * mapping, so a scan that never touches a document should cost the merge and nothing else. If those
 * two converge, "reading one field of a large document costs the field" has stopped being true.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
open class ReadBenchmark {

    /**
     * How many documents the fixture holds, so a smoke run can build one it can afford.
     *
     * A parameter rather than a constant because this suite and `QueryBenchmark` were **excluded from
     * the smoke configuration entirely** — at smoke size their `@Setup` was the run — which meant
     * neither had ever started in CI while `.claude/rules/testing.md` claimed the smoke run "proves
     * they still compile, start and measure the thing they name". Phase 16 made a benchmark that did
     * not run fail the build; a benchmark nothing selects is the same hole one step further out.
     *
     * The numbers at 2 000 documents are not comparable to the numbers at 200 000 and are not meant to
     * be — the smoke configuration's numbers never were.
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
    private lateinit var snapshot: Snapshot
    private lateinit var probes: IntArray
    private var cursor = 0

    @Setup
    fun setUp() {
        directory = Corpus.scratch("read")
        store = DocumentStore.open(
            directory,
            StoreOptions(
                durability = Durability.BUFFERED,
                memtableMaxBytes = 32L * 1024 * 1024,
                backgroundMaintenance = false,
            ),
        )
        for (index in 0 until documentCount) {
            store.put(Corpus.key(index), Variant.fromJson(Corpus.json(index)))
            if (index % perSegment == perSegment - 1) store.flush()
        }
        store.flush()
        store.compact()
        snapshot = store.snapshot()
        probes = Corpus.probes(64 * 1024, documentCount)
    }

    @TearDown
    fun tearDown() {
        snapshot.close()
        store.close()
        Corpus.deleteRecursively(directory)
    }

    /** A key that is there: bloom filter, block index, block, decode. */
    @Benchmark
    fun getPresent(hole: Blackhole) {
        hole.consume(store.get(Corpus.key(probes[cursor++ and (probes.size - 1)]), snapshot))
    }

    /** A key that is not: every segment's filter says no, and no block is read. */
    @Benchmark
    fun getMissing(hole: Blackhole) {
        hole.consume(store.get(Corpus.key(documentCount + (cursor++ and 0xFFFF)), snapshot))
    }

    /** The lookup plus one field, which is what an application usually wanted. */
    @Benchmark
    fun getAndReadField(hole: Blackhole) {
        val document = store.get(Corpus.key(probes[cursor++ and (probes.size - 1)]), snapshot)
        hole.consume(document?.field("team")?.stringValue())
    }

    /** A thousand keys in order: the merge, with no document touched. */
    @Benchmark
    fun scanKeysOnly(hole: Blackhole): Int {
        var count = 0
        val from = Corpus.key(probes[cursor++ and (probes.size - 1)])
        store.scan(from = from, snapshot = snapshot).use { entries ->
            while (entries.next() && count < 1_000) {
                hole.consume(entries.key)
                count++
            }
        }
        return count
    }

    /** The same thousand, reading one field of each. */
    @Benchmark
    fun scanDocuments(hole: Blackhole): Int {
        var count = 0
        val from = Corpus.key(probes[cursor++ and (probes.size - 1)])
        store.scan(from = from, snapshot = snapshot).use { entries ->
            while (entries.next() && count < 1_000) {
                hole.consume(entries.document.field("score")?.longValue())
                count++
            }
        }
        return count
    }
}
