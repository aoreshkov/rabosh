package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.catalog.DEFAULT_MAX_CHILDREN

/**
 * What to do about a sidecar that will not decode.
 *
 * The same choice `DamagedSketchPolicy` offers, and for the same reason: an index is derived, so
 * damage costs a rescan rather than data — but which of "tell me" and "fix it" is wanted depends on
 * whether anybody is watching.
 */
public enum class DamagedIndexPolicy {
    /** Raise the failure. The default, because silent repair hides a disk that is going. */
    REPORT,

    /** Record it in `IndexCatalog.problems`, delete the sidecar, and rebuild it from the segment. */
    REBUILD,
}

/**
 * Tuning for [IndexCatalog].
 *
 * Every bound here is on *caller-controlled shape* rather than on volume, and that is the same
 * distinction `CatalogOptions` draws. A document's nesting, its array lengths and the number of
 * distinct values at a path all come from whoever wrote it, and this code runs inside flush and
 * compaction — a document that made the walk expensive would make the engine's background
 * maintenance expensive, which is a far worse outcome than an index that reports itself incomplete.
 */
public class IndexOptions(
    /**
     * Distinct terms one index may hold for one segment.
     *
     * Exceeding it **drops the index for that segment**, which then reads as not covered and is
     * scanned. It does not keep the first *n* terms: a dictionary holding some of a path's values
     * and no record of which ones is an index that returns wrong answers, and nothing downstream
     * could tell it apart from a complete one. The same rule that makes a missing sidecar read as
     * "not collected" rather than "collected and empty".
     */
    public val maxTermsPerSegment: Int = 1 shl 20,

    /**
     * Bytes one term may occupy.
     *
     * A path whose values are whole documents encoded as strings would otherwise put those documents
     * into the term dictionary twice over. A value above this contributes nothing to the index; the
     * document is still found by the scan that covers what the index does not.
     */
    public val maxTermBytes: Int = 512,

    /** How deep the walk goes. Matches `CatalogOptions.maxDepth`'s reasoning. */
    public val maxDepth: Int = 32,

    /**
     * How many fields of an object, or elements of an array, are visited.
     *
     * **The default is `CatalogOptions`', deliberately, and it used to be a quarter of it.** This walk
     * is `SegmentSketchBuilder`'s with a filter on it — `TermExtractor` says so and gives the reason:
     * a differently-shaped traversal would make the estimator and the index disagree about what a
     * path *is*. A narrower bound here is exactly such a disagreement, and it points the wrong way.
     * The catalog counted a path's occurrences to 4096 and recommended an index on the strength of
     * them; the index then recorded 1024 of them and reported itself covered. Tie the two together so
     * that an index records what the model measured, and set both in the same place —
     * [app.oreshkov.rabosh.catalog.DEFAULT_MAX_CHILDREN], where the argument for the number is.
     *
     * **It takes [maxTermsPerSegment]'s escape, and until it did it was the one budget in the engine
     * that could cost a document silently.** A container wider than this is walked to the bound, so
     * the terms recorded for a path under it are a *prefix* of the values the document holds there —
     * and a dictionary holding some of a path's values with no record of which ones is an index that
     * deletes documents from a result. So a segment in which this bound fired is left **not covered**
     * by the index being built, exactly as one whose dictionary overflowed is, and the scan that
     * replaces it walks the document whole: `TermExtractor.reading` carries no budget, because the
     * argument for one is about background maintenance and a query is not that.
     *
     * The consequence to plan for is coverage, not correctness. Lowering this number costs scans;
     * raising it costs walk time inside flush and compaction; neither changes an answer. A store
     * whose containers are wider than this reports the shortfall in `IndexCatalog.problems` and in
     * [IndexCoverage], which is what a caller tunes against.
     */
    public val maxChildren: Int = DEFAULT_MAX_CHILDREN,

    /** What to do about a sidecar that will not decode. */
    public val damagedSidecars: DamagedIndexPolicy = DamagedIndexPolicy.REPORT,

    // Options added by later phases go at the end, so adding one is not an ABI break. The same rule
    // `StoreOptions` follows, and the reason both are plain classes rather than data classes.

    /**
     * Values one shredded column may hold for one segment.
     *
     * Exceeding it drops the column for that segment, which then reads as not covered and is scanned
     * — the same escape [maxTermsPerSegment] takes, and for the same reason: a column holding some of
     * a path's values with no record of which ones would answer a range predicate wrongly, and
     * nothing downstream could tell it apart from a complete one.
     */
    public val maxColumnValuesPerSegment: Int = 1 shl 24,

    /**
     * Bytes of a string a column's bounds may keep before truncating.
     *
     * Truncation **widens**: the minimum is a prefix and the maximum is a prefix with its last byte
     * raised, so a truncated bound still contains every value it stands for. A smaller limit
     * therefore costs skipping precision and never correctness.
     */
    public val columnTextBoundBytes: Int = 64,
) {
    init {
        require(maxTermsPerSegment > 0) { "maxTermsPerSegment must be positive, was $maxTermsPerSegment" }
        require(maxTermBytes in 1..IndexFormat.MAX_TERM_BYTES) {
            "maxTermBytes must be in 1..${IndexFormat.MAX_TERM_BYTES}, was $maxTermBytes"
        }
        require(maxDepth > 0) { "maxDepth must be positive, was $maxDepth" }
        require(maxChildren > 0) { "maxChildren must be positive, was $maxChildren" }
        require(maxColumnValuesPerSegment > 0) {
            "maxColumnValuesPerSegment must be positive, was $maxColumnValuesPerSegment"
        }
        require(columnTextBoundBytes > 0) {
            "columnTextBoundBytes must be positive, was $columnTextBoundBytes"
        }
    }

    override fun toString(): String =
        "IndexOptions(maxTermsPerSegment=$maxTermsPerSegment, maxTermBytes=$maxTermBytes, " +
            "maxColumnValuesPerSegment=$maxColumnValuesPerSegment, " +
            "columnTextBoundBytes=$columnTextBoundBytes, " +
            "maxDepth=$maxDepth, maxChildren=$maxChildren, damagedSidecars=$damagedSidecars)"

    public companion object {
        public val DEFAULT: IndexOptions = IndexOptions()
    }
}

/**
 * How much of a store an index can answer for, at one snapshot.
 *
 * **Three counters where `CatalogCoverage` has two**, and the third is the important one. A segment
 * is *stale* when the index over it is complete and correct and nonetheless unusable at this
 * snapshot: an observation reports only the newest version of each key, but a segment may hold
 * several versions of one key whenever an older snapshot pinned them, and a reader older than the
 * segment's largest sequence is entitled to see a version the index never recorded. The guard is
 * exact — an index over a segment is sound at snapshot `S` if and only if `S.sequence` is at or
 * above that segment's largest sequence — and a segment that fails it is scanned instead.
 *
 * That is the same mechanism as a segment with no sidecar at all: one concept, two causes, both
 * ending in "the caller scans this one". Which is why an index is usable while it is still being
 * built, with no cutover — a half-built index is simply an index with low coverage.
 */
public class IndexCoverage internal constructor(
    /** Segments this index can answer for at this snapshot. */
    public val segmentsCovered: Int,
    /** Segments with a complete index that this snapshot is too old to use. */
    public val segmentsStale: Int,
    /** Live segments in total. */
    public val segmentsTotal: Int,
) {
    /** Whether every live segment is covered and usable. */
    public val isComplete: Boolean get() = segmentsCovered == segmentsTotal

    /** Segments the caller has to scan: the ones missing an index and the ones too new for it. */
    public val segmentsUncovered: Int get() = segmentsTotal - segmentsCovered

    /** Covered share of the live segments, `1.0` for a store with none. */
    public val fraction: Double
        get() = if (segmentsTotal == 0) 1.0 else segmentsCovered.toDouble() / segmentsTotal

    override fun toString(): String =
        "IndexCoverage($segmentsCovered/$segmentsTotal covered, $segmentsStale stale)"
}
