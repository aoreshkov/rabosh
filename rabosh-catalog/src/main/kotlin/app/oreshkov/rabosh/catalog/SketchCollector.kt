package app.oreshkov.rabosh.catalog

import app.oreshkov.rabosh.variant.Variant
import app.oreshkov.rabosh.variant.VariantBasicType
import java.util.TreeMap

/**
 * Walks documents and accumulates one segment's [SegmentSketch].
 *
 * This is the pass that makes "model later" cheap: it runs inside the flush or compaction that was
 * going to touch every document anyway, so the marginal cost is the walk over an already-decoded
 * value, not a scan of the store.
 *
 * Three bounds are enforced, and all three are on *caller-controlled* shape rather than on volume.
 * A document's nesting, its array lengths and its field names all come from whoever wrote it, and
 * this code runs inside compaction — a document that made the walk expensive would make the engine's
 * background maintenance expensive, which is a much worse outcome than a slightly less complete
 * model. So depth stops at [CatalogOptions.maxDepth], children at [CatalogOptions.maxChildren], and
 * distinct paths at [CatalogOptions.maxPaths], and every one of them reports what it dropped.
 */
internal class SegmentSketchBuilder(private val options: CatalogOptions) {
    private val builders = HashMap<CatalogPath, PathSketchBuilder>()
    private val droppedPaths = HyperLogLog()
    private val steps = ArrayList<CatalogStep>()

    private var documents = 0L
    private var observations = 0L
    private var droppedObservations = 0L

    /** Documents added so far. */
    val documentCount: Long get() = documents

    /** Adds one document. Tombstones are not documents and must not be passed here. */
    fun add(document: Variant) {
        documents++
        check(steps.isEmpty()) { "the path stack was left dirty by a previous document" }
        walk(document, 0)
    }

    fun build(): SegmentSketch {
        val entries = TreeMap<CatalogPath, PathSketch>()
        for ((path, builder) in builders) entries[path] = builder.build()
        return SegmentSketch(
            documentCount = documents,
            observationCount = observations,
            entries = entries,
            droppedPaths = droppedPaths,
            droppedObservations = droppedObservations,
        )
    }

    private fun walk(value: Variant, depth: Int) {
        val builder = record(value)
        if (depth >= options.maxDepth) return
        when (value.basicType) {
            VariantBasicType.OBJECT -> {
                val children = minOf(value.fieldCount, options.maxChildren)
                for (index in 0 until children) {
                    steps.add(CatalogStep.Field(value.fieldName(index)))
                    walk(value.fieldValue(index), depth + 1)
                    steps.removeAt(steps.size - 1)
                }
            }

            VariantBasicType.ARRAY -> {
                val children = minOf(value.elementCount, options.maxChildren)
                if (children == 0) return
                // One step for the whole array, not one per index: see [CatalogPath].
                steps.add(CatalogStep.AnyElement)
                for (index in 0 until children) walk(value.element(index), depth + 1)
                steps.removeAt(steps.size - 1)
            }

            // A scalar has no children. Its own observation was recorded above.
            VariantBasicType.PRIMITIVE, VariantBasicType.SHORT_STRING -> {
                if (builder != null) recordScalar(builder, value)
            }
        }
    }

    /**
     * Counts one observation of the current path, returning its builder or `null` when the path
     * budget is full.
     *
     * A dropped path still costs its observation, which is counted into [droppedObservations] rather
     * than lost — the totals in a report have to add up whatever the budget did.
     */
    private fun record(value: Variant): PathSketchBuilder? {
        observations++
        val path = CatalogPath(steps.toList())
        var builder = builders[path]
        if (builder == null) {
            if (builders.size >= options.maxPaths) {
                droppedPaths.add(path.toString().encodeToByteArray())
                droppedObservations++
                return null
            }
            builder = PathSketchBuilder(options.textBoundBytes)
            builders[path] = builder
        }
        // `kind` reads the value's header and reports an unknown type id rather than defaulting.
        // Letting that propagate abandons this segment's observation and leaves the write untouched,
        // which is the right trade for derived data.
        builder.observe(value.kind, value.byteSize)
        return builder
    }

    /**
     * Records what a scalar contributes beyond its type: a bound, and a value for the distinct
     * count.
     *
     * The distinct count is taken over [ValueSignature], which is shared with `rabosh-index` so
     * that the estimator recommending an index and the index that gets built agree on when two
     * values are the same value. A bound is a separate question and is deliberately narrower: only
     * numbers and text have one, because those are the only two [SketchFormat] has a tag for.
     */
    private fun recordScalar(builder: PathSketchBuilder, value: Variant) {
        builder.observeBounds(value)
        ValueSignature.of(value)?.let(builder::observeDistinct)
    }
}
