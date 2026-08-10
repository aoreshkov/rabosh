package app.oreshkov.rabosh.query

import app.oreshkov.rabosh.catalog.InferredSchema
import app.oreshkov.rabosh.index.IndexHandle
import app.oreshkov.rabosh.variant.VariantKind

/**
 * One index source of a plan, with what it would actually admit.
 *
 * [candidates] is measured, not estimated: the sources are read to produce it. That is deliberate —
 * an explanation that guessed would be a second cost model, and the one thing a planner must not have
 * is two.
 */
public class ExplainSource internal constructor(
    public val index: IndexHandle,
    public val describes: String,
    /** Ordinals this source admits across every evaluated segment. */
    public val candidates: Int,
    /** Of those, the ones it decides outright — the rest are documents to open. */
    public val certain: Int,
    /** What the schema expected, if there was one. */
    public val estimate: String,
) {
    override fun toString(): String =
        "#${index.id} ${index.kind} $describes -> $candidates candidate(s), $certain certain, $estimate"
}

/**
 * A leaf whose predicate family disagrees with the types actually stored at its path.
 *
 * **A diagnostic, never a coercion.** Nothing here changes an answer, a plan or a bound. A numeric
 * predicate still matches numeric values only — that is type bracketing, it is part of the query
 * contract, and skipping a column whose numeric bound misses depends on it. What this adds is the
 * *reason* a query returned fewer rows than a caller expected, at the one moment they are asking.
 *
 * The case it exists for is a third-party payload archive, where a vendor sends `"500"` in some
 * documents and `500` in others. `where(path("$.status") eq 500)` then silently matches only half of
 * them, and there is nothing in the result to say why — which the README's own sample calls "the part
 * that surprises people".
 *
 * @property path the path the leaf tests.
 * @property describes the leaf, as it appears in the plan.
 * @property family the family the leaf's literals bracket to: `numeric`, `text` or `boolean`.
 * @property mismatchedFraction how much of the observed data at [path] is outside that family, as the
 *   catalog's sketch reports it. An estimate over the segments the model covers, not a count.
 * @property mismatchedTypes the offending types, commonest first, with each one's share.
 */
public class ExplainTypeNote internal constructor(
    public val path: String,
    public val describes: String,
    public val family: String,
    public val mismatchedFraction: Double,
    public val mismatchedTypes: List<String>,
) {
    override fun toString(): String =
        "$describes is a $family test, and ${percent(mismatchedFraction)} of the values at $path are " +
            mismatchedTypes.joinToString(", ") + " — those are not matched, and that is not an error"

    private fun percent(fraction: Double): String = "%.1f%%".format(fraction * 100)
}

/**
 * How a query would be answered.
 *
 * The reason this exists rather than being left to a debugger is the one `IndexCandidate.reason`
 * already makes: a decision that cannot say what it was based on is one nobody can argue with. It is
 * also the instrument the work-assertion tests use to say *which* index a plan chose and in what
 * order it intersects, which is a fact about the plan rather than a stopwatch reading.
 */
public class Explain internal constructor(
    public val query: Query,
    /** Sources in the order the intersection would run them: cheapest first. */
    public val sources: List<ExplainSource>,
    public val segmentsIndexed: Int,
    public val segmentsScanned: Int,
    public val scansUnflushed: Boolean,
    /**
     * Whether every projected field is bound to a shredded column that can reconstruct it exactly.
     *
     * A fact about the *plan*, decided before a row is produced, which is why it belongs here rather
     * than only in `QueryStats.rowsProjectedFromColumns`. `true` does not promise every row avoids its
     * document — a residual ordinal still needs one — so the two answer different questions and a test
     * that means "push-down fired" wants the counter.
     */
    public val projectsFromColumns: Boolean,
    /**
     * Leaves whose predicate family disagrees with the types stored at their path.
     *
     * Empty for a query whose predicates and data agree, and empty when there is no schema to
     * compare against — no statistics is not the same as a statistic saying no. See
     * [ExplainTypeNote].
     */
    public val typeNotes: List<ExplainTypeNote>,
    private val shape: String,
) {
    /** Whether this plan reads any sidecar at all. */
    public val usesIndexes: Boolean get() = sources.isNotEmpty() && segmentsIndexed > 0

    public fun render(): String = buildString {
        appendLine("query: $query")
        appendLine("plan:  $shape")
        appendLine(
            "segments: $segmentsIndexed indexed, $segmentsScanned scanned" +
                if (scansUnflushed) ", plus unflushed writes" else "",
        )
        appendLine(
            if (projectsFromColumns) {
                "projection: read from shredded columns; no document is opened for a certain row"
            } else {
                "projection: read from documents"
            },
        )
        if (sources.isEmpty()) {
            appendLine("sources: none — every document is scanned and matched")
        } else {
            appendLine("sources, cheapest first:")
            for (source in sources) appendLine("  $source")
        }
        if (typeNotes.isNotEmpty()) {
            appendLine("notes:")
            for (note in typeNotes) appendLine("  $note")
        }
    }

    override fun toString(): String = render()

    internal companion object {
        fun of(query: Query, plan: QueryPlan, schema: InferredSchema?): Explain {
            val work = WorkStats()
            val measured = plan.expression?.sources().orEmpty().map { source ->
                var candidates = 0
                var certain = 0
                for (segment in plan.evaluated.keys) {
                    if (segment !in source.reader.usableSegments) continue
                    val ordinals = source.ordinals(segment, work)
                    candidates += ordinals.candidates.cardinality
                    certain += ordinals.certain.cardinality
                }
                ExplainSource(
                    index = source.handle,
                    describes = source.leaf.toString(),
                    candidates = candidates,
                    certain = certain,
                    estimate = Selectivity.describe(source.leaf, schema),
                )
            }
            return Explain(
                query = query,
                sources = measured.sortedBy { it.candidates },
                segmentsIndexed = plan.evaluated.size,
                segmentsScanned = plan.scanned.size,
                scansUnflushed = plan.hasUnflushedDocuments,
                projectsFromColumns = plan.projection != null,
                typeNotes = typeNotes(plan, schema),
                shape = plan.expression?.render() ?: "full scan",
            )
        }

        /**
         * Below this, a mismatch is noise rather than a finding.
         *
         * A single stray value at a path with a million observations is not what surprises anybody,
         * and a note for it would train a reader to skim the notes. One percent is low enough to
         * catch the "one producer in fifty sends a string" case the samples themselves demonstrate.
         */
        private const val WORTH_REPORTING = 0.01

        /**
         * Every leaf whose family disagrees with the types observed at its path.
         *
         * Over **all** the plan's leaves rather than only its indexed sources, which is deliberate:
         * a path with no index is exactly where a caller has no other signal at all, and the plan is
         * a full scan whose result is quietly short. `DocumentMatcher` already holds the leaves, so
         * this asks the object that has them rather than re-walking the tree.
         *
         * Returns nothing without a schema. No statistics is not a statistic saying no, and a note
         * asserting agreement it never checked would be worse than silence.
         */
        private fun typeNotes(plan: QueryPlan, schema: InferredSchema?): List<ExplainTypeNote> {
            if (schema == null) return emptyList()
            val notes = LinkedHashMap<String, ExplainTypeNote>()
            for (leaf in plan.matcher.leaves) {
                if (leaf.family == LeafFamily.ANY) continue
                val field = schema[leaf.path] ?: continue
                val observed = field.types.values.sum()
                if (observed <= 0L) continue

                // `NULL` is left out of the denominator rather than counted as a mismatch. A null is
                // absent from every scalar family — a numeric predicate does not match it and neither
                // does a text one — so reporting it here would fire on every nullable field and say
                // nothing about the surprise this note exists for.
                val comparable = field.types.filterKeys { it != VariantKind.NULL }
                val total = comparable.values.sum()
                if (total <= 0L) continue

                val mismatched = comparable.filterKeys { it !in leaf.family.kinds }
                val fraction = mismatched.values.sum().toDouble() / total
                if (fraction < WORTH_REPORTING) continue

                val note = ExplainTypeNote(
                    path = leaf.path.toString(),
                    describes = leaf.toString(),
                    family = leaf.family.description,
                    mismatchedFraction = fraction,
                    mismatchedTypes = mismatched.entries
                        .sortedByDescending { it.value }
                        .map { (kind, count) ->
                            "${kind.name.lowercase()} (%.1f%%)".format(count.toDouble() / total * 100)
                        },
                )
                // One note per leaf, keyed by what it says: a query naming the same path twice with
                // the same family would otherwise report the same sentence twice.
                notes.putIfAbsent(note.describes, note)
            }
            return notes.values.toList()
        }
    }
}
