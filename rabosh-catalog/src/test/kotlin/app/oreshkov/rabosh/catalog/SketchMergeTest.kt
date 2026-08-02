package app.oreshkov.rabosh.catalog

import app.oreshkov.rabosh.testkit.json.JsonGens
import app.oreshkov.rabosh.testkit.json.JsonValue
import app.oreshkov.rabosh.testkit.property.Gen
import app.oreshkov.rabosh.testkit.property.forAll
import app.oreshkov.rabosh.testkit.property.list
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The phase's stated acceptance criterion: **sketch merge is associative and commutative.**
 *
 * It matters because the model of a store is the fold of its live segments' sketches. If the fold
 * depended on order, the answer would depend on which compactions happened to have run — so the same
 * data, loaded the same way, would model differently on two machines. These properties are what rule
 * that out.
 *
 * The claim is made precisely rather than generally. Below the path budget the merge is *exactly*
 * associative and commutative, and that is asserted on the sketches themselves rather than on a
 * summary of them. Above the budget it cannot be, because which paths survive truncation depends on
 * what has been seen so far; what survives there is **conservation**, and that is asserted
 * separately.
 */
class SketchMergeTest {

    private val documents: Gen<List<JsonValue.Obj>> = Gen.list(JsonGens.document(), sizes = 0..8)

    @Test
    fun `merge is commutative`() {
        forAll(documents, documents) { left, right ->
            val a = sketch(left)
            val b = sketch(right)
            assertEquals(a.merge(b), b.merge(a))
        }
    }

    @Test
    fun `merge is associative`() {
        forAll(Gen.list(documents, sizes = 3..3)) { groups ->
            val (a, b, c) = groups.map(::sketch)
            assertEquals(a.merge(b).merge(c), a.merge(b.merge(c)))
        }
    }

    @Test
    fun `the empty sketch is the identity`() {
        forAll(documents) { corpus ->
            val a = sketch(corpus)
            assertEquals(a, a.merge(SegmentSketch.EMPTY))
            assertEquals(a, SegmentSketch.EMPTY.merge(a))
        }
    }

    @Test
    fun `merging is the same as sketching the union`() {
        // The strongest form: splitting a corpus across segments and folding must give exactly what
        // sketching it in one go gives. This is what makes a compaction invisible to the model.
        forAll(documents, documents) { left, right ->
            assertEquals(sketch(left + right), sketch(left).merge(sketch(right)))
        }
    }

    @Test
    fun `documents and observations are conserved`() {
        forAll(documents, documents) { left, right ->
            val merged = sketch(left).merge(sketch(right))
            assertEquals((left.size + right.size).toLong(), merged.documentCount)
            val tracked = merged.entries().values.sumOf { it.observations }
            assertEquals(merged.observationCount, tracked + merged.droppedObservations)
        }
    }

    @Test
    fun `observations survive the path budget being exceeded`() {
        // Conservation is the property that still holds once truncation starts, and it is the one
        // every ratio in a report is computed from — so it is the one that must not depend on the
        // fold order.
        val wide = List(40) { document ->
            JsonValue.Obj((0 until 20).map { "field-${document * 20 + it}" to JsonValue.Bool(true) })
        }
        val tight = CatalogOptions(maxPaths = 8)
        val halves = wide.chunked(20).map { sketch(it, tight) }

        val forwards = halves[0].merge(halves[1], tight.maxPaths)
        val backwards = halves[1].merge(halves[0], tight.maxPaths)

        for (merged in listOf(forwards, backwards)) {
            assertTrue(merged.pathCount <= tight.maxPaths, "the budget is enforced")
            assertTrue(merged.estimatedDroppedPaths > 0, "and the overflow is reported")
            val tracked = merged.entries().values.sumOf { it.observations }
            assertEquals(merged.observationCount, tracked + merged.droppedObservations)
        }
        assertEquals(forwards.observationCount, backwards.observationCount)
        assertEquals(forwards.documentCount, backwards.documentCount)
    }

    @Test
    fun `a path sketch merge sums every counter`() {
        val left = sketch(listOf(flatObject("a" to "1", "b" to "\"x\"")))
        val right = sketch(listOf(flatObject("a" to "2", "b" to "\"y\"")))
        val merged = left.merge(right)

        val a = merged[catalogPathOf("a")]!!
        assertEquals(2, a.observations)
        assertEquals(2, a.distinctEstimate)
        val numeric = a.bounds.numeric!!
        assertEquals("1", numeric.min.toPlainString())
        assertEquals("2", numeric.max.toPlainString())

        val text = merged[catalogPathOf("b")]!!.bounds.text!!
        assertEquals("x", text.min)
        assertEquals("y", text.max)
        assertTrue(text.minIsExact && text.maxIsExact)
    }

    /** A one-level object whose values are given as JSON literals. */
    private fun flatObject(vararg fields: Pair<String, String>): JsonValue.Obj = JsonValue.Obj(
        fields.map { (name, literal) ->
            name to if (literal.startsWith("\"")) {
                JsonValue.Str(literal.removeSurrounding("\""))
            } else {
                JsonValue.Num(literal)
            }
        },
    )

    private fun sketch(
        corpus: List<JsonValue>,
        options: CatalogOptions = CatalogOptions.DEFAULT,
    ): SegmentSketch {
        val builder = SegmentSketchBuilder(options)
        for (document in corpus) builder.add(jsonDocument(document))
        return builder.build()
    }
}
