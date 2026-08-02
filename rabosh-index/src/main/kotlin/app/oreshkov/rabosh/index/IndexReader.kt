package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.catalog.CatalogPath
import app.oreshkov.rabosh.core.Key
import app.oreshkov.rabosh.core.Snapshot

/**
 * One index, read at one snapshot, with every sidecar it may consult pinned for its lifetime.
 *
 * **This returns candidates, never answers.** An index over a segment says "this segment's newest
 * version of key `K` carried that value". Whether the version a caller can *see* still carries it is
 * a different question, because a newer version may live in a shallower segment or in a memtable
 * neither of which this index covers. So every hit has to be rechecked against the visible document,
 * and [uncoveredSegments] has to be scanned. [IndexQuery] is the reference implementation of both.
 *
 * An index that could return the answer would be an index that could be wrong, and the design rule it
 * would be wrong against is the one that matters most here: **an index may change query speed, never
 * query answers.**
 *
 * **Sidecars are pinned up front, not per term.** A compaction may retire a segment at any moment, and
 * acquiring lazily would let one disappear between the second and third term of an `IN (a, b, c)`.
 * The pins are released by [close], which must be called — on Windows a mapped file cannot be deleted
 * at all, so a reader left open blocks reclamation of everything it touched.
 */
public class IndexReader internal constructor(
    private val handle: IndexHandle,
    /** The snapshot this reader answers at. */
    public val snapshot: Snapshot,
    internal val options: IndexOptions,
    private val live: Set<Long>,
    private val pinned: List<SegmentIndex>,
    /** Whether the store held documents outside a segment when this reader pinned. */
    public val hasUnflushedDocuments: Boolean,
) : AutoCloseable {

    private var closed = false

    /** Which segments answer at this snapshot. The predicate lives in [SegmentSelection]. */
    private val selection: SegmentSelection =
        SegmentSelection.of(pinned, live, snapshot) { it.postings(handle.id) != null }

    private val usable: List<SegmentIndex> get() = selection.usable

    /** The index this reads. */
    public val index: IndexHandle get() = handle

    /** The path indexed. */
    public val path: CatalogPath get() = handle.path

    /** Covered, stale and total, at this snapshot. See [IndexCoverage]. */
    public val coverage: IndexCoverage get() = selection.coverage

    /**
     * Live segments this reader cannot answer for, which the caller must scan.
     *
     * Two causes with one consequence: a segment with no posting file for this index — the state a
     * build in progress leaves — and a segment too new for this snapshot. Neither is an error, and
     * treating them identically is what makes an index *usable while it is still being built*, with
     * no cutover.
     */
    public val uncoveredSegments: Set<Long> get() = selection.uncoveredSegments

    /**
     * Whether the candidates alone are a superset of the answer.
     *
     * False when a segment is uncovered, and false when the store holds documents that are not in a
     * segment at all. A memtable has no sidecar and never will — there is no per-segment unit to
     * attach one to — so a store with unflushed writes always needs the scan.
     *
     * The check is made **before** the live segment set is read, and the order is what makes this
     * sound rather than merely likely: anything landing in a memtable afterwards carries a sequence
     * above this snapshot's and is invisible to it, and anything the snapshot *can* see was already
     * in a segment the live set then named.
     */
    public val isAuthoritative: Boolean get() = coverage.isComplete && !hasUnflushedDocuments

    /**
     * Segments this reader can answer for, ascending. The domain of every accessor below.
     *
     * A planner intersects this with the segments its *snapshot* pinned, and scans the rest. The two
     * sets are not the same and neither contains the other: this one comes from the store's live set
     * at the moment the reader pinned, which moves under a long-lived snapshot.
     */
    public val usableSegments: List<Long> get() = selection.segmentNumbers

    /** Whether this index can answer for [term] at all. See [IndexOptions.maxTermBytes]. */
    public fun answers(term: IndexTerm): Boolean = term.size <= options.maxTermBytes

    // --- ordinals ---------------------------------------------------------------------------------
    //
    // Everything below works in one segment's ordinal space rather than in keys, so that a planner can
    // intersect two indexes before a single ordinal is decoded. Both indexes over a segment hang off
    // that segment's one base sidecar, so their ordinals are the same ordinals — which is what makes
    // `a = x AND b in [1, 2]` a bitmap intersection rather than two key lists to merge.
    //
    // Every one of them throws for a segment outside `usableSegments`; see `SegmentSelection.require`.

    /** Candidate ordinals in [segmentNumber] whose indexed version carried [term]. */
    public fun candidateOrdinals(segmentNumber: Long, term: IndexTerm): ReadableBitmap {
        checkOpen()
        require(answers(term)) {
            "term of ${term.size} bytes is above maxTermBytes=${options.maxTermBytes}; ask `answers` first"
        }
        return selection.require(segmentNumber).postings(handle.id)?.postings(term.bytes) ?: Bitmap()
    }

    /** Candidate ordinals carrying any of [terms]. The `IN` case, unioned in one pass. */
    public fun candidateOrdinals(segmentNumber: Long, terms: Collection<IndexTerm>): ReadableBitmap {
        checkOpen()
        require(terms.all(::answers)) { "some terms are above maxTermBytes=${options.maxTermBytes}" }
        val file = selection.require(segmentNumber).postings(handle.id) ?: return Bitmap()
        val lists = terms.mapNotNull { file.postings(it.bytes) }
        return if (lists.isEmpty()) Bitmap() else Bitmap.union(lists)
    }

    /** Ordinals carrying any value at [path]. The `EXISTS` case. */
    public fun presentOrdinals(segmentNumber: Long): ReadableBitmap {
        checkOpen()
        return selection.require(segmentNumber).postings(handle.id)?.presence() ?: Bitmap()
    }

    /** Ordinals carrying **no** value at [path], taken against [documentOrdinals]. `NOT EXISTS`. */
    public fun absentOrdinals(segmentNumber: Long): ReadableBitmap {
        checkOpen()
        val segment = selection.require(segmentNumber)
        val postings = segment.postings(handle.id) ?: return Bitmap()
        return segment.base.present().andNot(postings.presence())
    }

    /**
     * The segment's live-document universe: ordinals whose newest version there is a document.
     *
     * The set a complement is taken over, and it is deliberately not `0 until documentCount` — a
     * tombstone occupies an ordinal and is not a document, so the wider form offers candidates whose
     * recheck resolves to nothing.
     */
    public fun documentOrdinals(segmentNumber: Long): ReadableBitmap {
        checkOpen()
        return selection.require(segmentNumber).base.present()
    }

    /** The key at [ordinal] of [segmentNumber]. A key-block read, not a document read. */
    public fun keyAt(segmentNumber: Long, ordinal: Int): Key {
        checkOpen()
        return selection.require(segmentNumber).base.keyAt(ordinal)
    }

    /**
     * The ordinals of [segmentNumber] whose keys lie in `[from, to]`, both bounds inclusive.
     *
     * Ordinals ascend with keys, so a key range is one contiguous ordinal range. That is how a
     * query's key bounds are pushed into ordinal space instead of being applied after every candidate
     * has been decoded.
     */
    public fun ordinalRange(segmentNumber: Long, from: Key?, to: Key?): IntRange {
        checkOpen()
        return selection.ordinalRange(segmentNumber, from, to)
    }

    /** Whether [key] appears in exactly one usable segment. See [SegmentSelection.isUniqueKey]. */
    public fun isUniqueKey(key: Key, exceptSegment: Long): Boolean {
        checkOpen()
        return selection.isUniqueKey(key, exceptSegment)
    }

    /** Candidate keys whose indexed version carried [term]. */
    public fun candidates(term: IndexTerm): KeyCursor {
        checkOpen()
        require(answers(term)) {
            "term of ${term.size} bytes is above maxTermBytes=${options.maxTermBytes}; ask `answers` first"
        }
        return cursor { segment -> segment.postings(handle.id)?.postings(term.bytes) }
    }

    /** Candidate keys whose indexed version carried any of [terms]. The `IN` case. */
    public fun candidates(terms: Collection<IndexTerm>): KeyCursor {
        checkOpen()
        val usableTerms = terms.filter(::answers)
        require(usableTerms.size == terms.size) { "some terms are above maxTermBytes=${options.maxTermBytes}" }
        if (usableTerms.isEmpty()) return KeyCursor(emptyList())
        return cursor { segment ->
            val file = segment.postings(handle.id) ?: return@cursor null
            // Union rather than a fold of pairwise ors: `IN (a, b, c)` is the ordinary case and
            // folding would allocate a whole bitmap per step.
            val lists = usableTerms.mapNotNull { file.postings(it.bytes) }
            if (lists.isEmpty()) null else Bitmap.union(lists)
        }
    }

    /** Candidate keys carrying any value at [path]. The `EXISTS` case. */
    public fun existing(): KeyCursor {
        checkOpen()
        return cursor { segment -> segment.postings(handle.id)?.presence() }
    }

    /**
     * Candidate keys carrying **no** value at [path]. The `NOT EXISTS` case.
     *
     * Taken against the segment's live documents rather than against its ordinals, so a tombstone —
     * which occupies an ordinal but is not a document — is not offered as a candidate whose recheck
     * would resolve to nothing.
     */
    public fun absent(): KeyCursor {
        checkOpen()
        return cursor { segment ->
            val presence = segment.postings(handle.id) ?: return@cursor null
            segment.base.present().andNot(presence.presence())
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        for (segment in pinned) segment.release()
    }

    override fun toString(): String = "IndexReader(${handle.definition}, $coverage)"

    private inline fun cursor(select: (SegmentIndex) -> ReadableBitmap?): KeyCursor {
        val hits = ArrayList<SegmentHits>(usable.size)
        for (segment in usable) {
            val bitmap = select(segment) ?: continue
            if (!bitmap.isEmpty) hits.add(SegmentHits(segment, bitmap))
        }
        return KeyCursor(hits)
    }

    private fun checkOpen() {
        if (closed) throw IndexStateException("this index reader is closed")
    }
}

internal class SegmentHits(val segment: SegmentIndex, val ordinals: ReadableBitmap)

/**
 * Candidate keys, in segment order and ascending within each segment.
 *
 * A key may be reported more than once, by different segments — an updated document is in the newest
 * segment that holds it and in every older one that has not yet compacted it away. That is not a
 * defect to be smoothed over here: the recheck resolves each key against the *visible* version, so
 * one key reported three times yields one answer, and deduplicating early would cost a set over
 * millions of keys to save the caller a fact it already has to handle.
 *
 * Valid only while the [IndexReader] that produced it is open, because the ordinals are read straight
 * off that reader's mappings.
 */
public class KeyCursor internal constructor(private val hits: List<SegmentHits>) {
    private var segmentIndex = -1
    private var cursor: BitmapCursor? = null
    private var current: Key? = null

    /** The key at the cursor. */
    public val key: Key
        get() = current ?: throw IndexStateException("the cursor is not positioned on a key")

    /** The segment the current key was found in. */
    public val segmentNumber: Long
        get() {
            check(segmentIndex in hits.indices) { "the cursor is not positioned on a key" }
            return hits[segmentIndex].segment.segmentNumber
        }

    /** Advances to the next candidate, or returns `false` when there are none left. */
    public fun next(): Boolean {
        while (true) {
            val active = cursor
            if (active != null && active.next()) {
                current = hits[segmentIndex].segment.base.keyAt(active.value)
                return true
            }
            segmentIndex++
            if (segmentIndex >= hits.size) {
                current = null
                cursor = null
                return false
            }
            cursor = hits[segmentIndex].ordinals.cursor()
        }
    }

    /** Every candidate key, deduplicated and sorted. Convenient for a caller with a small result. */
    public fun toKeyList(): List<Key> {
        val keys = sortedSetOf<Key>()
        while (next()) keys.add(key)
        return keys.toList()
    }
}
