package app.oreshkov.rabosh.jsonpath

import app.oreshkov.rabosh.variant.Variant
import app.oreshkov.rabosh.variant.VariantBasicType
import app.oreshkov.rabosh.variant.VariantKind
import app.oreshkov.rabosh.variant.VariantPath
import app.oreshkov.rabosh.variant.VariantPathStep

// Applying a compiled query to one document.
//
// Three decisions shape this file, and each is here rather than in the KDoc because a reader who
// changes one of them will be looking at the code.
//
// **The composition is by sink, so a frame is per *segment* and never per document level.**
// `applySegments` recurses once per segment and each segment streams into the next, which means the
// nodelist of an intermediate segment is never materialised: `$..*..*` over a large document costs
// its answer and not the product of its stages. The recursion is bounded by the query — the parser
// refuses more than 1024 selectors — and a query is a value the caller wrote.
//
// **The descendant walk is iterative, over an explicit stack.** `DEFAULT_MAX_JSON_DEPTH` bounds
// anything `JsonParser` ingested, but a `Variant` assembled through `VariantBuilder` is never
// re-checked against it, so a recursive descent here would be a stack overflow reachable from data.
// With the stack explicit there is no guard to get wrong and no answer to truncate — which matters
// more than the crash, because a truncated nodelist is a *wrong answer with no signal*.
//
// **A sink answers `false` to stop.** Existence tests and `value()` need one node and two nodes
// respectively, and a filter that walked an entire subtree to learn what its first node already said
// would make `$[?@..x]` cost the document rather than the answer. Every loop below honours it.

/**
 * Where a node is, as a link to its parent rather than as a path.
 *
 * A walk visits far more nodes than it reports — every descendant of every candidate — so the
 * location of a node has to be cheap to *extend* and is only ever paid for in full when a node is
 * emitted. One small object per visited node, sharing its whole prefix with its parent, against a
 * copied list per node.
 */
internal class NodeLocation private constructor(
    private val parent: NodeLocation?,
    private val step: VariantPathStep?,
    private val depth: Int,
) {
    /** This location extended by one step. */
    fun child(step: VariantPathStep): NodeLocation = NodeLocation(this, step, depth + 1)

    /** The location as a [VariantPath], built root-first. Called once per *emitted* node. */
    fun toPath(): VariantPath {
        val steps = ArrayList<VariantPathStep>(depth)
        var node: NodeLocation? = this
        while (node?.step != null) {
            steps.add(node.step)
            node = node.parent
        }
        steps.reverse()
        return VariantPath(steps)
    }

    companion object {
        /** `$`. */
        val ROOT: NodeLocation = NodeLocation(null, null, 0)
    }
}

/** Receives one node. Answers `false` to stop the walk; every caller here honours that. */
internal fun interface NodeSink {
    fun emit(value: Variant, location: NodeLocation): Boolean
}

/**
 * Applies [segments] from [index] onwards, streaming into [sink].
 *
 * @param root the document, which `$` inside a filter resolves against however deep the walk is.
 * @return `false` if the sink asked to stop.
 */
internal fun applySegments(
    segments: List<Segment>,
    index: Int,
    value: Variant,
    location: NodeLocation,
    root: Variant,
    sink: NodeSink,
): Boolean {
    if (index == segments.size) return sink.emit(value, location)
    return applySegment(segments[index], value, location, root) { next, at ->
        applySegments(segments, index + 1, next, at, root, sink)
    }
}

private fun applySegment(
    segment: Segment,
    value: Variant,
    location: NodeLocation,
    root: Variant,
    out: NodeSink,
): Boolean = when (segment) {
    is Segment.Child -> applySelectors(segment.selectors, value, location, root, out)
    is Segment.Descendant -> descend(segment.selectors, value, location, root, out)
}

private fun applySelectors(
    selectors: List<Selector>,
    value: Variant,
    location: NodeLocation,
    root: Variant,
    out: NodeSink,
): Boolean {
    for (selector in selectors) {
        if (!applySelector(selector, value, location, root, out)) return false
    }
    return true
}

/**
 * Depth-first pre-order: the selectors are applied to a node **before** its children are visited.
 *
 * Children are pushed in reverse so that popping yields document order, which is what makes
 * `$..a`'s nodelist the one RFC 9535 §2.5.2.2 describes rather than a permutation of it.
 */
private fun descend(
    selectors: List<Selector>,
    value: Variant,
    location: NodeLocation,
    root: Variant,
    out: NodeSink,
): Boolean {
    val pending = ArrayDeque<Visit>()
    pending.addLast(Visit(value, location))
    while (pending.isNotEmpty()) {
        val visit = pending.removeLast()
        if (!applySelectors(selectors, visit.value, visit.location, root, out)) return false
        pushChildren(visit, pending)
    }
    return true
}

private class Visit(val value: Variant, val location: NodeLocation)

private fun pushChildren(visit: Visit, pending: ArrayDeque<Visit>) {
    val value = visit.value
    when (value.basicType) {
        VariantBasicType.ARRAY -> {
            for (index in value.elementCount - 1 downTo 0) {
                pending.addLast(Visit(value.element(index), visit.location.child(VariantPathStep.Index(index))))
            }
        }

        VariantBasicType.OBJECT -> {
            for (index in value.fieldCount - 1 downTo 0) {
                pending.addLast(
                    Visit(value.fieldValue(index), visit.location.child(VariantPathStep.Field(value.fieldName(index)))),
                )
            }
        }

        VariantBasicType.PRIMITIVE, VariantBasicType.SHORT_STRING -> Unit
    }
}

private fun applySelector(
    selector: Selector,
    value: Variant,
    location: NodeLocation,
    root: Variant,
    out: NodeSink,
): Boolean = when (selector) {
    is Selector.Name -> {
        val child = if (value.basicType == VariantBasicType.OBJECT) value.field(selector.name) else null
        child == null || out.emit(child, location.child(VariantPathStep.Field(selector.name)))
    }

    Selector.Wildcard -> applyWildcard(value, location, out)

    is Selector.Index -> applyIndex(selector.index, value, location, out)

    is Selector.Slice -> applySlice(selector, value, location, out)

    is Selector.Filter -> applyFilter(selector.expression, value, location, root, out)
}

private fun applyWildcard(value: Variant, location: NodeLocation, out: NodeSink): Boolean {
    when (value.basicType) {
        VariantBasicType.ARRAY -> {
            val count = value.elementCount
            for (index in 0 until count) {
                if (!out.emit(value.element(index), location.child(VariantPathStep.Index(index)))) return false
            }
        }

        VariantBasicType.OBJECT -> {
            val count = value.fieldCount
            for (index in 0 until count) {
                val at = location.child(VariantPathStep.Field(value.fieldName(index)))
                if (!out.emit(value.fieldValue(index), at)) return false
            }
        }

        VariantBasicType.PRIMITIVE, VariantBasicType.SHORT_STRING -> Unit
    }
    return true
}

/**
 * An index selector, negative counting from the end.
 *
 * The index is a `Long` because the grammar admits I-JSON's whole range; an array this engine can
 * hold is indexed by an `Int`, so an index outside it simply selects nothing. That is an answer —
 * `$[9007199254740991]` is a perfectly good query over a two-element array — and not an overflow.
 */
private fun applyIndex(index: Long, value: Variant, location: NodeLocation, out: NodeSink): Boolean {
    if (value.basicType != VariantBasicType.ARRAY) return true
    val count = value.elementCount
    val resolved = if (index >= 0) index else count + index
    if (resolved < 0 || resolved >= count) return true
    val at = resolved.toInt()
    return out.emit(value.element(at), location.child(VariantPathStep.Index(at)))
}

/**
 * RFC 9535 §2.3.4.2.2's slice, transcribed rather than re-derived.
 *
 * The arithmetic is in `Long` throughout because `start`, `end` and `step` each range over I-JSON's
 * integers while the array's length is an `Int`; mixing the two is how a slice acquires an overflow
 * that only fires on a query nobody writes twice. A zero step selects nothing, which the RFC states
 * and which is *not* the same as a step of one.
 */
private fun applySlice(slice: Selector.Slice, value: Variant, location: NodeLocation, out: NodeSink): Boolean {
    if (value.basicType != VariantBasicType.ARRAY) return true
    val length = value.elementCount.toLong()
    val step = slice.step ?: 1L
    if (step == 0L) return true

    if (step > 0) {
        val lower = normalise(slice.start ?: 0L, length).coerceIn(0L, length)
        val upper = normalise(slice.end ?: length, length).coerceIn(0L, length)
        var at = lower
        while (at < upper) {
            if (!out.emit(value.element(at.toInt()), location.child(VariantPathStep.Index(at.toInt())))) return false
            at += step
        }
    } else {
        val upper = normalise(slice.start ?: (length - 1), length).coerceIn(-1L, length - 1)
        val lower = normalise(slice.end ?: (-length - 1), length).coerceIn(-1L, length - 1)
        var at = upper
        while (lower < at) {
            if (!out.emit(value.element(at.toInt()), location.child(VariantPathStep.Index(at.toInt())))) return false
            at += step
        }
    }
    return true
}

private fun normalise(index: Long, length: Long): Long = if (index >= 0) index else length + index

/**
 * A filter selects among a node's **children**, never the node itself.
 *
 * That is the whole difference between `$[?@.a]` and `$[?@]`, and it is the reason a filter over a
 * scalar selects nothing rather than testing the scalar.
 */
private fun applyFilter(
    expression: FilterExpression,
    value: Variant,
    location: NodeLocation,
    root: Variant,
    out: NodeSink,
): Boolean {
    when (value.basicType) {
        VariantBasicType.ARRAY -> {
            val count = value.elementCount
            for (index in 0 until count) {
                val element = value.element(index)
                if (!testFilter(expression, element, root)) continue
                if (!out.emit(element, location.child(VariantPathStep.Index(index)))) return false
            }
        }

        VariantBasicType.OBJECT -> {
            val count = value.fieldCount
            for (index in 0 until count) {
                val member = value.fieldValue(index)
                if (!testFilter(expression, member, root)) continue
                val at = location.child(VariantPathStep.Field(value.fieldName(index)))
                if (!out.emit(member, at)) return false
            }
        }

        VariantBasicType.PRIMITIVE, VariantBasicType.SHORT_STRING -> Unit
    }
    return true
}

/** Evaluates a `logical-expr` against one candidate node. Never throws for a shape it did not expect. */
internal fun testFilter(expression: FilterExpression, current: Variant, root: Variant): Boolean =
    when (expression) {
        is FilterExpression.Or -> expression.operands.any { testFilter(it, current, root) }
        is FilterExpression.And -> expression.operands.all { testFilter(it, current, root) }
        is FilterExpression.Not -> !testFilter(expression.operand, current, root)
        is FilterExpression.Existence -> hasNode(expression.query, current, root)
        is FilterExpression.Call -> testCall(expression.call, current, root)

        is FilterExpression.Comparison -> compareValues(
            valueOf(expression.left, current, root),
            expression.operator,
            valueOf(expression.right, current, root),
        )
    }

/** The two functions whose declared result is `LogicalType`. Nothing else reaches a logical position. */
private fun testCall(call: FunctionCall, current: Variant, root: Variant): Boolean = when (call.function) {
    JsonPathFunction.MATCH -> testPattern(call, current, root, anchored = true)
    JsonPathFunction.SEARCH -> testPattern(call, current, root, anchored = false)

    // A ValueType function in a logical position is rejected while parsing — `$[?length(@.a)]` is one
    // of the 247 invalid selectors — and no registered function returns NodesType. Stated rather than
    // assumed, so that a sixth function has to be classified here too.
    JsonPathFunction.LENGTH, JsonPathFunction.COUNT, JsonPathFunction.VALUE ->
        error("'${call.function.spelling}' returns ${call.function.result} and cannot be tested")
}

/**
 * RFC 9535 §2.4.6 and §2.4.7, which differ by one word and therefore by one flag.
 *
 * **Every failure is `LogicalFalse` and none is an error.** A subject that is not a string, a pattern
 * that is not a string, a pattern that is a string and not an I-Regexp — the specification gives all
 * three the same answer, and giving any of them an exception would make a filter over a corpus fail
 * on the one document whose field holds a number.
 */
private fun testPattern(call: FunctionCall, current: Variant, root: Variant, anchored: Boolean): Boolean {
    val subject = stringOf(valueArgument(call, 0, current, root)) ?: return false
    val regexp = when (val pattern = call.pattern) {
        // Compiled once, when the query was compiled.
        is PatternSource.Fixed -> pattern.regexp

        // The pattern is a value of the document, so it is read and compiled for this node. No memo:
        // a cache would be the first mutable state in a class that promises immutability, and
        // nothing has measured the compile against the walk it sits inside.
        PatternSource.PerNode, null -> stringOf(valueArgument(call, 1, current, root))?.let(IRegexp::compileOrNull)
    } ?: return false
    return if (anchored) regexp.matches(subject) else regexp.search(subject)
}

/** A `ValueType` as its string, or `null` for `Nothing` and for every value that is not a string. */
private fun stringOf(value: FilterValue): String? {
    if (value !is FilterValue.Node) return null
    return if (value.value.kind == VariantKind.STRING) value.value.stringValue() else null
}

/** Whether [query] selects at least one node. Stops at the first, which is what the sink is for. */
private fun hasNode(query: QueryExpression, current: Variant, root: Variant): Boolean {
    var found = false
    applySegments(query.segments, 0, startOf(query.root, current, root), NodeLocation.ROOT, root) { _, _ ->
        found = true
        false
    }
    return found
}

private fun startOf(queryRoot: QueryRoot, current: Variant, root: Variant): Variant = when (queryRoot) {
    QueryRoot.ROOT -> root
    QueryRoot.CURRENT -> current
}

/** The `ValueType` a comparison operand carries for this candidate node. */
private fun valueOf(comparable: ComparableExpression, current: Variant, root: Variant): FilterValue =
    when (comparable) {
        is ComparableExpression.Literal -> FilterValue.Node(comparable.value)

        is ComparableExpression.Singular ->
            resolve(comparable.query, current, root)?.let { FilterValue.Node(it) } ?: FilterValue.Absent

        is ComparableExpression.Call -> evaluateCall(comparable.call, current, root)
    }

/**
 * Follows a singular query, or answers `null` for `Nothing`.
 *
 * Written as a loop rather than routed through [applySegments] because a singular query is exactly
 * `Variant.select` with negative indices added — no nodelist, no sink, no location. A comparison
 * runs once per candidate node, so this is the hottest path in the module.
 */
private fun resolve(query: SingularQuery, current: Variant, root: Variant): Variant? {
    var value = startOf(query.root, current, root)
    for (step in query.steps) {
        val next = when (step) {
            is SingularStep.Name ->
                if (value.basicType == VariantBasicType.OBJECT) value.field(step.name) else null

            is SingularStep.Index -> if (value.basicType == VariantBasicType.ARRAY) {
                val count = value.elementCount
                val resolved = if (step.index >= 0) step.index else count + step.index
                if (resolved in 0 until count.toLong()) value.element(resolved.toInt()) else null
            } else {
                null
            }
        }
        value = next ?: return null
    }
    return value
}

private fun evaluateCall(call: FunctionCall, current: Variant, root: Variant): FilterValue =
    when (call.function) {
        JsonPathFunction.LENGTH -> lengthOf(valueArgument(call, 0, current, root))
        JsonPathFunction.COUNT -> FilterValue.Integral(countNodes(nodesArgument(call, 0), current, root))
        JsonPathFunction.VALUE -> singleNode(nodesArgument(call, 0), current, root)

        // Both are LogicalType, and a LogicalType function is never a comparison operand: the parser
        // reports `match(…) == true` as one of the invalid selectors rather than compiling it.
        JsonPathFunction.MATCH, JsonPathFunction.SEARCH ->
            error("'${call.function.spelling}' returns LogicalType and is not a comparison operand")
    }

private fun valueArgument(call: FunctionCall, index: Int, current: Variant, root: Variant): FilterValue =
    valueOf((call.arguments[index] as FunctionArgument.Value).comparable, current, root)

private fun nodesArgument(call: FunctionCall, index: Int): QueryExpression =
    (call.arguments[index] as FunctionArgument.Nodes).query

/**
 * RFC 9535 §2.4.4: the number of Unicode scalar values in a string, members in an object, elements
 * in an array — and `Nothing` for anything else, including a number and including `Nothing` itself.
 */
private fun lengthOf(value: FilterValue): FilterValue {
    if (value !is FilterValue.Node) return FilterValue.Absent
    val subject = value.value
    return when (subject.basicType) {
        VariantBasicType.ARRAY -> FilterValue.Integral(subject.elementCount.toLong())
        VariantBasicType.OBJECT -> FilterValue.Integral(subject.fieldCount.toLong())
        VariantBasicType.SHORT_STRING -> FilterValue.Integral(codePointLength(subject.stringValue()))
        VariantBasicType.PRIMITIVE -> if (subject.kind == VariantKind.STRING) {
            FilterValue.Integral(codePointLength(subject.stringValue()))
        } else {
            FilterValue.Absent
        }
    }
}

private fun codePointLength(text: String): Long = text.codePointCount(0, text.length).toLong()

private fun countNodes(query: QueryExpression, current: Variant, root: Variant): Long {
    var count = 0L
    applySegments(query.segments, 0, startOf(query.root, current, root), NodeLocation.ROOT, root) { _, _ ->
        count++
        true
    }
    return count
}

/**
 * RFC 9535 §2.4.8: the value of a one-node nodelist, and `Nothing` for any other size.
 *
 * Stops after the second node, because "more than one" is all the rule needs and a nodelist over a
 * large document is not worth counting to answer it.
 */
private fun singleNode(query: QueryExpression, current: Variant, root: Variant): FilterValue {
    var first: Variant? = null
    var several = false
    applySegments(query.segments, 0, startOf(query.root, current, root), NodeLocation.ROOT, root) { value, _ ->
        if (first == null) {
            first = value
            true
        } else {
            several = true
            false
        }
    }
    val only = first
    return if (several || only == null) FilterValue.Absent else FilterValue.Node(only)
}
