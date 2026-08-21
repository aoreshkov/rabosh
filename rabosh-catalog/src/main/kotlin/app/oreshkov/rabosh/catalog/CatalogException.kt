package app.oreshkov.rabosh.catalog

/**
 * Base class for every failure the catalog raises on its own account.
 *
 * Deliberately **not** a subclass of `StoreException`: that type is sealed, and it should stay
 * sealed — a caller catching it is asking about the storage engine, and a sketch that will not
 * decode has not cost them a document. The distinction is the point. Sketches are derived data:
 * every one of these failures is recoverable by rescanning the segment it came from, which is what
 * [SchemaCatalog.rebuild] does.
 *
 * Ordinary IO failures are not wrapped, for the same reason the core does not wrap them: a full disk
 * is an `java.io.IOException` and the operating system's own diagnosis is the useful part.
 */
public sealed class CatalogException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * A sketch sidecar cannot be read as one this implementation wrote.
 *
 * A `.cat` file is written whole to a temporary name, forced, and moved into place atomically, so
 * there is no torn-tail reading of one: a file that is there is a file that was complete. Damage
 * here happened after the fact, and — unlike a damaged segment — it costs only the time to rescan.
 *
 * @property file name of the sidecar that failed to read.
 * @property offset byte offset within it, or `-1` if no single byte is to blame.
 */
public class CorruptSketchException(
    message: String,
    public val file: String,
    public val offset: Long = -1,
    cause: Throwable? = null,
) : CatalogException(
    if (offset >= 0) "$message (in $file at byte offset $offset)" else "$message (in $file)",
    cause,
)

/**
 * A sidecar was written by a newer build, or at a cardinality-estimator precision this one does not
 * use.
 *
 * Distinct from [CorruptSketchException] for the reason the core keeps `UnsupportedFormatException`
 * distinct: the bytes are fine and reporting them as damage would send somebody looking for a disk
 * fault. The fix is either a newer build or, because this is derived data, a rebuild.
 */
public class UnsupportedSketchFormatException(message: String) : CatalogException(message)

/** The catalog has been closed, or used before it was attached to a store. A programming error. */
public class CatalogStateException(message: String) : CatalogException(message)

/**
 * A walk budget fired while modelling a segment, so that segment's model is built from part of a
 * container rather than all of it.
 *
 * **Never thrown, and not a failure.** It is recorded in [SchemaCatalog.problems], the same channel a
 * sidecar that would not decode reaches under `DamagedSketchPolicy.REBUILD`, because truncating is
 * what [CatalogOptions.maxChildren] and [CatalogOptions.maxDepth] are *for* — a document's shape is
 * caller-controlled and a sketch pass rides inside compaction. What is not allowed is doing it
 * quietly: `maxPaths` has said what it dropped since the format was written, and this is the other
 * two budgets brought up to that rule.
 *
 * **What it means for the model.** Counts under [example] and below it are low, so a field's
 * occurrence rate is understated and `IndexCandidate` may rank it lower than a complete walk would.
 * It does not mean an index over that path would be wrong: an index build that hits the same bound
 * leaves its segment **not covered**, so a query scans it rather than answering from a prefix. A
 * recommendation moves; an answer does not.
 *
 * **This is a fact about a run, not about a file.** Sketch sidecars carry no counter for it, so a
 * model assembled from sidecars an earlier process wrote reports nothing here. Absence means *not
 * observed in this process* and never *did not happen* — the same reading `SECTION_FIDELITY`'s
 * absence gets in a `.col`, and the reason the counter was not simply defaulted to zero somewhere it
 * would be read as a claim.
 *
 * @property segmentNumber the segment whose model is short.
 * @property containers how many containers were visited in part.
 * @property skippedChildren how many of their children were never visited, summed.
 * @property example the path of the first such container, to point a caller at their own data.
 */
public class TruncatedWalkException(
    public val segmentNumber: Long,
    public val containers: Long,
    public val skippedChildren: Long,
    public val example: CatalogPath,
) : CatalogException(
    "the model of segment $segmentNumber is built from part of $containers container(s): " +
        "$skippedChildren child value(s) were not visited, first at $example",
)
