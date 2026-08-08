package app.oreshkov.rabosh.bench

import app.oreshkov.rabosh.variant.Variant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The arithmetic behind a measurement, pinned.
 *
 * `BenchmarkRunReport`'s reason, applied to a number rather than to a decision: a diagnostic whose
 * output nobody can check is a diagnostic that gets believed. Every assertion here is against a
 * **relationship** that must hold for any encoding — never against a remembered byte count, which
 * would be pinning the encoder rather than the measurement, and would have to be edited the first
 * time a header changed.
 *
 * The relationship the whole file rests on: **eliding is exact.** Whatever the encoding, the elided
 * model must total precisely the bytes of the *outermost* typed subtrees, because
 * `(outer - inner) + inner` telescopes. Any double count or any lost lift breaks it.
 */
class ExplodeCostTest {

    private fun measure(json: String) = ExplodeCost.measure(Variant.fromJson(json), "@type")

    @Test
    fun `a document with no discriminator costs nothing to explode`() {
        val cost = measure("""{"a":1,"b":[{"c":2}]}""")

        assertEquals(0L, cost.typedElements)
        assertEquals(0L, cost.wholeModelBytes)
        assertEquals(0L, cost.elidedModelBytes)
        assertEquals(0, cost.maxTypedDepth)
    }

    @Test
    fun `siblings that are not nested duplicate nothing`() {
        val document = Variant.fromJson("""{"items":[{"@type":"A","v":1},{"@type":"B","v":2}]}""")
        val cost = ExplodeCost.measure(document, "@type")

        val first = document.select("$.items[0]")!!.byteSize
        val second = document.select("$.items[1]")!!.byteSize

        assertEquals(2L, cost.typedElements)
        assertEquals(0L, cost.nestedElements)
        assertEquals(first + second, cost.wholeModelBytes)
        // Nothing is nested, so the two models agree — the case where an explode is free.
        assertEquals(cost.wholeModelBytes, cost.elidedModelBytes)
        assertEquals(mapOf(0 to 2L), cost.elementsByTypedDepth)
    }

    @Test
    fun `one typed element inside another is stored twice by the whole model and once by the elided`() {
        val document = Variant.fromJson("""{"@type":"Outer","child":{"@type":"Inner","v":1}}""")
        val cost = ExplodeCost.measure(document, "@type")

        val outer = document.byteSize
        val inner = document.select("$.child")!!.byteSize

        assertEquals(2L, cost.typedElements)
        assertEquals(1L, cost.nestedElements)
        assertEquals(outer + inner, cost.wholeModelBytes)
        assertEquals(outer, cost.elidedModelBytes)
        assertEquals(mapOf(0 to 1L, 1 to 1L), cost.elementsByTypedDepth)
        assertTrue(cost.wholeFactor > cost.elidedFactor)
    }

    @Test
    fun `three levels telescope, so eliding still totals the outermost subtree`() {
        val document = Variant.fromJson(
            """{"@type":"A","c":{"@type":"B","c":{"@type":"C","v":1}}}""",
        )
        val cost = ExplodeCost.measure(document, "@type")

        val a = document.byteSize
        val b = document.select("$.c")!!.byteSize
        val c = document.select("$.c.c")!!.byteSize

        assertEquals(3L, cost.typedElements)
        assertEquals(2L, cost.nestedElements)
        assertEquals(a + b + c, cost.wholeModelBytes)
        assertEquals(a, cost.elidedModelBytes)
        assertEquals(2, cost.maxTypedDepth)
    }

    /**
     * The branch most likely to be wrong, and the one a corpus would not fail loudly on.
     *
     * A plain object or array between two typed elements has to lift its descendants' bytes to the
     * typed element above it. Without the lift the inner element reads as un-nested: the whole model
     * is unchanged, so a corpus run still looks plausible, while the elided model silently counts the
     * inner subtree twice.
     */
    @Test
    fun `an untyped container between two typed elements still lifts`() {
        val document = Variant.fromJson(
            """{"@type":"Outer","wrap":{"list":[{"@type":"Inner","v":1}]}}""",
        )
        val cost = ExplodeCost.measure(document, "@type")

        val outer = document.byteSize
        val inner = document.select("$.wrap.list[0]")!!.byteSize

        assertEquals(2L, cost.typedElements)
        assertEquals(1L, cost.nestedElements)
        assertEquals(outer + inner, cost.wholeModelBytes)
        assertEquals(outer, cost.elidedModelBytes)
        // Depth is counted in *typed* ancestors, not in containers: the wrapper adds none.
        assertEquals(mapOf(0 to 1L, 1 to 1L), cost.elementsByTypedDepth)
    }

    @Test
    fun `two typed children of one parent are both elided from it`() {
        val document = Variant.fromJson(
            """{"@type":"P","kids":[{"@type":"K","v":1},{"@type":"K","v":2}]}""",
        )
        val cost = ExplodeCost.measure(document, "@type")

        val parent = document.byteSize
        val one = document.select("$.kids[0]")!!.byteSize
        val two = document.select("$.kids[1]")!!.byteSize

        assertEquals(3L, cost.typedElements)
        assertEquals(2L, cost.nestedElements)
        assertEquals(parent + one + two, cost.wholeModelBytes)
        assertEquals(parent, cost.elidedModelBytes)
    }

    /**
     * A `@type` that is not a string is a field sharing the name, not a discriminated element.
     * Counting it would inflate every number the diagnostic prints.
     */
    @Test
    fun `a non-string discriminator is not an element`() {
        assertEquals(0L, measure("""{"@type":{"nested":"object"},"v":1}""").typedElements)
        assertEquals(0L, measure("""{"@type":7,"v":1}""").typedElements)
        assertEquals(1L, measure("""{"@type":"real","v":1}""").typedElements)
    }

    @Test
    fun `the elided model never exceeds the whole model, and the whole never undercounts it`() {
        val document = Variant.fromJson(
            """
            {"@type":"Root","a":[{"@type":"X","b":{"@type":"Y","c":[{"@type":"Z","v":1}]}}],
             "d":{"e":[{"@type":"W","v":2}]}}
            """.trimIndent(),
        )
        val cost = ExplodeCost.measure(document, "@type")

        assertEquals(5L, cost.typedElements)
        assertEquals(4L, cost.nestedElements)
        // One outermost typed subtree — the root — so eliding totals exactly it.
        assertEquals(document.byteSize, cost.elidedModelBytes)
        assertTrue(cost.wholeModelBytes > cost.elidedModelBytes)
        assertEquals(cost.typedElements, cost.elementsByTypedDepth.values.sum())
        assertEquals(cost.wholeModelBytes, cost.bytesByTypedDepth.values.sum())
    }
}
