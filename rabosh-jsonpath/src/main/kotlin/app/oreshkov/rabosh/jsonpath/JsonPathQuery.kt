package app.oreshkov.rabosh.jsonpath

import app.oreshkov.rabosh.variant.Variant
import app.oreshkov.rabosh.variant.VariantNode

/**
 * A compiled [RFC 9535](https://www.rfc-editor.org/info/rfc9535/) JSONPath query, applied to one
 * document the caller is already holding.
 *
 * ```kotlin
 * val document = Variant.fromJson("""{"items":[{"sku":"a","qty":1},{"sku":"b","qty":5}]}""")
 * val query = JsonPathQuery.compile("$.items[?@.sku == 'b' && @.qty > 2]")
 *
 * query.forEachNodeIn(document) { println(it.toJsonSummaryString()) }
 * //   $['items'][1] {"qty":5,"sku":"b"}
 * ```
 *
 * **What this answers that nothing else in the engine does.** `CatalogPath.forEachNodeIn` answers
 * *where is this path*; an index answers *which documents hold a value*. Neither can express *which
 * elements satisfy a condition*, and until this module the answer was a walk every caller wrote by
 * hand — differently each time. `$..` is the other half: a location shape here does not have to know
 * how deeply the data nests, which is what a self-recursive document needs.
 *
 * **It cannot change which documents a query returns, by construction.** This module sits beside the
 * storage chain rather than in it: nothing in `rabosh-core`, `rabosh-index`, `rabosh-query` or
 * `rabosh-api` depends on it, so no plan, no bound and no posting list can reach this grammar. That
 * matters because RFC 9535's comparison rules and the engine's `Predicate` rules genuinely disagree —
 * `$.a == 1` over `{"a":[1,2]}` is false in both and for different reasons, `!` here complements the
 * *node* while `Not` there complements the *document* — and two definitions of comparison are a
 * defect exactly when both can decide the same question. Here they cannot.
 *
 * **Conformance, stated at the strength of its evidence.** The grammar, the selectors, the
 * descendant segment, the filter selector and the `length`, `count` and `value` function extensions
 * are implemented and checked against the JSONPath Compliance Test Suite: 647 of its 703 cases run,
 * every one of them passes, and the 56 that do not run are excluded by tag and counted. Those 56 are
 * `match` and `search`, which are defined over RFC 9485 I-Regexp; [compile] **rejects** a query
 * naming either rather than compiling something that would answer. So this is not "RFC 9535" without
 * qualification, and it will not be described as such until the number is 703.
 *
 * **Immutable and thread-safe.** Compiling is the expensive half and holding the result is the point
 * of the name — a query re-parsed per document is what this API exists to stop. One instance may be
 * applied to any number of documents from any number of threads at once.
 *
 * @see forEachNodeIn for the lifetime rule the results carry.
 */
public class JsonPathQuery private constructor(private val text: String, private val segments: List<Segment>) {

    /**
     * Every node [document] holds at this query's locations, in RFC 9535's nodelist order.
     *
     * **A sink, not a `Sequence`, and for the same reason `CatalogPath.forEachNodeIn` is one.**
     * [VariantNode.value] is a view over the bytes it was built from, so a lazy sequence would let one
     * escape the snapshot that maps them — a read of freed memory, or on Windows a mapping that then
     * cannot be unmapped. The nodes are valid for the duration of the call; anything kept beyond it
     * must be copied, with `Variant.toByteArray`. [VariantNode.location] is an ordinary value and
     * outlives everything.
     *
     * The walk carries no depth, breadth or path budget and must not acquire one: a truncated
     * nodelist is a wrong answer with nothing to say so. The bounds are on the *query* instead, and
     * [compile] applies them.
     *
     * @throws app.oreshkov.rabosh.variant.VariantFormatException if the document's bytes do not
     *   decode. A value the engine cannot read is reported, never skipped.
     */
    public fun forEachNodeIn(document: Variant, sink: (VariantNode) -> Unit) {
        applySegments(segments, 0, document, NodeLocation.ROOT, document) { value, location ->
            sink(VariantNode(location.toPath(), value))
            true
        }
    }

    /**
     * The same, materialised.
     *
     * For a caller who wants the count, or an index into it, or to iterate twice. `O(nodes)` in
     * memory where [forEachNodeIn] is `O(1)`. The nodes it holds are still views and the lifetime
     * rule on [forEachNodeIn] applies to them unchanged: the list outlives the call, the bytes behind
     * the values do not.
     */
    public fun nodesIn(document: Variant): List<VariantNode> = buildList { forEachNodeIn(document) { add(it) } }

    /** The query as it was written. */
    override fun toString(): String = text

    public companion object {
        /**
         * Compiles [query], or reports where it is wrong.
         *
         * `compile` rather than `parse`, because the name says the object is worth keeping: the
         * whole cost of a JSONPath query is here, and applying a compiled one to a document touches
         * no grammar at all.
         *
         * **Strict, on purpose.** RFC 9535's 247 invalid-selector cases are each rejected with a
         * position — `$.a[]`, `$[01]`, `$[?@.a]]`, `$['a'"b']`, `length(@.a, @.b)`. A lenient reader
         * is what makes two implementations disagree later, and this one is read by nobody but the
         * caller who wrote the query, so there is no compatibility to buy with leniency.
         *
         * Two limits apply to the query itself, and neither can cost an answer because both are
         * bounds on what the caller wrote: at most **1024 selectors**, and at most **64** levels of
         * nested filters, parentheses and function calls.
         *
         * @throws IllegalArgumentException if [query] is not a valid JSONPath query, with the
         *   offending position; if it exceeds either limit; or if it names `match` or `search`,
         *   which this build declares and does not implement — see the class documentation.
         */
        public fun compile(query: String): JsonPathQuery = JsonPathQuery(query, JsonPathParser(query).parse())
    }
}
