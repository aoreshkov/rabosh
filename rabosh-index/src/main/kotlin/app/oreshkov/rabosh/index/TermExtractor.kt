package app.oreshkov.rabosh.index

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
 */
public class TermExtractor(
    /** The indexed paths, in the order terms are reported against. */
    private val paths: List<CatalogPath>,
    private val options: IndexOptions,
) {
    private val all = IntArray(paths.size) { it }

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
        if (paths.isEmpty()) return
        walk(document, 0, all, sink)
    }

    private fun walk(
        value: Variant,
        depth: Int,
        candidates: IntArray,
        sink: (Int, Variant) -> Unit,
    ) {
        when (value.basicType) {
            VariantBasicType.OBJECT -> {
                if (depth >= options.maxDepth) return
                val children = minOf(value.fieldCount, options.maxChildren)
                for (index in 0 until children) {
                    val name = value.fieldName(index)
                    val next = narrow(candidates, depth) { it is CatalogStep.Field && it.name == name }
                    if (next.isNotEmpty()) walk(value.fieldValue(index), depth + 1, next, sink)
                }
            }

            VariantBasicType.ARRAY -> {
                if (depth >= options.maxDepth) return
                // One step for the whole array, not one per index: an index over `$.tags[*]` is over
                // the values, and which position a value happened to occupy is not what a query asks.
                val next = narrow(candidates, depth) { it is CatalogStep.AnyElement }
                if (next.isEmpty()) return
                val children = minOf(value.elementCount, options.maxChildren)
                for (index in 0 until children) walk(value.element(index), depth + 1, next, sink)
            }

            VariantBasicType.PRIMITIVE, VariantBasicType.SHORT_STRING -> {
                for (candidate in candidates) {
                    // A candidate has matched `depth` steps; it is a *complete* match only if that is
                    // all the steps it has. A path that continues deeper does not match a scalar.
                    if (paths[candidate].steps.size != depth) continue
                    sink(candidate, value)
                }
            }
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
}
