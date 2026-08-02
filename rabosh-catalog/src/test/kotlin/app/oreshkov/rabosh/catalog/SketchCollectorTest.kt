package app.oreshkov.rabosh.catalog

import app.oreshkov.rabosh.testkit.json.JsonGens
import app.oreshkov.rabosh.testkit.json.JsonValue
import app.oreshkov.rabosh.testkit.property.Gen
import app.oreshkov.rabosh.testkit.property.forAll
import app.oreshkov.rabosh.testkit.property.list
import app.oreshkov.rabosh.variant.VariantKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Path enumeration, checked **differentially** against a second implementation.
 *
 * [expectedObservations] walks the testkit's own JSON model; the collector walks the encoded
 * Variant. Two independent walks agreeing is evidence; one walk agreeing with itself would not be.
 * It is the same instrument the codec phase used against `kotlinx-serialization`.
 */
class SketchCollectorTest {

    @Test
    fun `every path a document contains is counted exactly once per occurrence`() {
        forAll(Gen.list(JsonGens.document(), sizes = 0..6)) { corpus ->
            val sketch = sketchModel(corpus)
            val expected = expectedCounts(corpus)
            assertEquals(expected.keys, sketch.paths, "the set of paths")
            for ((path, count) in expected) {
                assertEquals(count, sketch[path]?.observations, "observations at $path")
            }
            assertEquals(corpus.size.toLong(), sketch.documentCount)
            assertEquals(expected.values.sum(), sketch.observationCount)
        }
    }

    @Test
    fun `array indices collapse into one path`() {
        val sketch = sketch(listOf(jsonDocument("""{"items":[{"sku":"a"},{"sku":"b"},{"sku":"c"}]}""")))

        assertEquals(
            setOf(
                CatalogPath.ROOT,
                catalogPathOf("items"),
                CatalogPath(listOf(CatalogStep.Field("items"), CatalogStep.AnyElement)),
                CatalogPath(
                    listOf(CatalogStep.Field("items"), CatalogStep.AnyElement, CatalogStep.Field("sku")),
                ),
            ),
            sketch.paths,
        )
        assertEquals(1, sketch[catalogPathOf("items")]!!.observations, "the array itself, once")
        assertEquals(3, sketch[CatalogPath.parse("$.items[*]")]!!.observations, "its elements, three times")
        assertEquals(3, sketch[CatalogPath.parse("$.items[*].sku")]!!.observations)
        assertEquals(3, sketch[CatalogPath.parse("$.items[*].sku")]!!.distinctEstimate)
    }

    @Test
    fun `an empty container is present but has nothing inside it`() {
        val sketch = sketch(listOf(jsonDocument("""{"tags":[],"meta":{}}""")))
        assertEquals(1, sketch[catalogPathOf("tags")]!!.observations)
        assertEquals(VariantKind.ARRAY, sketch[catalogPathOf("tags")]!!.dominantType)
        assertEquals(VariantKind.OBJECT, sketch[catalogPathOf("meta")]!!.dominantType)
        assertNull(sketch[CatalogPath.parse("$.tags[*]")], "no element path for an empty array")
    }

    @Test
    fun `a null is present, not absent`() {
        val sketch = sketch(
            listOf(
                jsonDocument("""{"note":null}"""),
                jsonDocument("""{"note":"hello"}"""),
                jsonDocument("""{}"""),
            ),
        )
        val note = sketch[catalogPathOf("note")]!!
        assertEquals(2, note.observations, "present in two of three documents")
        assertEquals(1, note.nullObservations)
        // The null must not become a distinct value, or a column of nothing but nulls would look
        // worth indexing.
        assertEquals(1, note.distinctEstimate, "only the string counts")
    }

    @Test
    fun `numeric widths do not multiply the distinct count`() {
        // 1, 1.0 and 1.00 are one value to any query that would use an index over this path, and an
        // estimator that disagreed would recommend against indexing a column whose cardinality is 1.
        val sketch = sketch(
            listOf(
                jsonDocument("""{"n":1}"""),
                jsonDocument("""{"n":1.0}"""),
                jsonDocument("""{"n":1.00}"""),
                jsonDocument("""{"n":2}"""),
            ),
        )
        val n = sketch[catalogPathOf("n")]!!
        assertEquals(4, n.observations)
        assertEquals(2, n.distinctEstimate)
        val range = n.bounds.numeric!!
        assertEquals("1", range.min.stripTrailingZeros().toPlainString())
        assertEquals("2", range.max.stripTrailingZeros().toPlainString())
    }

    @Test
    fun `a mixed path keeps a range for each type`() {
        val sketch = sketch(
            listOf(jsonDocument("""{"v":7}"""), jsonDocument("""{"v":"apple"}""")),
        )
        val v = sketch[catalogPathOf("v")]!!
        assertEquals(mapOf(VariantKind.INTEGER to 1L, VariantKind.STRING to 1L), v.types)
        assertEquals(0.5, v.typeStability)
        assertEquals("7", v.bounds.numeric!!.min.toPlainString())
        assertEquals("apple", v.bounds.text!!.min)
    }

    @Test
    fun `depth beyond the limit is counted but not descended into`() {
        val options = CatalogOptions(maxDepth = 2)
        val sketch = sketch(listOf(jsonDocument("""{"a":{"b":{"c":1}}}""")), options)
        assertEquals(
            setOf(CatalogPath.ROOT, catalogPathOf("a"), catalogPathOf("a", "b")),
            sketch.paths,
            "the value at the limit is still observed; its children are not",
        )
        assertEquals(VariantKind.OBJECT, sketch[catalogPathOf("a", "b")]!!.dominantType)
    }

    @Test
    fun `paths beyond the budget go to the overflow bucket`() {
        val options = CatalogOptions(maxPaths = 4)
        val document = JsonValue.Obj((0 until 20).map { "field-$it" to JsonValue.Num("$it") })
        val sketch = sketchModel(listOf(document), options)

        assertEquals(4, sketch.pathCount)
        assertTrue(sketch.estimatedDroppedPaths > 0, "the overflow is reported, not silent")
        val tracked = sketch.entries().values.sumOf { it.observations }
        assertEquals(sketch.observationCount, tracked + sketch.droppedObservations, "nothing is lost")
    }

    @Test
    fun `a long string bound is truncated so that it still bounds`() {
        val long = "z".repeat(200)
        val sketch = sketch(listOf(jsonDocument("""{"s":"$long"}""")), CatalogOptions(textBoundBytes = 8))
        val bounds = sketch[catalogPathOf("s")]!!.bounds.text!!

        assertEquals("zzzzzzzz", bounds.min)
        assertTrue(!bounds.minIsExact && !bounds.maxIsExact)
        val value = long.encodeToByteArray()
        assertTrue(compareUnsigned(bounds.minUtf8(), value) <= 0, "the lower bound is no larger")
        assertTrue(compareUnsigned(bounds.maxUtf8()!!, value) >= 0, "the upper bound is no smaller")
    }

    @Test
    fun `the root path accounts for every document`() {
        forAll(Gen.list(JsonGens.document(), sizes = 1..6)) { corpus ->
            val sketch = sketchModel(corpus)
            assertEquals(corpus.size.toLong(), sketch[CatalogPath.ROOT]!!.observations)
            assertEquals(VariantKind.OBJECT, sketch[CatalogPath.ROOT]!!.dominantType)
        }
    }

    private fun compareUnsigned(left: ByteArray, right: ByteArray): Int =
        java.util.Arrays.compareUnsigned(left, right)

    private fun sketch(
        corpus: List<app.oreshkov.rabosh.variant.Variant>,
        options: CatalogOptions = CatalogOptions.DEFAULT,
    ): SegmentSketch {
        val builder = SegmentSketchBuilder(options)
        for (document in corpus) builder.add(document)
        return builder.build()
    }

    private fun sketchModel(
        corpus: List<JsonValue>,
        options: CatalogOptions = CatalogOptions.DEFAULT,
    ): SegmentSketch = sketch(corpus.map(::jsonDocument), options)
}
