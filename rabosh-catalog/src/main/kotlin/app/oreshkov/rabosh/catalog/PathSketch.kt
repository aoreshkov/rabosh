package app.oreshkov.rabosh.catalog

import app.oreshkov.rabosh.variant.VariantKind

/**
 * What has been observed at one path.
 *
 * Immutable and mergeable. Mergeable is the load-bearing half: the model of a whole store is the
 * fold of its live segments' sketches, so if [merge] were not associative and commutative the model
 * would depend on the order compaction happened to run in rather than on the data. Every field here
 * merges by summation or by a bound, both of which have that property; the cardinality estimator has
 * it too, by construction — see [HyperLogLog].
 *
 * **[observations] counts documents, not values.** A path is observed once per document that has it,
 * so `observations / documentCount` is the presence ratio, and a path under an
 * [CatalogStep.AnyElement] step is observed once per *element*, which is why its ratio can exceed
 * one. That is the honest number for an array: `$.tags[*]` really does occur three times in a
 * document with three tags.
 *
 * **[totalBytes] is the encoded size of the value at this path**, subtree included. So an object's
 * bytes are also counted in each of its fields. That is deliberate: the question "what fraction of a
 * document is this subtree" is the one that decides whether shredding a path is worth it, and it
 * needs the subtree total.
 */
public class PathSketch internal constructor(
    /** Documents — or array elements — in which this path was present. */
    public val observations: Long,
    /**
     * Observations whose value was the JSON `null`.
     *
     * Distinct from being absent, which is what the difference between [observations] and the
     * document count measures. A path present with a null value and a path that is missing are
     * different facts about the data and lead to different schema decisions.
     */
    public val nullObservations: Long,
    private val typeCounts: LongArray,
    /** Total encoded size of the values at this path. See the class documentation. */
    public val totalBytes: Long,
    /** Numeric and text ranges. See [ValueBounds]. */
    public val bounds: ValueBounds,
    private val distinct: HyperLogLog,
) {
    /**
     * How many observations each kind accounted for, largest first.
     *
     * Only kinds actually seen appear. Ties break by the kind's name, so a report is stable between
     * runs — an inferred schema is something people diff.
     */
    public val types: Map<VariantKind, Long>
        get() = VariantKind.entries
            .filter { typeCounts[it.ordinal] > 0 }
            .sortedWith(compareByDescending<VariantKind> { typeCounts[it.ordinal] }.thenBy { it.name })
            .associateWith { typeCounts[it.ordinal] }

    /** Observations of [kind] at this path. */
    public fun count(kind: VariantKind): Long = typeCounts[kind.ordinal]

    /**
     * The kind that accounts for the most observations, or `null` when nothing was observed.
     *
     * Note that this is the *most common* kind, not the only one; [typeStability] says how dominant
     * it is, and a caller deciding whether a path can be shredded wants both.
     */
    public val dominantType: VariantKind?
        get() = types.entries.firstOrNull()?.key

    /** The share of observations that took [dominantType], in `0.0..1.0`. */
    public val typeStability: Double
        get() {
            if (observations == 0L) return 0.0
            val dominant = dominantType ?: return 0.0
            return typeCounts[dominant.ordinal].toDouble() / observations
        }

    /**
     * Estimated distinct scalar values at this path.
     *
     * Scalar only. An object or an array contributes to [observations] and to [types] but not to the
     * distinct count: hashing whole subtrees would cost the subtree on every document, and "how many
     * distinct shapes does this object have" is not the question an index recommendation asks.
     * Nulls are excluded too — [nullObservations] already reports them, and folding them in would
     * make a column of nothing but nulls look like it had a value worth indexing.
     */
    public val distinctEstimate: Long get() = distinct.estimate

    /** Whether [distinctEstimate] is exact rather than estimated. See [HyperLogLog]. */
    public val distinctIsExact: Boolean get() = distinct.isSparse

    /** Mean encoded size of a value at this path. */
    public val averageBytes: Double
        get() = if (observations == 0L) 0.0 else totalBytes.toDouble() / observations

    /** This sketch folded with [other]. Associative, commutative, and never lossy. */
    public fun merge(other: PathSketch): PathSketch {
        val counts = LongArray(typeCounts.size)
        for (index in counts.indices) counts[index] = typeCounts[index] + other.typeCounts[index]
        return PathSketch(
            observations = observations + other.observations,
            nullObservations = nullObservations + other.nullObservations,
            typeCounts = counts,
            totalBytes = totalBytes + other.totalBytes,
            bounds = bounds.merge(other.bounds),
            distinct = distinct.mergedWith(other.distinct),
        )
    }

    internal fun distinctSketch(): HyperLogLog = distinct

    internal fun typeCountsArray(): LongArray = typeCounts

    override fun equals(other: Any?): Boolean =
        this === other || (
            other is PathSketch &&
                observations == other.observations &&
                nullObservations == other.nullObservations &&
                typeCounts.contentEquals(other.typeCounts) &&
                totalBytes == other.totalBytes &&
                bounds == other.bounds &&
                distinct == other.distinct
            )

    override fun hashCode(): Int {
        var result = observations.hashCode()
        result = 31 * result + nullObservations.hashCode()
        result = 31 * result + typeCounts.contentHashCode()
        result = 31 * result + totalBytes.hashCode()
        result = 31 * result + bounds.hashCode()
        return 31 * result + distinct.hashCode()
    }

    override fun toString(): String =
        "PathSketch($observations obs, ${types.keys.joinToString("|")}, " +
            "~$distinctEstimate distinct, ${"%.1f".format(averageBytes)} B)"
}

/**
 * Accumulates a [PathSketch] over one segment.
 *
 * Mutable where [PathSketch] is not, because this is called once per value on every flush and every
 * compaction: an immutable fold would allocate a sketch and copy a cardinality estimator for each
 * one, which would make collecting statistics cost more than writing the segment.
 */
internal class PathSketchBuilder(private val textBoundBytes: Int) {
    private val typeCounts = LongArray(VariantKind.entries.size)
    private val distinct = HyperLogLog()

    var observations: Long = 0
        private set
    private var nullObservations = 0L
    private var totalBytes = 0L
    private val bounds = ValueBoundsBuilder(textBoundBytes)

    fun observe(kind: VariantKind, byteSize: Long) {
        observations++
        typeCounts[kind.ordinal]++
        totalBytes += byteSize
        if (kind == VariantKind.NULL) nullObservations++
    }

    fun observeDistinct(signature: ByteArray) {
        distinct.add(signature)
    }

    /** Widens the bounds to include [value], if its kind has one. See [ValueBoundsBuilder]. */
    fun observeBounds(value: app.oreshkov.rabosh.variant.Variant) {
        bounds.add(value)
    }

    fun build(): PathSketch = PathSketch(
        observations = observations,
        nullObservations = nullObservations,
        typeCounts = typeCounts,
        totalBytes = totalBytes,
        bounds = bounds.build(),
        distinct = distinct,
    )
}
