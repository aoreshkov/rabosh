package app.oreshkov.rabosh.query

import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.core.Key
import app.oreshkov.rabosh.core.Snapshot
import app.oreshkov.rabosh.variant.Variant

/**
 * The rows a query matched, in key order.
 *
 * **A candidate is not an answer, and this is where the difference is settled.** An index reports what
 * *a segment's* recorded version of a key carried; a partial scan reports what *the sources it was
 * given* hold. Either can be out of date, because a newer version may live in a segment the index
 * does not cover or in a memtable that has no sidecar. So every candidate is re-resolved against the
 * version the snapshot can actually see — except in the two cases where that is **provably** a no-op,
 * each of which checks its own preconditions rather than trusting the plan:
 *
 * 1. **The scan already holds the deciding version.** If no evaluated segment holds the key, then the
 *    sources the scan was given are the whole of what that key could be in, and the document in hand
 *    is the visible one. When the plan is a full scan this is every key, which is why a store with no
 *    index pays nothing for the machinery.
 * 2. **One segment holds the key and its index decided outright.** With nothing unflushed and no
 *    other segment of this snapshot holding the key — a bisect over mapped key blocks, not a document
 *    read — that segment's recorded version *is* the visible version.
 *
 * **A row is a view.** Its document reads straight out of a mapped segment and is valid until the
 * next [next], the same contract a `DocumentCursor` offers. Anything kept beyond that is copied by
 * the caller, with [Row.toJsonString] or `Variant.toByteArray`.
 *
 * Close it. The readers it holds pin index sidecars, and on Windows a mapped file cannot be deleted
 * at all, so a cursor left open blocks reclamation of everything it touched.
 */
public class QueryCursor internal constructor(
    private val store: DocumentStore,
    private val query: Query,
    private val snapshot: Snapshot,
    private val ownedSnapshot: Snapshot?,
    private val plan: QueryPlan,
) : AutoCloseable {

    private val work = WorkStats()
    private val segments = ArrayList<SegmentKeys>()
    private val sources = ArrayList<KeySource>()
    private var scan: ScanKeys? = null

    /** Segment and a reader that can say whether it holds a key. See [heldByEvaluatedSegment]. */
    private val probes = ArrayList<Pair<Long, LeafReader>>()

    private var closed = false

    /**
     * The sources sitting on the current key, reused between rows rather than rebuilt.
     *
     * A `filter` here allocated a list per row, and `resolve` allocated two more picking the two roles
     * out of it — three lists to describe what is usually one source. Reusing it is safe because it is
     * only read between the row being handed out and the next [next], which is exactly what
     * [hasPending] tracks.
     */
    private val contributors = ArrayList<KeySource>(4)
    private var hasPending = false
    private var currentKey: Key? = null
    private var currentRow: Row? = null

    private var rowsReturned = 0
    private var candidateKeys = 0
    private var documentsRead = 0
    private var rowsProjectedFromColumns = 0

    init {
        try {
            open()
        } catch (failure: Throwable) {
            closeQuietly()
            throw failure
        }
    }

    /** The key of the row the cursor is on. */
    public val key: Key
        get() = checkNotNull(currentKey) { "the cursor is not on a row" }

    /** The row the cursor is on. Valid until the next [next]. */
    public val row: Row
        get() = checkNotNull(currentRow) { "the cursor is not on a row" }

    /** What the query has cost so far; final once [next] has returned `false`. */
    public val stats: QueryStats
        get() = QueryStats(
            rowsReturned = rowsReturned,
            candidateKeys = candidateKeys,
            documentsRead = documentsRead,
            rowsProjectedFromColumns = rowsProjectedFromColumns,
            segmentsIndexed = plan.evaluated.size,
            segmentsScanned = plan.scanned.size,
            segmentsSkipped = work.segmentsSkipped,
            blocksScanned = work.blocksScanned,
            blocksSkipped = work.blocksSkipped,
            scannedUnflushed = scan != null && plan.hasUnflushedDocuments,
            indexes = indexUses(),
        )

    /** Advances to the next matching row, or returns `false` when there are none left. */
    public fun next(): Boolean {
        check(!closed) { "the cursor is closed" }
        // Deferred so that the row handed out last time stays readable for the whole of its turn.
        if (hasPending) {
            advanceContributors()
            hasPending = false
        }
        currentKey = null
        currentRow = null

        if (query.limit != Query.NO_LIMIT && rowsReturned >= query.limit) return false

        while (true) {
            val next = smallestKey() ?: return false

            // One pass finds the contributors and the two roles `resolve` needs of them. Three
            // traversals producing three lists said nothing this does not.
            contributors.clear()
            var scanned: ScanKeys? = null
            var certain: SegmentKeys? = null
            for (source in sources) {
                if (source.peek() != next) continue
                contributors.add(source)
                when {
                    source is ScanKeys && scanned == null -> scanned = source
                    source is SegmentKeys && certain == null && source.isCertain -> certain = source
                }
            }
            candidateKeys++

            val resolved = resolve(next, scanned, certain)
            if (resolved == null) {
                advanceContributors()
                continue
            }

            currentKey = next
            currentRow = resolved
            rowsReturned++
            hasPending = true
            return true
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        closeQuietly()
    }

    override fun toString(): String = "QueryCursor($query, rows=$rowsReturned)"

    // --- execution ----------------------------------------------------------------------------

    private fun open() {
        for ((segment, restriction) in plan.evaluated) {
            val reader = restriction.expression.sources().first().reader
            val within = if (query.from == null && query.to == null) {
                null
            } else {
                reader.ordinalRange(segment, query.from, query.to)
            }
            val ordinals = restriction.evaluate(segment, within, work)
            probes.add(segment to reader)
            if (ordinals.candidates.isEmpty) continue
            val source = SegmentKeys(segment, ordinals, reader)
            segments.add(source)
            sources.add(source)
        }

        // The memtables are always read when the snapshot can see one: they carry no sidecar and
        // never will, and a key re-put after a tombstone in an uncovered segment is invisible
        // without them — a missing answer rather than a stale one.
        if (plan.scanned.isNotEmpty() || plan.hasUnflushedDocuments) {
            val cursor = store.scanSegments(
                segmentNumbers = plan.scanned,
                snapshot = snapshot,
                includeUnflushed = true,
                from = query.from,
                to = query.to,
            )
            val source = ScanKeys(cursor)
            scan = source
            sources.add(source)
        }
    }

    private fun advanceContributors() {
        for (source in contributors) source.advance()
    }

    private fun smallestKey(): Key? {
        var smallest: Key? = null
        for (source in sources) {
            val key = source.peek() ?: continue
            if (smallest == null || key < smallest) smallest = key
        }
        return smallest
    }

    /**
     * Resolves one candidate key into a row, or `null` when it does not match after all.
     *
     * The two skips are guarded by `check` rather than by the planner's word for it: a wrong
     * certainty flag is a document silently admitted or silently dropped, which is the failure class
     * this whole layer exists to prevent, and the cost of asserting it is one comparison.
     */
    private fun resolve(key: Key, scanned: ScanKeys?, certain: SegmentKeys?): Row? {
        val certainSegment = certain?.segment

        if (scanned != null) documentsRead++

        // (1) The scan holds the deciding version: no evaluated segment can hold this key.
        if (scanned != null && !heldByEvaluatedSegment(key)) {
            val document = checkNotNull(scanned.head) { "a scan cursor on a key must hold its document" }
            return if (plan.matcher.matches(document)) row(key, document) else null
        }

        // (2) One segment holds the key and its index decided outright.
        if (certain != null && certainSegment != null && !plan.hasUnflushedDocuments) {
            val unique = plan.uniqueness
            if (unique != null && unique(key, certainSegment)) {
                check(plan.evaluated.containsKey(certainSegment)) { "a certain hit came from an unevaluated segment" }
                return projected(key, certainSegment, certain.ordinal) ?: row(key, document = null)
            }
        }

        // (3) Everything else: ask the store what this snapshot sees.
        val visible = store.get(key, snapshot) ?: return null
        documentsRead++
        return if (plan.matcher.matches(visible)) row(key, visible) else null
    }

    /**
     * Whether any segment the index answered for holds [key].
     *
     * A bisect over mapped key blocks per evaluated segment — a sidecar read, not a document read —
     * and empty work for a plan that evaluated nothing, which is the plain full scan.
     */
    private fun heldByEvaluatedSegment(key: Key): Boolean =
        probes.any { (segment, reader) -> !reader.ordinalRange(segment, key, key).isEmpty() }

    private fun row(key: Key, document: Variant?): Row {
        val projection = query.projection
        if (projection === Projection.KEY) return Row(key, projection, source = null)
        val source = document ?: store.get(key, snapshot)?.also { documentsRead++ }
        return Row(key, projection, source)
    }

    /**
     * The row read out of columns, or `null` where even one projected field cannot be.
     *
     * The whole of what phase 12 adds, and it sits here rather than in [row] on purpose: this is the
     * one place a projection was paying for a document the *filter* had already avoided. Cases (1)
     * and (3) hold the document either way — a scan carries it, a recheck had to fetch it — so
     * reading columns there would be work for nothing.
     *
     * Returning `null` rather than throwing keeps the fallback ordinary: a residual value, a column
     * that cannot reconstruct exactly, a segment the projection's readers do not cover, and a field
     * with no column at all are all the same answer here, which is *read the document*.
     */
    private fun projected(key: Key, segment: Long, ordinal: Int): Row? {
        val columns = plan.projection ?: return null
        if (ordinal < 0 || !columns.canProject(segment, ordinal)) return null
        rowsProjectedFromColumns++
        return Row(key, query.projection, columns.valuesAt(segment, ordinal))
    }

    private fun indexUses(): List<IndexUse> = plan.readers.map { reader ->
        IndexUse(
            index = reader.handle,
            coverage = reader.coverage,
            segmentsEvaluated = plan.evaluated.count { (_, restriction) ->
                restriction.expression.sources().any { it.reader === reader }
            },
        )
    }

    private fun closeQuietly() {
        sources.forEach { runCatching { it.close() } }
        plan.readers.forEach { runCatching { it.close() } }
        ownedSnapshot?.let { runCatching { it.close() } }
    }
}
