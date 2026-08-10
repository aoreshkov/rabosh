package app.oreshkov.rabosh.jsonpath

/**
 * What a single evaluation is allowed to cost, before it is abandoned.
 *
 * **This is a bound that *refuses*, never one that truncates**, and the distinction is the whole
 * design. A budget that stopped early and returned what it had would be a wrong answer with nothing
 * to say so — which is why [JsonPathQuery.forEachNodeIn] still carries no such thing, and must not
 * acquire one. Exceeding a limit here raises [JsonPathLimitExceededException]: the caller learns that
 * the query was too expensive, rather than quietly receiving fewer nodes than the document holds.
 *
 * **What this is for.** `JsonPathQuery.compile` already refuses a query that is too *large* — 1024
 * selectors, 64 levels of nesting — and the I-Regexp matcher is a Thompson construction precisely
 * because RFC 9535 lets a `match` pattern come from the document. Neither bounds what a *small,
 * valid* query costs against a *large* document: `$..*..*` is eleven characters and is quadratic in
 * the document's node count, and a filter applied to every node of a descendant expansion is the
 * same shape. Where the expression is supplied by someone you do not trust — which is this module's
 * chosen use case — that gap is the whole attack.
 *
 * **Counted in steps, never on a clock**, for the reason the regex bound is: a wall-clock budget
 * makes the failure depend on the machine, so the same query would be rejected on a loaded CI runner
 * and accepted on a developer's laptop. Every number here is a count of work the evaluator does.
 *
 * **The defaults are a backstop, not a policy.** They are set so that no honest query over a
 * document this engine can hold will meet them — the module's own fixtures walk a 20 000-deep
 * document and a 5 000-wide array well inside them — which means a deployment that actually runs
 * hostile expressions should set its own, far tighter, and size them against the documents it holds.
 * [NONE] turns them off for a caller who has established trust some other way.
 *
 * ```kotlin
 * // A public endpoint compiling whatever it is handed.
 * val limits = JsonPathLimits(maxNodesVisited = 50_000, maxNodesProduced = 1_000, maxDescendantDepth = 32)
 * val query = JsonPathQuery.compile(untrusted, limits)
 *
 * val nodes = try {
 *     query.nodesIn(document)
 * } catch (rejected: JsonPathLimitExceededException) {
 *     respondTooExpensive(rejected.limit)      // never a partial nodelist
 * }
 * ```
 *
 * Immutable, and safe to share: the *limits* live on the query, the *counters* do not. Each call to
 * `forEachNodeIn` or `nodesIn` starts its own, which is what keeps one instance applicable to any
 * number of documents from any number of threads at once.
 *
 * @property maxNodesVisited node-touches allowed in one evaluation, across the whole query including
 *   the sub-walks a filter runs. Not distinct nodes: a node reached twice by two segments costs
 *   twice, because this bounds *work* and work is what an attacker buys. `0` or less means no bound.
 * @property maxNodesProduced nodes the caller's sink may be handed. A query whose answer is genuinely
 *   enormous is refused rather than delivered, which is what a caller materialising with `nodesIn`
 *   needs. `0` or less means no bound.
 * @property maxDescendantDepth levels a `..` expansion may descend below the node it started at.
 *   Bounds the location chain each node carries, and with it the cost of naming one. `0` or less
 *   means no bound.
 */
public class JsonPathLimits(
    public val maxNodesVisited: Long = DEFAULT_MAX_NODES_VISITED,
    public val maxNodesProduced: Long = DEFAULT_MAX_NODES_PRODUCED,
    public val maxDescendantDepth: Int = DEFAULT_MAX_DESCENDANT_DEPTH,
) {
    override fun toString(): String =
        "JsonPathLimits(visited=${describe(maxNodesVisited)}, produced=${describe(maxNodesProduced)}, " +
            "depth=${describe(maxDescendantDepth.toLong())})"

    private fun describe(value: Long): String = if (value <= 0) "unbounded" else value.toString()

    public companion object {
        /**
         * Node-touches allowed by default: ten million.
         *
         * Sized against the attack rather than against a typical query. `$..*..*` over a document
         * with *n* nodes costs `O(n²)`, so this is met by a document of a few thousand nodes under a
         * quadratic expression — while a linear walk of a document with ten million nodes is one
         * this engine would struggle to hold in a `Variant` at all.
         */
        public const val DEFAULT_MAX_NODES_VISITED: Long = 10_000_000L

        /** Nodes deliverable by default: one million. A materialised nodelist of that size is ~24 MiB of node objects alone. */
        public const val DEFAULT_MAX_NODES_PRODUCED: Long = 1_000_000L

        /**
         * Descendant depth allowed by default: one hundred thousand.
         *
         * Deliberately above `JsonPathQueryTest`'s 20 000-deep fixture, which exists to prove the
         * walk is iterative and would be a strange thing to then forbid. Depth is the weakest of the
         * three bounds — a document's depth is already bounded by the memory it took to build —
         * and it is here because the *location* of a node is a chain that long.
         */
        public const val DEFAULT_MAX_DESCENDANT_DEPTH: Int = 100_000

        /** The defaults, as a value. What [JsonPathQuery.compile] applies when asked for nothing else. */
        public val DEFAULT: JsonPathLimits = JsonPathLimits()

        /**
         * No bound of any kind.
         *
         * For a caller whose queries are its own — the engine's own tests, a query written in source
         * — where the only thing a limit could do is turn a correct answer into an exception.
         */
        public val NONE: JsonPathLimits = JsonPathLimits(
            maxNodesVisited = 0,
            maxNodesProduced = 0,
            maxDescendantDepth = 0,
        )
    }
}

/** Which bound an evaluation hit. */
public enum class JsonPathLimit {
    /** [JsonPathLimits.maxNodesVisited]. */
    NODES_VISITED,

    /** [JsonPathLimits.maxNodesProduced]. */
    NODES_PRODUCED,

    /** [JsonPathLimits.maxDescendantDepth]. */
    DESCENDANT_DEPTH,
}

/**
 * An evaluation cost more than [JsonPathLimits] allowed, and was abandoned.
 *
 * **Nothing was returned and nothing is partial.** A caller that catches this has no nodelist, which
 * is the point: the alternative — a short answer — cannot be told apart from a document that
 * genuinely holds fewer nodes.
 *
 * Distinct and catchable so that "this query is too expensive" can be answered differently from "this
 * query is malformed" (`IllegalArgumentException`, from `compile`) and from "this document will not
 * decode" (`VariantFormatException`). A caller serving untrusted expressions needs all three to be
 * separable, and separating them by message is not separating them.
 *
 * It extends `RuntimeException` directly rather than joining a module exception hierarchy, because
 * there is not one: `rabosh-jsonpath` reads no files and writes none, so it has no corruption, no
 * format version and no state — the three things that make the other modules' sealed hierarchies
 * worth having.
 *
 * @property limit which bound was hit.
 * @property allowed the value of that bound.
 */
public class JsonPathLimitExceededException internal constructor(
    public val limit: JsonPathLimit,
    public val allowed: Long,
) : RuntimeException(
    "JSONPath evaluation exceeded its ${limit.name.lowercase().replace('_', ' ')} limit of $allowed; " +
        "no nodes are returned, because a truncated nodelist cannot be told from a short document",
)
