package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.testkit.property.forAll
import java.util.BitSet
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The phase's acceptance criterion: a bitmap and a `java.util.BitSet` under the same operations.
 *
 * Differential testing against a reference is the house rule — the parser against
 * `kotlinx-serialization`, the LSM against a `TreeMap`, and a bitmap against the JDK's own bit set. It
 * is the right reference precisely because it is *not* compressed: it has no containers, no encodings
 * and no boundaries, so it cannot make the same mistake in the same place.
 *
 * The comparison is made after **every** step rather than at the end of a script. A bitmap that goes
 * wrong on the fourth operation and right again on the sixth would pass an end-of-run comparison, and
 * that is exactly the shape a container-transition bug has.
 */
class BitmapModelTest {

    @Test
    fun `a bitmap matches a BitSet through any sequence of operations`() {
        forAll(IndexGens.script) { script ->
            val bitmap = Bitmap()
            val model = BitSet()
            assertMatches(model, bitmap, "before any operation")
            script.operations.forEachIndexed { index, operation ->
                apply(operation, bitmap, model)
                assertMatches(model, bitmap, "after step ${index + 1}, $operation")
            }
        }
    }

    /** The same, over the ordinal shapes rather than the operation scripts. */
    @Test
    fun `a bitmap built one ordinal at a time matches a BitSet`() {
        forAll(IndexGens.ordinals) { ordinals ->
            assertMatches(bitSetOf(ordinals), bitmapOf(ordinals))
        }
    }

    /** Removing every ordinal must leave an empty bitmap with no blocks, not empty blocks. */
    @Test
    fun `removing everything empties the bitmap`() {
        forAll(IndexGens.ordinals) { ordinals ->
            val bitmap = bitmapOf(ordinals)
            for (ordinal in ordinals.shuffled(java.util.Random(ordinals.size.toLong()))) {
                bitmap.remove(ordinal)
            }
            assertMatches(BitSet(), bitmap, "after removing every ordinal")
            assertEquals(BitmapFormat.HEADER_BYTES, bitmap.encode().size, "an empty bitmap is a header")
        }
    }

    private fun apply(operation: Operation, bitmap: Bitmap, model: BitSet) {
        when (operation) {
            is Operation.Add -> {
                val expected = !model.get(operation.ordinal)
                assertEquals(expected, bitmap.add(operation.ordinal), "add reported the wrong change")
                model.set(operation.ordinal)
            }

            is Operation.Remove -> {
                val expected = model.get(operation.ordinal)
                assertEquals(expected, bitmap.remove(operation.ordinal), "remove reported the wrong change")
                model.clear(operation.ordinal)
            }

            is Operation.AddRange -> {
                bitmap.addAll(operation.range)
                model.set(operation.range.first, operation.range.last + 1)
            }

            is Operation.RemoveRange -> {
                bitmap.removeAll(operation.range)
                model.clear(operation.range.first, operation.range.last + 1)
            }

            is Operation.And -> {
                bitmap.andWith(bitmapOf(operation.ordinals))
                model.and(bitSetOf(operation.ordinals))
            }

            is Operation.Or -> {
                bitmap.orWith(bitmapOf(operation.ordinals))
                model.or(bitSetOf(operation.ordinals))
            }

            is Operation.AndNot -> {
                bitmap.andNotWith(bitmapOf(operation.ordinals))
                model.andNot(bitSetOf(operation.ordinals))
            }

            is Operation.Xor -> {
                bitmap.xorWith(bitmapOf(operation.ordinals))
                model.xor(bitSetOf(operation.ordinals))
            }
        }
    }
}
