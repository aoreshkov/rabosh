package app.oreshkov.rabosh.query

import app.oreshkov.rabosh.catalog.CatalogPath
import app.oreshkov.rabosh.catalog.IndexKind
import app.oreshkov.rabosh.core.Key
import app.oreshkov.rabosh.index.Bitmap
import app.oreshkov.rabosh.index.ColumnReader
import app.oreshkov.rabosh.index.IndexCoverage
import app.oreshkov.rabosh.index.IndexHandle
import app.oreshkov.rabosh.index.IndexOptions
import app.oreshkov.rabosh.index.IndexReader
import app.oreshkov.rabosh.index.IndexTerm
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
internal class LeafSource private constructor(
    /**
     * The leaf this answers, or `null` when it answers an `elemMatch` instead.
     *
     * Kept only so `Explain` can put a cardinality estimate beside a measured one; nothing about the
     * evaluation reads it. An element node has no per-path estimate to give, because what the catalog
     * knows is how many documents carry a path and not how many *elements* carry a tuple.
     */
    val leaf: Normal.Leaf?,
    private val description: String,
    private val negated: Boolean,
    val reader: LeafReader,
    private val positive: (Long, WorkStats) -> Ordinals,
) {
    val handle: IndexHandle get() = reader.handle

    /**
     * The ordinals of [segment] this source admits, and the ones it decides.
     *
     * [Ordinals.candidates] is a superset of the matches there and [Ordinals.certain] a subset — the
     * two coincide exactly when the index answered without qualification. Everything between them is
     * a document the executor must read.
     */
    fun ordinals(segment: Long, work: WorkStats): Ordinals {
        val found = positive(segment, work)
        if (!negated) return found
        // The dual, and it is exact in both directions: an ordinal that certainly matches
        // certainly fails the negation, and one that was not even a candidate certainly satisfies it.
        val universe = reader.documentOrdinals(segment)
        return Ordinals(
            candidates = universe.andNot(found.certain),
            certain = universe.andNot(found.candidates),
        )
    }

    override fun toString(): String = description

    companion object {
        fun of(leaf: Normal.Leaf, reader: LeafReader): LeafSource {
            val kind = if (reader is LeafReader.Inverted) "inverted" else "column"
            return LeafSource(
                leaf = leaf,
                description = "$leaf via $kind #${reader.handle.id}",
                negated = leaf.negated,
                reader = reader,
                positive = { segment, work -> positiveOrdinals(leaf, reader, segment, work) },
            )
        }

        /**
         * An `elemMatch` answered by a composite index.
         *
         * **Exact, and that is the whole point of the kind.** A composite term exists for an element
         * that carried the entire declared tuple, so an ordinal in the posting list is a document
         * with an element satisfying every conjunct — not a candidate for one. `Ordinals.exact` is
         * therefore the honest answer and the plan may decide the leaf without opening a document,
         * exactly as an inverted equality leaf does. Had the term been a hash, this would have had to
         * be candidates-only.
         */
        fun composite(node: Normal.Element, terms: Set<IndexTerm>, reader: LeafReader): LeafSource =
            LeafSource(
                leaf = null,
                description = "$node via composite #${reader.handle.id}",
                negated = node.negated,
                reader = reader,
                positive = { segment, _ ->
                    Ordinals.exact((reader as LeafReader.Inverted).reader.candidateOrdinals(segment, terms))
                },
            )

        private fun positiveOrdinals(
            leaf: Normal.Leaf,
            reader: LeafReader,
            segment: Long,
            work: WorkStats,
        ): Ordinals = when (reader) {
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
    val over = indexes.filter { it.path == leaf.path && it.kind != IndexKind.COMPOSITE_TERM }
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

/**
 * The terms a composite index can answer [node] with, or `null` when it cannot answer it at all.
 *
 * **The limit this inherits from `jsonb_path_ops`, made mechanical.** A composite term is the whole
 * declared tuple, so it can be *spelled* only when the query fixes every declared field by equality.
 * A conjunction naming three of four declared fields, one naming a range, one negated, one over a
 * different path — each of those is answered by no term at all, and each therefore falls back to the
 * walk. That is the sense in which this **supplements** the leaf indexes rather than replacing them,
 * and it is why a composite index is never the only index anybody wants.
 *
 * Five conditions, and every one of them is a soundness condition rather than a preference:
 *
 * 1. the index is over the element path this node walks;
 * 2. the operand is a conjunction of equality leaves — nothing negated, no range, no nesting;
 * 3. its paths are **exactly** the declared field set: a missing field would leave the term
 *    unspellable, and an extra one is a conjunct the term does not decide;
 * 4. every leaf's literals can be spelled as terms, which excludes a JSON null and a value the writer
 *    dropped for length — the same bound applied to the same bytes on both sides;
 * 5. the cross product of the per-field literals stays small, because `IN` over two fields is a
 *    product and a plan that built ten thousand terms would be slower than the scan it replaced.
 *
 * A `null` is not a failure: the node becomes a residual, answered by the recheck and by the scan,
 * which is what every predicate did before there were any indexes.
 */
internal fun chooseComposite(
    node: Normal.Element,
    indexes: List<IndexHandle>,
    options: IndexOptions,
): Pair<IndexHandle, Set<IndexTerm>>? {
    // A negated element node is not answerable: the complement of "some element has this tuple" is
    // sound but very nearly the whole segment, exactly as a negated equality leaf is.
    if (node.negated) return null

    val conjuncts = when (val inner = node.inner) {
        is Normal.Conjunction -> inner.operands
        is Normal.Leaf -> listOf(inner)
        else -> return null
    }
    val leaves = conjuncts.map { it as? Normal.Leaf ?: return null }
    if (leaves.any { it.negated || it.kind != LeafKind.EQUALITY || it.terms == null }) return null

    val byPath = LinkedHashMap<CatalogPath, Set<IndexTerm>>()
    for (leaf in leaves) {
        // A path named twice is two conjunctions over one field, which is an intersection of literal
        // sets rather than a union — expressible, and not worth the branch. Declined.
        if (byPath.put(leaf.path, leaf.terms!!) != null) return null
    }

    for (handle in indexes) {
        if (handle.kind != IndexKind.COMPOSITE_TERM || handle.path != node.path) continue
        val fields = handle.definition.fields
        if (fields.size != byPath.size || !byPath.keys.containsAll(fields)) continue

        val terms = compositeTerms(fields.map { byPath.getValue(it) }, options) ?: continue
        return handle to terms
    }
    return null
}

/**
 * Every tuple the cross product of [perField] spells, or `null` when there are too many.
 *
 * The product is the honest cost of `IN` inside an `elemMatch`: two fields with four literals each is
 * sixteen dictionary lookups, which is still far cheaper than a scan, and sixteen thousand is not.
 * The ceiling is on the *plan* rather than on the data, so exceeding it costs a residual and never an
 * answer.
 */
private fun compositeTerms(perField: List<Set<IndexTerm>>, options: IndexOptions): Set<IndexTerm>? {
    var combinations = 1L
    for (field in perField) {
        combinations *= field.size
        if (combinations > MAX_COMPOSITE_COMBINATIONS) return null
    }

    var rows = listOf(emptyList<IndexTerm>())
    for (field in perField) {
        rows = rows.flatMap { prefix -> field.map { prefix + it } }
    }

    val terms = LinkedHashSet<IndexTerm>(rows.size)
    for (row in rows) {
        // A tuple too long to key is one the writer dropped too, so declining the whole index is the
        // only sound answer: keeping the tuples that fit would look up a subset of what was asked and
        // report it as exact.
        terms += IndexTerm.composite(row, options) ?: return null
    }
    return terms
}

/** Sixteen lookups is a plan; sixteen thousand is a scan with extra steps. */
private const val MAX_COMPOSITE_COMBINATIONS = 256L

/**
 * An element node rewritten into ordinary document-level leaves, with whether the rewrite is **exact**.
 *
 * `null` when it cannot be rewritten soundly at all.
 *
 * **The identity this rests on.** The values at `p + r` are exactly the union, over the elements at
 * `p`, of the values at `r` within each — the walk that produces one produces the other. So:
 *
 * ```
 * elemMatch(p, L)          <=>  leaf(p + r, L)                    exact: nothing to correlate
 * elemMatch(p, A or B)     <=>  elemMatch(p, A) or elemMatch(p, B) exact: ∃ distributes over ∨
 * elemMatch(p, A and B)     =>  leaf(p+ra, A) and leaf(p+rb, B)    superset: ∃ does NOT distribute over ∧
 * elemMatch(p, elemMatch(q, X)) <=> elemMatch(p + q, X)            exact: one union of unions
 * ```
 *
 * The third line **is** the correlation gap, seen from the other side: it is why a composite term
 * exists, and it is why a conjunction decomposed this way narrows without deciding. The caller marks
 * the resulting expression incomplete, which is the mechanism a dropped conjunct already uses, and the
 * recheck — one element walk over what survives — settles it.
 *
 * **What this buys is the half of §10.6's gate that measured as real.** The composite index answers a
 * fully known conjunction in nanoseconds; everything else fell back to a walk at ~400 ns *per
 * element*. This puts the ordinary indexes a caller already has in front of that walk, for a range
 * inside an element, for a subset of a tuple's fields, and for a disjunction — and it costs no format
 * change, no index kind and no id, which is why it was preferred to a second ordinal space.
 *
 * A negated node is declined, at either level: the complement of "some element satisfies this" is
 * sound and very nearly the whole segment, exactly as it is for a negated leaf.
 */
internal fun decomposeElement(node: Normal.Element): Pair<Normal, Boolean>? {
    if (node.negated) return null
    return rewrite(node.path, node.inner)
}

private fun rewrite(prefix: CatalogPath, node: Normal): Pair<Normal, Boolean>? = when (node) {
    is Normal.Leaf -> if (node.negated) {
        null
    } else {
        Normal.Leaf(
            path = CatalogPath(prefix.steps + node.path.steps),
            kind = node.kind,
            predicates = node.predicates,
            terms = node.terms,
            negated = false,
        ) to true
    }

    is Normal.Disjunction -> {
        val parts = node.operands.map { rewrite(prefix, it) ?: return null }
        Normal.Disjunction(parts.map { it.first }) to parts.all { it.second }
    }

    // Every conjunct still narrows, and together they no longer decide: two conjuncts satisfied by
    // two *different* elements pass this and fail the element walk. That is the whole of what a
    // composite term was built to close, and here it is priced as a lost certainty instead.
    is Normal.Conjunction -> {
        val parts = node.operands.map { rewrite(prefix, it) ?: return null }
        Normal.Conjunction(parts.map { it.first }) to false
    }

    // A nested `elemMatch` composes by concatenation and stays exact at *this* level; whatever its own
    // operand costs in certainty is decided when the rewritten node is itself visited.
    is Normal.Element -> if (node.negated) {
        null
    } else {
        Normal.Element(CatalogPath(prefix.steps + node.path.steps), node.inner, negated = false) to true
    }

    // A constant narrows nothing, and a node that narrows nothing is a source the plan is better off
    // without: its candidate set is the segment's whole universe.
    Normal.AlwaysTrue, Normal.AlwaysFalse -> null
}
