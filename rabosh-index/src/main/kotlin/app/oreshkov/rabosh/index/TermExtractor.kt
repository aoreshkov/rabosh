package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.RaboshExperimental
import app.oreshkov.rabosh.catalog.CatalogPath
import app.oreshkov.rabosh.catalog.CatalogStep
import app.oreshkov.rabosh.variant.Variant
import app.oreshkov.rabosh.variant.VariantBasicType

/**
 * Walks a document and emits the values it contributes to a set of indexed paths.
 *
 * The same walk `SegmentSketchBuilder` makes, with a filter on it. That is deliberate and it is not
 * merely reuse: the paths being indexed were recommended by a walk of exactly this shape, so a
 * document that contributed an observation to `$.items[*].sku` there must contribute a term to it
 * here. A second, differently-shaped traversal would make the estimator and the index disagree about
 * what a path even *is* — which array elements collapse, how deep a document is followed, what counts
 * as a leaf.
 *
 * **This is a filtered walk, not a path expander**, and it stays one. A [CatalogPath] holding `[*]`
 * describes a *set* of locations and `VariantPath` describes one; writing asks "for each scalar under
 * one of these paths, what is its signature", which this walk answers natively because it is the walk
 * that produces `[*]` in the first place, and reading asks "which ordinals carry this signature",
 * which the posting file answers. Neither ever enumerates the locations a wildcard stands for, and
 * neither should: an ordinal per element would be a second ordinal space per segment, a `.idx` layout
 * change and a `BASE_VERSION` bump.
 *
 * **A reader that does want them has `CatalogPath.forEachNodeIn`** — phase 20, in `rabosh-catalog`,
 * where the wildcard step lives. It is not this walk with a different sink and must not be confused
 * for one. It runs when somebody asks about one document they already hold, not inside compaction, so
 * it carries **no** [IndexOptions.maxDepth] or [IndexOptions.maxChildren] budget; the direction that
 * makes that safe is that its nodes are a *superset* of the terms emitted here, never a subset, and
 * `NodeExpansionDifferentialTest` is where the two are compared. The sentence above used to read
 * "nothing in the engine converts between them, and nothing needs to". The first half was made false
 * by that function; the second half was only ever true of the *writer*, and that is what it now says.
 *
 * **Candidates are narrowed on the way down, so an unindexed subtree is not walked at all.** Each
 * step keeps only the paths still matching, and an empty set prunes the whole subtree. A store with
 * two indexes over shallow paths therefore pays for two field comparisons per document, not for a
 * full traversal — which matters, because this runs inside compaction.
 *
 * **Public because the query layer's evaluation has to be provably the code that built the index.**
 * The rule is that a candidate is rechecked by the same walk that produced the term, and the only way
 * to make that a fact rather than an intention is for there to be one class with every layer as a
 * caller of it. `rabosh-query` builds exactly one of these over every path a predicate mentions, so
 * a whole predicate costs one narrowing walk per document rather than one walk per leaf.
 *
 * **One class, two budgets: the writer's constructor carries them and [reading] does not.** The
 * narrowing, the pruning and what counts as a value are identical — that is the part the rule above
 * is about — but a walk that runs inside compaction is bounded and a walk that answers a question is
 * not. The two never disagree about a document either of them would report on, because a segment
 * whose build hit a budget is not covered by the index it was building; the argument is under
 * [reading], and `.claude/rules/index-and-query.md` states it as the rule it now is.
 */
@RaboshExperimental
public class TermExtractor(
    /** The indexed paths, in the order terms are reported against. */
    private val paths: List<CatalogPath>,
    private val options: IndexOptions,
) {
    /**
     * A walk state is one `(path, position)` pair packed into an `Int`, and this is the base.
     *
     * One past the longest path, so a position of *size* — meaning **arrived** — is still inside its
     * path's range and no two paths can collide. Positions are small (a path is a value a caller
     * wrote) and so is the path count (one per indexed path), so the product cannot overflow.
     */
    private val stride = (paths.maxOfOrNull { it.steps.size } ?: 0) + 1

    /** Whether any path holds a `..`. Every widening below is skipped when it does not. */
    private val anyDescendant =
        paths.any { path -> path.steps.any { it === CatalogStep.AnyDescendant } }

    private val all = IntArray(paths.size) { it * stride }

    /** Whether there is anything to extract. */
    public val isEmpty: Boolean get() = paths.isEmpty()

    /**
     * Reports every `(pathIndex, value)` this document contributes.
     *
     * A path may be reported more than once for one document — `$.tags[*]` over three tags reports
     * three values — and a duplicate reports the same value twice. Both are correct: an inverted
     * index's posting list is a set of ordinals, and a column stores one slot per occurrence.
     *
     * **The raw [Variant] is reported, not a term**, so that one walk serves both index kinds. An
     * inverted index turns it into a `ValueSignature`; a column turns it into a `ColumnValue`. This is
     * what makes "the recheck runs the same walk that built the index" true of *both* — a second,
     * differently-shaped traversal would be a second definition of what a path means, and the two
     * would eventually disagree about an array or a nested null.
     *
     * A JSON null is reported like any other value. It is *present*, which is what makes `EXISTS`
     * exact: a document with `{"note": null}` has a `note`.
     *
     * The value is a view over bytes valid only for the duration of the call; anything kept must be
     * copied.
     */
    public fun extract(document: Variant, sink: (pathIndex: Int, value: Variant) -> Unit) {
        extract(document, NOTHING_TRUNCATED, sink)
    }

    /**
     * The same walk, reporting every path a budget stopped it short of.
     *
     * [onTruncated] fires with a `pathIndex` when [IndexOptions.maxChildren] or
     * [IndexOptions.maxDepth] cut a container this path was still a candidate under — the case where
     * the values reported for it are a *prefix* of the values the document holds there. Nothing else
     * distinguishes that from a complete walk: the sink reports what was found and cannot report what
     * was never looked at.
     *
     * **The one caller that must use this overload is the one writing an index.** A dictionary built
     * from a prefix of a path's values, in a segment that then reads as *covered*, is an index that
     * deletes documents from a result — and because the recheck and the scan would truncate at the
     * same element, both differential oracles would agree with the shortfall. So `IndexCatalog` marks
     * the segment **not covered** for that index rather than writing the sidecar, which is the escape
     * [IndexOptions.maxTermsPerSegment] already takes and for the same reason. A reader's walk has no
     * budget to fire — see [reading] — so nothing on the query path has to ask.
     *
     * Conservative in the direction that costs a scan rather than a document. A truncated object
     * reports every candidate still alive at it, because deciding which of them a skipped *field*
     * would have matched means reading the names the bound exists to avoid reading; a truncated array
     * reports only the candidates the wildcard step kept, which is exact.
     *
     * A path may be reported more than once for one document, and once per document that truncated
     * it. The caller wants a set; this reports events.
     */
    public fun extract(
        document: Variant,
        onTruncated: (pathIndex: Int) -> Unit,
        sink: (pathIndex: Int, value: Variant) -> Unit,
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
                // One step for the whole array, not one per index: an index over `$.tags[*]` is over
                // the values, and which position a value happened to occupy is not what a query asks.
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

            VariantBasicType.PRIMITIVE, VariantBasicType.SHORT_STRING -> {
                for (state in states) {
                    // A state has matched `position` steps; it is a *complete* match only if that is
                    // all the steps it has. A path that continues deeper does not match a scalar.
                    if (positionOf(state) != paths[pathOf(state)].steps.size) continue
                    sink(pathOf(state), value)
                }
            }
        }
    }

    /**
     * The states reachable from [states] without consuming a child: across a `..`, which matches at
     * zero levels as well as below.
     *
     * **This is what makes a descendant path an automaton rather than a cursor.** Without one, a
     * path's progress is its depth — step *n* is matched at level *n* — and one integer per path is
     * the whole state. `..` breaks that: after it, the next step may match here, or one level down,
     * or twenty, so a path is at *several* positions at once and the walk carries a set of
     * `(path, position)` pairs instead. `CatalogPath` forbids two `..` in a row, which is what makes
     * one pass enough here rather than a fixpoint.
     *
     * A store that indexes no descendant path never reaches the body — [anyDescendant] is false, the
     * array is returned unchanged, and the walk is exactly the one it was, allocation for allocation.
     * That is deliberate: this runs inside every flush and every compaction.
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
        return distinct(widened, count)
    }

    /**
     * The states one child down: a step that matches consumes the child, and a `..` stays where it is.
     *
     * The second half is the descent rule in one line — a descendant is still open at every node
     * below the one it was written at, so its state is copied down unchanged and its *successor* is
     * what [closure] tries at each level.
     */
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
        if (anyDescendant) return distinct(kept, count)
        return if (count == kept.size) kept else kept.copyOf(count)
    }

    /**
     * Reports the paths a container's bound could have cost, which is not all of them.
     *
     * A path that has consumed every step it has is *arrived*: it reports at a scalar and matches
     * nothing below a container, so a skipped child could not have carried a value for it. Including
     * it would uncover a segment on the strength of a path the bound never touched. The test is
     * [advance]'s own — a state is alive here only while its position is inside its path.
     *
     * A path at two positions reports twice, which the caller already tolerates: `IndexCatalog` sets
     * a flag, and the KDoc above says this reports events rather than a set.
     */
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

    private fun distinct(states: IntArray, count: Int): IntArray = distinctStates(states, count)

    public companion object {
        /**
         * The **reader's** walk over [paths]: the same narrowing, with no budget on it.
         *
         * The budgets are on the writer for one stated reason — this walk runs inside flush and
         * compaction, and a document that made it expensive would make the engine's background
         * maintenance expensive. A recheck or a scan is neither: it happens because a caller asked a
         * question, on the caller's own thread, about documents the caller is already paying to read.
         * `CatalogPath.forEachNodeIn` is the same argument reached earlier and independently.
         *
         * **This is what makes the budget cost a scan instead of a document.** A segment whose build
         * truncated is not covered, so it is scanned — and a scan that truncated at the same element
         * would answer exactly as short as the index it replaced, which is no fix at all. The pair is
         * the mechanism: bounded where an index is *written*, complete where an answer is *decided*.
         *
         * It does not weaken *the recheck runs the same walk that built the index*. Where an index
         * answers, its segment is covered, and a covered segment is by construction one whose build
         * did not truncate — so on every document a recheck sees, the two walks visit the same
         * children. The widening is only ever reached where no index claims anything.
         *
         * No [IndexOptions] is asked for because none is used: this walk reads `maxDepth` and
         * `maxChildren` and nothing else, and it wants neither. Depth stays bounded by the longest
         * path — a candidate is dropped once it has no step left, so nothing descends past the deepest
         * one — which is why removing the ceiling cannot deepen the recursion.
         */
        public fun reading(paths: List<CatalogPath>): TermExtractor = TermExtractor(paths, UNBOUNDED)

        /** A no-op, so the two-argument [extract] costs a call and not a branch per container. */
        private val NOTHING_TRUNCATED: (Int) -> Unit = {}

        internal val UNBOUNDED: IndexOptions = IndexOptions(maxDepth = Int.MAX_VALUE, maxChildren = Int.MAX_VALUE)
    }
}

/**
 * The first [count] entries of [states], sorted and with duplicates removed.
 *
 * **A duplicate is not a tidiness question, it is a wrong column.** Two descendants in one path can
 * reach the same `(path, position)` by two routes — one where a `..` stayed open and one where the
 * step before it consumed a child — and a walk that kept both would report the value at that path
 * twice for one document. An inverted index would shrug, because a posting list is a set of
 * ordinals; a shredded column stores **one slot per occurrence**, so the second copy would be a
 * value the document does not have, in a file whose bounds and block statistics are then computed
 * over it. The states are ints and there are a handful of them, so a sort is the cheapest correct
 * answer, and it runs only for a store that indexes a descendant path.
 */
internal fun distinctStates(states: IntArray, count: Int): IntArray {
    if (count <= 1) return if (count == states.size) states else states.copyOf(count)
    val sorted = states.copyOf(count)
    sorted.sort()
    var unique = 1
    for (index in 1 until count) if (sorted[index] != sorted[index - 1]) sorted[unique++] = sorted[index]
    return if (unique == count) sorted else sorted.copyOf(unique)
}
