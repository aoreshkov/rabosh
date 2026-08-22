package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.RaboshExperimental
import app.oreshkov.rabosh.catalog.CatalogPath
import app.oreshkov.rabosh.catalog.CatalogStep
import app.oreshkov.rabosh.variant.Variant
import app.oreshkov.rabosh.variant.VariantBasicType

/**
 * Walks a document and emits the **containers** a set of paths stands for.
 *
 * [TermExtractor]'s sibling, and deliberately not a mode of it. That walk reaches its sink for
 * *scalars* — a path landing on an object or an array reports nothing, which is why `Exists($.items)`
 * over an array is false and has always been — and quietly widening it to report containers would
 * change what every existing index and every existing predicate means. Two walks, one narrowing rule.
 *
 * **The narrowing and the pruning are the same**, and that is what has to stay true. Built from an
 * [IndexOptions] this walk runs inside flush and compaction like the other one, so it carries
 * [IndexOptions.maxDepth] and [IndexOptions.maxChildren]; a document that made the walk expensive
 * would make the engine's background maintenance expensive. Built by [reading] it carries neither,
 * for the reason [TermExtractor.reading] gives — and that is the half of this sentence that changed:
 * the budgets are the *writer's*, not the walk's. It is still not `CatalogPath.forEachNodeIn`, which
 * arrived at the same conclusion for a reader's walk with no ordinal in sight.
 *
 * **Public for the reason [TermExtractor] is.** The recheck of a composite candidate has to be the
 * code that built the term, not a second traversal that agrees with it today — so `rabosh-query`
 * calls this class, and there is exactly one definition of "which containers does `$.items[*]` stand
 * for in this document".
 */
@RaboshExperimental
public class ElementExtractor(
    /** The container paths, in the order elements are reported against. */
    private val paths: List<CatalogPath>,
    private val options: IndexOptions,
) {
    /** `(path, position)` packed into an `Int`. See [TermExtractor]'s, which this mirrors exactly. */
    private val stride = (paths.maxOfOrNull { it.steps.size } ?: 0) + 1

    private val anyDescendant =
        paths.any { path -> path.steps.any { it === CatalogStep.AnyDescendant } }

    private val all = IntArray(paths.size) { it * stride }

    /** Whether there is anything to extract. */
    public val isEmpty: Boolean get() = paths.isEmpty()

    /**
     * Reports every `(pathIndex, element)` this document holds.
     *
     * A path may be reported many times — `$.items[*]` over three items reports three elements — and
     * the elements arrive in document order, which is what makes a per-element predicate's answer
     * independent of how the walk is implemented.
     *
     * A path landing on a scalar reports nothing, and neither does one landing on nothing at all.
     * Same rule as `Variant.select`: "this document has nothing there" is an answer.
     *
     * The element is a view over bytes valid only for the duration of the call; anything kept must be
     * copied.
     */
    public fun extract(document: Variant, sink: (pathIndex: Int, element: Variant) -> Unit) {
        extract(document, NOTHING_TRUNCATED, sink)
    }

    /**
     * The same walk, reporting every path a budget stopped it short of.
     *
     * [TermExtractor.extract]'s three-argument form one level up, with the same contract and for the
     * same caller: a composite index whose element walk saw a prefix of a container is an index that
     * has *fewer tuples than the document has elements*, in a segment that would otherwise read as
     * covered. `IndexCatalog` marks it not covered instead.
     *
     * A truncated array reports the candidates the wildcard step kept; a truncated object reports
     * every candidate still alive at it. Arrived paths — the containers this walk exists to find —
     * are excluded, because a skipped child cannot carry a container a completed path already named.
     */
    public fun extract(
        document: Variant,
        onTruncated: (pathIndex: Int) -> Unit,
        sink: (pathIndex: Int, element: Variant) -> Unit,
    ) {
        if (paths.isEmpty()) return
        walk(document, 0, all, onTruncated, sink)
    }

    private fun walk(
        value: Variant,
        depth: Int,
        arriving: IntArray,
        onTruncated: (Int) -> Unit,
        sink: (Int, Variant) -> Unit,
    ) {
        val states = closure(arriving)
        // A path that has consumed all its steps has *arrived*, and this walk reports what it arrived
        // at whatever shape it is. The check is before the descent rather than inside the scalar
        // branch, which is the one structural difference from `TermExtractor`.
        for (state in states) {
            if (positionOf(state) == paths[pathOf(state)].steps.size) sink(pathOf(state), value)
        }

        when (value.basicType) {
            VariantBasicType.OBJECT -> {
                if (depth >= options.maxDepth) return reportTruncated(states, onTruncated) { true }
                val children = minOf(value.fieldCount, options.maxChildren)
                if (children < value.fieldCount) reportTruncated(states, onTruncated) { true }
                for (index in 0 until children) {
                    val name = value.fieldName(index)
                    val next = advance(states) { it is CatalogStep.Field && it.name == name }
                    if (next.isNotEmpty()) walk(value.fieldValue(index), depth + 1, next, onTruncated, sink)
                }
            }

            VariantBasicType.ARRAY -> {
                if (depth >= options.maxDepth) return reportTruncated(states, onTruncated) { true }
                val next = advance(states) { it is CatalogStep.AnyElement }
                if (next.isEmpty()) return
                val children = minOf(value.elementCount, options.maxChildren)
                // Reported over the states as they stood *at* the array, filtered to the ones an
                // element would have carried — a state that already advanced past its last step has
                // arrived, and the elements it never saw are what the bound cost.
                if (children < value.elementCount) reportTruncated(states, onTruncated) { step ->
                    step is CatalogStep.AnyElement || step === CatalogStep.AnyDescendant
                }
                for (index in 0 until children) walk(value.element(index), depth + 1, next, onTruncated, sink)
            }

            VariantBasicType.PRIMITIVE, VariantBasicType.SHORT_STRING -> Unit
        }
    }

    /**
     * [TermExtractor.closure] and [TermExtractor.advance], on the same state encoding.
     *
     * The duplication is two dozen lines and it is the deliberate kind: these are two walks with two
     * *sinks* — one reports scalars, one reports containers — and the one structural difference above
     * is exactly what a shared base class would have had to parameterise. The rule they must not
     * break is that they agree about what a path **means**, and `NodeExpansionDifferentialTest` is
     * what checks that rather than a shared superclass asserting it by construction.
     */
    private fun closure(states: IntArray): IntArray {
        if (!anyDescendant) return states
        var extra = 0
        for (state in states) if (isAtDescendant(state)) extra++
        if (extra == 0) return states
        val widened = IntArray(states.size + extra)
        var count = 0
        for (state in states) {
            widened[count++] = state
            if (isAtDescendant(state)) widened[count++] = state + 1
        }
        return distinctStates(widened, count)
    }

    private inline fun advance(states: IntArray, matches: (CatalogStep) -> Boolean): IntArray {
        var count = 0
        val kept = IntArray(states.size)
        for (state in states) {
            val steps = paths[pathOf(state)].steps
            val position = positionOf(state)
            if (position >= steps.size) continue
            val step = steps[position]
            when {
                step === CatalogStep.AnyDescendant -> kept[count++] = state
                matches(step) -> kept[count++] = state + 1
            }
        }
        if (anyDescendant) return distinctStates(kept, count)
        return if (count == kept.size) kept else kept.copyOf(count)
    }

    /** [TermExtractor]'s rule: a path with no step left here is past what the bound cut. */
    private inline fun reportTruncated(
        states: IntArray,
        onTruncated: (Int) -> Unit,
        alive: (CatalogStep) -> Boolean,
    ) {
        for (state in states) {
            val steps = paths[pathOf(state)].steps
            val position = positionOf(state)
            if (position < steps.size && alive(steps[position])) onTruncated(pathOf(state))
        }
    }

    private fun isAtDescendant(state: Int): Boolean {
        val steps = paths[pathOf(state)].steps
        val position = positionOf(state)
        return position < steps.size && steps[position] === CatalogStep.AnyDescendant
    }

    private fun pathOf(state: Int): Int = state / stride

    private fun positionOf(state: Int): Int = state % stride

    public companion object {
        /**
         * The **reader's** walk over [paths]: the same narrowing, with no budget on it.
         *
         * [TermExtractor.reading]'s argument applies here unchanged — an `elemMatch` rechecked or
         * scanned is a question a caller asked, not maintenance the engine chose — and it has to
         * apply here, because a correlated predicate is decided by *this* walk feeding a nested
         * matcher. Widening one and not the other would leave a correlated query truncating where an
         * uncorrelated one no longer does.
         */
        public fun reading(paths: List<CatalogPath>): ElementExtractor =
            ElementExtractor(paths, TermExtractor.UNBOUNDED)

        /** A no-op, so the two-argument [extract] costs a call and not a branch per container. */
        private val NOTHING_TRUNCATED: (Int) -> Unit = {}
    }
}
