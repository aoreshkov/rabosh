package app.oreshkov.rabosh.index

/**
 * Base class for every failure the indexing layer raises on its own account.
 *
 * Deliberately **not** a subclass of `StoreException`, and for the same reason `CatalogException` is
 * not: that type is sealed and should stay sealed, because a caller catching it is asking about the
 * storage engine. An index sidecar that will not decode has not cost anybody a document — every
 * failure here is repaired by rebuilding the sidecar from the segment it describes, which is the
 * whole point of keeping indexes outside the data.
 *
 * Ordinary IO failures are not wrapped, matching the core and the catalog: a full disk is an
 * `java.io.IOException` and the operating system's own diagnosis is the useful part of it.
 */
public sealed class IndexException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * A serialized bitmap cannot be read as one this implementation wrote.
 *
 * The failure names the file and the byte offset within it, because "a bitmap did not decode" is not
 * an actionable report. The engine's rule is that unreadable data becomes a signalled failure naming
 * where it was found, and a bitmap block sits at an offset inside a larger file, so the offset is the
 * only thing that locates it.
 *
 * @property file name of the file the bitmap was read from.
 * @property offset byte offset within that file, or `-1` if no single byte is to blame.
 */
public class CorruptBitmapException(
    message: String,
    public val file: String,
    public val offset: Long = -1,
    cause: Throwable? = null,
) : IndexException(
    if (offset >= 0) "$message (in $file at byte offset $offset)" else "$message (in $file)",
    cause,
)

/**
 * A bitmap was written at a format version, or with a container kind, this build does not know.
 *
 * Distinct from [CorruptBitmapException] for the reason the core keeps `UnsupportedFormatException`
 * separate from `CorruptSegmentException`: the bytes are intact and reporting them as damage would
 * send somebody looking for a disk fault. The container kind byte exists precisely so a denser
 * encoding can be added later, and a build that predates one has to say so rather than guess.
 */
public class UnsupportedBitmapFormatException(message: String) : IndexException(message)

/**
 * An index sidecar or the index registry cannot be read as this implementation wrote it.
 *
 * Separate from [CorruptBitmapException] although a sidecar is mostly bitmaps, because the two are
 * repaired differently and the message has to say which. A damaged posting list is rebuilt by
 * rescanning one segment; a damaged registry has lost an index *definition*, which no rescan can
 * recover because nobody wrote it down anywhere else.
 *
 * The failure also covers a sidecar that decodes perfectly but describes something it cannot be
 * describing — a segment number that disagrees with its own filename, an index id that disagrees
 * with the registry, a maximum sequence that disagrees with the base sidecar. A file copied or
 * renamed into place must not be folded in as if it belonged there.
 *
 * @property file name of the file that would not read.
 * @property offset byte offset within it, or `-1` if no single byte is to blame.
 */
public class CorruptIndexException(
    message: String,
    public val file: String,
    public val offset: Long = -1,
    cause: Throwable? = null,
) : IndexException(
    if (offset >= 0) "$message (in $file at byte offset $offset)" else "$message (in $file)",
    cause,
)

/**
 * A sidecar or registry was written at a format version, section kind or posting encoding this build
 * does not know.
 *
 * Distinct from [CorruptIndexException] for the reason [UnsupportedBitmapFormatException] is distinct
 * from [CorruptBitmapException]: the bytes are intact, and calling them damaged would send somebody
 * looking for a disk fault. Every id byte in these formats exists so that a denser encoding or a new
 * kind of sidecar is a new number rather than a new version, and a build that predates one has to
 * say so rather than guess at it.
 */
public class UnsupportedIndexFormatException(message: String) : IndexException(message)

/**
 * An index catalog was asked something it cannot answer in the state it is in.
 *
 * Attaching is what loads the sidecars and covers whatever they do not, so until it has happened
 * there is no honest answer to give — and a reader that quietly reported on part of a store would be
 * worse than one that refuses. The same rule, and the same reasoning, as `CatalogStateException`.
 */
public class IndexStateException(message: String) : IndexException(message)
