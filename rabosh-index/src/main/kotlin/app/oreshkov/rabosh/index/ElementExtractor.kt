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
    private val all = IntArray(paths.size) { it }

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
        candidates: IntArray,
        onTruncated: (Int) -> Unit,
        sink: (Int, Variant) -> Unit,
    ) {
        // A path that has consumed all its steps has *arrived*, and this walk reports what it arrived
        // at whatever shape it is. The check is before the descent rather than inside the scalar
        // branch, which is the one structural difference from `TermExtractor`.
        for (candidate in candidates) {
            if (paths[candidate].steps.size == depth) sink(candidate, value)
        }

        when (value.basicType) {
            VariantBasicType.OBJECT -> {
                if (depth >= options.maxDepth) return reportTruncated(candidates, depth, onTruncated)
                val children = minOf(value.fieldCount, options.maxChildren)
                if (children < value.fieldCount) reportTruncated(candidates, depth, onTruncated)
                for (index in 0 until children) {
                    val name = value.fieldName(index)
                    val next = narrow(candidates, depth) { it is CatalogStep.Field && it.name == name }
                    if (next.isNotEmpty()) walk(value.fieldValue(index), depth + 1, next, onTruncated, sink)
                }
            }

            VariantBasicType.ARRAY -> {
                if (depth >= options.maxDepth) return reportTruncated(candidates, depth, onTruncated)
                val next = narrow(candidates, depth) { it is CatalogStep.AnyElement }
                if (next.isEmpty()) return
                val children = minOf(value.elementCount, options.maxChildren)
                if (children < value.elementCount) reportTruncated(next, depth, onTruncated)
                for (index in 0 until children) walk(value.element(index), depth + 1, next, onTruncated, sink)
            }

            VariantBasicType.PRIMITIVE, VariantBasicType.SHORT_STRING -> Unit
        }
    }

    private inline fun narrow(candidates: IntArray, depth: Int, matches: (CatalogStep) -> Boolean): IntArray {
        var count = 0
        val kept = IntArray(candidates.size)
        for (candidate in candidates) {
            val steps = paths[candidate].steps
            if (depth < steps.size && matches(steps[depth])) kept[count++] = candidate
        }
        return if (count == kept.size) kept else kept.copyOf(count)
    }

    /** [TermExtractor]'s rule: a path with no step left at this depth is past what the bound cut. */
    private inline fun reportTruncated(candidates: IntArray, depth: Int, onTruncated: (Int) -> Unit) {
        for (candidate in candidates) if (depth < paths[candidate].steps.size) onTruncated(candidate)
    }

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
