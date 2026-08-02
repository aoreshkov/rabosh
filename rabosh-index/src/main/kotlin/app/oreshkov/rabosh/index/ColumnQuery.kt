package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.core.Key

/**
 * What a column scan found, and what it cost.
 *
 * The counters are the point. "A column scan touches no document" is a claim about *work*, not about
 * results, and a result set alone cannot demonstrate it — so [documentsRead] is reported and the
 * suite asserts it is zero **in the same test** as the differential equality against a full scan. On
 * its own it would pass trivially for a query that returned nothing.
 */
public class ColumnScan internal constructor(
    /** The matching keys, deduplicated and sorted. */
    public val keys: List<Key>,
    /**
     * Documents opened to produce [keys].
     *
     * Zero when every match was answered from the column: fully covered, nothing unflushed, no
     * residual ordinal among the candidates, and every matching key unique to its segment. Any of
     * those failing costs reads, correctly and visibly.
     */
    public val documentsRead: Int,
    /** Statistics blocks whose values were examined. */
    public val blocksScanned: Int,
    /** Statistics blocks ruled out by their bounds without reading a value. */
    public val blocksSkipped: Int,
    /** Segments ruled out by their bounds without reading a block. */
    public val segmentsSkipped: Int,
    /** Whether the column answered at all, or the scan was a fallback. */
    public val usedColumn: Boolean,
    public val coverage: IndexCoverage,
) {
    override fun toString(): String =
        "ColumnScan(${keys.size} key(s), $documentsRead document(s) read, " +
            "$blocksScanned/$blocksSkipped blocks scanned/skipped, $segmentsSkipped segment(s) skipped, " +
            "column=$usedColumn)"
}

/**
 * Answers a predicate from a shredded column where it can and a scan where it cannot.
 *
 * **The narrow reference implementation**, exactly like [IndexQuery] and kept for the same reason.
 * `rabosh-query` owns the predicate AST, the planner and execution, and answers anything this does
 * while combining it with other predicates; what this remains is a second, independently written
 * implementation of the same claims — *identical results with and without a column*, *no document
 * opened*, *bounds prune blocks* — over the same reader. Reach for `QueryEngine` instead.
 *
 * **The recheck, and the one place it is skipped.** `CLAUDE.md` requires every index hit to be
 * re-evaluated against the version the snapshot can see, because a key may live in several segments
 * and the newest wins. That rule is kept. It is skipped only where it is **provably** a no-op: when
 * the reader is authoritative and the key appears in exactly one usable segment, that segment's
 * version *is* the visible version, and uniqueness is decided from the key blocks — a sidecar read,
 * not a document read. Everywhere else the document is opened and counted.
 *
 * That qualification is what makes `documentsRead == 0` an honest claim rather than a fudge: it holds
 * on a compacted, write-once, fully covered store, which is the same shape `SchemaInferenceTest`
 * requires before it asserts the catalog's counts are exact.
 */
public object ColumnQuery {
    /**
     * Keys whose visible document satisfies [predicate] at the reader's path.
     *
     * Falls back to a full scan when the column cannot be trusted alone, and says so through
     * [ColumnScan.usedColumn].
     */
    public fun keysMatching(
        store: DocumentStore,
        reader: ColumnReader,
        predicate: ColumnPredicate,
    ): ColumnScan {
        if (!reader.isAuthoritative) return scan(store, reader, predicate, usedColumn = false)

        val evaluation = reader.evaluate(predicate)
        val keys = sortedSetOf<Key>()
        var documentsRead = 0

        for ((segment, ordinal) in evaluation.matches) {
            val key = reader.keyAt(segment, ordinal)
            if (key in keys) continue
            if (reader.isUniqueKey(key, segment.segmentNumber)) {
                // The recheck is provably redundant: one segment holds this key, so the value the
                // column stored is the value the snapshot sees.
                keys.add(key)
                continue
            }
            documentsRead++
            if (satisfies(store, reader, predicate, key)) keys.add(key)
        }

        // Residual ordinals are the documents a column genuinely cannot answer for. Reporting them
        // rather than hiding them is what keeps the counter meaningful.
        for ((segment, ordinal) in evaluation.residuals) {
            val key = reader.keyAt(segment, ordinal)
            if (key in keys) continue
            documentsRead++
            if (satisfies(store, reader, predicate, key)) keys.add(key)
        }

        return ColumnScan(
            keys = keys.toList(),
            documentsRead = documentsRead,
            blocksScanned = evaluation.blocksScanned,
            blocksSkipped = evaluation.blocksSkipped,
            segmentsSkipped = evaluation.segmentsSkipped,
            usedColumn = true,
            coverage = evaluation.coverage,
        )
    }

    /**
     * Every key a full scan says matches, with no column involved.
     *
     * The other half of every differential test, and the fallback whenever the column cannot answer.
     * The predicate is evaluated by the same walk that built the column, so "does this document
     * match" has one definition rather than two.
     */
    public fun scanKeys(store: DocumentStore, reader: ColumnReader, predicate: ColumnPredicate): ColumnScan =
        scan(store, reader, predicate, usedColumn = false)

    private fun scan(
        store: DocumentStore,
        reader: ColumnReader,
        predicate: ColumnPredicate,
        usedColumn: Boolean,
    ): ColumnScan {
        val keys = sortedSetOf<Key>()
        var documentsRead = 0
        val extractor = TermExtractor(listOf(reader.path), reader.options)
        store.scan(snapshot = reader.snapshot).use { cursor ->
            while (cursor.next()) {
                documentsRead++
                var matched = false
                extractor.extract(cursor.document) { _, value -> if (predicate.matches(value)) matched = true }
                if (matched) keys.add(cursor.key)
            }
        }
        return ColumnScan(
            keys = keys.toList(),
            documentsRead = documentsRead,
            blocksScanned = 0,
            blocksSkipped = 0,
            segmentsSkipped = 0,
            usedColumn = usedColumn,
            coverage = reader.coverage,
        )
    }

    private fun satisfies(
        store: DocumentStore,
        reader: ColumnReader,
        predicate: ColumnPredicate,
        key: Key,
    ): Boolean {
        val document = store.get(key, reader.snapshot) ?: return false
        val extractor = TermExtractor(listOf(reader.path), reader.options)
        var matched = false
        extractor.extract(document) { _, value -> if (predicate.matches(value)) matched = true }
        return matched
    }
}
