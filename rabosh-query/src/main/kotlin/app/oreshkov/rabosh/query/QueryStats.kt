package app.oreshkov.rabosh.query

import app.oreshkov.rabosh.index.IndexCoverage
import app.oreshkov.rabosh.index.IndexHandle

/** One index a plan used, and how far it reached at this snapshot. */
public class IndexUse internal constructor(
    public val index: IndexHandle,
    /** Covered, stale and total, as the reader saw the store. */
    public val coverage: IndexCoverage,
    /** Segments of *this snapshot* the index actually answered for. */
    public val segmentsEvaluated: Int,
) {
    override fun toString(): String =
        "IndexUse(#${index.id} ${index.path} ${index.kind}, evaluated=$segmentsEvaluated, $coverage)"
}

/**
 * What a query found and what it cost.
 *
 * The counters are the point rather than decoration. "The index answered without opening a document"
 * is a claim about **work**, and a result set cannot demonstrate it — which is why every assertion
 * the test suite makes on these numbers sits in the same test as the differential equality against a
 * full scan. On its own `documentsRead == 0` passes trivially for a query that returned nothing.
 *
 * Read them off a cursor at any point; they are final once it is exhausted.
 */
public class QueryStats internal constructor(
    public val rowsReturned: Int,
    /** Keys the plan offered, before the recheck and before deduplication across sources. */
    public val candidateKeys: Int,
    /**
     * Documents whose bytes were examined: scanned, rechecked, or opened for a projection.
     *
     * Zero is reachable, and its conditions are worth stating because they are the phase's claim:
     * a fully covered store with nothing unflushed, a plan whose indexes decided every candidate
     * outright, keys unique to their segment, and a projection asking for no document — or asking
     * only for fields a shredded column can give back exactly. See [rowsProjectedFromColumns].
     */
    public val documentsRead: Int,
    /**
     * Rows whose projected fields were read out of shredded columns rather than out of a document.
     *
     * The counter that says push-down actually fired. `documentsRead == 0` alone cannot: a query
     * returning nothing satisfies it, and so does one projecting only keys. Equal to [rowsReturned]
     * when every row was served from columns, and zero when no projected path had one — which is not
     * a failure but the ordinary case for a path nobody shredded.
     */
    public val rowsProjectedFromColumns: Int,
    /** Segments answered from sidecars. */
    public val segmentsIndexed: Int,
    /** Segments the plan had to read, because no index covered them at this snapshot. */
    public val segmentsScanned: Int,
    /** Segments whose column bounds or physical type ruled the predicate out without a block read. */
    public val segmentsSkipped: Int,
    public val blocksScanned: Int,
    public val blocksSkipped: Int,
    /** Whether the plan had to merge memtables, which no sidecar can ever cover. */
    public val scannedUnflushed: Boolean,
    public val indexes: List<IndexUse>,
) {
    override fun toString(): String =
        "QueryStats(rows=$rowsReturned, candidates=$candidateKeys, documentsRead=$documentsRead, " +
            "columnProjected=$rowsProjectedFromColumns, " +
            "segments indexed/scanned/skipped=$segmentsIndexed/$segmentsScanned/$segmentsSkipped, " +
            "blocks scanned/skipped=$blocksScanned/$blocksSkipped, unflushed=$scannedUnflushed, " +
            "indexes=$indexes)"
}
