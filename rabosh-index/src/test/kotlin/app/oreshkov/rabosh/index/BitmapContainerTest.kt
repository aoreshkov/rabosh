package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.testkit.property.forAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Container transitions, at every boundary rather than near one.
 *
 * The whole reason a compressed bitmap is not just a bit set is that each block picks an encoding, and
 * every one of those decisions is a threshold. A threshold tested at "well below" and "well above" is
 * not tested: the bugs live at *exactly* the value, and at the value one either side of it, so each case
 * here pins all three.
 *
 * The encoding is read back out of the serialized directory rather than asked of the object, because the
 * encoding that matters is the one that reaches a file. See [encodedKinds].
 */
class BitmapContainerTest {

    /**
     * The array/bitset threshold: 4096 values, where the two encodings cost exactly the same.
     *
     * The tie goes to the array, which is what [BitmapFormat.smallestKind] decides by considering
     * candidates in ascending kind order. Values are scattered so that a run encoding cannot interfere —
     * with no two consecutive, a run list costs `4 + 4n` and never wins, which isolates the boundary
     * under test.
     */
    @Test
    fun `an array becomes a bitset one value past the tie`() {
        for ((count, expected) in listOf(
            BitmapFormat.ARRAY_MAX_CARDINALITY - 1 to BitmapFormat.KIND_ARRAY,
            BitmapFormat.ARRAY_MAX_CARDINALITY to BitmapFormat.KIND_ARRAY,
            BitmapFormat.ARRAY_MAX_CARDINALITY + 1 to BitmapFormat.KIND_BITSET,
        )) {
            val bitmap = bitmapOf(scatteredOrdinals(count))
            assertEquals(count, bitmap.cardinality, "$count scattered ordinals")
            assertEquals(
                listOf(expected),
                encodedKinds(bitmap),
                "$count scattered ordinals chose the wrong encoding",
            )
            assertMatches(bitSetOf(scatteredOrdinals(count)), bitmap, "$count scattered ordinals")
        }
    }

    /**
     * And back down again, which is the transition a library that only ever promotes gets wrong.
     *
     * An intersection or a difference can leave a block far sparser than it was, and a bitset that stayed
     * one would cost 8 KB for a handful of ordinals in every sidecar written from then on.
     */
    @Test
    fun `a bitset becomes an array again when it thins out`() {
        val bitmap = bitmapOf(scatteredOrdinals(BitmapFormat.ARRAY_MAX_CARDINALITY + 1))
        assertEquals(listOf(BitmapFormat.KIND_BITSET), encodedKinds(bitmap))

        bitmap.remove(0)
        assertEquals(BitmapFormat.ARRAY_MAX_CARDINALITY, bitmap.cardinality)
        assertEquals(
            listOf(BitmapFormat.KIND_ARRAY),
            encodedKinds(bitmap),
            "a block that thinned back to the tie stayed a bitset",
        )
    }

    /**
     * The run threshold: a run of four values is an array, a run of five is a run list.
     *
     * `4 + 4 * 1 = 8` bytes for one run against `2n` for an array, so they tie at four values and the run
     * encoding wins from five. Exactly the arithmetic [BitmapFormat.smallestKind] applies, asserted from
     * outside it.
     */
    @Test
    fun `a run encoding is adopted the moment it is smaller`() {
        assertEquals(listOf(BitmapFormat.KIND_ARRAY), encodedKinds(Bitmap.ofRange(0..3)), "four consecutive")
        assertEquals(listOf(BitmapFormat.KIND_RUN), encodedKinds(Bitmap.ofRange(0..4)), "five consecutive")
    }

    /** A whole block is one run, and the only case `lengthMinusOne` exists for. */
    @Test
    fun `a block covered end to end is a single run`() {
        val bitmap = Bitmap.ofRange(0 until BitmapFormat.CONTAINER_VALUES)
        assertEquals(BitmapFormat.CONTAINER_VALUES, bitmap.cardinality)
        assertEquals(listOf(BitmapFormat.KIND_RUN), encodedKinds(bitmap))
        assertEquals(
            BitmapFormat.HEADER_BYTES + BitmapFormat.ENTRY_BYTES + BitmapFormat.runBytes(1),
            bitmap.encode().size,
            "a full block should cost one run",
        )
        assertEquals(0, bitmap.first())
        assertEquals(BitmapFormat.CONTAINER_VALUES - 1, bitmap.last())
    }

    /**
     * And abandoned again once the runs outgrow the alternatives.
     *
     * Removing every other ordinal from a long run turns one run into thousands, which is the point at
     * which a run list is the *worst* of the three encodings rather than the best.
     */
    @Test
    fun `a run encoding is abandoned when the runs multiply`() {
        val bitmap = Bitmap.ofRange(0..8000)
        assertEquals(listOf(BitmapFormat.KIND_RUN), encodedKinds(bitmap))

        for (ordinal in 1..8000 step 2) bitmap.remove(ordinal)
        assertEquals(4001, bitmap.cardinality)
        assertEquals(
            listOf(BitmapFormat.KIND_ARRAY),
            encodedKinds(bitmap),
            "4001 scattered ordinals are cheapest as an array",
        )
        assertMatches(bitSetOf(0..8000 step 2), bitmap, "every other ordinal removed from a run")
    }

    /** Mutating a run block expands only that block, and the rest of the bitmap keeps its runs. */
    @Test
    fun `adding to one block leaves the others encoded as runs`() {
        val bitmap = Bitmap.ofRange(0..200_000)
        assertTrue(encodedKinds(bitmap).all { it == BitmapFormat.KIND_RUN }, "a long range is all runs")

        bitmap.remove(100_000)
        val kinds = encodedKinds(bitmap)
        assertEquals(BitmapFormat.KIND_RUN, kinds[0], "the first block was not touched")
        assertEquals(BitmapFormat.KIND_RUN, kinds[1], "a block split into two runs is still cheapest as runs")
        assertEquals(200_001 - 1, bitmap.cardinality)
    }

    /** A range crossing a block boundary becomes two blocks, each holding its own half. */
    @Test
    fun `a range spanning a boundary splits into blocks`() {
        val bitmap = Bitmap.ofRange(65_530..65_540)
        assertEquals(listOf(0, 1), encodedKeys(bitmap), "the range should occupy two blocks")
        assertEquals(11, bitmap.cardinality)
        assertContentEquals((65_530..65_540).toList().toIntArray(), bitmap.toIntArray())
        assertMatches(bitSetOf(65_530..65_540), bitmap, "a range across a block boundary")
    }

    /** Blocks with nothing in them never reach the encoding, whatever emptied them. */
    @Test
    fun `an emptied block leaves the bitmap`() {
        val bitmap = Bitmap.of(5, 65_536 + 5, 131_072 + 5)
        assertEquals(listOf(0, 1, 2), encodedKeys(bitmap))

        bitmap.remove(65_536 + 5)
        assertEquals(listOf(0, 2), encodedKeys(bitmap), "the middle block should be gone, not empty")

        bitmap.removeAll(0..IndexGens.MAX_ORDINAL)
        assertEquals(emptyList(), encodedKeys(bitmap))
        assertEquals(BitmapFormat.HEADER_BYTES, bitmap.encode().size)
    }

    /**
     * Whatever the data, the encoding chosen is the smallest of the three.
     *
     * The property behind every case above. `verify` asserts it on the way in as well, so this is the
     * writer's half of the same claim — stated separately because a writer and a reader agreeing on a
     * wrong rule would satisfy neither.
     */
    @Test
    fun `every block is encoded as small as it can be`() {
        forAll(IndexGens.ordinals) { ordinals ->
            val bitmap = bitmapOf(ordinals)
            val encoded = bitmap.encode()
            val view = BitmapView.open(encoded, "smallest.idx")
            val source = view.source
            for (index in 0 until source.containerCount) {
                val block = source.containerAt(index)
                val expected = BitmapFormat.smallestKind(block.cardinality, block.runCount)
                assertEquals(
                    expected,
                    block.kind,
                    "block ${source.keyAt(index)} of ${block.cardinality} ordinal(s) in " +
                        "${block.runCount} run(s)",
                )
            }
        }
    }
}
