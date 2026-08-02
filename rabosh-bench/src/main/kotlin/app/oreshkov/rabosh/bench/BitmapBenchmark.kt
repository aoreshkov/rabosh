package app.oreshkov.rabosh.bench

import app.oreshkov.rabosh.index.Bitmap
import app.oreshkov.rabosh.index.BitmapView
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * The bitmap, across the three container kinds and the two ways of reading one.
 *
 * **`viewAnd` against `heapAnd` is the claim phase 6 made and never measured**: owning the format
 * lets a sidecar be read straight off a mapping with no deserialization step, where a library bitmap
 * would parse into heap objects on every open. If the two are close, the argument for the in-repo
 * bitmap is weaker than it was written down as; if the view wins by the margin the design assumes,
 * that is the dividend, stated in numbers.
 *
 * The `density` parameter selects the container: sparse enough is an array, dense enough is a bitset,
 * and contiguous is a run. Container *selection* is decided by encoded size rather than by
 * measurement — an open question §9.6 left for exactly this suite — so the three are measured apart.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
open class BitmapBenchmark {

    /** `array`: a few hundred scattered. `bitset`: a third of the space. `run`: contiguous. */
    @Param("array", "bitset", "run")
    var density: String = "array"

    private val universe = 1 shl 20

    private lateinit var left: Bitmap
    private lateinit var right: Bitmap
    private lateinit var encoded: ByteArray
    private lateinit var view: BitmapView
    private var cardinality = 0

    @Setup
    fun setUp() {
        left = build(seed = 1)
        right = build(seed = 2)
        encoded = left.encode()
        view = BitmapView.open(encoded, "benchmark")
        cardinality = left.cardinality
    }

    private fun build(seed: Int): Bitmap {
        val random = Random(seed)
        val bitmap = Bitmap()
        when (density) {
            "array" -> repeat(2_000) { bitmap.add(random.nextInt(universe)) }
            "bitset" -> repeat(universe / 3) { bitmap.add(random.nextInt(universe)) }
            else -> {
                var start = 0
                while (start < universe) {
                    bitmap.addAll(start until start + 4_096)
                    start += 8_192
                }
            }
        }
        return bitmap
    }

    /** Intersection on the heap: the phase-8 planner's inner operation. */
    @Benchmark
    fun heapAnd(hole: Blackhole) {
        hole.consume(left.and(right).cardinality)
    }

    /** The same intersection with one side read off the encoded bytes, with no parse. */
    @Benchmark
    fun viewAnd(hole: Blackhole) {
        hole.consume(view.and(right).cardinality)
    }

    /** Union, which is `IN` and a disjunction. */
    @Benchmark
    fun heapOr(hole: Blackhole) {
        hole.consume(left.or(right).cardinality)
    }

    /** Complement, which is `NOT EXISTS` and a negated leaf. */
    @Benchmark
    fun heapAndNot(hole: Blackhole) {
        hole.consume(left.andNot(right).cardinality)
    }

    /**
     * "Do these overlap" without building the answer — what a planner asks before deciding to read
     * a posting list at all.
     */
    @Benchmark
    fun intersects(hole: Blackhole) {
        hole.consume(left.intersects(right))
    }

    @Benchmark
    fun andCardinality(hole: Blackhole) {
        hole.consume(left.andCardinality(right))
    }

    /** Opening a mapped bitmap: the operation that is supposed to be free. */
    @Benchmark
    fun openView(hole: Blackhole) {
        hole.consume(BitmapView.open(encoded, "benchmark").cardinality)
    }

    /** Parsing one into heap containers: what opening would cost if the format were not ours. */
    @Benchmark
    fun decodeToHeap(hole: Blackhole) {
        hole.consume(BitmapView.open(encoded, "benchmark").toBitmap().cardinality)
    }

    /** Iterating every ordinal, which is what decoding candidates to keys walks. */
    @Benchmark
    fun iterateView(hole: Blackhole): Int {
        val cursor = view.cursor()
        var total = 0
        while (cursor.next()) total += cursor.value
        hole.consume(total)
        return total
    }

    @Benchmark
    fun rankAndSelect(hole: Blackhole) {
        hole.consume(view.rank(universe / 2))
        hole.consume(view.select(cardinality / 2))
    }

    @Benchmark
    fun encode(hole: Blackhole) {
        hole.consume(left.encode().size)
    }
}
