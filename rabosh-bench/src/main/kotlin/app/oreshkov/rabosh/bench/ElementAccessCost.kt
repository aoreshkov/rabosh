package app.oreshkov.rabosh.bench

/**
 * What an element ordinal space would buy, priced against the two things that already exist.
 *
 * The gate on §10.6 is deliberately two-sided — *Tier 0's walk too slow **and** Tier 1's composite
 * term insufficient* — and this is the arithmetic half of answering it. The measured half is
 * `ElementAccessCostMain`, which times the walk against the document read it rides on.
 *
 * ## The three designs, and what actually separates them
 *
 * | | space | element query | element *identity* from the index | reassemble a parent |
 * |---|---|---|---|---|
 * | today + `elemMatch` | 1.00x | composite lookup, else a walk | no — the walk gives it | **1 read** |
 * | elided explode | 1.13-1.26x | native, every index works | yes | **k reads** |
 * | element ordinals | ~1x + the ordinal space | native | yes | **1 read** |
 *
 * **The row that matters is the last, and it is not the one §10.6 expected.** The measurement that
 * was supposed to revive the case is "a workload that reassembles parents often" — but that argument
 * is against the *explode*, which turns one read into k. It says nothing about element ordinals
 * versus today, because **today already reassembles a parent in one read**: the document is whole and
 * has never stopped being whole. So reassembly frequency separates the explode from the other two and
 * cannot separate the other two from each other, whatever its value.
 *
 * What is left separating element ordinals from today is exactly one thing: whether the *index* can
 * hand back which element matched without walking the document it just read. [walkShare] is the price
 * of that walk as a fraction of the row it rides on, and it is the whole of the first gate.
 *
 * ## Why the two gates are not independent
 *
 * Tier 1 being *insufficient* — a range inside an element, a subset of the declared fields, a
 * disjunction, a negation — only costs anything at the speed of the thing it falls back to, which is
 * Tier 0's walk. So the second gate is not a second condition; it is a multiplier on the first. A
 * gate stated as a conjunction of two conditions, one of which is a function of the other, is
 * satisfied or refused by the first alone.
 */
class ElementAccessCost(
    /** Nanoseconds to read and decode one document, with no element walk at all. */
    val readNanosPerDocument: Double,
    /** Nanoseconds to read, decode **and** walk the elements evaluating a per-element predicate. */
    val walkNanosPerDocument: Double,
    /** Elements per document in the corpus these came from. */
    val elementsPerDocument: Int,
) {
    init {
        require(readNanosPerDocument > 0) { "a document read must cost something, was $readNanosPerDocument" }
        require(elementsPerDocument >= 0) { "elementsPerDocument must not be negative" }
    }

    /** What the walk adds, on top of the read it rides on. Never negative in a sound measurement. */
    val walkNanos: Double get() = walkNanosPerDocument - readNanosPerDocument

    /** Per element, so the number is comparable across corpus shapes. */
    val walkNanosPerElement: Double
        get() = if (elementsPerDocument == 0) 0.0 else walkNanos / elementsPerDocument

    /**
     * The share of a row's cost an element ordinal space could remove.
     *
     * **The ceiling on the whole tier, and it is a ceiling rather than an estimate.** An index that
     * knew which element matched would still have to read the document to return anything, so the
     * most it can save is the walk — and this is the walk as a fraction of read-plus-walk. A
     * permanent `BASE_VERSION` bump buys at most this.
     */
    val walkShare: Double get() = (walkNanos / walkNanosPerDocument).coerceIn(0.0, 1.0)

    /**
     * Reads to reassemble one parent under the elided explode, against one under the other two.
     *
     * Reported so the *explode's* weakness is priced in the same place as everything else — and so
     * that the finding above is checkable rather than asserted: this number rises with the elements
     * per document and moves neither of the other two designs, which is what "reassembly cannot
     * separate them" means arithmetically.
     */
    val explodeReadsPerReassembly: Int get() = maxOf(1, elementsPerDocument)

    override fun toString(): String =
        "ElementAccessCost(elements=$elementsPerDocument, read=%.0fns, walk=+%.0fns, share=%.1f%%)"
            .format(readNanosPerDocument, walkNanos, walkShare * 100)

    companion object {
        /**
         * The speed-up an element ordinal space could deliver on a query, at best.
         *
         * `1 / (1 - share)`, because removing the walk leaves the read. Stated as a function so the
         * verdict is arithmetic rather than an impression: a walk that is a fifth of a row caps the
         * tier at 1.25x, which is not what a permanent format version is spent on.
         */
        fun bestCaseSpeedup(walkShare: Double): Double {
            require(walkShare in 0.0..1.0) { "a share must lie in 0..1, was $walkShare" }
            return if (walkShare >= 1.0) Double.POSITIVE_INFINITY else 1.0 / (1.0 - walkShare)
        }

        /**
         * What the ordinal space itself costs, in bytes per element.
         *
         * §10.6's own sketch: segments are immutable and a stored document is never edited in place,
         * so plain pre-order numbering suffices and ORDPATH's machinery is not needed — **one varint
         * per element**. This is the optimistic figure deliberately: if the tier cannot be justified
         * at its cheapest, it cannot be justified.
         */
        fun ordinalSpaceBytes(elements: Long): Long = elements * OPTIMISTIC_VARINT_BYTES

        private const val OPTIMISTIC_VARINT_BYTES = 2L
    }
}
