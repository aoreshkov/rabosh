package app.oreshkov.rabosh.catalog

import app.oreshkov.rabosh.RaboshExperimental
import java.util.TreeMap

/**
 * Everything one segment's documents said about their shape.
 *
 * Immutable, and the unit the whole catalog is built out of: one of these per live segment, folded
 * with [merge] to produce the model of the store. Because the fold is over *live segments*, a
 * compaction that rewrites two segments into one replaces two sketches with one automatically —
 * there is no separate invalidation step and nothing to go stale.
 *
 * **The path budget is enforced here, and the overflow is reported rather than hidden.** Beyond
 * [CatalogOptions.maxPaths] distinct paths, the rest are folded into [estimatedDroppedPaths] and
 * [droppedObservations]. Machine-generated field names — an object keyed by user id, a log line
 * carrying a request id in the key — would otherwise make the path space a copy of the data.
 */
@RaboshExperimental
public class SegmentSketch internal constructor(
    /** Documents observed. Tombstones are not documents and are not counted. */
    public val documentCount: Long,
    /** Total path observations, dropped ones included. Conserved by [merge]. */
    public val observationCount: Long,
    private val entries: TreeMap<CatalogPath, PathSketch>,
    private val droppedPaths: HyperLogLog,
    /** Observations that belonged to a path beyond the budget. */
    public val droppedObservations: Long,
) {
    /** The paths this sketch tracks, in canonical order. */
    public val paths: Set<CatalogPath> get() = entries.keys

    /** How many paths are tracked, dropped ones excluded. */
    public val pathCount: Int get() = entries.size

    /** What was observed at [path], or `null` if it was never seen or was dropped. */
    public operator fun get(path: CatalogPath): PathSketch? = entries[path]

    /** Every tracked path and its sketch, in canonical order. */
    public fun entries(): Map<CatalogPath, PathSketch> = entries

    /** Estimated distinct paths that did not fit the budget. Zero when nothing was dropped. */
    public val estimatedDroppedPaths: Long get() = droppedPaths.estimate

    /**
     * This sketch folded with [other], keeping at most [maxPaths] paths.
     *
     * **Exactly associative and commutative while the union fits the budget** — the case the
     * property tests assert, and the case that holds for any store whose documents have a bounded
     * shape. Beyond the budget it is not, and cannot be: which paths survive depends on which
     * observations have been seen when the truncation happens, and that is the fold order. What
     * remains true unconditionally is **conservation**: [observationCount] and the sum of tracked
     * and dropped observations are preserved whatever the order, so the totals a report is built on
     * never depend on it.
     *
     * The alternative — truncating only at the very end — would be exactly associative and would
     * hold every path of every segment in memory until then, which for a store with a hundred
     * segments is a hundred times the budget. Bounded memory is worth the caveat, and the caveat is
     * written down rather than discovered.
     */
    public fun merge(other: SegmentSketch, maxPaths: Int = DEFAULT_MAX_PATHS): SegmentSketch {
        require(maxPaths > 0) { "maxPaths must be positive, was $maxPaths" }
        val union = TreeMap<CatalogPath, PathSketch>(entries)
        for ((path, sketch) in other.entries) {
            union.merge(path, sketch) { existing, incoming -> existing.merge(incoming) }
        }

        val dropped = droppedPaths.mergedWith(other.droppedPaths)
        var droppedCount = droppedObservations + other.droppedObservations
        if (union.size > maxPaths) {
            // Keep the paths with the most to say. Ties break on the path itself, so the choice is
            // deterministic rather than dependent on hash order.
            val survivors = union.entries
                .sortedWith(compareByDescending<Map.Entry<CatalogPath, PathSketch>> { it.value.observations }
                    .thenBy { it.key })
                .take(maxPaths)
                .mapTo(HashSet()) { it.key }
            val iterator = union.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key in survivors) continue
                dropped.add(entry.key.toString().encodeToByteArray())
                droppedCount += entry.value.observations
                iterator.remove()
            }
        }

        return SegmentSketch(
            documentCount = documentCount + other.documentCount,
            observationCount = observationCount + other.observationCount,
            entries = union,
            droppedPaths = dropped,
            droppedObservations = droppedCount,
        )
    }

    internal fun droppedPathSketch(): HyperLogLog = droppedPaths

    override fun equals(other: Any?): Boolean =
        this === other || (
            other is SegmentSketch &&
                documentCount == other.documentCount &&
                observationCount == other.observationCount &&
                entries == other.entries &&
                droppedPaths == other.droppedPaths &&
                droppedObservations == other.droppedObservations
            )

    override fun hashCode(): Int {
        var result = documentCount.hashCode()
        result = 31 * result + observationCount.hashCode()
        result = 31 * result + entries.hashCode()
        result = 31 * result + droppedPaths.hashCode()
        return 31 * result + droppedObservations.hashCode()
    }

    override fun toString(): String =
        "SegmentSketch($documentCount documents, $pathCount paths" +
            (if (droppedObservations > 0) ", ~$estimatedDroppedPaths dropped" else "") + ")"

    public companion object {
        /** A sketch of nothing. The identity of [merge]. */
        public val EMPTY: SegmentSketch = SegmentSketch(0, 0, TreeMap(), HyperLogLog(), 0)
    }
}
