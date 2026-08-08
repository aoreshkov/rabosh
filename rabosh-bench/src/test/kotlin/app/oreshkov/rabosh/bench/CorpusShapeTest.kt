package app.oreshkov.rabosh.bench

import app.oreshkov.rabosh.catalog.CatalogPath
import app.oreshkov.rabosh.variant.Variant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the shape scanner counts, pinned.
 *
 * Same rule as `ExplodeCostTest`: assertions are over structure the measurement must have, never over
 * a remembered number from a corpus — a corpus number would pin the corpus rather than the code, and
 * this diagnostic's whole job is to be pointed at corpora nobody has seen.
 *
 * The property carrying the file is that **indices collapse**. A shape is what an index is defined
 * over, so `$.items[0]` and `$.items[7]` must be one shape and not two; a scanner that reported them
 * separately would make every corpus look infinitely scattered and would answer the declarability
 * question "no" by construction.
 */
class CorpusShapeTest {

    private fun measure(json: String) = CorpusShape.measure(Variant.fromJson(json), "@type")

    private fun path(expression: String) = CatalogPath.parse(expression)

    @Test
    fun `object and leaf shapes are recorded separately`() {
        val shape = measure("""{"a":1,"b":{"c":2}}""")

        assertEquals(2L, shape.objects)
        assertEquals(0L, shape.arrays)
        assertEquals(setOf(path("$"), path("$.b")), shape.objectPathShapes.keys)
        assertEquals(setOf(path("$.a"), path("$.b.c")), shape.leafPathShapes.keys)
    }

    @Test
    fun `array indices collapse into one shape`() {
        val shape = measure("""{"items":[{"sku":"a"},{"sku":"b"},{"sku":"c"}]}""")

        assertEquals(setOf(path("$"), path("""$.items[*]""")), shape.objectPathShapes.keys)
        // Three objects, one shape — the collapse, stated as a count rather than as a key set.
        assertEquals(3L, shape.objectPathShapes[path("""$.items[*]""")])
        assertEquals(3L, shape.leafPathShapes[path("""$.items[*].sku""")])
        assertEquals(1L, shape.arrays)
    }

    @Test
    fun `nested arrays each contribute a step`() {
        val shape = measure("""{"grid":[[1,2],[3]]}""")

        assertEquals(setOf(path("""$.grid[*][*]""")), shape.leafPathShapes.keys)
        assertEquals(3L, shape.leafPathShapes[path("""$.grid[*][*]""")])
        assertEquals(3L, shape.arrays)
    }

    @Test
    fun `a type at two sites is reported at both`() {
        val shape = measure(
            """{"a":{"@type":"T","v":1},"b":{"inner":{"@type":"T","v":2}},"c":{"@type":"U","v":3}}""",
        )

        assertEquals(2, shape.distinctTypes)
        assertEquals(3L, shape.discriminatorHits)
        assertEquals(setOf(path("$.a"), path("$.b.inner")), shape.typePathShapes.getValue("T").keys)
        assertEquals(setOf(path("$.c")), shape.typePathShapes.getValue("U").keys)
        assertEquals(3, shape.typePathPairs)
    }

    /**
     * The reading the corpus survey turned on, and the one most easily got backwards: most *types*
     * can sit at a single shape while most *elements* do not, because the scattered types are the
     * populous ones. Here `Scattered` has 4 instances over 2 shapes and `Single` has 1 over 1 — so
     * two thirds of types are single-shape and only a fifth of elements are.
     */
    @Test
    fun `single-shape types and single-shape elements are different questions`() {
        val shape = measure(
            """
            {"x":[{"@type":"Scattered","v":1},{"@type":"Scattered","v":2}],
             "y":[{"@type":"Scattered","v":3},{"@type":"Scattered","v":4}],
             "z":{"@type":"Single","v":5},
             "w":{"@type":"AlsoSingle","v":6}}
            """.trimIndent(),
        )

        assertEquals(mapOf(1 to 2, 2 to 1), shape.pathsPerType())
        assertEquals(2L, shape.elementsOfSingleShapeTypes())
        assertEquals(6L, shape.discriminatorHits)
    }

    @Test
    fun `a self-recursive structure scatters one type across a shape per level`() {
        val shape = measure(
            """{"@type":"R","kids":[{"@type":"R","kids":[{"@type":"R","kids":[]}]}]}""",
        )

        assertEquals(
            setOf(path("$"), path("""$.kids[*]"""), path("""$.kids[*].kids[*]""")),
            shape.typePathShapes.getValue("R").keys,
        )
        // One more level of the same data would add a fourth: the property that makes a declared
        // scope list a snapshot of the data rather than a statement about the schema.
        assertEquals(3, shape.spread().single().pathShapes)
    }

    @Test
    fun `spread orders the widest type first`() {
        val shape = measure(
            """{"a":{"@type":"Wide"},"b":{"@type":"Wide"},"c":{"@type":"Narrow"}}""",
        )

        val widest = shape.spread().first()
        assertEquals("Wide", widest.type)
        assertEquals(2, widest.pathShapes)
        assertEquals(2L, widest.elements)
    }

    @Test
    fun `a non-string discriminator is not an element`() {
        assertEquals(0, measure("""{"@type":{"n":"o"},"v":1}""").distinctTypes)
        assertEquals(0, measure("""{"@type":7,"v":1}""").distinctTypes)
        assertEquals(1, measure("""{"@type":"real","v":1}""").distinctTypes)
    }

    @Test
    fun `depth counts containers, and a leaf does not add one`() {
        val shape = measure("""{"a":{"b":{"c":1}}}""")

        // $, $.a, $.a.b are the containers; $.a.b.c is a leaf and must not raise the number.
        assertEquals(2, shape.maxPathDepth)
    }

    /**
     * Every shape this produces must be one the engine's own parser reads back identically.
     *
     * The point of holding shapes as `CatalogPath` rather than as assembled text is that there is no
     * second opinion about what a path is; this is what says so, and it covers the names a hand-built
     * renderer gets wrong — a dot, a quote, a backslash, a bracket, and protobuf's `@type` itself.
     */
    @Test
    fun `every shape round-trips through the engine's own parser`() {
        val shape = measure(
            """{"@type":"T","a.b":{"x":1},"q\"uote":[{"y":2}],"back\\slash":3,"br[a]cket":4}""",
        )

        val everyShape = shape.objectPathShapes.keys + shape.leafPathShapes.keys
        assertTrue(everyShape.size >= 5)
        for (rendered in everyShape) {
            assertEquals(rendered, CatalogPath.parse(rendered.toString()))
        }
    }
}
