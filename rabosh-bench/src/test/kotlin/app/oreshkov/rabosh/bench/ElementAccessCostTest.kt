package app.oreshkov.rabosh.bench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The arithmetic the §10.6 gate is decided by.
 *
 * `ElementAccessCostMain` prints the curve and nothing checks its timings — the same split
 * `ExplodeCostTest` and `CorrelationCostTest` take. What is checked here is the reasoning the timings
 * feed: that the walk's share is a *ceiling*, that the speed-up it implies is bounded by it, and that
 * reassembly moves the explode and nothing else.
 */
class ElementAccessCostTest {

    @Test
    fun `the walk is what the element ordinal space could remove, and no more`() {
        val cost = ElementAccessCost(
            readNanosPerDocument = 800.0,
            walkNanosPerDocument = 1000.0,
            elementsPerDocument = 8,
        )

        assertEquals(200.0, cost.walkNanos)
        assertEquals(25.0, cost.walkNanosPerElement)
        assertEquals(0.2, cost.walkShare)
        assertEquals(1.25, ElementAccessCost.bestCaseSpeedup(cost.walkShare), 1e-9)
    }

    /**
     * A measurement where the walk looks free is reported as free, not as negative.
     *
     * Two scans of the same corpus differ by noise as well as by work, so the walk can measure
     * *below* the floor. Clamping is the honest response — the quantity is a share of a cost — and
     * saying so here stops a later reader from reading a negative share as evidence of anything.
     */
    @Test
    fun `a walk that measures below the floor is clamped rather than reported negative`() {
        val cost = ElementAccessCost(
            readNanosPerDocument = 1000.0,
            walkNanosPerDocument = 990.0,
            elementsPerDocument = 4,
        )

        assertEquals(0.0, cost.walkShare)
        assertEquals(1.0, ElementAccessCost.bestCaseSpeedup(cost.walkShare), 1e-9)
    }

    @Test
    fun `the speedup is bounded by the share and unbounded only at one`() {
        assertEquals(1.0, ElementAccessCost.bestCaseSpeedup(0.0), 1e-9)
        assertEquals(2.0, ElementAccessCost.bestCaseSpeedup(0.5), 1e-9)
        assertEquals(10.0, ElementAccessCost.bestCaseSpeedup(0.9), 1e-9)
        assertTrue(ElementAccessCost.bestCaseSpeedup(1.0).isInfinite(), "a row that is all walk has no floor")
        assertFailsWith<IllegalArgumentException> { ElementAccessCost.bestCaseSpeedup(1.5) }
    }

    /**
     * **Reassembly separates the explode from the other two, and cannot separate those from each
     * other.**
     *
     * The finding this whole file exists to make checkable. §10.6 says the case for element ordinals
     * would be revived by "a workload that reassembles parents often" — but that argument is against
     * the *explode*, which turns one read into k. Today's engine and an element-ordinal engine both
     * keep the document whole, so both reassemble in one read at every k. The assertion is that the
     * explode's cost rises with k while nothing else here moves at all.
     */
    @Test
    fun `reassembly cost rises for the explode alone`() {
        val reads = listOf(1, 4, 32).map { elements ->
            ElementAccessCost(
                readNanosPerDocument = 800.0,
                walkNanosPerDocument = 800.0 + 25.0 * elements,
                elementsPerDocument = elements,
            ).explodeReadsPerReassembly
        }

        assertEquals(listOf(1, 4, 32), reads, "the explode pays one read per element it split out")
        // And a document that was never split is one read whatever k is — which is today, and which
        // is also what an element ordinal space would be. There is nothing between them to measure.
        assertEquals(1, ElementAccessCost(800.0, 800.0, 0).explodeReadsPerReassembly)
    }

    /** One varint per element, the optimistic figure: if it cannot be justified there, it cannot be. */
    @Test
    fun `the ordinal space is priced at its cheapest`() {
        assertEquals(2_000_000L, ElementAccessCost.ordinalSpaceBytes(1_000_000))
        assertEquals(0L, ElementAccessCost.ordinalSpaceBytes(0))
    }

    @Test
    fun `a document read that costs nothing is a measurement, not a cost model`() {
        assertFailsWith<IllegalArgumentException> { ElementAccessCost(0.0, 10.0, 1) }
        assertFailsWith<IllegalArgumentException> { ElementAccessCost(10.0, 10.0, -1) }
    }
}
