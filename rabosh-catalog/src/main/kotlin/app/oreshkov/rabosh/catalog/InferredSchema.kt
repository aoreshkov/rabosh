package app.oreshkov.rabosh.catalog

import app.oreshkov.rabosh.RaboshExperimental
import app.oreshkov.rabosh.variant.VariantKind

/**
 * How much of the store the model actually accounts for.
 *
 * Reported rather than assumed, because a model with a hole in it is worse than no model: a path
 * that looks absent because its segment was never sketched is indistinguishable, to a caller, from a
 * path that is genuinely absent. Two things can leave a hole — a segment whose sidecar has not been
 * built yet, and documents that are still in the memtable — and each has its own number here.
 */
public class CatalogCoverage internal constructor(
    /** Live segments whose sketches went into the model. */
    public val segmentsCovered: Int,
    /** Live segments in the store. */
    public val segmentsTotal: Int,
) {
    /** Whether every live segment contributed. */
    public val isComplete: Boolean get() = segmentsCovered == segmentsTotal

    /** Fraction of live segments covered, in `0.0..1.0`. */
    public val fraction: Double
        get() = if (segmentsTotal == 0) 1.0 else segmentsCovered.toDouble() / segmentsTotal

    override fun toString(): String = "$segmentsCovered/$segmentsTotal segments"
}

/**
 * What the catalog knows about one path.
 *
 * A thin, named reading of a [PathSketch] against the document count, so that a caller asking "is
 * this field always there" gets a ratio rather than having to divide two counters and know which
 * denominator is the right one.
 */
public class InferredField internal constructor(
    /** The path, with array indices collapsed. See [CatalogPath]. */
    public val path: CatalogPath,
    /**
     * The raw statistics this reading is derived from.
     *
     * Outside the stable core, and the only member of this class that is: the named readings below
     * are a contract, and a `PathSketch` is a serialised estimator whose registers, hash and sparse
     * limit belong to `SketchFormat`. Everything a caller needs about a path is already a property
     * here; reaching for the sketch means reaching for the format.
     */
    @RaboshExperimental
    public val sketch: PathSketch,
    private val documentCount: Long,
) {
    /** Documents — or array elements — in which the path was present. */
    public val observations: Long get() = sketch.observations

    /**
     * Observations divided by documents.
     *
     * **May exceed 1.0**, and legitimately: a path under an [CatalogStep.AnyElement] step occurs
     * once per element, so `$.tags[*]` really does occur three times in a document with three tags.
     * Clamping it would hide the most useful thing this number says about an array.
     */
    public val presence: Double
        get() = if (documentCount == 0L) 0.0 else observations.toDouble() / documentCount

    /** Observations by kind, largest first. See [PathSketch.types]. */
    public val types: Map<VariantKind, Long> get() = sketch.types

    /** The most common kind, or `null` if nothing was observed. */
    public val dominantType: VariantKind? get() = sketch.dominantType

    /** The share of observations that took [dominantType]. One means the path has a single type. */
    public val typeStability: Double get() = sketch.typeStability

    /** The share of observations whose value was the JSON `null`, as opposed to being absent. */
    public val nullFraction: Double
        get() = if (observations == 0L) 0.0 else sketch.nullObservations.toDouble() / observations

    /** Estimated distinct scalar values. Exact for low-cardinality paths; see [PathSketch]. */
    public val distinctEstimate: Long get() = sketch.distinctEstimate

    /** Whether [distinctEstimate] is exact. */
    public val distinctIsExact: Boolean get() = sketch.distinctIsExact

    /** Mean encoded size of the value at this path, subtree included. */
    public val averageBytes: Double get() = sketch.averageBytes

    /** Numeric and text ranges. */
    public val bounds: ValueBounds get() = sketch.bounds

    /** Whether the path holds one scalar type in every document that has it. */
    public val isStableScalar: Boolean
        get() {
            val dominant = dominantType ?: return false
            return typeStability == 1.0 && dominant.isScalar
        }

    override fun toString(): String =
        "$path: ${types.keys.joinToString("|") { it.name.lowercase() }} " +
            "in ${"%.1f".format(presence * 100)}% of documents, " +
            (if (distinctIsExact) "$distinctEstimate" else "~$distinctEstimate") + " distinct"
}

/**
 * The model the catalog derives from the store's segments.
 *
 * This is the answer to "what is actually in there", and it is produced by folding the per-segment
 * sketches rather than by scanning — so asking for it costs the fold, not the data.
 *
 * **Two approximations are inherent and are not bugs.** A key that has been overwritten but not yet
 * compacted is counted once per segment that holds a live version, and a deleted document's earlier
 * contribution survives until compaction drops the superseded version. Both have the same cause —
 * an LSM tree holds several versions of a key until it merges them — and the same fix, which is
 * `DocumentStore.compact()`. Against an append-only or compacted store the counts are exact.
 */
public class InferredSchema internal constructor(
    /** Documents the model accounts for. See the class documentation for what "accounts for" means. */
    public val documentCount: Long,
    /** Every tracked path, in canonical order. */
    public val fields: List<InferredField>,
    /** How much of the store contributed. See [CatalogCoverage]. */
    public val coverage: CatalogCoverage,
    /** Estimated distinct paths dropped for exceeding [CatalogOptions.maxPaths]. */
    public val truncatedPathEstimate: Long,
    /** Observations that belonged to a dropped path. */
    public val truncatedObservations: Long,
) {
    /** The field at [path], or `null` if the catalog does not track it. */
    public operator fun get(path: CatalogPath): InferredField? = fields.firstOrNull { it.path == path }

    /** The field at the path [expression] parses to. See [CatalogPath.parse]. */
    public operator fun get(expression: String): InferredField? = get(CatalogPath.parse(expression))

    /** Fields present in at least [fraction] of documents, most present first. */
    public fun required(fraction: Double = 1.0): List<InferredField> =
        fields.filter { it.presence >= fraction }.sortedByDescending { it.presence }

    /** Whether any path was dropped for exceeding the budget. */
    public val isTruncated: Boolean get() = truncatedObservations > 0

    /** A human-readable table, for a report or a failing assertion. */
    public fun render(): String = buildString {
        append("documents: ").append(documentCount)
        append(", paths: ").append(fields.size)
        append(", coverage: ").append(coverage)
        if (isTruncated) append(", ~").append(truncatedPathEstimate).append(" paths dropped")
        append('\n')
        for (field in fields) {
            append("  ").append(field.path).append('\n')
            append("      presence ").append("%.1f%%".format(field.presence * 100))
            append("  types ").append(
                field.types.entries.joinToString { "${it.key.name.lowercase()}=${it.value}" },
            )
            // Containers contribute no distinct values by design, so the column is left off rather
            // than printed as a zero that reads like "no values were seen".
            if (field.distinctEstimate > 0) {
                append("  distinct ")
                append(if (field.distinctIsExact) "" else "~").append(field.distinctEstimate)
            }
            append("  avg ").append("%.1f B".format(field.averageBytes))
            if (field.nullFraction > 0) append("  null ").append("%.1f%%".format(field.nullFraction * 100))
            if (!field.bounds.isEmpty) append("  ").append(field.bounds)
            append('\n')
        }
    }

    override fun toString(): String =
        "InferredSchema($documentCount documents, ${fields.size} paths, $coverage)"
}

/** Whether a kind is a single value rather than a container. */
internal val VariantKind.isScalar: Boolean
    get() = this != VariantKind.OBJECT && this != VariantKind.ARRAY
