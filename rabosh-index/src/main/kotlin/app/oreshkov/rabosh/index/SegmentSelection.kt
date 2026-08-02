package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.core.Key
import app.oreshkov.rabosh.core.Snapshot

/**
 * Which pinned segments an index can answer for at one snapshot.
 *
 * **The soundness predicate lives here and nowhere else.** An observation reports only the newest
 * version of each key, but a segment holds older versions exactly when a snapshot pinned them — so an
 * index over a segment is sound at snapshot `S` if and only if `S.sequence` is at or above that
 * segment's largest sequence. A second copy of that rule drifting from this one is the failure §9.7
 * records as unreachable by any differential test taken at the current sequence, and there are now two
 * readers that need it.
 *
 * A segment that fails the predicate is *stale*; a segment carrying no sidecar for this index is
 * *missing*. Both end in "the caller scans this one", which is what makes an index usable while it is
 * still being built.
 */
internal class SegmentSelection(
    /** Segments this index can be read from, in the order they were pinned. */
    val usable: List<SegmentIndex>,
    val coverage: IndexCoverage,
    /** Live segments the caller has to scan: missing a sidecar, or too new for this snapshot. */
    val uncoveredSegments: Set<Long>,
) {
    /** The usable segments by number, ascending — the domain of every accessor below. */
    val segmentNumbers: List<Long> = usable.map { it.segmentNumber }.sorted()

    /**
     * The usable segment numbered [segmentNumber].
     *
     * **Throws rather than answering emptily**, and that is the whole reason it exists as a lookup.
     * An empty bitmap for a segment this index cannot answer for is an index quietly changing a
     * query's answer, which is the one thing this module may not do; a caller must decide from
     * [segmentNumbers] which segments it may ask about, and scan the rest.
     */
    fun require(segmentNumber: Long): SegmentIndex =
        usable.firstOrNull { it.segmentNumber == segmentNumber }
            ?: throw IndexStateException(
                "segment $segmentNumber is not usable by this reader; usable segments are $segmentNumbers",
            )

    /**
     * The ordinals of [segmentNumber] whose keys lie in `[from, to]`, both bounds inclusive.
     *
     * Ordinals are assigned in ascending key order — one per distinct user key, tombstones included —
     * so a key range is one contiguous ordinal range and nothing has to be decoded to find it. Two
     * bisects over mapped key-block bytes, and the empty range when the bounds cross.
     */
    fun ordinalRange(segmentNumber: Long, from: Key?, to: Key?): IntRange {
        val base = require(segmentNumber).base
        val first = if (from == null) 0 else insertionPoint(base.ordinalOf(from))
        val last = if (to == null) base.documentCount - 1 else {
            val found = base.ordinalOf(to)
            if (found >= 0) found else insertionPoint(found) - 1
        }
        return if (first > last) IntRange.EMPTY else first..last
    }

    /**
     * Whether [key] appears in exactly one usable segment.
     *
     * Decided from the key blocks, which is a **sidecar** read: `BaseSidecar.ordinalOf` is a bisect
     * over mapped bytes and opens no document. That is what lets a caller skip a recheck it can prove
     * is a no-op without weakening the rule that every hit is rechecked — a key living in one segment
     * has one version there, so that segment's recorded value *is* the visible value.
     */
    fun isUniqueKey(key: Key, exceptSegment: Long): Boolean {
        for (segment in usable) {
            if (segment.segmentNumber == exceptSegment) continue
            if (segment.base.ordinalOf(key) >= 0) return false
        }
        return true
    }

    private fun insertionPoint(found: Int): Int = if (found >= 0) found else -found - 1

    companion object {
        /**
         * @param covers whether a segment carries this index's sidecar at all — the one thing that
         *   differs between an inverted index and a column.
         */
        fun of(
            pinned: List<SegmentIndex>,
            live: Set<Long>,
            snapshot: Snapshot,
            covers: (SegmentIndex) -> Boolean,
        ): SegmentSelection {
            val usable = pinned.filter { covers(it) && it.largestSequence <= snapshot.sequence }
            val stale = pinned.count { covers(it) && it.largestSequence > snapshot.sequence }
            return SegmentSelection(
                usable = usable,
                coverage = IndexCoverage(
                    segmentsCovered = usable.size,
                    segmentsStale = stale,
                    segmentsTotal = live.size,
                ),
                uncoveredSegments = live - usable.mapTo(HashSet()) { it.segmentNumber },
            )
        }
    }
}
