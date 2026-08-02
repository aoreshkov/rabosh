package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.catalog.CatalogPath
import app.oreshkov.rabosh.catalog.IndexCandidate
import app.oreshkov.rabosh.catalog.IndexKind

/**
 * What an index is over.
 *
 * A path and a kind, and nothing else — no name, no options, no build state. Everything else about an
 * index is either derived from the segments (its coverage) or assigned when it is created (its id),
 * and putting any of it here would make two definitions of the same thing unequal.
 *
 * The path is a [CatalogPath] rather than a `VariantPath` because that is the vocabulary the
 * recommendation arrives in, and because it is the one that can describe an array: `$.tags[*]`
 * indexes every element of `tags`, which is what a query asking "which documents are tagged `x`"
 * means. A `VariantPath` names one location and cannot say that.
 */
public class IndexDefinition(
    /** The path indexed. Array indices collapse to `[*]`; see [CatalogPath]. */
    public val path: CatalogPath,
    /** What kind of index. */
    public val kind: IndexKind = IndexKind.INVERTED,
) {
    init {
        require(!path.isRoot) {
            "an index over the root path would be an index over every document with no term to look " +
                "it up by; index a field within it instead"
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is IndexDefinition && path == other.path && kind == other.kind)

    override fun hashCode(): Int = 31 * path.hashCode() + kind.hashCode()

    override fun toString(): String = "$path -> $kind"

    public companion object {
        /** An inverted index over [path]. Equality, `IN` and existence. */
        public fun inverted(path: CatalogPath): IndexDefinition = IndexDefinition(path, IndexKind.INVERTED)

        /** An inverted index over the path [expression] names, e.g. `$.items[*].sku`. */
        public fun inverted(expression: String): IndexDefinition = inverted(CatalogPath.parse(expression))

        /**
         * A shredded typed column over [path]. Ranges, and scans that never open a document.
         *
         * The kind to reach for when a query orders or compares rather than matches: an inverted
         * index's terms are sorted for lookup, not by value, so it cannot answer `<` at all.
         */
        public fun column(path: CatalogPath): IndexDefinition = IndexDefinition(path, IndexKind.SHREDDED_COLUMN)

        /** A shredded typed column over the path [expression] names. */
        public fun column(expression: String): IndexDefinition = column(CatalogPath.parse(expression))

        /**
         * The index a catalog recommendation asks for.
         *
         * The bridge between "what is in this store" and "build me one of those", and the reason
         * [IndexKind] lives in `rabosh-catalog` rather than here — the layer that recommends must
         * not have to know about the layer that builds.
         */
        public fun of(candidate: IndexCandidate): IndexDefinition =
            IndexDefinition(candidate.path, candidate.kind)
    }
}

/**
 * An index that has been defined, as the registry records it.
 *
 * **Identity is the [id], not the definition.** An index dropped and created again over the same path
 * is a different index with a different id, and the ids of dropped indexes are never reused. That is
 * not bookkeeping fastidiousness: posting files are named after the id, and a crash can leave one
 * behind. Reusing an id would let a stale file from a dropped index be read as the postings of a live
 * one — a wrong answer produced by a file that decodes perfectly.
 */
public class IndexHandle internal constructor(
    /** Assigned when the index was created, unique within the store, never reused. */
    public val id: Int,
    /** What it is over. */
    public val definition: IndexDefinition,
    /**
     * The store's sequence when the index was created.
     *
     * Recorded rather than used: it is what lets somebody reading a hex dump of a registry tell a
     * definition that predates a segment from one that follows it.
     */
    public val createdAtSequence: Long,
) {
    /** The path indexed. */
    public val path: CatalogPath get() = definition.path

    /** What kind of index. */
    public val kind: IndexKind get() = definition.kind

    override fun equals(other: Any?): Boolean = this === other || (other is IndexHandle && id == other.id)

    override fun hashCode(): Int = id

    override fun toString(): String = "IndexHandle(#$id, $definition)"
}
