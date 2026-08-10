package app.oreshkov.rabosh.catalog

import app.oreshkov.rabosh.variant.VariantKind

/**
 * One path worth promoting to a typed column in a Parquet **Variant shredding schema**, with the
 * evidence behind the recommendation.
 *
 * @property path the path, with array indices collapsed. See [CatalogPath].
 * @property parquetType the Parquet physical type the observations point at — `INT64`, `DOUBLE`,
 *   `DECIMAL(p, s)`, `BINARY (UTF8)`, `BOOLEAN`. Named in Parquet's vocabulary rather than the
 *   engine's, because the whole point of this object is to be read by somebody writing a schema.
 * @property presence how much of the corpus carries the path, in `0.0..1.0`. A shredded field that
 *   is usually absent costs a definition level per row and buys little.
 * @property typeStability how much of the path's observed values are of [parquetType]'s family.
 *   Below 1.0 the residual values still have to go somewhere — see [residual].
 * @property nullFraction how much of the *present* values are JSON null.
 * @property distinctEstimate distinct values, exact below the sketch's sparse limit and an estimate
 *   above it. See [InferredField.distinctIsExact].
 * @property byteShare how much of the stored document bytes this path accounts for. The number that
 *   decides whether shredding it is worth anything: a path holding 0.1% of the bytes cannot save a
 *   reader much however typed it is.
 * @property residual `true` when the path holds values outside [parquetType]'s family, so the
 *   shredded column needs its untyped `variant_value` fallback populated rather than being a pure
 *   typed column. This is the field a hand-written schema gets wrong.
 * @property reason the recommendation in words, for a human reading a report.
 */
public class ShreddingAdvice internal constructor(
    public val path: CatalogPath,
    public val parquetType: String,
    public val presence: Double,
    public val typeStability: Double,
    public val nullFraction: Double,
    public val distinctEstimate: Long,
    public val byteShare: Double,
    public val residual: Boolean,
    public val reason: String,
) {
    /**
     * The field as a line of a shredding schema, in the specification's own shape.
     *
     * Deliberately **not** a Parquet schema file: this project writes no Parquet and takes no
     * dependency on one, so emitting something that looked like a complete schema would be a claim
     * about a format it does not own. What this is, is the one line a reader needs to transcribe,
     * with the decision that is easy to get wrong — whether `variant_value` can be dropped —
     * already made.
     */
    public fun render(): String = buildString {
        append(path.toString())
        append(": { typed_value: ")
        append(parquetType)
        append(if (residual) ", variant_value: required }" else ", variant_value: omitted }")
        append("  — ")
        append(reason)
    }

    override fun toString(): String = render()
}

/**
 * Which paths are worth shredding into typed columns, best first.
 *
 * **The same statistics as [SchemaCatalog.indexCandidates], rendered for a different decision.**
 * `IndexCandidate` with `IndexKind.SHREDDED_COLUMN` already scores this question for the engine's own
 * columns; what was missing is a rendering aimed at a *Parquet shredding schema* rather than at an
 * index. The two differ in what they emphasise — an index cares about selectivity, a shredding
 * schema cares about type stability and byte share — and in one thing an index never has to say:
 * whether the typed column can stand alone or needs its `variant_value` fallback.
 *
 * **This emits advice and bytes, and never Parquet.** Writing the file is the caller's, with the
 * caller's own writer; the engine's claim of zero runtime dependencies is not spent on it. The other
 * half of the hand-off is `Variant.detached()`, which produces the self-contained
 * `(metadata, value)` pair a Variant column wants.
 *
 * The published shredding measurements put the read gain at around **8×** over unshredded Variant,
 * which is why this is worth generating even for a caller who will shred by hand.
 *
 * @param options the same thresholds the index recommendations use, so a path this declines and a
 *   path `indexCandidates` declines are declined for reasons a reader can compare.
 */
public fun InferredSchema.shreddingAdvice(
    options: IndexCandidateOptions = IndexCandidateOptions.DEFAULT,
): List<ShreddingAdvice> {
    // Once, not per field: the denominator is a property of the model rather than of any path, and
    // recomputing it inside the loop would make this quadratic in the number of paths for an answer
    // that cannot change.
    val totalBytes = fields.sumOf { it.averageBytes * it.observations }
    return fields
        .asSequence()
        .filter { it.observations >= options.minObservations }
        .filter { it.presence >= options.minPresence }
        // A container has no typed_value to promote: shredding describes *scalar* leaves, and
        // `$.items` is shredded by shredding the paths inside it. Left out rather than reported as
        // unsuitable, because "an array is not one column" is a fact about the format, not advice.
        .mapNotNull { field -> advise(field, totalBytes, options) }
        .sortedByDescending { it.byteShare }
        .toList()
}

private fun advise(field: InferredField, totalBytes: Double, options: IndexCandidateOptions): ShreddingAdvice? {
    val dominant = field.dominantType ?: return null
    val parquetType = parquetTypeOf(dominant) ?: return null
    if (field.typeStability < options.minTypeStability) return null

    // Byte share is the number that decides whether this is worth doing at all, and it is the same
    // threshold the engine applies to its own columns — a path carrying almost none of the bytes
    // cannot save a reader much however well typed it is.
    if (totalBytes <= 0.0) return null
    val byteShare = field.averageBytes * field.observations / totalBytes
    if (byteShare < options.minColumnByteShare) return null

    val residual = field.types.keys.any { it != dominant && it != VariantKind.NULL }
    val reason = buildString {
        append("carries ${percent(byteShare)} of the stored bytes as ${percent(field.typeStability)} $dominant")
        append(", present in ${percent(field.presence)} of documents")
        if (residual) {
            val others = field.types.keys
                .filter { it != dominant && it != VariantKind.NULL }
                .joinToString(", ") { it.name.lowercase() }
            append("; keep variant_value for the $others values")
        }
        if (field.nullFraction > 0.0) append("; ${percent(field.nullFraction)} null")
    }

    return ShreddingAdvice(
        path = field.path,
        parquetType = parquetType,
        presence = field.presence,
        typeStability = field.typeStability,
        nullFraction = field.nullFraction,
        distinctEstimate = field.distinctEstimate,
        byteShare = byteShare,
        residual = residual,
        reason = reason,
    )
}

/**
 * The engine's kinds mapped to Parquet's, or `null` for a shape that is not a shreddable leaf.
 *
 * `ARRAY` and `OBJECT` are `null` because a container has no single typed column; the temporal and
 * binary kinds are mapped because the Variant specification gives them Parquet types directly. An
 * exhaustive `when` with no `else`, so a kind added later has to be classified here rather than
 * silently becoming unshreddable.
 */
private fun parquetTypeOf(kind: VariantKind): String? = when (kind) {
    VariantKind.INTEGER -> "INT64"
    VariantKind.DECIMAL -> "DECIMAL"
    VariantKind.FLOAT -> "FLOAT"
    VariantKind.DOUBLE -> "DOUBLE"
    VariantKind.BOOLEAN -> "BOOLEAN"
    VariantKind.STRING -> "BINARY (UTF8)"
    VariantKind.BINARY -> "BINARY"
    VariantKind.DATE -> "INT32 (DATE)"
    VariantKind.TIME -> "INT64 (TIME(MICROS))"
    VariantKind.TIMESTAMP -> "INT64 (TIMESTAMP(MICROS))"
    VariantKind.UUID -> "FIXED_LEN_BYTE_ARRAY(16) (UUID)"
    // Not leaves, and a null-only path has no type to promote.
    VariantKind.ARRAY, VariantKind.OBJECT, VariantKind.NULL -> null
}

private fun percent(fraction: Double): String = "%.0f%%".format(fraction * 100)
