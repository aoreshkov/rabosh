package app.oreshkov.rabosh.query

import app.oreshkov.rabosh.catalog.CatalogPath

/** How a [Predicate.Compare] compares. There is no `NE`; see [Predicate]. */
public enum class Comparison {
    /** Equal, with numbers compared across widths and strings as UTF-8 bytes. */
    EQ,

    /** Strictly less than. */
    LT,

    /** Less than or equal. */
    LE,

    /** Strictly greater than. */
    GT,

    /** Greater than or equal. */
    GE,
}

/**
 * What a query asks of a document.
 *
 * Two rules give the tree its meaning, and both are consequences of how a path is walked rather than
 * choices made here.
 *
 * **A leaf is existential over the values at its path.** `$.tags[*] eq "a"` holds for a document with
 * *any* tag equal to `a`, because the walk reports one value per occurrence and a leaf asks whether
 * any of them satisfies it. A "for all" reading would need a second walk and a second definition of
 * what a path means, which is the thing this engine most consistently refuses.
 *
 * **Each leaf is existential *independently*, so a conjunction over `[*]` is not correlated.** This is
 * the consequence of the rule above that surprises people, and it is a defined semantics rather than
 * an oversight:
 *
 * ```
 * {"items":[{"sku":"A","qty":1},{"sku":"B","qty":5}]}    matches
 * {"items":[{"sku":"A","qty":5},{"sku":"B","qty":1}]}    matches
 *
 * and($.items[*].sku eq "A", $.items[*].qty eq 5)
 * ```
 *
 * The first document matches although no single element satisfies both: the `sku` comes from element
 * 0 and the `qty` from element 1. Each leaf is settled by *any* value at its own path, and the
 * conjunction is then over the two per-leaf answers rather than over elements. The indexed and
 * unindexed answers are identical, which is the invariant holding exactly — an index changed the
 * speed and not the answer.
 *
 * **To correlate, ask for it: [ElemMatch].** `elemMatch(path("$.items[*]"), and(…))` holds only when
 * *one element* satisfies the whole of its operand. It is a separate node rather than a mode of
 * [And] precisely because the reading above is a defined semantics that must not change: an existing
 * conjunction means what it has always meant, and the correlated question is a different question
 * with a different spelling.
 *
 * **Splitting the document is still the other answer, and still a good one.** One key per element —
 * `order:00123#item:00007` — makes each element a document, so the conjunction is over one document
 * and the correlation is exact. It costs no engine feature, an ordered-key LSM reassembles the parent
 * in one contiguous range scan, and `$.items[*].sku` collapses to `$.sku`. Which to reach for is a
 * modelling decision: split when the elements are the things you query, and use [ElemMatch] when the
 * document is.
 *
 * **[Not] is the document-level complement of that**, so `not($.tags[*] eq "a")` holds for a document
 * whose tags are all something else, for one whose tags are numbers, and for one with no `tags` at
 * all. Which values count as comparable is type bracketing — a numeric predicate matches numbers only
 * — and that rule has exactly one definition in the engine, in `ColumnPredicate.matches`, which every
 * leaf lowers to.
 *
 * **There is deliberately no `NE` operator.** `not(path("$.a") eq 1)` is the only spelling, so
 * negation has one meaning; a separate `NE` would have to decide for itself what an absent path or a
 * string-valued `$.a` does, and the day it disagreed with `NOT EQ` nothing would say which was right.
 *
 * Build one with the DSL rather than by hand:
 *
 * ```kotlin
 * val predicate = and(
 *     path("$.team") eq "analytics",
 *     path("$.score") ge 10,
 *     not(path("$.retired").exists()),
 * )
 * ```
 */
public sealed interface Predicate {

    /** Matches every document. What an empty conjunction folds to. */
    public data object True : Predicate

    /** Matches nothing. What an empty disjunction folds to. */
    public data object False : Predicate

    /** Every operand holds. */
    public data class And(public val operands: List<Predicate>) : Predicate

    /** At least one operand holds. */
    public data class Or(public val operands: List<Predicate>) : Predicate

    /** The operand does not hold — of the document, not of a value. See [Predicate]. */
    public data class Not(public val operand: Predicate) : Predicate

    /** Some value at [path] compares [operator] against [value]. */
    public data class Compare(
        public val path: CatalogPath,
        public val operator: Comparison,
        public val value: QueryValue,
    ) : Predicate

    /** Some value at [path] equals one of [values]. The `IN` case. */
    public data class AnyOf(public val path: CatalogPath, public val values: List<QueryValue>) : Predicate

    /** [path] carries at least one value, the JSON null included. */
    public data class Exists(public val path: CatalogPath) : Predicate

    /** Some value at [path] is the JSON null. */
    public data class IsNull(public val path: CatalogPath) : Predicate

    /**
     * Some **single element** at [path] satisfies [operand], whose paths are relative to that element.
     *
     * ```
     * {"items":[{"sku":"A","qty":1},{"sku":"B","qty":5}]}
     *
     * and(     $.items[*].sku eq "A",  $.items[*].qty eq 5)    matches — different elements
     * elemMatch($.items[*], and($.sku eq "A", $.qty eq 5))     does not
     * ```
     *
     * **The paths inside are relative, and that is the whole of the type discipline here.** [path]
     * names the elements — a `CatalogPath` ending in `[*]`, ordinarily — and every path in [operand]
     * is read from an element as if it were the document. `$.sku` inside means the element's `sku`,
     * never the document's. Nesting is therefore ordinary: an `ElemMatch` inside an `ElemMatch` walks
     * two levels of elements, and each level's paths are relative to its own.
     *
     * **Existential over elements, and negated at the document like every other leaf.**
     * `not(elemMatch(…))` holds for a document where *no* element satisfies the operand — including
     * one with no elements at all, and one whose `items` is a string. It is not "some element fails
     * it"; the same rule, and the same reason, as [Not] over a `Compare`.
     *
     * **What it costs, and what makes it worth asking for.** Without an index this is a walk of each
     * element per document, which is what a caller was writing by hand anyway. With a
     * `IndexKind.COMPOSITE_TERM` index over [path] declaring exactly the fields the operand compares,
     * it is one dictionary lookup and the answer is *exact* — no recheck, no document opened. The
     * measurement that justified building that index is the gap this node closes: over corpora whose
     * element fields vary independently the uncorrelated conjunction returns **5-6x** the documents a
     * caller keeps.
     */
    public data class ElemMatch(public val path: CatalogPath, public val operand: Predicate) : Predicate
}

/**
 * Every path this predicate mentions **of the document**, deduplicated, in the order first seen.
 *
 * A [Predicate.ElemMatch] contributes its own path and *not* the paths inside it: those are read from
 * an element rather than from the document, so `$.sku` inside one is not the document's `$.sku` and
 * listing it here would name a location this predicate never looks at.
 */
public fun Predicate.paths(): List<CatalogPath> {
    val paths = LinkedHashSet<CatalogPath>()
    fun walk(predicate: Predicate) {
        when (predicate) {
            is Predicate.True, is Predicate.False -> Unit
            is Predicate.And -> predicate.operands.forEach(::walk)
            is Predicate.Or -> predicate.operands.forEach(::walk)
            is Predicate.Not -> walk(predicate.operand)
            is Predicate.Compare -> paths.add(predicate.path)
            is Predicate.AnyOf -> paths.add(predicate.path)
            is Predicate.Exists -> paths.add(predicate.path)
            is Predicate.IsNull -> paths.add(predicate.path)
            is Predicate.ElemMatch -> paths.add(predicate.path)
        }
    }
    walk(this)
    return paths.toList()
}
