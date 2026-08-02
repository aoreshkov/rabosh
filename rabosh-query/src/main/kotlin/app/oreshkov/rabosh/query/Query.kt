package app.oreshkov.rabosh.query

import app.oreshkov.rabosh.core.Key

/**
 * A predicate, what to return for each match, and how much of the store to look at.
 *
 * Immutable, and every builder returns a new one, so a query is safe to keep and to reuse across
 * snapshots. Rows come back **in key order**; there is no ordering by value, because a shredded
 * column is ordered only within a segment and a value order across segments would need either full
 * materialisation or a per-value merge — neither of which this phase claims.
 *
 * ```kotlin
 * val query = Query.where(path("$.team") eq "analytics" and (path("$.score") ge 10))
 *     .project(Projection.of("$.team", "$.score"))
 *     .limit(100)
 * ```
 */
public class Query private constructor(
    public val predicate: Predicate,
    public val projection: Projection,
    /** Inclusive lower key bound, pushed into both the index and the scan. */
    public val from: Key?,
    /** Inclusive upper key bound. */
    public val to: Key?,
    /** Rows to return at most, or [NO_LIMIT]. */
    public val limit: Int,
) {
    /** The same query returning [projection]. */
    public fun project(projection: Projection): Query = Query(predicate, projection, from, to, limit)

    /** The same query returning the fields named by [expressions]. See [Projection.of]. */
    public fun project(vararg expressions: String): Query = project(Projection.of(*expressions))

    /**
     * The same query restricted to keys in `[from, to]`, both bounds inclusive and both optional.
     *
     * A key range is pushed into both halves of the plan — as an ordinal range on the index side,
     * because ordinals ascend with keys, and as the cursor's own bounds on the scan side. It is not
     * a filter applied to the answer.
     */
    public fun range(from: Key?, to: Key?): Query = Query(predicate, projection, from, to, limit)

    /**
     * The same query returning at most [rows].
     *
     * Genuinely stops the work: rows come out in key order from a merge, so the ordinals past the
     * limit are never decoded, their documents never fetched and never projected. What it does not
     * bound is the bitmap algebra, which happens per segment before the first row is emitted.
     */
    public fun limit(rows: Int): Query {
        require(rows >= 0) { "a limit is a count of rows; use Query.NO_LIMIT for all of them" }
        return Query(predicate, projection, from, to, rows)
    }

    override fun toString(): String = buildString {
        append("where $predicate")
        append(" project $projection")
        if (from != null || to != null) append(" range [$from, $to]")
        if (limit != NO_LIMIT) append(" limit $limit")
    }

    public companion object {
        /** Every matching row. */
        public const val NO_LIMIT: Int = -1

        /** A query for the documents [predicate] holds of, returning keys. */
        public fun where(predicate: Predicate): Query =
            Query(predicate, Projection.KEY, from = null, to = null, limit = NO_LIMIT)

        /** Every document, returning keys. */
        public fun all(): Query = where(Predicate.True)
    }
}
