package app.oreshkov.rabosh.query

import app.oreshkov.rabosh.catalog.IndexKind
import app.oreshkov.rabosh.core.Key
import app.oreshkov.rabosh.index.Bitmap
import app.oreshkov.rabosh.index.ColumnReader
import app.oreshkov.rabosh.index.IndexCoverage
import app.oreshkov.rabosh.index.IndexHandle
import app.oreshkov.rabosh.index.IndexReader
import app.oreshkov.rabosh.index.ReadableBitmap

/**
 * A reader of one index, whichever kind it is.
 *
 * The two reader classes answer different questions and share no supertype in `rabosh-index`, on
 * purpose — a planner that could confuse them would reach for a term dictionary to answer a range.
 * This wrapper gives the executor the handful of operations that genuinely *are* common (which
 * segments, which key, which ordinals are documents) without blurring the two apart.
 */
internal sealed interface LeafReader : AutoCloseable {

    val handle: IndexHandle
    val usableSegments: List<Long>
    val coverage: IndexCoverage
    val hasUnflushedDocuments: Boolean

    fun documentOrdinals(segment: Long): ReadableBitmap

    fun keyAt(segment: Long, ordinal: Int): Key

    fun ordinalRange(segment: Long, from: Key?, to: Key?): IntRange

    fun isUniqueKey(key: Key, exceptSegment: Long): Boolean

    class Inverted(val reader: IndexReader) : LeafReader {
        override val handle: IndexHandle get() = reader.index
        override val usableSegments: List<Long> get() = reader.usableSegments
        override val coverage: IndexCoverage get() = reader.coverage
        override val hasUnflushedDocuments: Boolean get() = reader.hasUnflushedDocuments
        override fun documentOrdinals(segment: Long): ReadableBitmap = reader.documentOrdinals(segment)
        override fun keyAt(segment: Long, ordinal: Int): Key = reader.keyAt(segment, ordinal)
        override fun ordinalRange(segment: Long, from: Key?, to: Key?): IntRange =
            reader.ordinalRange(segment, from, to)

        override fun isUniqueKey(key: Key, exceptSegment: Long): Boolean = reader.isUniqueKey(key, exceptSegment)
        override fun close(): Unit = reader.close()
    }

    class Column(val reader: ColumnReader) : LeafReader {
        override val handle: IndexHandle get() = reader.index
        override val usableSegments: List<Long> get() = reader.usableSegments
        override val coverage: IndexCoverage get() = reader.coverage
        override val hasUnflushedDocuments: Boolean get() = reader.hasUnflushedDocuments
        override fun documentOrdinals(segment: Long): ReadableBitmap = reader.documentOrdinals(segment)
        override fun keyAt(segment: Long, ordinal: Int): Key = reader.keyAt(segment, ordinal)
        override fun ordinalRange(segment: Long, from: Key?, to: Key?): IntRange =
            reader.ordinalRange(segment, from, to)

        override fun isUniqueKey(key: Key, exceptSegment: Long): Boolean = reader.isUniqueKey(key, exceptSegment)
        override fun close(): Unit = reader.close()
    }
}

/**
 * One leaf and the index that will answer it.
 *
 * **Which index kind may answer which leaf is a correctness rule, not a preference**, and it is
 * stated here because this is where it would be broken:
 *
 * | leaf | inverted | column |
 * |---|---|---|
 * | equality, `IN` | the posting lists, exact — preferred, one dictionary lookup per term | exact for shredded values, plus residuals |
 * | range | **never** | the only source |
 * | exists | the presence bitmap, exact — preferred | the column's presence, exact |
 * | not exists | the complement of presence, exact | — |
 * | is null | presence, a *superset* — accepted only with the recheck it forces | exact |
 *
 * **A range is never answered by an inverted index.** Its terms are ordered for *lookup*, so
 * `NUMERIC || "10"` precedes `NUMERIC || "9"`; there is no interval of the dictionary that is an
 * interval of values. A planner reaching for it would return a subset of the answer with nothing
 * anywhere reporting a problem. A leaf whose only index is of the wrong kind therefore gets **no**
 * source and is answered by the scan and the recheck, which is slower and right.
 */
internal class LeafSource(
    val leaf: Normal.Leaf,
    val reader: LeafReader,
) {
    val handle: IndexHandle get() = reader.handle

    /**
     * The ordinals of [segment] this leaf admits, and the ones it decides.
     *
     * [Ordinals.candidates] is a superset of the leaf's matches there and [Ordinals.certain] a
     * subset — the two coincide exactly when the index answered without qualification. Everything
     * between them is a document the executor must read.
     */
    fun ordinals(segment: Long, work: WorkStats): Ordinals {
        val positive = positiveOrdinals(segment, work)
        if (!leaf.negated) return positive
        // The dual, and it is exact in both directions: an ordinal that certainly matches the leaf
        // certainly fails its negation, and one that was not even a candidate certainly satisfies it.
        val universe = reader.documentOrdinals(segment)
        return Ordinals(
            candidates = universe.andNot(positive.certain),
            certain = universe.andNot(positive.candidates),
        )
    }

    private fun positiveOrdinals(segment: Long, work: WorkStats): Ordinals = when (reader) {
        is LeafReader.Inverted -> when (leaf.kind) {
            LeafKind.EQUALITY -> Ordinals.exact(reader.reader.candidateOrdinals(segment, leaf.terms!!))
            LeafKind.EXISTS -> Ordinals.exact(reader.reader.presentOrdinals(segment))
            // Presence holds every ordinal carrying a value, and a JSON null carries no term — so
            // this is a superset and the recheck it forces is what keeps it honest.
            LeafKind.IS_NULL -> Ordinals(reader.reader.presentOrdinals(segment), Bitmap())
            LeafKind.RANGE -> error("an inverted index cannot answer a range; see LeafSource")
        }

        is LeafReader.Column -> when (leaf.kind) {
            LeafKind.EXISTS -> Ordinals.exact(reader.reader.presentOrdinals(segment))
            else -> {
                val matches = Bitmap()
                val residuals = Bitmap()
                for (predicate in leaf.predicates) {
                    val found = reader.reader.evaluate(segment, predicate)
                    matches.orWith(found.matches)
                    residuals.orWith(found.residuals)
                    work.blocksScanned += found.blocksScanned
                    work.blocksSkipped += found.blocksSkipped
                    if (found.segmentSkipped) work.segmentsSkipped++
                }
                // A residual ordinal is one the column did not store, so only its document decides.
                Ordinals(candidates = matches.or(residuals), certain = matches)
            }
        }
    }

    override fun toString(): String {
        val kind = if (reader is LeafReader.Inverted) "inverted" else "column"
        return "$leaf via $kind #${handle.id}"
    }
}

/** Counters an evaluation fills in as it goes. See [QueryStats]. */
internal class WorkStats {
    var blocksScanned: Int = 0
    var blocksSkipped: Int = 0
    var segmentsSkipped: Int = 0
}

/**
 * Chooses an index for [leaf], or `null` when nothing may answer it.
 *
 * `null` is not a failure: it means the leaf is a **residual**, evaluated by the recheck and by the
 * scan, which is what every leaf did before there were any indexes at all.
 */
internal fun chooseIndex(leaf: Normal.Leaf, indexes: List<IndexHandle>): IndexHandle? {
    val over = indexes.filter { it.path == leaf.path }
    if (over.isEmpty()) return null
    val inverted = over.firstOrNull { it.kind == IndexKind.INVERTED }
    val column = over.firstOrNull { it.kind == IndexKind.SHREDDED_COLUMN }

    // A negated leaf is answerable only where the complement is both exact and selective. `NOT
    // EXISTS` is that case — a rarely-present optional field makes it the sharpest filter in the
    // store — and the general complement is the opposite: sound, and very nearly the whole segment.
    // It stays a residual rather than becoming a bitmap the size of the data.
    if (leaf.negated) return if (leaf.kind == LeafKind.EXISTS) inverted else null

    return when (leaf.kind) {
        LeafKind.EQUALITY -> if (leaf.terms != null) inverted ?: column else column
        LeafKind.RANGE -> column
        LeafKind.EXISTS -> inverted ?: column
        LeafKind.IS_NULL -> column ?: inverted
    }
}
