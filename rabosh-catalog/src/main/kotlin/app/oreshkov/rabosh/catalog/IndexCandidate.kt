package app.oreshkov.rabosh.catalog

/**
 * What kind of sidecar a path is worth.
 *
 * The catalog's own vocabulary, not the index module's: `rabosh-index` sits *above* this one, so
 * naming its types here would be an upward dependency. It is a recommendation, and the layer that
 * builds indexes decides what to make of it.
 */
public enum class IndexKind {
    /**
     * A `value -> bitmap` inverted index. Equality, `IN`, and existence, answered by intersecting
     * bitmaps before any document is touched.
     */
    INVERTED,

    /**
     * A shredded typed column, per the Variant shredding specification: the path's values lifted out
     * of the documents into a typed run with min/max statistics, so a scan of that one field does
     * not touch the documents at all.
     */
    SHREDDED_COLUMN,

    /**
     * A composite term over one array element: the values of several fields *of the same element*,
     * keyed together.
     *
     * The one kind that answers a **correlated** question. An ordinary conjunction over an array path
     * is existential in each leaf independently, so `and($.items[*].sku eq "A", $.items[*].qty eq 5)`
     * matches a document whose `sku` came from one element and `qty` from another — a defined
     * semantics, and one that returned **5-6x** the documents a caller wanting the correlated question
     * keeps, measured over corpora whose element fields vary independently. This kind keys the tuple,
     * so the correlated question is answered by a dictionary lookup.
     *
     * **Never recommended, only asked for.** The same measurement found the rate is exactly zero on a
     * corpus whose element fields move together, so a recommender would be spending bytes on a shape
     * it cannot see from a sketch. [IndexCandidate]s are never of this kind; it is created by name,
     * with the fields named too.
     *
     * It answers a *fully known* conjunction and nothing else — every declared field compared for
     * equality, in one `elemMatch` — which is Postgres `jsonb_path_ops`'s documented limit and the
     * reason this **supplements** the leaf indexes rather than replacing them.
     */
    COMPOSITE_TERM,
}

/**
 * A path the catalog thinks is worth an index, and why.
 *
 * The [reason] is not decoration. An automatic recommendation that cannot say what it is based on is
 * one nobody can argue with, and the numbers behind these are estimates — a caller who knows the
 * workload should be able to see that a path was recommended because it is in 98% of documents with
 * one type and reject it because nothing ever filters on it.
 */
public class IndexCandidate internal constructor(
    /** The path an index would be built for. */
    public val path: CatalogPath,
    /** What kind of index. */
    public val kind: IndexKind,
    /**
     * A relative ranking in `0.0..1.0`. Comparable between candidates of the same [kind] and only
     * loosely between kinds — they are answering different questions.
     */
    public val score: Double,
    /** What the recommendation is based on, in words. */
    public val reason: String,
    /** The statistics it was derived from. */
    public val field: InferredField,
) {
    override fun toString(): String = "$path -> $kind (${"%.2f".format(score)}): $reason"
}

/**
 * The thresholds [SchemaCatalog.indexCandidates] applies.
 *
 * Every one of them is a heuristic and every one is here rather than buried in the code, because a
 * recommendation engine whose rules cannot be seen or changed is one that gets ignored the first
 * time it is wrong about somebody's workload.
 */
public class IndexCandidateOptions(
    /**
     * Observations a path needs before it is considered at all.
     *
     * A path seen three times says nothing about the shape of a store, and every ratio computed from
     * it is noise.
     */
    public val minObservations: Long = 16,
    /** How much of the corpus a path must appear in. An index over a rare path rarely pays. */
    public val minPresence: Double = 0.5,
    /**
     * How dominant the path's type must be.
     *
     * A path that is a string in half its documents and an object in the other half cannot be
     * indexed as either without deciding what to do with the rest, which is a decision this layer
     * should not be making silently.
     */
    public val minTypeStability: Double = 0.9,
    /**
     * Distinct values a path needs **to be worth an inverted index**.
     *
     * Two, because an index over a path with one value returns every document and has cost nothing
     * but space. The upper end is [maxDistinctFraction], and it is unbounded by default for the
     * reason stated there.
     *
     * It does not apply to [IndexKind.SHREDDED_COLUMN], which is not answering an equality question:
     * a column exists so that a scan of one field never touches the documents, and it pays in
     * proportion to the bytes it avoids reading whatever the cardinality is.
     */
    public val minDistinct: Long = 2,
    /**
     * Distinct values a path may have **per document** and still be worth an inverted index.
     *
     * Unbounded by default, and that default is not a placeholder: a path with a distinct value per
     * document is a perfectly good equality index — it is the best one — so excluding it *by
     * default* would be confusing a bitmap's storage shape with an index's usefulness. The estimator
     * cannot tell an identifier from a category, because they are the same shape.
     *
     * What separates them is **how many rows the caller expects back**, which the scorer never knows
     * and the caller always does. `1.0 / 50` says *a term should name a category*: at most one
     * distinct value per fifty documents, so a lookup returns a group rather than a row. Without it
     * the top recommendation over a corpus of prose is the prose — every line of it, perfectly
     * type-stable, enormously distinct, and not a question anybody has.
     *
     * Measured against [InferredSchema.documentCount] and against the *estimate* at the path, so a
     * repeated path may legitimately exceed `1.0`: `$.tags[*]` can hold more distinct values than
     * there are documents. That is the same asymmetry [InferredField.presence] has, kept for the
     * same reason — documents are the unit the answer is counted in.
     *
     * Like [minDistinct] it gates the inverted index alone and never [IndexKind.SHREDDED_COLUMN].
     */
    public val maxDistinctFraction: Double = Double.POSITIVE_INFINITY,
    /**
     * The share of the corpus's bytes a path must carry before a shredded column is suggested.
     *
     * Shredding lifts a field out of the documents so a scan of it never touches them. That pays in
     * proportion to how much of the document it avoids reading, which is what this measures.
     */
    public val minColumnByteShare: Double = 0.05,
    /** How many candidates to return. */
    public val limit: Int = 16,
) {
    init {
        require(minObservations >= 0) { "minObservations must not be negative, was $minObservations" }
        require(minPresence in 0.0..Double.MAX_VALUE) { "minPresence must not be negative" }
        require(minTypeStability in 0.0..1.0) { "minTypeStability must be in 0.0..1.0" }
        require(minDistinct >= 1) { "minDistinct must be at least 1, was $minDistinct" }
        // Positive rather than "in 0.0..1.0", at both ends. Zero admits nothing and is a mistake
        // rather than an intent, and a NaN fails here rather than silently admitting nothing later,
        // since every comparison against it is false. Above 1.0 is meaningful — see the property.
        require(maxDistinctFraction > 0.0) {
            "maxDistinctFraction must be positive, was $maxDistinctFraction"
        }
        require(minColumnByteShare in 0.0..1.0) { "minColumnByteShare must be in 0.0..1.0" }
        require(limit > 0) { "limit must be positive, was $limit" }
    }

    /**
     * The parameter list before [maxDistinctFraction] existed, kept so that already-compiled callers
     * keep linking.
     *
     * `IndexCandidateOptions` is stable core, and adding a parameter to a primary constructor
     * *replaces* its JVM signature rather than adding one — every caller who named all six arguments
     * would fail to link against a release that merely gained a seventh default. `HIDDEN` is what
     * `STABILITY.md`'s deprecation cycle reaches for at exactly this point: the symbol stays in the
     * bytecode and leaves the source language, so nothing new can reach it and nothing old breaks.
     * There is no replacement to name because the primary constructor with the default *is* the
     * replacement, and it means what this one meant: no ceiling.
     *
     * Verified in both directions, because a compatibility claim nobody has watched fail is worth
     * nothing: a `OldCaller.class` compiled against the released `0.3.0` jar and naming all six
     * arguments runs against this build and reads back `maxDistinctFraction=Infinity`, and deleting
     * this constructor makes the same class file fail with `NoSuchMethodError` on the same six-argument
     * descriptor. `checkKotlinAbi` sees it too — the dump keeps `(JDDJDI)V`, now marked synthetic.
     */
    @Deprecated("Binary compatibility only.", level = DeprecationLevel.HIDDEN)
    public constructor(
        minObservations: Long,
        minPresence: Double,
        minTypeStability: Double,
        minDistinct: Long,
        minColumnByteShare: Double,
        limit: Int,
    ) : this(
        minObservations = minObservations,
        minPresence = minPresence,
        minTypeStability = minTypeStability,
        minDistinct = minDistinct,
        minColumnByteShare = minColumnByteShare,
        limit = limit,
    )

    override fun toString(): String =
        "IndexCandidateOptions(minObservations=$minObservations, minPresence=$minPresence, " +
            "minTypeStability=$minTypeStability, minDistinct=$minDistinct, " +
            "maxDistinctFraction=$maxDistinctFraction, " +
            "minColumnByteShare=$minColumnByteShare, limit=$limit)"

    public companion object {
        /**
         * Present in half the documents, one type in nine out of ten, at least two values, and no
         * ceiling on cardinality.
         */
        public val DEFAULT: IndexCandidateOptions = IndexCandidateOptions()
    }
}

/**
 * Turns a model into recommendations.
 *
 * Kept out of [SchemaCatalog] so the ranking can be tested against a schema built by hand, with no
 * store, no segments and no files involved.
 */
internal fun rankIndexCandidates(
    schema: InferredSchema,
    options: IndexCandidateOptions,
): List<IndexCandidate> {
    // The root path's byte total is the size of the whole corpus, which is what a column's share is
    // measured against. Without it — a store of nothing but scalars at the root, or a truncated
    // model — no share can be computed and no column is suggested.
    val corpusBytes = schema[CatalogPath.ROOT]?.sketch?.totalBytes ?: 0

    // The ceiling in values rather than as a fraction, because that is what a path's estimate is
    // compared against. With no documents there is nothing to be a fraction *of*, so there is no
    // ceiling rather than a ceiling of zero — the same shape as the byte share below, and the case
    // that would otherwise multiply an infinity by nothing and compare everything against a NaN.
    val maxDistinct =
        if (schema.documentCount > 0) options.maxDistinctFraction * schema.documentCount
        else Double.POSITIVE_INFINITY

    val candidates = ArrayList<IndexCandidate>()
    for (field in schema.fields) {
        if (field.path.isRoot) continue
        if (field.observations < options.minObservations) continue
        if (field.presence < options.minPresence) continue
        if (field.typeStability < options.minTypeStability) continue
        val dominant = field.dominantType ?: continue
        // An index is over values. A path whose values are objects or arrays is a place to look
        // *inside*, and the paths inside it are the ones being considered here in their own right.
        if (!dominant.isScalar) continue

        val stability = field.typeStability
        val presence = minOf(field.presence, 1.0)

        // Cardinality gates the inverted index and nothing else: a column is not answering an
        // equality question, so a constant field can still be worth lifting out of the documents —
        // and neither is an identifier-shaped one any less worth lifting for having too many values
        // to be a useful *term*.
        if (field.distinctEstimate >= options.minDistinct && field.distinctEstimate <= maxDistinct) {
            val discrimination = 1.0 - 1.0 / field.distinctEstimate.toDouble()
            candidates += IndexCandidate(
                path = field.path,
                kind = IndexKind.INVERTED,
                score = presence * stability * discrimination,
                reason = buildString {
                    append("present in ").append("%.0f%%".format(field.presence * 100))
                    append(" of documents, ").append("%.0f%%".format(stability * 100))
                    append(' ').append(dominant.name.lowercase())
                    append(", ").append(if (field.distinctIsExact) "" else "~")
                    append(field.distinctEstimate).append(" distinct values")
                },
                field = field,
            )
        }

        val byteShare = if (corpusBytes > 0) field.sketch.totalBytes.toDouble() / corpusBytes else 0.0
        if (byteShare >= options.minColumnByteShare) {
            candidates += IndexCandidate(
                path = field.path,
                kind = IndexKind.SHREDDED_COLUMN,
                score = presence * stability * byteShare,
                reason = buildString {
                    append("carries ").append("%.0f%%".format(byteShare * 100))
                    append(" of the stored bytes as ").append("%.0f%%".format(stability * 100))
                    append(' ').append(dominant.name.lowercase())
                    append(", present in ").append("%.0f%%".format(field.presence * 100))
                    append(" of documents")
                },
                field = field,
            )
        }
    }

    // Score descending, then path, so the list is stable between runs on the same data.
    return candidates
        .sortedWith(compareByDescending<IndexCandidate> { it.score }.thenBy { it.path }.thenBy { it.kind })
        .take(options.limit)
}
