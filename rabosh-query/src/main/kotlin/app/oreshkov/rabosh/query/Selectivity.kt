package app.oreshkov.rabosh.query

import app.oreshkov.rabosh.catalog.InferredSchema

/**
 * How much of the store a leaf is expected to admit, and what to do about it.
 *
 * **Two instruments, and the split is deliberate.** The schema decides *which sources to open*,
 * because opening a reader pins sidecars and that cost is paid before a single bitmap exists — an
 * estimate is the only thing available that early. Actual bitmap cardinality decides *the order the
 * intersection runs in*, because by then it is exact and free: a posting list's cardinality is in its
 * header. Guessing where the truth is cheap would be the wrong way round, and estimating nothing at
 * all would mean pinning a sidecar to discover it was not worth pinning.
 *
 * Without an [InferredSchema] every leaf is worth opening, which is the honest default: no statistics
 * is not the same as a statistic saying no.
 */
internal object Selectivity {

    /** Above this, a conjunct admits so much of the store that its sibling has already bounded it. */
    private const val NEARLY_EVERYTHING = 0.95

    /** Below this, a conjunct is selective enough to make its siblings redundant. */
    private const val SELECTIVE = 0.5

    /** The fraction of documents [leaf] is expected to admit, or `null` when nothing knows. */
    fun estimate(leaf: Normal.Leaf, schema: InferredSchema?): Double? {
        val field = schema?.get(leaf.path) ?: return null
        val presence = field.presence.coerceIn(0.0, 1.0)
        val positive = when (leaf.kind) {
            LeafKind.EQUALITY -> {
                val distinct = field.distinctEstimate.coerceAtLeast(1L)
                val values = leaf.predicates.size
                (presence * values / distinct).coerceIn(0.0, presence)
            }

            // The textbook third. Nothing here interpolates inside a bound yet — see the phase notes.
            LeafKind.RANGE -> presence / 3.0
            LeafKind.EXISTS -> presence
            LeafKind.IS_NULL -> (presence * field.nullFraction).coerceIn(0.0, presence)
        }
        return if (leaf.negated) (1.0 - positive).coerceIn(0.0, 1.0) else positive
    }

    /**
     * Which conjuncts are worth reading a sidecar for.
     *
     * A conjunct estimated to admit nearly everything is dropped **only when a sibling is selective
     * enough to bound the answer without it**. Dropping is always sound — a conjunction's candidate
     * set only widens — and it is only ever *useful* when something else is doing the work. On its
     * own, a near-universal index is still better than scanning the store.
     */
    fun chooseConjuncts(operands: List<Normal>, schema: InferredSchema?): List<Normal> {
        if (schema == null || operands.size < 2) return operands
        val estimates = operands.map { (it as? Normal.Leaf)?.let { leaf -> estimate(leaf, schema) } }
        if (estimates.none { it != null && it <= SELECTIVE }) return operands
        return operands.filterIndexed { index, _ ->
            estimates[index]?.let { it < NEARLY_EVERYTHING } ?: true
        }
    }

    /** How a leaf's estimate reads in an [Explain]. */
    fun describe(leaf: Normal.Leaf, schema: InferredSchema?): String =
        estimate(leaf, schema)?.let { "~${"%.1f".format(it * 100)}% of documents" } ?: "no estimate"
}
