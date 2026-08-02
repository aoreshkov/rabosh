package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.testkit.property.forAll
import java.util.BitSet
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The four set operations, over every pairing of encodings and of storage.
 *
 * An operation between two blocks dispatches on what each of them is, and a bitmap read from a file
 * dispatches differently again — so "and works" is nine claims about encodings times four about which
 * side is mapped. Each is cheap to state and none of them is implied by the others, which is why they are
 * enumerated rather than sampled.
 *
 * The reference is `java.util.BitSet` throughout. Its own `and`, `or`, `andNot` and `xor` are the
 * definition being checked against, so a disagreement is unambiguous.
 */
class BitmapOperationTest {

    /** One representative bitmap per encoding, all inside the same block so the pairings actually meet. */
    private val samples: Map<String, List<Int>> = mapOf(
        "array" to scatteredOrdinals(100),
        "bitset" to scatteredOrdinals(6_000),
        "run" to (0..20_000).toList(),
    )

    @Test
    fun `every operation agrees with a BitSet across all encoding pairings`() {
        for ((leftName, left) in samples) {
            for ((rightName, right) in samples) {
                for (leftMapped in listOf(false, true)) {
                    for (rightMapped in listOf(false, true)) {
                        val a = source(left, leftMapped)
                        val b = source(right, rightMapped)
                        val note = "$leftName${if (leftMapped) " (mapped)" else ""} vs " +
                            "$rightName${if (rightMapped) " (mapped)" else ""}"

                        assertMatches(model(left) { it.and(bitSetOf(right)) }, a.and(b), "and: $note")
                        assertMatches(model(left) { it.or(bitSetOf(right)) }, a.or(b), "or: $note")
                        assertMatches(model(left) { it.andNot(bitSetOf(right)) }, a.andNot(b), "andNot: $note")
                        assertMatches(model(left) { it.xor(bitSetOf(right)) }, a.xor(b), "xor: $note")
                    }
                }
            }
        }
    }

    /** The same four, over generated data rather than three fixed shapes. */
    @Test
    fun `every operation agrees with a BitSet on generated data`() {
        forAll(IndexGens.ordinals, IndexGens.ordinals) { left, right ->
            val a = bitmapOf(left)
            val b = bitmapOf(right)

            assertMatches(bitSetOf(left).also { it.and(bitSetOf(right)) }, a.and(b), "and")
            assertMatches(bitSetOf(left).also { it.or(bitSetOf(right)) }, a.or(b), "or")
            assertMatches(bitSetOf(left).also { it.andNot(bitSetOf(right)) }, a.andNot(b), "andNot")
            assertMatches(bitSetOf(left).also { it.xor(bitSetOf(right)) }, a.xor(b), "xor")

            // The mapped operands answer the same, which is what the shared block algorithms buy.
            val mapped = BitmapView.open(b.encode(), "operand.idx")
            assertEquals(a.and(b), a.and(mapped), "a mapped operand changed an intersection")
            assertEquals(a.or(b), a.or(mapped), "a mapped operand changed a union")
            assertEquals(a.andNot(b), a.andNot(mapped), "a mapped operand changed a difference")
            assertEquals(a.xor(b), a.xor(mapped), "a mapped operand changed a symmetric difference")
        }
    }

    /** In-place and pure forms are the same operation. */
    @Test
    fun `the in-place operations agree with the pure ones`() {
        forAll(IndexGens.ordinals, IndexGens.ordinals) { left, right ->
            val other = bitmapOf(right)
            assertEquals(bitmapOf(left).and(other), bitmapOf(left).also { it.andWith(other) })
            assertEquals(bitmapOf(left).or(other), bitmapOf(left).also { it.orWith(other) })
            assertEquals(bitmapOf(left).andNot(other), bitmapOf(left).also { it.andNotWith(other) })
            assertEquals(bitmapOf(left).xor(other), bitmapOf(left).also { it.xorWith(other) })
        }
    }

    /**
     * An operand is never modified by an operation on it.
     *
     * Worth asserting rather than reading off the code: `materialise` hands back the receiver itself for a
     * heap block, so an implementation that mutated what it was given would corrupt the bitmap it was
     * reading — and would do it silently, because the *result* would still be right.
     */
    @Test
    fun `an operation leaves both operands alone`() {
        forAll(IndexGens.ordinals, IndexGens.ordinals) { left, right ->
            val a = bitmapOf(left)
            val b = bitmapOf(right)
            val beforeA = a.toIntArray()
            val beforeB = b.toIntArray()
            a.and(b)
            a.or(b)
            a.andNot(b)
            a.xor(b)
            assertContentEquals(beforeA, a.toIntArray(), "the left operand changed")
            assertContentEquals(beforeB, b.toIntArray(), "the right operand changed")
        }
    }

    /**
     * A union or an intersection over many bitmaps is the fold of the pairwise one.
     *
     * `IN (a, b, c)` over three sidecars goes through these, and they exist to avoid allocating a bitmap
     * per step — so the thing to prove is that the shortcut did not change the answer.
     */
    @Test
    fun `union and intersection agree with folding`() {
        forAll(IndexGens.ordinals, IndexGens.ordinals) { left, right ->
            val third = left.filter { it % 3 == 0 }
            val bitmaps = listOf(bitmapOf(left), bitmapOf(right), bitmapOf(third))

            val expectedUnion = bitSetOf(left).also {
                it.or(bitSetOf(right))
                it.or(bitSetOf(third))
            }
            assertMatches(expectedUnion, Bitmap.union(bitmaps), "union")

            val expectedIntersection = bitSetOf(left).also {
                it.and(bitSetOf(right))
                it.and(bitSetOf(third))
            }
            assertMatches(expectedIntersection, Bitmap.intersection(bitmaps), "intersection")
        }
    }

    @Test
    fun `union and intersection of nothing are empty`() {
        assertEquals(Bitmap(), Bitmap.union(emptyList()))
        assertEquals(Bitmap(), Bitmap.intersection(emptyList()))
    }

    /**
     * The two questions a planner asks, against the answer it would have got by building the intersection.
     *
     * These skip the allocation, so they are the two operations most likely to drift away from the
     * operation they are shortcuts for.
     */
    @Test
    fun `intersects and andCardinality agree with a materialised intersection`() {
        forAll(IndexGens.ordinals, IndexGens.ordinals) { left, right ->
            val a = bitmapOf(left)
            val b = bitmapOf(right)
            val intersection = a.and(b)
            assertEquals(!intersection.isEmpty, a.intersects(b), "intersects")
            assertEquals(intersection.cardinality, a.andCardinality(b), "andCardinality")

            // And through the mapping, which takes the word-wise path for two dense blocks.
            val mappedA = BitmapView.open(a.encode(), "a.idx")
            val mappedB = BitmapView.open(b.encode(), "b.idx")
            assertEquals(!intersection.isEmpty, mappedA.intersects(mappedB), "mapped intersects")
            assertEquals(intersection.cardinality, mappedA.andCardinality(mappedB), "mapped andCardinality")
        }
    }

    /** The laws, which catch a mistake symmetric enough to survive a comparison against a model. */
    @Test
    fun `the set operations obey their algebra`() {
        forAll(IndexGens.ordinals, IndexGens.ordinals) { left, right ->
            val a = bitmapOf(left)
            val b = bitmapOf(right)

            assertEquals(a.and(b), b.and(a), "and is commutative")
            assertEquals(a.or(b), b.or(a), "or is commutative")
            assertEquals(a.xor(b), b.xor(a), "xor is commutative")

            assertEquals(Bitmap(), a.andNot(a), "a bitmap minus itself is empty")
            assertEquals(Bitmap(), a.xor(a), "a bitmap exclusive-or itself is empty")
            assertEquals(a, a.and(a), "a bitmap intersected with itself is itself")
            assertEquals(a, a.or(a), "a bitmap united with itself is itself")

            assertEquals(a.andNot(b), a.and(a.xor(b)), "difference through symmetric difference")
            assertEquals(a.or(b), a.xor(b).or(a.and(b)), "union splits into difference and intersection")

            // De Morgan, inside a universe the caller supplies — which is also how a complement is
            // spelled. The identity holds for any universe, so a small one that still crosses a block
            // boundary says as much as the whole ordinal space and costs a fraction of it.
            val universe = Bitmap.ofRange(0..70_000)
            val notA = universe.andNot(a)
            val notB = universe.andNot(b)
            assertEquals(universe.andNot(a.or(b)), notA.and(notB), "not (a or b) == not a and not b")
            assertEquals(universe.andNot(a.and(b)), notA.or(notB), "not (a and b) == not a or not b")
        }
    }

    /** An intersection of bitmaps that share no block never opens one. */
    @Test
    fun `bitmaps in different blocks do not intersect`() {
        val low = Bitmap.ofRange(0..1_000)
        val high = Bitmap.ofRange(131_072..132_000)
        assertFalse(low.intersects(high))
        assertEquals(0, low.andCardinality(high))
        assertTrue(low.and(high).isEmpty)
        assertEquals(low.cardinality + high.cardinality, low.or(high).cardinality)
    }

    private fun source(ordinals: List<Int>, mapped: Boolean): ReadableBitmap {
        val bitmap = bitmapOf(ordinals)
        return if (mapped) BitmapView.open(bitmap.encode(), "sample.idx") else bitmap
    }

    /** The model's answer: [left] as a `BitSet`, with [operation] applied to it. */
    private fun model(left: List<Int>, operation: (BitSet) -> Unit): BitSet = bitSetOf(left).also(operation)
}
