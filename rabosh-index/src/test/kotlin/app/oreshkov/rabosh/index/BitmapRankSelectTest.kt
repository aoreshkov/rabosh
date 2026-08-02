package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.testkit.property.forAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `rank`, `select` and the cursor's leapfrog.
 *
 * These are the operations phase 8 uses to turn a bitmap position into a document and back, and they are
 * the ones the exclusive prefix cardinalities in the directory exist for. The reference is
 * `java.util.BitSet` again — `rank` is what its `cardinality` of a prefix says, and `select` is its nth
 * set bit — so the assertions are about agreement with the JDK rather than with the implementation's own
 * arithmetic.
 */
class BitmapRankSelectTest {

    @Test
    fun `rank agrees with a BitSet prefix count`() {
        forAll(IndexGens.ordinals) { ordinals ->
            val bitmap = bitmapOf(ordinals)
            val view = BitmapView.open(bitmap.encode(), "rank.idx")
            val model = bitSetOf(ordinals)
            for (ordinal in rankProbes(ordinals)) {
                val expected = model.get(0, ordinal + 1).cardinality()
                assertEquals(expected, bitmap.rank(ordinal), "rank($ordinal)")
                assertEquals(expected, view.rank(ordinal), "rank($ordinal) through a mapping")
            }
        }
    }

    @Test
    fun `select agrees with the nth set bit`() {
        forAll(IndexGens.ordinals) { ordinals ->
            val expected = bitSetOf(ordinals).ordinals()
            val bitmap = bitmapOf(ordinals)
            val view = BitmapView.open(bitmap.encode(), "select.idx")
            for (index in expected.indices) {
                assertEquals(expected[index], bitmap.select(index), "select($index)")
                assertEquals(expected[index], view.select(index), "select($index) through a mapping")
            }
        }
    }

    /** `rank` and `select` are inverses at every present ordinal, whichever encoding the block is in. */
    @Test
    fun `rank and select invert each other`() {
        forAll(IndexGens.ordinals) { ordinals ->
            val bitmap = bitmapOf(ordinals)
            for (index in 0 until bitmap.cardinality) {
                val ordinal = bitmap.select(index)
                assertEquals(index + 1, bitmap.rank(ordinal), "rank(select($index))")
            }
        }
    }

    /**
     * `select` past the end is reported, never clamped.
     *
     * "The last ordinal" and "there is no such ordinal" are different answers, and a caller that
     * conflated them would go on to read a document that is not in the result.
     */
    @Test
    fun `select outside the bitmap is reported`() {
        val bitmap = Bitmap.of(3, 70_000)
        assertFailsWith<IndexOutOfBoundsException> { bitmap.select(2) }
        assertFailsWith<IndexOutOfBoundsException> { bitmap.select(-1) }
        assertFailsWith<IndexOutOfBoundsException> { Bitmap().select(0) }
        assertFailsWith<IndexOutOfBoundsException> {
            BitmapView.open(bitmap.encode(), "select.idx").select(2)
        }
    }

    @Test
    fun `rank of an ordinal below the first is zero and of one above the last is the cardinality`() {
        val bitmap = Bitmap.of(100, 70_000, 200_000)
        assertEquals(0, bitmap.rank(0))
        assertEquals(0, bitmap.rank(99))
        assertEquals(1, bitmap.rank(100))
        assertEquals(1, bitmap.rank(69_999))
        assertEquals(2, bitmap.rank(65_536 * 2))
        assertEquals(3, bitmap.rank(BitmapFormat.MAX_ORDINAL))
        assertFailsWith<IllegalArgumentException> { bitmap.rank(-1) }
    }

    /**
     * A cursor walking a bitmap yields exactly what it holds, and `advanceTo` skips to the same places a
     * scan would reach.
     */
    @Test
    fun `advanceTo lands where a scan would`() {
        forAll(IndexGens.ordinals) { ordinals ->
            val expected = bitSetOf(ordinals).ordinals()
            for (bitmap in listOf<ReadableBitmap>(bitmapOf(ordinals), BitmapView.open(bitmapOf(ordinals).encode()))) {
                for (target in rankProbes(ordinals)) {
                    val cursor = bitmap.cursor()
                    val landed = cursor.advanceTo(target)
                    val scanned = expected.firstOrNull { it >= target }
                    if (scanned == null) {
                        assertFalse(landed, "advanceTo($target) found something a scan did not")
                    } else {
                        assertTrue(landed, "advanceTo($target) found nothing; a scan found $scanned")
                        assertEquals(scanned, cursor.value, "advanceTo($target)")
                    }
                }
            }
        }
    }

    /** A cursor never moves backwards, which is what a leapfrog join depends on. */
    @Test
    fun `advanceTo does not move a cursor backwards`() {
        val bitmap = Bitmap.ofRange(0..5).also { it.addAll(70_000..70_005) }
        val cursor = bitmap.cursor()
        assertTrue(cursor.advanceTo(3))
        assertEquals(3, cursor.value)
        assertTrue(cursor.advanceTo(0), "asking again for a value already passed must not rewind")
        assertEquals(3, cursor.value)
        assertTrue(cursor.advanceTo(3), "asking for the current value must stay put")
        assertEquals(3, cursor.value)
        assertTrue(cursor.next())
        assertEquals(4, cursor.value)
        assertTrue(cursor.advanceTo(70_002))
        assertEquals(70_002, cursor.value)
        assertFalse(cursor.advanceTo(200_000))
        assertFalse(cursor.next(), "an exhausted cursor stays exhausted")
    }

    /**
     * The leapfrog itself: intersecting two bitmaps by jumping rather than merging gives the same answer.
     *
     * This is the algorithm phase 8's planner will run over sidecars, so getting `advanceTo` exactly
     * right matters more than it looks — an implementation that skipped one value too far would silently
     * lose documents from a result.
     */
    @Test
    fun `a leapfrog intersection agrees with and`() {
        forAll(IndexGens.ordinals, IndexGens.ordinals) { left, right ->
            val a = bitmapOf(left)
            val b = BitmapView.open(bitmapOf(right).encode(), "leapfrog.idx")
            val found = mutableListOf<Int>()
            val ours = a.cursor()
            val theirs = b.cursor()
            while (ours.next()) {
                if (!theirs.advanceTo(ours.value)) break
                if (theirs.value == ours.value) found += ours.value
            }
            assertEquals(a.and(b).toIntArray().toList(), found, "a leapfrog intersection")
        }
    }

    /** Ordinals worth asking about: every present one, its neighbours, and the block boundaries. */
    private fun rankProbes(ordinals: List<Int>): List<Int> = buildList {
        for (ordinal in ordinals.take(48)) {
            add(ordinal)
            if (ordinal > 0) add(ordinal - 1)
            add(ordinal + 1)
        }
        for (key in 0..(IndexGens.MAX_ORDINAL ushr 16) + 1) {
            add(key * BitmapFormat.CONTAINER_VALUES)
            if (key > 0) add(key * BitmapFormat.CONTAINER_VALUES - 1)
        }
        add(0)
        add(IndexGens.MAX_ORDINAL)
    }.filter { it in 0..BitmapFormat.MAX_ORDINAL }.distinct().sorted()
}
