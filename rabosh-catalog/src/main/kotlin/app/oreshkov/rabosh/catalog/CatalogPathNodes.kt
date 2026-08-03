package app.oreshkov.rabosh.catalog

import app.oreshkov.rabosh.variant.Variant
import app.oreshkov.rabosh.variant.VariantBasicType
import app.oreshkov.rabosh.variant.VariantNode
import app.oreshkov.rabosh.variant.VariantPath
import app.oreshkov.rabosh.variant.VariantPathStep

// Turning a location *shape* back into the locations it stands for.
//
// `CatalogPath`'s own documentation says the relationship is one-way — "a `CatalogPath` describes a
// set of `VariantPath`s" — and until this file that was a sentence about types with no operation
// behind it. It is here, and not in `rabosh-variant` beside `Variant.select`, for the one reason
// that decides it: `CatalogPath` is this module's type, and a function taking it cannot live below
// this module without an upward edge.
//
// It is a *reader's* walk. `SegmentSketchBuilder` and `TermExtractor` are writers' walks and run
// inside a flush or a compaction, which is why both carry bounds on depth and breadth; this one runs
// when somebody asks a question about one document they are already holding, so it carries none. See
// the superset paragraph on `forEachNodeIn`, which is where that difference stops being a nuance.

/**
 * Every node this path stands for in [document], in document order.
 *
 * ```
 * {"items":[{"sku":"a"},{"sku":"b"}]}
 *
 * CatalogPath.parse("$.items[*].sku").forEachNodeIn(document) { println(it.toJsonSummaryString()) }
 * //   $['items'][0]['sku'] "a"
 * //   $['items'][1]['sku'] "b"
 * ```
 *
 * This is the half the engine used to leave to the caller. An index over `$.items[*].sku` narrows to
 * the *documents* holding a value; which `$.items[N]` carried it is a walk of one document, and
 * everybody who indexed an array path was writing that walk by hand — differently each time, and
 * with `elementCount` throwing on a non-array and `stringValue()` throwing on a number as the two
 * ways it goes wrong quietly.
 *
 * **A sink, not a `Sequence`.** [VariantNode.value] is a view over mapped bytes, so a lazy sequence
 * would let one escape the snapshot that maps it — a read of freed memory, or on Windows a mapping
 * that then cannot be unmapped. Views here are valid for the duration of the call, in the words
 * `TermExtractor.extract` already uses; anything kept must be copied. [nodesIn] is the form that
 * accepts the copy of the *list*, and it copies no bytes either.
 *
 * **The set is a superset of the locations an index over this path recorded — never a subset**, and
 * that direction is chosen rather than incidental. `TermExtractor` bounds its walk by
 * `IndexOptions.maxDepth` and `maxChildren`, so an index over `$.items[*].sku` on a document with
 * more elements than `maxChildren` recorded a term for only the first of them. An expander applying
 * the same bound would return *fewer* nodes than the index matched, and a caller who narrowed by the
 * index and then expanded would find nothing — a silent wrong answer. Returning more is harmless:
 * a caller re-checks the value it was looking for anyway. So this walk has no depth, breadth or path
 * budget, and it must not acquire one.
 *
 * **A container is a node.** `$.items` stands for the array itself, `$` for the whole document.
 * That is `SegmentSketchBuilder`'s reading of what a path means — it records an observation for every
 * container, not only for scalars — and it is RFC 9535's, whose nodelist holds values of any type.
 * It is also what keeps the paragraph above true, since `TermExtractor` reaches its sink for scalars
 * alone.
 *
 * A step that does not apply yields nothing and is not an error: an absent field, a field step into
 * an array, an element step into an object. Same rule as `Variant.select`, and for the same reason —
 * "this document has nothing there" is an answer, not a failure.
 *
 * @throws app.oreshkov.rabosh.variant.VariantFormatException if the document's bytes do not decode.
 */
public fun CatalogPath.forEachNodeIn(document: Variant, sink: (VariantNode) -> Unit) {
    // Recursion descends one path step per frame, so its depth is this path's and not the
    // document's. A deep document costs nothing; only a deep *path* could, and a path is a value the
    // caller wrote.
    expandNodes(document, steps, 0, ArrayList(steps.size), sink)
}

/**
 * The same, materialised.
 *
 * For a caller who wants the count, or an index into it, or to iterate twice. It is `O(nodes)` in
 * both time and memory where [forEachNodeIn] is `O(1)` in memory, which on a path ending in `[*]`
 * over a wide array is the whole difference. The `VariantNode`s it holds are still views, and the
 * lifetime rule on [forEachNodeIn] applies to them unchanged: the list outlives the call, the bytes
 * behind the values do not.
 */
public fun CatalogPath.nodesIn(document: Variant): List<VariantNode> =
    buildList { forEachNodeIn(document) { add(it) } }

private fun expandNodes(
    value: Variant,
    steps: List<CatalogStep>,
    depth: Int,
    location: ArrayList<VariantPathStep>,
    sink: (VariantNode) -> Unit,
) {
    if (depth == steps.size) {
        // Copied, not shared: `location` is one buffer for the whole walk, and a node outlives the
        // frame that reported it.
        sink(VariantNode(VariantPath(location.toList()), value))
        return
    }

    when (val step = steps[depth]) {
        is CatalogStep.Field -> {
            if (value.basicType != VariantBasicType.OBJECT) return
            val child = value.field(step.name) ?: return
            location.add(VariantPathStep.Field(step.name))
            expandNodes(child, steps, depth + 1, location, sink)
            location.removeAt(location.size - 1)
        }

        CatalogStep.AnyElement -> {
            if (value.basicType != VariantBasicType.ARRAY) return
            val count = value.elementCount
            for (index in 0 until count) {
                location.add(VariantPathStep.Index(index))
                expandNodes(value.element(index), steps, depth + 1, location, sink)
                location.removeAt(location.size - 1)
            }
        }
    }
}
