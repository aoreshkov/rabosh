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
 * **Conformance, stated at the strength of its evidence.** The grammar, the selectors, the descendant
 * segment, the filter selector and all five function extensions are implemented and checked against
 * the JSONPath Compliance Test Suite: **all 703 of its cases run and pass**, with nothing excluded and
 * the corpus's shape asserted before any case does. `match` and `search` are answered by an
 * [RFC 9485](https://www.rfc-editor.org/info/rfc9485/) I-Regexp matcher written for this module — a
 * Thompson construction, so it costs the pattern times the subject and never backtracks, which is
 * what makes a filter safe to run over a corpus with a pattern that came from the data.
 *
 * **Immutable and thread-safe.** Compiling is the expensive half and holding the result is the point
 * of the name — a query re-parsed per document is what this API exists to stop. One instance may be
 * applied to any number of documents from any number of threads at once.
 *
 * @see forEachNodeIn for the lifetime rule the results carry.
 */
public class JsonPathQuery private constructor(
    private val text: String,
    private val segments: List<Segment>,
    /**
     * What one evaluation of this query is allowed to cost. See [JsonPathLimits].
     *
     * On the query rather than on the call because it belongs with the expression it bounds: a
     * caller that compiled something untrusted should not have to remember to pass the limits again
     * at every use. [forEachNodeIn] takes an override for the case where one document is known to be
     * larger than the rest.
     */
    public val limits: JsonPathLimits,
) {

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
     * **The walk still carries no budget that *truncates*, and must not acquire one**: a short
     * nodelist is a wrong answer with nothing to say so. What it carries is a budget that
     * **refuses** — [limits], or [limits] overridden here — which raises
     * [JsonPathLimitExceededException] and delivers nothing rather than delivering part of an answer.
     * The two are opposite mechanisms and the distinction is the whole of why this is safe: a caller
     * that catches the exception knows it has no answer, where a caller handed a truncated nodelist
     * cannot tell it from a small document. The bounds on the *query* — 1024 selectors, 64 levels of
     * nesting — are separate again, and [compile] applies those.
     *
     * Nodes already handed to [sink] before a limit is met are **not** an answer and must not be
     * treated as one; the exception says the evaluation was abandoned, not that it finished early.
     *
     * @param limits overrides the query's own for this call. Pass [JsonPathLimits.NONE] to evaluate
     *   a trusted query over a document known to be large.
     * @throws JsonPathLimitExceededException if the evaluation costs more than [limits] allows.
     * @throws app.oreshkov.rabosh.variant.VariantFormatException if the document's bytes do not
     *   decode. A value the engine cannot read is reported, never skipped.
     */
    @JvmOverloads
    public fun forEachNodeIn(
        document: Variant,
        limits: JsonPathLimits = this.limits,
        sink: (VariantNode) -> Unit,
    ) {
        // Fresh per call. The limits are shared; the counters are not, which is what keeps one
        // compiled query applicable from any number of threads at once.
        val context = Evaluation(document, limits)
        applySegments(segments, 0, document, NodeLocation.ROOT, context) { value, location ->
            context.produce()
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
     *
     * This is the shape [JsonPathLimits.maxNodesProduced] is really for: a sink can drop what it does
     * not want, and a list cannot.
     *
     * @throws JsonPathLimitExceededException if the evaluation costs more than [limits] allows, in
     *   which case no list is returned at all.
     */
    @JvmOverloads
    public fun nodesIn(document: Variant, limits: JsonPathLimits = this.limits): List<VariantNode> =
        buildList { forEachNodeIn(document, limits) { add(it) } }

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
         * **[limits] is a different kind of bound and bounds a different thing.** Those two say how
         * large the expression may be; [JsonPathLimits] says how much one *evaluation* of it may
         * cost, which is the gap a small valid query over a large document walks straight through —
         * `$..*..*` is eleven characters and quadratic. Exceeding it raises
         * [JsonPathLimitExceededException] rather than returning a short nodelist. The defaults are a
         * backstop sized so no honest query meets them; a caller compiling expressions it does not
         * trust should set its own and set them far lower.
         *
         * **A regular expression is not one of the things this refuses.** `match` and `search` take
         * an RFC 9485 I-Regexp, and §2.4.6 rules that a second argument which is not one makes the
         * *result* `LogicalFalse` — so `$[?match(@.a, '[')]` compiles, and selects nothing. A literal
         * pattern is compiled here all the same, so that applying the query touches no grammar.
         *
         * @param limits what one evaluation of the compiled query may cost. See [JsonPathLimits].
         * @throws IllegalArgumentException if [query] is not a valid JSONPath query, with the
         *   offending position, or if it exceeds either limit.
         */
        @JvmStatic
        @JvmOverloads
        public fun compile(query: String, limits: JsonPathLimits = JsonPathLimits.DEFAULT): JsonPathQuery =
            JsonPathQuery(query, JsonPathParser(query).parse(), limits)
    }
}
