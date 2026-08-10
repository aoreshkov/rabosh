package app.oreshkov.rabosh.query

import app.oreshkov.rabosh.RaboshExperimental
import app.oreshkov.rabosh.catalog.InferredSchema
import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.core.Key
import app.oreshkov.rabosh.core.Snapshot
import app.oreshkov.rabosh.index.IndexCatalog

/**
 * Answers queries against a store, using the indexes that exist and scanning what they do not cover.
 *
 * ```kotlin
 * val engine = QueryEngine(store, indexes)
 * engine.execute(Query.where(path("$.team") eq "analytics")).use { rows ->
 *     while (rows.next()) println(rows.key)
 *     println(rows.stats)
 * }
 * ```
 *
 * **An index changes how fast this runs, never what it returns.** That is not a hope about the
 * planner; it is a property of how a plan is put together. An index yields candidates over the
 * segments it can answer for at this snapshot, everything else is scanned, and every candidate is
 * rechecked against the version the snapshot sees by the same walk that built the index. A store
 * whose index is half built, or stale for this snapshot, or has unflushed writes, is not a special
 * case: it is a plan with more segments in the scanned half.
 *
 * The engine holds nothing and owns nothing. It is safe to make one per query or one per store, and
 * to use one from several threads at once — the state of a query lives in its [QueryCursor].
 */
public class QueryEngine @RaboshExperimental constructor(
    private val store: DocumentStore,
    private val indexes: IndexCatalog,
    /**
     * Statistics used to decide which sidecars are worth reading, if there are any.
     *
     * Taken as a value rather than as a `SchemaCatalog` deliberately: the engine already asks a
     * caller to manage a store and an index catalog, and a planner that also owned a catalog's
     * lifecycle would make that three. Pass `catalog.inferSchema()` and refresh it when it suits —
     * or open a `Rabosh` from `rabosh-api`, which holds one of these and refolds it when the set of
     * live segments changes, which is the only thing that can make the fold stale.
     */
    private val schema: InferredSchema? = null,
) {
    /**
     * Runs [query] and returns its rows.
     *
     * Without a [snapshot] the cursor takes one and closes it with itself, so a query is consistent
     * whether or not the caller asked for that — the pattern `DocumentStore.scan` already sets.
     */
    @JvmOverloads
    public fun execute(query: Query, snapshot: Snapshot? = null): QueryCursor {
        val view = snapshot ?: store.snapshot()
        return try {
            val plan = QueryPlanner.plan(store, indexes, schema, query, view, indexes.options)
            QueryCursor(store, query, view, ownedSnapshot = if (snapshot == null) view else null, plan = plan)
        } catch (failure: Throwable) {
            if (snapshot == null) runCatching { view.close() }
            throw failure
        }
    }

    /** The keys [query] matches, materialised. Convenient where the result is known to be small. */
    @JvmOverloads
    public fun keys(query: Query, snapshot: Snapshot? = null): List<Key> =
        execute(query.project(Projection.KEY), snapshot).use { cursor ->
            buildList { while (cursor.next()) add(cursor.key) }
        }

    /**
     * How [query] would be answered, and why.
     *
     * Reads the sidecars it would use, so the cardinalities it reports are measured rather than
     * estimated. That is what makes it usable as a test instrument: asserting which index a plan
     * chose, and in which order it intersects, without timing anything.
     */
    @JvmOverloads
    public fun explain(query: Query, snapshot: Snapshot? = null): Explain {
        val view = snapshot ?: store.snapshot()
        try {
            val plan = QueryPlanner.plan(store, indexes, schema, query, view, indexes.options)
            try {
                return Explain.of(query, plan, schema)
            } finally {
                plan.readers.forEach { runCatching { it.close() } }
            }
        } finally {
            if (snapshot == null) runCatching { view.close() }
        }
    }
}
