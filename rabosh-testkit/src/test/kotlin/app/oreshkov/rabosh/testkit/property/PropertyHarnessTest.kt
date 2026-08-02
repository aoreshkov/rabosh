package app.oreshkov.rabosh.testkit.property

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The harness's own acceptance tests.
 *
 * Phase 1 exists to make later phases debuggable, so what is verified here is not "does `forAll`
 * run" but the three things that make a failure actionable: it is *caught*, it is *reported with
 * a seed*, and that seed *reproduces it exactly*.
 */
class PropertyHarnessTest {

    @Test
    fun `a true property passes`() {
        forAll(Gen.int(-1000..1000), iterations = 100, seed = 1L) { value ->
            assertEquals(value, value * 1)
        }
    }

    @Test
    fun `a broken invariant is caught`() {
        assertFailsWith<PropertyFailure> {
            forAll(Gen.int(0..1000), iterations = 200, seed = 42L) { value ->
                // False for every value above 100.
                assertTrue(value <= 100, "value was $value")
            }
        }
    }

    @Test
    fun `the failure report names the seed that reproduces it`() {
        val seed = 987654321L
        val failure = assertFailsWith<PropertyFailure> {
            forAll(Gen.int(0..1000), iterations = 200, seed = seed) { value ->
                assertTrue(value <= 100, "value was $value")
            }
        }

        val message = failure.message ?: fail("failure carried no message")
        assertContains(message, "$seed", message = "report must name the seed")
        assertContains(message, "Counterexample")
        assertContains(message, PropertyConfig.SEED_PROPERTY)
    }

    @Test
    fun `the same seed reproduces the identical counterexample`() {
        val seed = 20260725L

        fun run(): String {
            val failure = assertFailsWith<PropertyFailure> {
                forAll(Gen.long(0..Long.MAX_VALUE), iterations = 500, seed = seed) { value ->
                    assertTrue(value % 7L != 3L, "value was $value")
                }
            }
            return failure.message ?: fail("failure carried no message")
        }

        assertEquals(run(), run(), "a pinned seed must replay exactly")
    }

    @Test
    fun `different seeds are actually different runs`() {
        // Guards against a seed that is accepted but ignored — which would make every
        // "reproduces exactly" test above pass vacuously.
        val first = Gen.long().generate(RandomSource(1L))
        val second = Gen.long().generate(RandomSource(2L))
        assertTrue(first != second, "distinct seeds produced identical values")
    }

    @Test
    fun `failures are shrunk towards a minimal counterexample`() {
        val failure = assertFailsWith<PropertyFailure> {
            forAll(Gen.int(0..1_000_000), iterations = 200, seed = 7L) { value ->
                assertTrue(value < 512, "value was $value")
            }
        }

        // The minimal failing value is exactly 512; without shrinking the report would name
        // whatever large number happened to be generated first.
        val message = failure.message ?: fail("failure carried no message")
        assertContains(message, "Counterexample")
        assertTrue(
            message.lineSequence().any { it.trim() == "512" },
            "expected the counterexample to shrink to 512, got:\n$message",
        )
    }

    @Test
    fun `lists shrink to the shortest failing list`() {
        val failure = assertFailsWith<PropertyFailure> {
            forAll(Gen.list(Gen.int(0..100), sizes = 0..20), iterations = 300, seed = 3L) { values ->
                assertTrue(values.sum() < 50, "sum was ${values.sum()}")
            }
        }

        val message = failure.message ?: fail("failure carried no message")
        // A single element can reach 50, so the minimum is a one-element list.
        val counterexample = message.lineSequence()
            .dropWhile { !it.startsWith("Counterexample") }
            .drop(1)
            .first()
            .trim()
        assertEquals(1, counterexample.count { it == ',' } + 1, "expected one element in $counterexample")
    }

    @Test
    fun `edge cases run before random values`() {
        val seen = mutableListOf<Int>()
        runCatching {
            forAll(Gen.int(-10..10), iterations = 5, seed = 11L) { value ->
                seen += value
            }
        }

        // Gen.int offers 0, 1, -1 and both bounds as edge cases; they must come first.
        assertEquals(listOf(0, 1, -1, -10, 10), seen.take(5))
    }

    @Test
    fun `an edge case failure is reported as such`() {
        val failure = assertFailsWith<PropertyFailure> {
            forAll(Gen.int(-10..10), iterations = 100, seed = 5L) { value ->
                // Fails on 0, which is the first edge case.
                assertTrue(value != 0, "value was $value")
            }
        }

        assertContains(failure.message ?: "", "edge case")
    }

    @Test
    fun `shrinking terminates even when a generator proposes non-simpler values`() {
        // A deliberately badly behaved generator: shrink() never makes progress. The budget is
        // what stops it, and this test pins that the budget is honoured.
        val hostile = object : Gen<Int> {
            override fun generate(source: RandomSource): Int = source.nextInt(1..100)
            override fun shrink(value: Int): Sequence<Int> = generateSequence { value + 1 }.take(10)
            override val edgeCases: List<Int> get() = listOf(1)
        }

        assertFailsWith<PropertyFailure> {
            forAll(hostile, iterations = 10, seed = 1L, maxShrinks = 50) {
                fail("always fails")
            }
        }
    }

    @Test
    fun `two-generator form passes both values through`() {
        forAll(Gen.int(0..100), Gen.int(0..100), iterations = 100, seed = 2L) { a, b ->
            assertEquals(a + b, b + a)
        }
    }

    @Test
    fun `filter preserves shrinking`() {
        val evens = Gen.int(0..1000).filter { it % 2 == 0 }
        val failure = assertFailsWith<PropertyFailure> {
            forAll(evens, iterations = 200, seed = 13L) { value ->
                assertTrue(value < 100, "value was $value")
            }
        }

        val message = failure.message ?: fail("failure carried no message")
        assertContains(message, "shrunk in")
    }
}
