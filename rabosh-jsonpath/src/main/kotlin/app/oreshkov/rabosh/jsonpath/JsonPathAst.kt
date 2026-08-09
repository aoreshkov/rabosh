package app.oreshkov.rabosh.jsonpath

import app.oreshkov.rabosh.variant.Variant

// The shape a compiled query is, and nothing else: no position, no source text, no evaluation.
//
// Every declaration here is `internal` on purpose. RFC 9535's grammar is somebody else's, and the
// day it grows a selector this AST changes shape — publishing it would make that an ABI event for a
// module whose whole public surface is meant to be `JsonPathQuery.compile` and two ways to walk a
// document. The one type a caller sees back is `VariantNode`, which `rabosh-variant` already owns.
//
// One thing here is a *parse* fact rather than a shape fact and is carried anyway:
// `QueryExpression.singularSpelling`. See its own comment.

/** One segment of a query: `['a']`, `.a`, `[0,1]`, `..a`. */
internal sealed interface Segment {
    /** Applied left to right; a segment with several selectors concatenates their results. */
    val selectors: List<Selector>

    /** `[…]` or `.name` — applied to the input node itself. */
    class Child(override val selectors: List<Selector>) : Segment

    /** `..[…]`, `..name`, `..*` — applied to the input node and every descendant, in pre-order. */
    class Descendant(override val selectors: List<Selector>) : Segment
}

/** One selector inside a segment. */
internal sealed interface Selector {
    /** `'name'`, `"name"`, or the `.name` shorthand. */
    class Name(val name: String) : Selector

    /** `*`. */
    object Wildcard : Selector

    /**
     * `[3]`, `[-1]`. Held as a `Long` because RFC 9535 §2.1 admits the whole I-JSON integer range,
     * which is wider than an array this engine can hold — an index outside the array selects
     * nothing, and that is an answer rather than an overflow.
     */
    class Index(val index: Long) : Selector

    /** `[start:end:step]`, each part absent when it was not written. */
    class Slice(val start: Long?, val end: Long?, val step: Long?) : Selector

    /** `[?…]`. */
    class Filter(val expression: FilterExpression) : Selector
}

/** Which node a query inside a filter starts from. */
internal enum class QueryRoot {
    /** `$` — the document the query was applied to, whatever node the filter is testing. */
    ROOT,

    /** `@` — the node the filter is testing. */
    CURRENT,
}

/** A `filter-query`: `@…` or `$…`, evaluated to a nodelist. */
internal class QueryExpression(
    val root: QueryRoot,
    val segments: List<Segment>,
    /**
     * Whether this query was **spelled** as RFC 9535's `singular-query` production.
     *
     * Not the same question as "does it produce at most one node", and the difference is why this
     * is a parse fact rather than something derived from [segments]. `singular-query-segments`
     * admits `['a']` and `[0]` and no whitespace inside the brackets, so `@[ 0 ]` produces at most
     * one node and is still not a singular query — and a `comparable` is required to be one. The
     * parser knows; nothing downstream could work it out.
     */
    val singularSpelling: Boolean,
)

/** The `logical-expr` of a filter selector. */
internal sealed interface FilterExpression {
    /** `||`, flattened: `a || b || c` is one node with three operands. */
    class Or(val operands: List<FilterExpression>) : FilterExpression

    /** `&&`, flattened for the same reason. */
    class And(val operands: List<FilterExpression>) : FilterExpression

    /** `!`. Applied to *this* node's answer, which is not what `Predicate.Not` means one module over. */
    class Not(val operand: FilterExpression) : FilterExpression

    /** A bare query in a logical position: true when it selects at least one node. */
    class Existence(val query: QueryExpression) : FilterExpression

    /** A function whose declared result type is `LogicalType`, or a `NodesType` one converted to it. */
    class Call(val call: FunctionCall) : FilterExpression

    /** `comparable op comparable`. */
    class Comparison(
        val left: ComparableExpression,
        val operator: ComparisonOperator,
        val right: ComparableExpression,
    ) : FilterExpression
}

/** RFC 9535 §2.3.5.1's six. */
internal enum class ComparisonOperator(val spelling: String) {
    EQUAL("=="),
    NOT_EQUAL("!="),
    LESS("<"),
    LESS_OR_EQUAL("<="),
    GREATER(">"),
    GREATER_OR_EQUAL(">="),
}

/** One side of a comparison. Always `ValueType`: a value, or `Nothing`. */
internal sealed interface ComparableExpression {
    /**
     * A literal, compiled to a [Variant] once.
     *
     * Done at compile time so that comparison has **one** implementation — `Variant` against
     * `Variant` — rather than three. A literal is a scalar by RFC 9535's grammar, so this costs a
     * few bytes on the heap per literal in the query and nothing per document.
     */
    class Literal(val value: Variant) : ComparableExpression

    /** A `singular-query`: at most one node, or `Nothing`. */
    class Singular(val query: SingularQuery) : ComparableExpression

    /** A function whose declared result type is `ValueType`. */
    class Call(val call: FunctionCall) : ComparableExpression
}

/** `@['a'][0].b` — the only query shape a comparison may hold. */
internal class SingularQuery(val root: QueryRoot, val steps: List<SingularStep>)

/** One step of a [SingularQuery]. */
internal sealed interface SingularStep {
    class Name(val name: String) : SingularStep

    class Index(val index: Long) : SingularStep
}

/** A function expression, with its arguments already checked against the declared parameter types. */
internal class FunctionCall(
    val function: JsonPathFunction,
    val arguments: List<FunctionArgument>,
    /** Where `match` and `search` get their I-Regexp from; `null` for the other three. */
    val pattern: PatternSource?,
)

/**
 * The second argument of `match` and `search`, resolved as far as compile time can take it.
 *
 * RFC 9535 lets the pattern be any `ValueType`, so it may be a value of the *document* and known only
 * per node. It usually is not: it is usually a literal, and this is the same move
 * [ComparableExpression.Literal] already makes — pay the cost once at compile time, so that applying
 * the query to a document touches no grammar at all, neither JSONPath's nor RFC 9485's.
 */
internal sealed interface PatternSource {

    /**
     * A string literal in the query, compiled once.
     *
     * [regexp] is `null` when the literal is not a string, or is a string that is not an I-Regexp.
     * That is **not** a compile failure: §2.4.6 says a non-conforming second argument makes the
     * result `LogicalFalse`, so `$[?match(@.a, '[')]` is a valid query that selects nothing — and
     * rejecting it would be this module refusing a query the specification admits.
     */
    class Fixed(val regexp: IRegexp?) : PatternSource

    /** Anything else: the pattern is read from the document and compiled per candidate node. */
    object PerNode : PatternSource
}

/** An argument, in the form the declared parameter type asked for. */
internal sealed interface FunctionArgument {
    /** For a `ValueType` parameter. */
    class Value(val comparable: ComparableExpression) : FunctionArgument

    /** For a `NodesType` parameter. */
    class Nodes(val query: QueryExpression) : FunctionArgument

    /** For a `LogicalType` parameter, including a query converted to one. */
    class Logical(val expression: FilterExpression) : FunctionArgument
}
