package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.testkit.property.forAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * The canonical-form property: **equal ordinals encode to identical bytes**.
 *
 * The strongest statement this format makes, and the reason [BitmapFormat.smallestKind] is a total
 * function with an explicit tie-break rather than a heuristic. It is the bitmap's counterpart to the
 * catalog's property that a `HyperLogLog` merge is byte-identical whatever order it folds in, and it is
 * asserted the same way — equality, not approximate agreement.
 *
 * Two things follow from it, and both are used elsewhere in the suite:
 *
 * - comparing encodings is comparing contents, so a roundtrip test can assert bytes rather than values;
 * - a sidecar written by a flush and the same sidecar rebuilt by a compaction are the same file, so phase
 *   7 can compare them without knowing how either was produced.
 */
class BitmapCanonicalTest {

    /**
     * The same ordinals reached five different ways.
     *
     * Ascending and descending insertion exercise different container growth; the union of two halves goes
     * through the algebra instead; the difference from a superset arrives by `andNot`, which leaves blocks
     * in whatever encoding the subtraction produced. All five must agree to the byte.
     */
    @Test
    fun `the encoding does not depend on how the bitmap was built`() {
        forAll(IndexGens.ordinals) { ordinals ->
            val expected = bitmapOf(ordinals).encode()

            assertContentEquals(expected, bitmapOf(ordinals.reversed()).encode(), "built descending")
            assertContentEquals(expected, bitmapOf(ordinals.shuffled(java.util.Random(7))).encode(), "shuffled")

            val halves = ordinals.withIndex().partition { it.index % 2 == 0 }
            val union = bitmapOf(halves.first.map { it.value })
            union.orWith(bitmapOf(halves.second.map { it.value }))
            assertContentEquals(expected, union.encode(), "built as a union of two halves")

            val superset = bitmapOf(ordinals)
            superset.addAll(0..40)
            superset.andNotWith(bitmapOf((0..40).filter { it !in ordinals }))
            assertContentEquals(expected, superset.encode(), "built by subtraction from a superset")

            // And a view of the encoding re-encodes to itself, so the rule holds on the way back too.
            assertContentEquals(expected, BitmapView.open(expected, "canonical.idx").encode(), "re-encoded")
        }
    }

    /**
     * A range built as a range and the same range built one ordinal at a time are the same file.
     *
     * The case most likely to break the property, because the two paths reach a block through completely
     * different code: `addAll` creates a run list directly, while repeated `add` builds an array and then
     * promotes it to a bitset. Only `normalise` makes them agree.
     */
    @Test
    fun `a range encodes the same however it is built`() {
        for (range in listOf(0..3, 0..4, 0..9, 0..4_095, 0..4_096, 0..70_000, 65_530..65_540)) {
            val byRange = Bitmap.ofRange(range).encode()
            val byOrdinal = bitmapOf(range).encode()
            assertContentEquals(byRange, byOrdinal, "the range $range")
        }
    }

    /** Equality and `hashCode` follow the ordinals, not the representation. */
    @Test
    fun `bitmaps holding the same ordinals are equal whatever their encodings`() {
        forAll(IndexGens.ordinals) { ordinals ->
            val heap = bitmapOf(ordinals)
            val view = BitmapView.open(heap.encode(), "equal.idx")
            val rebuilt = view.toBitmap()

            assertSameBitmap(heap, view, "a heap bitmap against a mapped one")
            assertSameBitmap(heap, rebuilt, "a heap bitmap against one rebuilt from a mapping")
            assertContentEquals(heap.encode(), rebuilt.encode())
        }
    }

    /** A bitmap is never equal to one holding different ordinals, however close. */
    @Test
    fun `one ordinal of difference is a difference`() {
        val bitmap = Bitmap.ofRange(0..999)
        assertEquals(bitmap, Bitmap.ofRange(0..999))
        assertEquals(false, bitmap == Bitmap.ofRange(0..998))
        assertEquals(false, bitmap == Bitmap.ofRange(1..999))
        assertEquals(false, bitmap == Bitmap.ofRange(0..1000))
        assertEquals(false, bitmap == Bitmap())
        assertEquals(false, Bitmap() == bitmap)
    }
}
