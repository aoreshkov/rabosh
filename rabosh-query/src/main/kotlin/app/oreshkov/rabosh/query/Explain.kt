package app.oreshkov.rabosh.query

import app.oreshkov.rabosh.catalog.InferredSchema
import app.oreshkov.rabosh.index.IndexHandle

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
                shape = plan.expression?.render() ?: "full scan",
            )
        }
    }
}
