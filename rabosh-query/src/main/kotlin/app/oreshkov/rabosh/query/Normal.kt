package app.oreshkov.rabosh.query

import app.oreshkov.rabosh.catalog.CatalogPath
import app.oreshkov.rabosh.index.ColumnPredicate
import app.oreshkov.rabosh.index.IndexOptions
import app.oreshkov.rabosh.index.IndexTerm
import app.oreshkov.rabosh.variant.Variant

/**
 * Rewrites a predicate into negation-normal form and folds away what it can.
 *
 * Negations end up directly above leaves and nowhere else, which is what lets the planner look at a
 * tree of `And`/`Or` over leaves and decide which of them an index can answer. Everything here is a
 * rewrite between predicates that hold for exactly the same documents.
 *
 * **One rewrite is forbidden, and it is the one that looks most obviously correct.**
 *
 * ```
 * not(path("$.score") ge 10)   is NOT   path("$.score") lt 10
 * ```
 *
 * The first holds for a document whose `score` is `"high"`, and for one with no `score` at all. The
 * second holds for neither, because a numeric predicate matches numbers only — that is the type
 * bracketing the whole engine is built on. Flipping the operator would delete documents from a
 * result, silently, and no differential test that only ever writes numbers at `$.score` would notice.
 * So a negated leaf stays a negated leaf, and the negation is applied to the *document's* answer.
 */
internal fun Predicate.normalise(): Predicate = rewrite(negated = false)

private fun Predicate.rewrite(negated: Boolean): Predicate = when (this) {
    is Predicate.True -> if (negated) Predicate.False else Predicate.True
    is Predicate.False -> if (negated) Predicate.True else Predicate.False
    is Predicate.Not -> operand.rewrite(!negated)
    is Predicate.And -> junction(operands, negated, conjunction = !negated)
    is Predicate.Or -> junction(operands, negated, conjunction = negated)

    is Predicate.AnyOf -> {
        val values = values.distinct()
        when {
            values.isEmpty() -> if (negated) Predicate.True else Predicate.False
            values.size == 1 -> Predicate.Compare(path, Comparison.EQ, values.single()).negatedIf(negated)
            else -> Predicate.AnyOf(path, values).negatedIf(negated)
        }
    }

    // An element node is atomic to this rewrite: De Morgan may not reach inside it, because "no
    // element satisfies P" is not "some element satisfies not P". The operand is normalised in its
    // own right — unnegated — and the negation stays on the node, exactly as it does on a leaf.
    is Predicate.ElemMatch -> Predicate.ElemMatch(path, operand.rewrite(negated = false)).negatedIf(negated)

    // A leaf, and the operator is never flipped by a negation. See the file KDoc.
    is Predicate.Compare, is Predicate.Exists, is Predicate.IsNull -> negatedIf(negated)
}

/** De Morgan: a negated `And` is a disjunction of negated operands, and the other way about. */
private fun junction(operands: List<Predicate>, negated: Boolean, conjunction: Boolean): Predicate {
    val rewritten = ArrayList<Predicate>(operands.size)
    for (operand in operands) {
        val normal = operand.rewrite(negated)
        // Flattening as it goes, so `a and (b and c)` is one node rather than a chain nothing else
        // would think to look through.
        val children = when {
            conjunction && normal is Predicate.And -> normal.operands
            !conjunction && normal is Predicate.Or -> normal.operands
            else -> listOf(normal)
        }
        for (child in children) {
            val absorbing = if (conjunction) Predicate.False else Predicate.True
            val identity = if (conjunction) Predicate.True else Predicate.False
            if (child == absorbing) return absorbing
            if (child == identity) continue
            if (child !in rewritten) rewritten.add(child)
        }
    }
    return when {
        rewritten.isEmpty() -> if (conjunction) Predicate.True else Predicate.False
        rewritten.size == 1 -> rewritten.single()
        conjunction -> Predicate.And(rewritten)
        else -> Predicate.Or(rewritten)
    }
}

private fun Predicate.negatedIf(negated: Boolean): Predicate = if (negated) Predicate.Not(this) else this

/**
 * A predicate in negation-normal form, with every leaf lowered to what the engine can evaluate.
 *
 * The difference from [Predicate] is that this is no longer a description: every leaf carries the
 * [ColumnPredicate] that decides it and, where one exists, the terms an inverted index is keyed by.
 * Building both here is what makes the planner a matter of choosing sources rather than of
 * interpreting operators twice.
 */
internal sealed interface Normal {

    data object AlwaysTrue : Normal

    data object AlwaysFalse : Normal

    class Conjunction(val operands: List<Normal>) : Normal

    class Disjunction(val operands: List<Normal>) : Normal

    /**
     * One test at one path.
     *
     * **Existential in the values, negated at the document.** [test] asks whether *a* value satisfies
     * the leaf; [negated] is applied to the answer for the whole document, after every value has had
     * its chance. That is the only reading consistent with a walk that reports one value per
     * occurrence — see [Predicate].
     */
    class Leaf(
        val path: CatalogPath,
        val kind: LeafKind,
        /** Satisfied when **any** of these is: one per literal, so `IN` is one leaf rather than a union. */
        val predicates: List<ColumnPredicate>,
        /**
         * The terms an inverted index is keyed by, or `null` when it cannot spell this leaf.
         *
         * Meaningful for [LeafKind.EQUALITY] only. Existence is answered by a posting file's presence
         * bitmap rather than by any term, and a range must never be answered by an inverted index at
         * all, so both carry `null` and neither means "no matches".
         */
        val terms: Set<IndexTerm>?,
        val negated: Boolean,
    ) : Normal {
        /** Whether this one value satisfies the leaf, ignoring [negated]. */
        fun test(value: Variant): Boolean = predicates.any { it.matches(value) }

        override fun toString(): String =
            (if (negated) "not " else "") + "$path ${predicates.joinToString(" or ")}"
    }

    /**
     * One element at [path] satisfies [inner], whose paths are relative to that element.
     *
     * **Atomic to everything above it, and that is what keeps the semantics honest.** [inner] is a
     * whole lowered tree over a *different* universe of paths, so `leaves()` does not descend into it
     * and the outer `TermExtractor` never sees its paths. The negation is at this node, applied to
     * the document's answer — "no element satisfies it" — for the same reason a leaf's is.
     */
    class Element(
        val path: CatalogPath,
        val inner: Normal,
        val negated: Boolean,
    ) : Normal {
        override fun toString(): String =
            (if (negated) "not " else "") + "elemMatch($path, $inner)"
    }
}

/** What a leaf asks, which is what decides whether an index kind can answer it. */
internal enum class LeafKind { EQUALITY, RANGE, EXISTS, IS_NULL }

/**
 * Lowers a normalised predicate, giving every leaf its [ColumnPredicate].
 *
 * **Every leaf gets one whether or not a column exists over its path**, and that is the point rather
 * than an accident: `.claude/rules/index-and-query.md` makes `ColumnPredicate.matches` the only
 * definition of type bracketing, *used by the column scan and the fallback document scan alike*. A
 * query layer that
 * wrote its own matcher for the paths with only an inverted index — or with no index at all — would
 * be a second definition, and the two would eventually disagree about a numeric string or a nested
 * null.
 */
internal fun Predicate.lower(options: IndexOptions): Normal = when (this) {
    is Predicate.True -> Normal.AlwaysTrue
    is Predicate.False -> Normal.AlwaysFalse
    is Predicate.And -> Normal.Conjunction(operands.map { it.lower(options) })
    is Predicate.Or -> Normal.Disjunction(operands.map { it.lower(options) })
    // `normalise` leaves a negation only above a leaf. A caller lowering a tree they built by hand
    // may not have run it, so this pushes rather than refuses — the same De Morgan, one level down.
    is Predicate.Not -> operand.lower(options).negate()

    is Predicate.ElemMatch -> Normal.Element(path, operand.lower(options), negated = false)

    is Predicate.Exists -> leaf(path, LeafKind.EXISTS, listOf(ColumnPredicate.exists()), terms = null)
    is Predicate.IsNull -> leaf(path, LeafKind.IS_NULL, listOf(ColumnPredicate.isNull()), null)

    is Predicate.Compare -> when (operator) {
        Comparison.EQ -> equality(path, listOf(value), options)
        else -> range(path, operator, value)
    }

    is Predicate.AnyOf -> equality(path, values, options)
}

private fun leaf(
    path: CatalogPath,
    kind: LeafKind,
    predicates: List<ColumnPredicate>,
    terms: Set<IndexTerm>?,
): Normal.Leaf = Normal.Leaf(path, kind, predicates, terms, negated = false)

/** De Morgan over a lowered tree. A leaf flips its own flag; a junction swaps and pushes down. */
private fun Normal.negate(): Normal = when (this) {
    Normal.AlwaysTrue -> Normal.AlwaysFalse
    Normal.AlwaysFalse -> Normal.AlwaysTrue
    is Normal.Leaf -> Normal.Leaf(path, kind, predicates, terms, negated = !negated)
    // The flag flips and `inner` is left alone: pushing the negation inside would turn "no element
    // satisfies P" into "some element satisfies not P", which is a different set of documents.
    is Normal.Element -> Normal.Element(path, inner, negated = !negated)
    is Normal.Conjunction -> Normal.Disjunction(operands.map { it.negate() })
    is Normal.Disjunction -> Normal.Conjunction(operands.map { it.negate() })
}

/**
 * An equality or `IN` leaf, with the terms an inverted index could answer it by.
 *
 * `terms` is `null` — meaning "no inverted source" rather than "no matches" — when any literal cannot
 * be spelled as a term: the JSON null, which has no signature at all and is therefore *present*
 * rather than keyed, and a value above [IndexOptions.maxTermBytes], which the writer dropped on the
 * way in. Taking a false negative from either is exactly the bug `IndexReader.answers` exists to
 * prevent, one layer down.
 */
private fun equality(path: CatalogPath, values: List<QueryValue>, options: IndexOptions): Normal {
    if (values.isEmpty()) return Normal.AlwaysFalse
    val predicates = values.map(::equalityPredicate)
    val kind = if (values.all { it == QueryValue.Null }) LeafKind.IS_NULL else LeafKind.EQUALITY
    val terms = LinkedHashSet<IndexTerm>(values.size)
    for (value in values) {
        val term = termOf(value) ?: return leaf(path, kind, predicates, terms = null)
        if (term.size > options.maxTermBytes) return leaf(path, kind, predicates, terms = null)
        terms.add(term)
    }
    return leaf(path, kind, predicates, terms)
}

private fun equalityPredicate(value: QueryValue): ColumnPredicate = when (value) {
    is QueryValue.Text -> ColumnPredicate.textEqualTo(value.value)
    is QueryValue.Numeric -> ColumnPredicate.numericEqualTo(value.value)
    is QueryValue.Bool -> ColumnPredicate.booleanEqualTo(value.value)
    QueryValue.Null -> ColumnPredicate.isNull()
}

private fun termOf(value: QueryValue): IndexTerm? = when (value) {
    is QueryValue.Text -> IndexTerm.ofString(value.value)
    is QueryValue.Numeric -> IndexTerm.ofNumber(value.value)
    is QueryValue.Bool -> IndexTerm.ofBoolean(value.value)
    // A JSON null carries no signature: a document holding one *has* the path, which is what makes
    // `EXISTS` exact, and there is nothing for a term dictionary to key it by.
    QueryValue.Null -> null
}

/**
 * An ordered leaf.
 *
 * A boolean or a null bound folds to [Normal.AlwaysFalse] rather than to an error: neither family has
 * an ordering here, so nothing can lie inside such a range, and by the type-bracketing rule that is a
 * value that does not match rather than a query that is wrong. There is no inverted source, ever —
 * `ValueSignature` sorts `NUMERIC || "10"` before `NUMERIC || "9"`, which is a lookup order and a
 * useless value order.
 */
private fun range(path: CatalogPath, operator: Comparison, value: QueryValue): Normal {
    val minExclusive = operator == Comparison.GT
    val maxExclusive = operator == Comparison.LT
    val lower = operator == Comparison.GT || operator == Comparison.GE
    val predicate = when (value) {
        is QueryValue.Numeric -> ColumnPredicate.numericRange(
            min = if (lower) value.value else null,
            minExclusive = minExclusive,
            max = if (lower) null else value.value,
            maxExclusive = maxExclusive,
        )

        is QueryValue.Text -> ColumnPredicate.textRange(
            min = if (lower) value.value else null,
            minExclusive = minExclusive,
            max = if (lower) null else value.value,
            maxExclusive = maxExclusive,
        )

        is QueryValue.Bool, QueryValue.Null -> return Normal.AlwaysFalse
    }
    return leaf(path, LeafKind.RANGE, listOf(predicate), terms = null)
}

/**
 * Every leaf of a normalised tree that reads **the document**, in the order they appear.
 *
 * Deliberately does not descend into a [Normal.Element]: its leaves read an *element*, so handing
 * them to the document's `TermExtractor` would ask for `$.sku` of the document when the predicate
 * asked for `$.sku` of an item. `DocumentMatcher` builds a nested matcher for each element node
 * instead, which is the same class over the same walk one level down.
 */
internal fun Normal.leaves(): List<Normal.Leaf> = buildList { collectLeaves(this@leaves, this) }

private fun collectLeaves(normal: Normal, into: MutableList<Normal.Leaf>) {
    when (normal) {
        is Normal.Leaf -> into.add(normal)
        is Normal.Conjunction -> normal.operands.forEach { collectLeaves(it, into) }
        is Normal.Disjunction -> normal.operands.forEach { collectLeaves(it, into) }
        is Normal.Element -> Unit
        Normal.AlwaysTrue, Normal.AlwaysFalse -> Unit
    }
}

/** Every element node of a normalised tree, in the order they appear. Not recursive into one another. */
internal fun Normal.elements(): List<Normal.Element> = buildList { collectElements(this@elements, this) }

private fun collectElements(normal: Normal, into: MutableList<Normal.Element>) {
    when (normal) {
        is Normal.Element -> into.add(normal)
        is Normal.Conjunction -> normal.operands.forEach { collectElements(it, into) }
        is Normal.Disjunction -> normal.operands.forEach { collectElements(it, into) }
        is Normal.Leaf, Normal.AlwaysTrue, Normal.AlwaysFalse -> Unit
    }
}
