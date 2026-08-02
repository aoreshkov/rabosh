package app.oreshkov.rabosh.catalog

import app.oreshkov.rabosh.testkit.property.Gen
import app.oreshkov.rabosh.testkit.property.forAll
import app.oreshkov.rabosh.testkit.property.list
import app.oreshkov.rabosh.testkit.property.long
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The cardinality estimator.
 *
 * Three separate claims are made here and they are tested separately, because they fail in
 * different ways: it is **exact** below the sparse limit, it is **accurate within a stated bound**
 * above it, and its merge is **associative, commutative and order-independent down to the bytes**.
 * The last is the one the catalog's whole design rests on — the model of a store is a fold of its
 * segments, so a merge that depended on order would make the model depend on the compaction history.
 */
class HyperLogLogTest {

    @Test
    fun `below the sparse limit the count is exact`() {
        for (size in 0..HyperLogLog.SPARSE_LIMIT) {
            val sketch = HyperLogLog()
            repeat(size) { sketch.add("value-$it".encodeToByteArray()) }
            assertEquals(size.toLong(), sketch.estimate, "$size distinct values")
            assertTrue(sketch.isSparse, "$size values should still be held exactly")
        }
    }

    @Test
    fun `repeats do not count`() {
        val sketch = HyperLogLog()
        repeat(1000) { sketch.add("only-one".encodeToByteArray()) }
        assertEquals(1, sketch.estimate)
        assertTrue(sketch.isSparse)
    }

    @Test
    fun `one value past the sparse limit switches to registers`() {
        val sketch = HyperLogLog()
        repeat(HyperLogLog.SPARSE_LIMIT) { sketch.add("value-$it".encodeToByteArray()) }
        assertTrue(sketch.isSparse, "at the limit it is still exact")

        sketch.add("one-more".encodeToByteArray())
        assertFalse(sketch.isSparse, "one past the limit it is not")
        // Not exact any more, but it must not be wildly wrong at a cardinality this small either:
        // this is the range where linear counting is doing the work.
        assertTrue(abs(sketch.estimate - 97) <= 5, "estimate ${sketch.estimate} near 97")
    }

    @Test
    fun `the estimate stays within three standard errors at scale`() {
        // The standard error of HyperLogLog is 1.04 / sqrt(m). Three of them is the bound a
        // deterministic test can assert without being flaky, and a regression that mattered — a
        // broken rank, a biased hash — misses it by an order of magnitude, not by a hair.
        val bound = 3 * 1.04 / kotlin.math.sqrt(HyperLogLog.REGISTER_COUNT.toDouble())
        for (size in listOf(1_000, 10_000, 100_000)) {
            val sketch = HyperLogLog()
            repeat(size) { sketch.add("value-$it".encodeToByteArray()) }
            val error = abs(sketch.estimate - size).toDouble() / size
            assertTrue(error <= bound, "$size distinct: estimated ${sketch.estimate}, error $error > $bound")
        }
    }

    @Test
    fun `a merge is the same sketch as adding everything`() {
        val left = HyperLogLog()
        val right = HyperLogLog()
        val together = HyperLogLog()
        repeat(5_000) {
            val value = "value-$it".encodeToByteArray()
            if (it % 2 == 0) left.add(value) else right.add(value)
            together.add(value)
        }
        assertEquals(together, left.mergedWith(right), "merging halves equals sketching the whole")
    }

    @Test
    fun `merge is commutative, associative and idempotent`() {
        forAll(Gen.list(Gen.long(), sizes = 0..40), Gen.list(Gen.long(), sizes = 0..40)) { first, second ->
            val a = sketchOf(first)
            val b = sketchOf(second)
            assertEquals(a.mergedWith(b), b.mergedWith(a), "commutative")
            assertEquals(a, a.mergedWith(a), "idempotent")
            assertEquals(a, a.mergedWith(HyperLogLog()), "empty is the identity")
        }
    }

    @Test
    fun `the fold order does not change a single byte`() {
        // The strong form of the claim: representation is a function of the union, never of the path
        // taken to it. That holds because a sparse sketch overflows exactly when the union does, and
        // unions are monotone. It is worth asserting at a size that straddles the sparse limit in
        // some orders and not others.
        forAll(
            Gen.list(Gen.list(Gen.long(-200L..200L), sizes = 0..40), sizes = 1..6),
        ) { groups ->
            if (groups.isEmpty()) return@forAll
            val sketches = groups.map(::sketchOf)
            val leftFold = sketches.reduce { acc, next -> acc.mergedWith(next) }
            val rightFold = sketches.reversed().reduce { acc, next -> acc.mergedWith(next) }
            val direct = sketchOf(groups.flatten())
            assertEquals(direct, leftFold, "left fold")
            assertEquals(direct, rightFold, "right fold")
        }
    }

    @Test
    fun `a copy is independent`() {
        val original = HyperLogLog()
        repeat(10) { original.add(it.toLong()) }
        val copy = original.copy()
        original.add(999L)
        assertEquals(10, copy.estimate, "the copy did not follow the original")
        assertEquals(11, original.estimate)
    }

    @Test
    fun `sparse and dense both survive a roundtrip`() {
        for (size in listOf(0, 1, HyperLogLog.SPARSE_LIMIT, HyperLogLog.SPARSE_LIMIT + 1, 20_000)) {
            val sketch = HyperLogLog()
            repeat(size) { sketch.add("value-$it".encodeToByteArray()) }
            assertEquals(sketch, roundtrip(sketch), "$size distinct values")
        }
    }

    @Test
    fun `an unknown mode is reported rather than defaulted`() {
        val out = SketchWriter()
        out.writeByte(99)
        out.writeByte(SketchFormat.HLL_PRECISION)
        val failure = assertFailsWith<CorruptSketchException> {
            HyperLogLog.readFrom(SketchReader(out.toByteArray(), "test.cat", 0))
        }
        assertTrue(failure.message!!.contains("99"), failure.message!!)
    }

    @Test
    fun `a different precision is a format failure, not corruption`() {
        val out = SketchWriter()
        out.writeByte(SketchFormat.HLL_DENSE)
        out.writeByte(SketchFormat.HLL_PRECISION + 1)
        assertFailsWith<UnsupportedSketchFormatException> {
            HyperLogLog.readFrom(SketchReader(out.toByteArray(), "test.cat", 0))
        }
    }

    @Test
    fun `sparse entries out of order are corruption`() {
        // Sorted-and-distinct is load-bearing: `add` binary-searches and `equals` compares
        // element-wise, so a file that broke it would make two sketches of the same data differ.
        val out = SketchWriter()
        out.writeByte(SketchFormat.HLL_SPARSE)
        out.writeByte(SketchFormat.HLL_PRECISION)
        out.writeInt(2)
        out.writeLong(20)
        out.writeLong(10)
        assertFailsWith<CorruptSketchException> {
            HyperLogLog.readFrom(SketchReader(out.toByteArray(), "test.cat", 0))
        }
    }

    private fun sketchOf(values: List<Long>): HyperLogLog =
        HyperLogLog().also { sketch -> values.forEach(sketch::add) }

    private fun roundtrip(sketch: HyperLogLog): HyperLogLog {
        val out = SketchWriter()
        sketch.writeTo(out)
        return HyperLogLog.readFrom(SketchReader(out.toByteArray(), "test.cat", 0))
    }
}
