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
 * **To correlate, split the document.** One key per element — `order:00123#item:00007` — makes each
 * element a document, so the conjunction is over one document and the correlation is exact. It costs
 * no engine feature, an ordered-key LSM reassembles the parent in one contiguous range scan, and
 * `$.items[*].sku` collapses to `$.sku`. Elasticsearch's `nested` and MongoDB's `$elemMatch` are the
 * mechanisms built for this question, and current Elasticsearch guidance itself puts document
 * splitting ahead of them.
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
}

/** Every path this predicate mentions, deduplicated, in the order first seen. */
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
        }
    }
    walk(this)
    return paths.toList()
}
