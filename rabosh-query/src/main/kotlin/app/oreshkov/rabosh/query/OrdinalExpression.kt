package app.oreshkov.rabosh.query

import app.oreshkov.rabosh.index.Bitmap
import app.oreshkov.rabosh.index.ReadableBitmap

/**
 * What an index expression said about one segment's ordinals.
 *
 * Two sets and the gap between them. [candidates] is a **superset** of the ordinals whose recorded
 * version satisfies the predicate — an ordinal outside it is dropped without a document being read,
 * so the superset property is the one thing here that must never be wrong. [certain] is a **subset**
 * the index decided outright; everything between the two is a document to open.
 *
 * Neither is an answer about the *visible* version of a key. That question is the executor's, and
 * answering it needs more than one segment.
 */
internal class Ordinals(val candidates: ReadableBitmap, val certain: ReadableBitmap) {
    companion object {
        /** An index that answered without qualification: every candidate is a match. */
        fun exact(ordinals: ReadableBitmap): Ordinals = Ordinals(ordinals, ordinals)
    }
}

/**
 * The predicate as bitmap algebra over one segment at a time.
 *
 * This is where the phase's claim is cashed: `team = "x" AND price in [10, 20]` intersects two
 * bitmaps over the same ordinal space and only then decodes what survives. Both indexes over a
 * segment hang off that segment's one base sidecar, so their ordinals are the same ordinals.
 *
 * **No operation mutates an operand.** Only `and`, `or` and `andNot` are used, all constructive by
 * contract — which matters because `Bitmap.union` hands back a bitmap that two branches of a
 * disjunction may both be holding.
 */
internal sealed interface OrdinalExpression {

    class Source(val source: LeafSource) : OrdinalExpression

    /**
     * Every operand.
     *
     * [complete] is `false` when a conjunct was left out because nothing could answer it — a leaf
     * over an unindexed path, or one whose only index is of the wrong kind. Dropping it is legal,
     * because a conjunction's candidates only widen; what it costs is the right to *decide*, and
     * forgetting to record that is a residual predicate silently ignored.
     */
    class All(val operands: List<OrdinalExpression>, val complete: Boolean = true) : OrdinalExpression

    /** Any operand. */
    class Any(val operands: List<OrdinalExpression>) : OrdinalExpression
}

/**
 * An expression narrowed to the sources one segment actually has.
 *
 * [whole] records whether anything was dropped getting here. A restriction that dropped a conjunct
 * still bounds the answer — that is what makes dropping legal — but it no longer *decides* it, so
 * [evaluate] hands back no certainty and every candidate is rechecked.
 */
internal class Restriction(val expression: OrdinalExpression, val whole: Boolean)

/**
 * This expression restricted to [segment], or `null` when its candidates would not be a superset
 * there.
 *
 * **The soundness argument, in one function.**
 *
 * An [OrdinalExpression.Any] needs *every* operand: a branch of a union that cannot be evaluated is a
 * set of documents nobody would look for, which is a missing answer rather than a slow one.
 *
 * An [OrdinalExpression.All] may **drop** operands, because dropping a conjunct only ever widens the
 * candidate set — the survivors still bound the answer and the recheck removes the rest.
 *
 * A node with nothing left is `null`: its candidate set would be the segment's whole document
 * universe, and decoding every ordinal there to a key is strictly more work than scanning it.
 */
internal fun OrdinalExpression.restrictTo(segment: Long): Restriction? = when (this) {
    is OrdinalExpression.Source ->
        if (segment in source.reader.usableSegments) Restriction(this, whole = true) else null

    is OrdinalExpression.All -> {
        val kept = operands.mapNotNull { it.restrictTo(segment) }
        if (kept.isEmpty()) {
            null
        } else {
            Restriction(
                OrdinalExpression.All(kept.map { it.expression }),
                // Two ways to lose the right to decide, and both have to count: a conjunct dropped
                // here because this segment lacks the sidecar, and one dropped when the plan was
                // built because nothing anywhere could answer it.
                whole = complete && kept.size == operands.size && kept.all { it.whole },
            )
        }
    }

    is OrdinalExpression.Any -> {
        val kept = operands.map { it.restrictTo(segment) }
        if (kept.any { it == null }) {
            null
        } else {
            val parts = kept.filterNotNull()
            Restriction(OrdinalExpression.Any(parts.map { it.expression }), whole = parts.all { it.whole })
        }
    }
}

/**
 * Evaluates over [segment], optionally within the ordinal range [within].
 *
 * Conjuncts are intersected in ascending order of actual cardinality and the intersection stops the
 * moment it is empty. That is the one cost decision here that is exact rather than estimated: a
 * posting list's cardinality is in its header, so the cheapest order is known rather than guessed.
 */
internal fun Restriction.evaluate(segment: Long, within: IntRange?, work: WorkStats): Ordinals {
    val found = expression.evaluateNode(segment, work)
    val candidates = if (within == null) found.candidates else found.candidates.and(range(within))
    // A dropped conjunct means the survivors bound the answer without deciding it.
    val certain = when {
        !whole -> Bitmap()
        within == null -> found.certain
        else -> found.certain.and(range(within))
    }
    return Ordinals(candidates, certain)
}

private fun range(within: IntRange): ReadableBitmap =
    if (within.isEmpty()) Bitmap() else Bitmap.ofRange(within)

private fun OrdinalExpression.evaluateNode(segment: Long, work: WorkStats): Ordinals = when (this) {
    is OrdinalExpression.Source -> source.ordinals(segment, work)

    is OrdinalExpression.All -> {
        val parts = operands.map { it.evaluateNode(segment, work) }.sortedBy { it.candidates.cardinality }
        var candidates: ReadableBitmap = parts.first().candidates
        var certain: ReadableBitmap = parts.first().certain
        for (part in parts.drop(1)) {
            if (candidates.isEmpty) break
            candidates = candidates.and(part.candidates)
            certain = certain.and(part.certain)
        }
        Ordinals(candidates, certain)
    }

    is OrdinalExpression.Any -> {
        val parts = operands.map { it.evaluateNode(segment, work) }
        var candidates: ReadableBitmap = parts.first().candidates
        // One branch certainly matching is the union certainly matching.
        var certain: ReadableBitmap = parts.first().certain
        for (part in parts.drop(1)) {
            candidates = candidates.or(part.candidates)
            certain = certain.or(part.certain)
        }
        Ordinals(candidates, certain)
    }
}

/** Every source this expression reads. */
internal fun OrdinalExpression.sources(): List<LeafSource> = buildList { collectSources(this@sources, this) }

private fun collectSources(expression: OrdinalExpression, into: MutableList<LeafSource>) {
    when (expression) {
        is OrdinalExpression.Source -> into.add(expression.source)
        is OrdinalExpression.All -> expression.operands.forEach { collectSources(it, into) }
        is OrdinalExpression.Any -> expression.operands.forEach { collectSources(it, into) }
    }
}

internal fun OrdinalExpression.render(): String = when (this) {
    is OrdinalExpression.Source -> source.toString()
    is OrdinalExpression.All -> operands.joinToString(" and ", "(", ")") { it.render() }
    is OrdinalExpression.Any -> operands.joinToString(" or ", "(", ")") { it.render() }
}
