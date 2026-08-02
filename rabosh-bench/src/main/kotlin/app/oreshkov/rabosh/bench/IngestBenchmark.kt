package app.oreshkov.rabosh.bench

import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.core.Durability
import app.oreshkov.rabosh.core.StoreOptions
import app.oreshkov.rabosh.core.WriteBatch
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
 * Writing: one document at a time, and a batch at a time, under each durability.
 *
 * **The `SYNC`/`BUFFERED` pair is the number that decides an operator's day**, and the design says so
 * without knowing it: `SYNC` is the default because the engine's promise is the acknowledged prefix,
 * and a buffered default quietly redefines "acknowledged". What that costs has been an open question
 * since phase 3, and it is the ratio here.
 *
 * Keys cycle over a bounded range so the store reaches a steady state instead of growing without
 * limit for the length of a run — an ingest benchmark that also measures a store getting larger is
 * measuring two things.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
open class IngestBenchmark {

    @Param("SYNC", "BUFFERED")
    var durability: String = "SYNC"

    private lateinit var directory: Path
    private lateinit var store: DocumentStore
    private lateinit var documents: Array<Variant>
    private var cursor = 0

    private val corpusSize = 20_000

    @Setup
    fun setUp() {
        directory = Corpus.scratch("ingest")
        store = DocumentStore.open(
            directory,
            StoreOptions(
                durability = Durability.valueOf(durability),
                memtableMaxBytes = 16L * 1024 * 1024,
            ),
        )
        // Pre-encoded: this measures the store, not the codec, which `VariantBenchmark` covers.
        documents = Array(corpusSize) { Variant.fromJson(Corpus.json(it)) }
    }

    @TearDown
    fun tearDown() {
        store.close()
        Corpus.deleteRecursively(directory)
    }

    /** One document, one commit — the latency-shaped path, and where `SYNC` is felt. */
    @Benchmark
    fun putOne() {
        val index = cursor++ % corpusSize
        store.put(Corpus.key(index), documents[index])
    }

    /** A hundred documents in one commit: one log append, one force. */
    @Benchmark
    fun putBatchOf100(hole: Blackhole) {
        val batch = WriteBatch()
        repeat(100) {
            val index = cursor++ % corpusSize
            batch.put(Corpus.key(index), documents[index])
        }
        store.write(batch)
        hole.consume(batch.size)
    }

    /** Encode and write, which is what an application that holds JSON text actually does. */
    @Benchmark
    fun parseAndPut() {
        val index = cursor++ % corpusSize
        store.put(Corpus.key(index), Variant.fromJson(Corpus.json(index)))
    }

    /** Deleting is a commit like any other, and the same durability applies to it. */
    @Benchmark
    fun delete() {
        store.delete(Corpus.key(cursor++ % corpusSize))
    }
}
