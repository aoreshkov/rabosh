package app.oreshkov.rabosh.api

import app.oreshkov.rabosh.catalog.CatalogOptions
import app.oreshkov.rabosh.core.SegmentObserver
import app.oreshkov.rabosh.core.StoreOptions
import app.oreshkov.rabosh.index.IndexOptions

/**
 * Tuning for [Rabosh.open].
 *
 * Three nested option objects rather than a flattened copy of them: the layers below own their own
 * tuning, and a facade that restated every field would be a second place to change when one of them
 * grows a knob. What is added here is only what a *composition* of them needs — which layers exist,
 * whether opening pays for a scan, and where a caller's own observer goes.
 *
 * A plain class with default arguments rather than a `data class`, for the reason [StoreOptions]
 * writes down: `copy` and `componentN` would join the published ABI, and adding an option later
 * would then be a binary-incompatible change to a type whose whole purpose is to grow.
 */
public class RaboshOptions(
    /**
     * Tuning for the store. See [StoreOptions].
     *
     * Its [StoreOptions.segmentObserver] must be `null`: the facade owns that slot, because
     * composing the catalogs into it is the whole of what [Rabosh.open] does that a caller cannot
     * easily do themselves. Pass an observer of your own as [segmentObserver] below and it is
     * composed alongside them.
     */
    public val store: StoreOptions = StoreOptions.DEFAULT,
    /** Tuning for the schema catalog, when [schema] is `true`. See [CatalogOptions]. */
    public val catalog: CatalogOptions = CatalogOptions.DEFAULT,
    /** Tuning for the index catalog, when [indexes] is `true`. See [IndexOptions]. */
    public val index: IndexOptions = IndexOptions.DEFAULT,
    /**
     * Whether a schema catalog is created, collected into and read from.
     *
     * `false` costs nothing: no observer, no `.cat` sidecar written on any flush or compaction, and
     * no fold on any query. It is a construction-time choice rather than a runtime branch precisely
     * so that a store which wants neither layer pays for neither.
     *
     * With this off, [Rabosh.schema] and [Rabosh.indexCandidates] throw rather than returning an
     * empty model — a model of nothing and a model that was never collected are different answers.
     */
    public val schema: Boolean = true,
    /**
     * Whether an index catalog is created, collected into and read from.
     *
     * `false` costs nothing, in the same sense as [schema]: no `.idx` base sidecar is written for a
     * segment, so a store that will never be indexed carries no index overhead at all.
     *
     * With this off, [Rabosh.query], [Rabosh.createIndex] and the rest of the query surface throw:
     * the planner is built around an index catalog, and a query with no catalog is
     * [Rabosh.scan] under a different name rather than a degraded plan.
     */
    public val indexes: Boolean = true,
    /**
     * Whether [Rabosh.open] builds derived data for segments that have none before it returns.
     *
     * `true` is what "model later" means for a store that has been running without either layer: one
     * pass over the segments, on the calling thread, and the model is complete when `open` returns.
     * The cost is proportional to the store, so a large one opens slowly the first time and quickly
     * ever after — a segment already covered is skipped without being read.
     *
     * `false` attaches to whatever sidecars exist and returns immediately. Nothing is wrong with the
     * result: uncovered segments are scanned by queries, coverage reports the state honestly, and
     * [Rabosh.attach] finishes the job whenever it suits. What it is not is a mode where anything
     * silently answers from partial data — that distinction is the layers' own, and the facade only
     * chooses when to pay.
     */
    public val backfill: Boolean = true,
    /**
     * A caller's own observer, composed alongside the catalogs.
     *
     * The facade owns [StoreOptions.segmentObserver], and owning it must not shut anybody out of the
     * seam. This is composed through the same `CompositeSegmentObserver` the catalogs are, so it gets
     * the same per-observer failure isolation: an observer that throws costs its own segment and
     * nothing else.
     */
    public val segmentObserver: SegmentObserver? = null,
) {
    init {
        require(store.segmentObserver == null) {
            "Rabosh installs its own segment observer; pass yours as RaboshOptions.segmentObserver " +
                "so it is composed with the catalogs rather than replaced by them"
        }
    }

    override fun toString(): String =
        "RaboshOptions(schema=$schema, indexes=$indexes, backfill=$backfill, " +
            "segmentObserver=${segmentObserver?.let { it::class.simpleName } ?: "none"}, store=$store)"

    public companion object {
        /** Both layers on, a backfilling open, and each layer's own defaults. */
        public val DEFAULT: RaboshOptions = RaboshOptions()
    }
}
