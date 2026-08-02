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
