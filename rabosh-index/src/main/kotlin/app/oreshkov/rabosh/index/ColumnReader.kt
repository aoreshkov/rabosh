package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.RaboshExperimental
import app.oreshkov.rabosh.catalog.CatalogPath
import app.oreshkov.rabosh.core.Key
import app.oreshkov.rabosh.core.Snapshot
import app.oreshkov.rabosh.variant.Variant

/**
 * One shredded column, read at one snapshot, with every sidecar it may consult pinned.
 *
 * Where an [IndexReader] answers "which documents carry this value", a column answers "what value do
 * these documents carry" — and answers it **without opening a document**, which is the whole reason a
 * column exists next to an inverted index. It also answers ranges, which an inverted index cannot: its
 * terms are ordered for lookup rather than by value.
 *
 * The same three-part coverage rule applies, from the same place: a segment is usable when it carries
 * this column and its largest sequence is at or below the snapshot's. See [SegmentSelection].
 *
 * Close it. On Windows a mapped file cannot be deleted, so a reader left open blocks reclamation of
 * everything it touched.
 */
@RaboshExperimental
public class ColumnReader internal constructor(
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

    private val selection: SegmentSelection =
        SegmentSelection.of(pinned, live, snapshot) { it.column(handle.id) != null }

    /** The index this reads. */
    public val index: IndexHandle get() = handle

    /** The path shredded. */
    public val path: CatalogPath get() = handle.path

    /** Covered, stale and total, at this snapshot. */
    public val coverage: IndexCoverage get() = selection.coverage

    /** Live segments this reader cannot answer for, which the caller must scan. */
    public val uncoveredSegments: Set<Long> get() = selection.uncoveredSegments

    /**
     * Whether the column alone is a superset of the answer.
     *
     * False when a segment is uncovered and false when documents sit in a memtable, which has no
     * sidecar and never will. The check is made before the live set is read, so anything landing in a
     * memtable afterwards carries a sequence above this snapshot's and is invisible to it.
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

    /** The physical types the usable segments chose, for diagnostics. One column may differ per segment. */
    public fun columnTypes(): List<String> =
        selection.usable.mapNotNull { it.column(handle.id)?.type?.toString() }

    /**
     * Evaluates [predicate] against one segment's column, opening no document.
     *
     * **Two bitmaps, and the distinction is the honest half.** [ColumnMatch.matches] are ordinals
     * whose *stored* value satisfies the predicate — a fact, decided without leaving the mapping —
     * and [ColumnMatch.residuals] are ordinals whose value is not in the column at all, which the
     * caller must resolve from the document. A column ruled out by its segment bound still reports
     * its residuals, because a bound makes no claim about values it did not store.
     */
    public fun evaluate(segmentNumber: Long, predicate: ColumnPredicate): ColumnMatch {
        checkOpen()
        val segment = selection.require(segmentNumber)
        val column = segment.column(handle.id)
            ?: throw IndexStateException("segment $segmentNumber carries no column for index #${handle.id}")

        // Residual ordinals must be resolved whatever the bounds say: the column makes no claim
        // about values it did not store, so a segment bound cannot rule them out.
        val residuals = column.residual()

        // Segment-level pruning. The bound covers *every* value including the residual ones, so a
        // miss here rules out only the shredded part — which is exactly what is being skipped. A
        // column of another type entirely holds no match under the type-bracketing rule, which is why
        // that is the same branch and not a special case.
        if (!predicate.mayContain(column.bounds) || !predicate.answerableBy(column.type)) {
            return ColumnMatch(
                matches = Bitmap(),
                residuals = residuals,
                blocksScanned = 0,
                blocksSkipped = column.blockCount,
                segmentSkipped = true,
            )
        }

        val matches = Bitmap()
        var blocksScanned = 0
        var blocksSkipped = 0
        val shredded = column.shredded()
        val starts = column.starts()
        val nulls = column.nulls()
        val values = column.values

        for (block in 0 until column.blockCount) {
            if (!predicate.mayContain(column, block)) {
                blocksSkipped++
                continue
            }
            blocksScanned++
            val from = block shl ColumnFormat.COLUMN_BLOCK_SHIFT
            val to = minOf(from + ColumnFormat.COLUMN_BLOCK_VALUES, column.valueCount)
            for (position in from until to) {
                val hit = if (nulls.contains(position)) {
                    predicate.matchesNull()
                } else {
                    matchesValue(predicate, column, values, position)
                }
                if (!hit) continue
                // A value position belongs to the shredded ordinal whose run it falls in. `rank` is
                // inclusive, so the run's index is one less. An ordinal matching on several of its
                // values lands on the same bit, which is why nothing has to remember the last one.
                matches.add(shredded.select(starts.rank(position) - 1))
            }
        }

        return ColumnMatch(matches, residuals, blocksScanned, blocksSkipped, segmentSkipped = false)
    }

    /**
     * Evaluates [predicate] against every usable segment.
     *
     * A fold over the per-segment form, kept because [ColumnQuery] answers in keys rather than in
     * ordinals. The order the segments are visited in is the order they were pinned.
     */
    internal fun evaluate(predicate: ColumnPredicate): ColumnEvaluation {
        checkOpen()
        val matches = ArrayList<Pair<SegmentIndex, Int>>()
        val residuals = ArrayList<Pair<SegmentIndex, Int>>()
        var blocksScanned = 0
        var blocksSkipped = 0
        var segmentsSkipped = 0

        for (segment in selection.usable) {
            if (segment.column(handle.id) == null) continue
            val found = evaluate(segment.segmentNumber, predicate)
            val residualCursor = found.residuals.cursor()
            while (residualCursor.next()) residuals.add(segment to residualCursor.value)
            val matchCursor = found.matches.cursor()
            while (matchCursor.next()) matches.add(segment to matchCursor.value)
            blocksScanned += found.blocksScanned
            blocksSkipped += found.blocksSkipped
            if (found.segmentSkipped) segmentsSkipped++
        }

        return ColumnEvaluation(matches, residuals, blocksScanned, blocksSkipped, segmentsSkipped, coverage)
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

    // --- projection -------------------------------------------------------------------------------
    //
    // Reading a value *out* is a stronger demand than deciding a predicate with it, and the difference
    // is the whole of what these three methods exist to police. A predicate asks "is this value in
    // range", which the stored form answers exactly. A projection asks "what is this value", and the
    // stored form answers a *numerically equal* one: the numeric family lives at one common scale per
    // segment, so a segment holding `{"price":10}` beside `{"price":9.99}` reads the first back as
    // `10.00`. Handing that to a caller would be an index changing an answer.
    //
    // So the column proves exactness when it is built and records it, and everything below refuses
    // rather than approximates. The rule lives here, with the statistic, and not in the caller — a
    // caller that forgot it could otherwise be handed a value the document does not hold.

    /**
     * Whether this segment's column can give values back exactly as the documents wrote them.
     *
     * `false` for a column written before the fidelity flag existed, and `false` for a numeric column
     * whose values do not all carry its common scale. Both mean the same thing to a caller: read the
     * document.
     */
    public fun canProject(segmentNumber: Long): Boolean {
        checkOpen()
        return selection.require(segmentNumber).column(handle.id)?.reconstructsExactly == true
    }

    /**
     * Whether [ordinal]'s value at [path] can be read without opening its document.
     *
     * Three things have to hold: the column reconstructs exactly, the ordinal is not residual — the
     * column did not store it, so only the document knows — and it has at most one value, because a
     * projection names one location and a path with several is not one a projection can spell.
     */
    public fun canProject(segmentNumber: Long, ordinal: Int): Boolean {
        checkOpen()
        val column = selection.require(segmentNumber).column(handle.id) ?: return false
        if (!column.reconstructsExactly) return false
        if (column.residual().contains(ordinal)) return false
        val range = column.valueRange(ordinal) ?: return true
        return range.last - range.first <= 0
    }

    /**
     * The value at [ordinal], or `null` where the document holds none at [path].
     *
     * @throws IndexStateException if [canProject] refuses this ordinal. Refusing rather than
     *   approximating is the point: a caller that ignored the gate would otherwise be handed a value
     *   that is *numerically* right and not the one in the document.
     */
    public fun valueAt(segmentNumber: Long, ordinal: Int): Variant? {
        checkOpen()
        if (!canProject(segmentNumber, ordinal)) {
            throw IndexStateException(
                "ordinal $ordinal of segment $segmentNumber cannot be projected from the column over " +
                    "$path; ask canProject first and read the document when it says no",
            )
        }
        return selection.require(segmentNumber).column(handle.id)?.projectedValueAt(ordinal)
    }

    /** Ordinals carrying at least one value at [path], whether or not the column stored it. */
    public fun presentOrdinals(segmentNumber: Long): ReadableBitmap {
        checkOpen()
        val segment = selection.require(segmentNumber)
        return segment.column(handle.id)?.presence() ?: Bitmap()
    }

    /** The key at [ordinal] of [segmentNumber]. A key-block read, not a document read. */
    public fun keyAt(segmentNumber: Long, ordinal: Int): Key {
        checkOpen()
        return selection.require(segmentNumber).base.keyAt(ordinal)
    }

    /**
     * The ordinals of [segmentNumber] whose keys lie in `[from, to]`, both bounds inclusive.
     *
     * Ordinals ascend with keys, so a key range is one contiguous ordinal range. See
     * [IndexReader.ordinalRange], which is the same bisect over the same key blocks.
     */
    public fun ordinalRange(segmentNumber: Long, from: Key?, to: Key?): IntRange {
        checkOpen()
        return selection.ordinalRange(segmentNumber, from, to)
    }

    private fun matchesValue(
        predicate: ColumnPredicate,
        column: ColumnFile,
        values: ColumnValueReader,
        position: Int,
    ): Boolean = when (predicate.kind) {
        ColumnPredicate.Kind.EXISTS -> true
        ColumnPredicate.Kind.IS_NULL -> false
        ColumnPredicate.Kind.NUMERIC -> predicate.matchesNumber(values.numberAt(position))
        ColumnPredicate.Kind.TEXT -> predicate.matchesText(values.textAt(position))
        ColumnPredicate.Kind.BOOLEAN ->
            column.type.id == ColumnFormat.COLUMN_TYPE_BOOLEAN && values.booleanAt(position) == predicate.boolean
    }

    /**
     * Whether [key] appears in exactly one usable segment.
     *
     * Decided from the key blocks, which is a **sidecar** read: `BaseSidecar.ordinalOf` is a bisect
     * over mapped bytes and opens no document. That is what lets a caller skip a recheck it can prove
     * is a no-op without weakening the rule that every hit is rechecked — a key living in one segment
     * has one version, so the column's value *is* the visible value.
     */
    public fun isUniqueKey(key: Key, exceptSegment: Long): Boolean {
        checkOpen()
        return selection.isUniqueKey(key, exceptSegment)
    }

    /** The key at [ordinal] of [segment]. A key block read, not a document read. */
    internal fun keyAt(segment: SegmentIndex, ordinal: Int): Key = segment.base.keyAt(ordinal)

    override fun close() {
        if (closed) return
        closed = true
        for (segment in pinned) segment.release()
    }

    override fun toString(): String = "ColumnReader(${handle.definition}, $coverage)"

    private fun checkOpen() {
        if (closed) throw IndexStateException("this column reader is closed")
    }
}

/**
 * What one segment's column said about a predicate, and what deciding it cost.
 *
 * The two bitmaps are over that segment's **ordinals**, so they intersect directly with an inverted
 * index's postings over the same segment — both hang off the one base sidecar. Neither is a set of
 * answers: [matches] is a claim about the version the column recorded, which a newer segment or a
 * memtable may have replaced.
 */
@RaboshExperimental
public class ColumnMatch internal constructor(
    /** Ordinals whose stored value satisfies the predicate. Exact, and decided without a document. */
    public val matches: ReadableBitmap,
    /** Ordinals the column did not store; only the document decides these. */
    public val residuals: ReadableBitmap,
    public val blocksScanned: Int,
    public val blocksSkipped: Int,
    /** Whether the segment's bounds, or its physical type, ruled the shredded part out entirely. */
    public val segmentSkipped: Boolean,
) {
    override fun toString(): String =
        "ColumnMatch(matches=${matches.cardinality}, residuals=${residuals.cardinality}, " +
            "blocksScanned=$blocksScanned, blocksSkipped=$blocksSkipped, segmentSkipped=$segmentSkipped)"
}

/** What a column scan found, before any document has been consulted. */
internal class ColumnEvaluation(
    /** Ordinals whose stored value satisfies the predicate. */
    val matches: List<Pair<SegmentIndex, Int>>,
    /** Ordinals whose value is not in the column and must be read from the document. */
    val residuals: List<Pair<SegmentIndex, Int>>,
    val blocksScanned: Int,
    val blocksSkipped: Int,
    val segmentsSkipped: Int,
    val coverage: IndexCoverage,
)
