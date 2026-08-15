package app.oreshkov.rabosh.catalog

/**
 * Default ceiling on distinct paths tracked per segment.
 *
 * One thousand and twenty-four, which is what ClickHouse's JSON type defaults `max_dynamic_paths`
 * to. The bound exists because machine-generated keys — an object keyed by user id, a log line with
 * a request id in the field name — otherwise turn the path space into a copy of the data.
 */
public const val DEFAULT_MAX_PATHS: Int = 1024

/** Default ceiling on how deep into a document the walk goes. */
public const val DEFAULT_MAX_DEPTH: Int = 32

/**
 * Default ceiling on children visited per container.
 *
 * **Sixty-five thousand five hundred and thirty-six, raised from 4096, and the reason is that this
 * bound does not behave like the others.** Every other budget in the engine either reports having
 * fired or is declined symmetrically by the reader: `maxPaths` overflow is counted in
 * [InferredSchema.truncatedPathEstimate], `IndexOptions.maxTermsPerSegment` drops the index for the
 * segment so it reads as *not covered* and is scanned, `maxTermBytes` is applied to the same bytes by
 * the planner so what the writer dropped is what the query declines, and a truncated bound widens.
 * This one does none of that. A container wider than the bound is walked to the bound, the segment
 * still reads as covered, and — because the recheck runs the same walk — the fallback scan truncates
 * identically, so both differential oracles agree with the shortfall. It is invisible to the suite by
 * construction.
 *
 * `IndexOptions.maxChildren`'s KDoc defends the bound as *"a far worse outcome than an index that
 * reports itself incomplete"*. That trade is the right one and it is not the trade actually on offer
 * here, because nothing reports. So the bound is set where truncating is genuinely the better answer
 * rather than where it is merely cheaper: past 65 536 children a container is generated data, and
 * below it a walk is `O(65 536)` against the segment write it rides on.
 *
 * **Raising it is a mitigation and not a fix.** The fix is a coverage signal, which would let the
 * bound come back down. Until then a corpus with containers wider than this must set the option —
 * the measured protobuf-JSON dump holds 12 040 elements under one path — and a store that does not
 * know its own widest array is trusting a number rather than checking one.
 */
public const val DEFAULT_MAX_CHILDREN: Int = 65_536

/** Default byte length at which a text bound is truncated. */
public const val DEFAULT_TEXT_BOUND_BYTES: Int = 64

/** What to do about a sidecar that will not decode. */
public enum class DamagedSketchPolicy {
    /**
     * Report it. The default, and the same rule the rest of the engine follows: unreadable data is a
     * signalled failure, never a value quietly guessed at.
     */
    REPORT,

    /**
     * Discard it and rescan the segment.
     *
     * Safe here in a way it would never be for a segment, because a sketch is derived: rescanning
     * reproduces exactly what the damaged file was supposed to hold. It is not the default only
     * because silently repairing damage is how a failing disk goes unnoticed.
     */
    REBUILD,
}

/**
 * Tuning for a [SchemaCatalog].
 *
 * A plain class with default arguments rather than a `data class`, for the reason
 * `StoreOptions` is: `copy` and `componentN` would become part of the published ABI of a type whose
 * whole purpose is to grow.
 *
 * The cardinality estimator's precision is deliberately **not** here. It is a property of the
 * sidecar format rather than a tuning knob — registers written at one precision cannot be merged
 * with registers written at another — so it lives in [SketchFormat] with the rest of the permanent
 * constants.
 */
public class CatalogOptions(
    /**
     * Distinct paths tracked per segment before the rest go to the overflow bucket.
     *
     * Reaching it is not an error and not silent: [InferredSchema.truncatedPathEstimate] says how
     * many paths were dropped and how many observations they carried.
     */
    public val maxPaths: Int = DEFAULT_MAX_PATHS,
    /**
     * How deep into a document the walk descends.
     *
     * A bound rather than trust, because a document's nesting is caller-controlled and a sketch pass
     * runs inside compaction. Values below the limit are still counted; only their children are not.
     */
    public val maxDepth: Int = DEFAULT_MAX_DEPTH,
    /** How many children of one object or array are visited. See [maxDepth] for why there is a bound. */
    public val maxChildren: Int = DEFAULT_MAX_CHILDREN,
    /**
     * Byte length at which a text bound is truncated.
     *
     * A path whose values are whole documents would otherwise put two of them in every sketch. The
     * truncation always **widens**: a minimum is cut to a prefix, which cannot be larger than the
     * value it came from, and a maximum is cut and then incremented, which cannot be smaller. So a
     * truncated bound stays a correct bound.
     *
     * **This dial does not decide what a query skips, and an earlier version of this comment said it
     * did.** A sketch bound is *descriptive*: it is rendered by [InferredSchema.render], readable
     * through [InferredField.bounds], and written to the `.cat` sidecar.
     * Nothing in `rabosh-query` reads it. Skipping is decided by a **shredded column's** bounds, which
     * are built from the same [ValueBoundsBuilder] — that is the sharing this module exists to enforce
     * — but truncated at `IndexOptions.columnTextBoundBytes`, a separate dial in `rabosh-index` that
     * also happens to default to 64. Widen *that* one to buy pruning; widen this one to see more of a
     * value when you look at the model.
     *
     * **A corpus of long shared-prefix strings is where either default stops earning its keep**, and
     * it is worth naming because it is a common shape rather than a pathological one. Protobuf-JSON
     * `@type` values begin `type.googleapis.com/`, which is 20 of the default 64 bytes:
     * `type.googleapis.com/com.example.game.player.v1.PlayerDTO` is 56 and still discriminates, one
     * package deeper does not, and at that point every bound is the same prefix. Here that costs
     * legibility; on a column it costs pruning, and `:rabosh-bench:runTextBoundCost` is the sweep that
     * prices it. Nothing about either is a correctness question — truncation widens — so both are
     * tuning.
     */
    public val textBoundBytes: Int = DEFAULT_TEXT_BOUND_BYTES,
    /** What to do about a sidecar that will not decode. See [DamagedSketchPolicy]. */
    public val damagedSketches: DamagedSketchPolicy = DamagedSketchPolicy.REPORT,
) {
    init {
        require(maxPaths > 0) { "maxPaths must be positive, was $maxPaths" }
        require(maxDepth > 0) { "maxDepth must be positive, was $maxDepth" }
        require(maxChildren > 0) { "maxChildren must be positive, was $maxChildren" }
        require(textBoundBytes in 1..4096) { "textBoundBytes must be in 1..4096, was $textBoundBytes" }
    }

    override fun toString(): String =
        "CatalogOptions(maxPaths=$maxPaths, maxDepth=$maxDepth, maxChildren=$maxChildren, " +
            "textBoundBytes=$textBoundBytes, damagedSketches=$damagedSketches)"

    public companion object {
        /** A 1024-path budget, a depth of 32, 64-byte text bounds, and damage reported. */
        public val DEFAULT: CatalogOptions = CatalogOptions()
    }
}
