package app.oreshkov.rabosh.bench

import app.oreshkov.rabosh.variant.Variant
import app.oreshkov.rabosh.variant.VariantBuilder
import app.oreshkov.rabosh.variant.VariantPath
import app.oreshkov.rabosh.variant.toJsonString
import app.oreshkov.rabosh.variant.toJsonSummaryString
import app.oreshkov.rabosh.variant.toSummaryString
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import java.util.concurrent.TimeUnit

/**
 * The codec: parsing into the encoding, and reading back out of it.
 *
 * Two of these settle questions the design deferred rather than merely reporting numbers.
 *
 * **`fieldByName` versus `selectPath`** is the one §4 left open: field lookup binary-searches the
 * dictionary comparing UTF-8 in place, and the note says a byte-keyed map would avoid the repeated
 * comparisons — *measure it before changing it*. This is that measurement.
 *
 * **`parse` versus `parseAndRead`** is what says whether reading one field of a document is really
 * cheaper than parsing it, which is the claim the whole encoding exists to make.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
open class VariantBenchmark {

    private lateinit var json: String
    private lateinit var jsonBytes: ByteArray
    private lateinit var document: Variant
    private lateinit var hugeDocument: Variant
    private lateinit var deepPath: VariantPath

    @Setup
    fun setUp() {
        json = Corpus.json(4_242)
        jsonBytes = json.toByteArray()
        document = Variant.fromJson(json)
        deepPath = VariantPath.parse("$.user.name")

        // Two thousand kilobyte strings: roughly two megabytes, and every child a scalar, so
        // nothing here is cheap by being a container the summary can decline to enter.
        val filler = "x".repeat(1_024)
        hugeDocument = VariantBuilder().apply {
            startObject()
            repeat(2_000) { index ->
                field("field${index.toString().padStart(4, '0')}")
                appendString(filler)
            }
            endObject()
        }.buildVariant()
    }

    /** JSON text to the encoding, which is what every write pays. */
    @Benchmark
    fun parse(hole: Blackhole) {
        hole.consume(Variant.fromJson(jsonBytes))
    }

    /** Parse, then read one field — the shape of an ingest that also indexes. */
    @Benchmark
    fun parseAndRead(hole: Blackhole) {
        hole.consume(Variant.fromJson(jsonBytes).field("team")?.stringValue())
    }

    /** One top-level field out of an encoded document: a bisect over field ids, no parse. */
    @Benchmark
    fun fieldByName(hole: Blackhole) {
        hole.consume(document.field("region")?.stringValue())
    }

    /** The last field of the object, which is the worst case for the bisect. */
    @Benchmark
    fun fieldByNameLast(hole: Blackhole) {
        hole.consume(document.field("user")?.fieldCount)
    }

    /** A nested path, which is two lookups and no allocation. */
    @Benchmark
    fun selectPath(hole: Blackhole) {
        hole.consume(document.select(deepPath)?.stringValue())
    }

    /** Rendering back to JSON: the cost a caller pays only when it asks for the whole document. */
    @Benchmark
    fun render(hole: Blackhole) {
        hole.consume(document.toJsonString())
    }

    /**
     * The same document described rather than rendered.
     *
     * Its pair is [render] above and [summariseHuge] below, and the pair is the measurement: a
     * summary claims to cost the same whatever the value holds, so the number worth reading is not
     * this one but its ratio to a document two thousand times larger. Reported against [render] on
     * the same document, it also says what the description buys over the rendering.
     */
    @Benchmark
    fun summarise(hole: Blackhole) {
        hole.consume(document.toSummaryString())
    }

    /** [summarise] over a two-megabyte document. Equal throughput is the whole claim. */
    @Benchmark
    fun summariseHuge(hole: Blackhole) {
        hole.consume(hugeDocument.toSummaryString())
    }

    /**
     * The top-level outline of that same two-megabyte document.
     *
     * Dearer than [summariseHuge] by the eight children it shows and no more — none of them is
     * decoded, because each is past the byte gate. `render` on this document is deliberately not a
     * benchmark: it is the thing that is too expensive to do, which is why these exist.
     */
    @Benchmark
    fun outlineHuge(hole: Blackhole) {
        hole.consume(hugeDocument.toJsonSummaryString())
    }

    /** Walking every field, which is what a sketch or an index build does per document. */
    @Benchmark
    fun walkAllFields(hole: Blackhole) {
        var total = 0
        for (index in 0 until document.fieldCount) {
            total += document.fieldName(index).length + document.fieldValue(index).byteSize.toInt()
        }
        hole.consume(total)
    }
}
