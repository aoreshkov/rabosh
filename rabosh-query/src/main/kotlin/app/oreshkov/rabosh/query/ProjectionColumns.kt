package app.oreshkov.rabosh.query

import app.oreshkov.rabosh.catalog.CatalogPath
import app.oreshkov.rabosh.catalog.CatalogStep
import app.oreshkov.rabosh.catalog.IndexKind
import app.oreshkov.rabosh.index.ColumnReader
import app.oreshkov.rabosh.index.IndexHandle
import app.oreshkov.rabosh.variant.Variant
import app.oreshkov.rabosh.variant.VariantPathStep

/**
 * The columns a projection's fields can be read from, when every one of them can.
 *
 * §9.8 claimed a shredded column means "a scan of one field never touches the documents". That was
 * true of *filtering* and false of *projection*: a plan that had already decided a key outright still
 * opened its document to fill the row in, which measured at 2.9x the cost of returning keys alone.
 * This is what closes it.
 *
 * **All fields or none.** Binding fails unless every projected field has a column, because one field
 * short means the document is read — and once it is read, every field comes from it. There is no
 * half-projected row: mixing the two sources would double the ways one value can be wrong while
 * saving nothing, since a document read serves the whole row at once.
 *
 * **A projected path is a [VariantPathStep.Field] chain and nothing else.** `Projection` speaks
 * `VariantPath`, which names exactly one location; a column is keyed by `CatalogPath`, which describes
 * a *set* of them. The two meet only where the projection has no array index and the column has no
 * wildcard — which is also why the repeated-path question that phase 12 was expected to answer does
 * not arise. `$.tags[*]` is already refused as a projection, so no column over it can ever be asked
 * to serve one, and no bound field has more than one value.
 */
internal class ProjectionColumns private constructor(private val fields: List<Bound>) {

    private class Bound(val reader: ColumnReader, val segments: Set<Long>)

    /**
     * Whether the whole row can be read from columns at this position.
     *
     * The segment check comes first and is not an optimisation: every `ColumnReader` accessor throws
     * for a segment outside `usableSegments`, and the readers bound here are opened for the
     * *projection* rather than for the plan, so their coverage is their own and need not match the
     * segments the plan evaluated.
     */
    fun canProject(segment: Long, ordinal: Int): Boolean =
        fields.all { it.segments.contains(segment) && it.reader.canProject(segment, ordinal) }

    /** The row's values, in the projection's own order. Only valid where [canProject] agreed. */
    fun valuesAt(segment: Long, ordinal: Int): Array<Variant?> =
        Array(fields.size) { fields[it].reader.valueAt(segment, ordinal) }

    companion object {
        /**
         * Binds [projection] to columns, or returns `null` when any field has none.
         *
         * [open] is the planner's own reader cache, so a column already opened to answer a *predicate*
         * is reused here rather than pinning the same sidecars twice — and every reader closes with
         * the plan whichever role it was opened for.
         */
        fun bind(
            projection: Projection,
            available: List<IndexHandle>,
            open: (IndexHandle) -> LeafReader,
        ): ProjectionColumns? {
            if (projection.wholeDocument || projection.fields.isEmpty()) return null

            val bound = ArrayList<Bound>(projection.fields.size)
            for (field in projection.fields) {
                val path = catalogPathOf(field.path.steps) ?: return null
                val handle = available.firstOrNull {
                    it.kind == IndexKind.SHREDDED_COLUMN && it.path == path
                } ?: return null
                val reader = open(handle) as? LeafReader.Column ?: return null
                bound += Bound(reader.reader, reader.usableSegments.toHashSet())
            }
            return ProjectionColumns(bound)
        }

        /**
         * The catalog path naming the same location, or `null` where there is none.
         *
         * An array index has no catalog spelling — `$.items[0].sku` and `$.items[*].sku` are different
         * questions, and the column answers the second — so such a projection simply does not bind.
         */
        private fun catalogPathOf(steps: List<VariantPathStep>): CatalogPath? {
            val catalog = ArrayList<CatalogStep>(steps.size)
            for (step in steps) {
                when (step) {
                    is VariantPathStep.Field -> catalog += CatalogStep.Field(step.name)
                    is VariantPathStep.Index -> return null
                }
            }
            return CatalogPath(catalog)
        }
    }
}
