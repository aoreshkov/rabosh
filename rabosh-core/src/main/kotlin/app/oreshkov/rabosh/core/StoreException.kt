package app.oreshkov.rabosh.core

/**
 * Base class for every failure the storage core raises on its own account.
 *
 * Sealed, so a caller can distinguish the situations that want genuinely different handling: the
 * data is unreadable ([CorruptLogException], [CorruptSegmentException], [CorruptManifestException]),
 * the format is from the future ([UnsupportedFormatException]), another process owns the directory
 * ([StoreLockedException]), and the caller is using a store it already closed
 * ([StoreClosedException]).
 *
 * The three unreadable-data cases stay separate because the operator's next move differs. A damaged
 * log costs the commits in it; a damaged segment costs data that was durable long ago and points at
 * the medium; a damaged manifest costs the *catalogue* of files while every one of them is still
 * intact on disk.
 *
 * Ordinary IO failures are **not** wrapped. A missing directory or a full disk is an
 * `java.io.IOException`, and re-dressing it as a store exception would hide the operating system's
 * own diagnosis, which is the useful part.
 */
public sealed class StoreException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * The write-ahead log cannot be read as one this implementation wrote.
 *
 * Raised for a bad checksum with intact data behind it, a gap in the sequence numbers, a record
 * whose contents contradict its own lengths, and — under
 * [LogRecoveryMode.STRICT] — any unreadable trailing bytes at all.
 *
 * A *torn tail*, which is what an interrupted write leaves behind, is not corruption: those bytes
 * were never acknowledged to anyone. See [LogRecoveryMode].
 *
 * @property file name of the log file that failed to read.
 * @property offset byte offset within that file at which reading stopped, or `-1` if no single byte
 *   is to blame.
 */
public class CorruptLogException(
    message: String,
    public val file: String,
    public val offset: Long = -1,
    cause: Throwable? = null,
) : StoreException(
    if (offset >= 0) "$message (in $file at byte offset $offset)" else "$message (in $file)",
    cause,
)

/**
 * A sorted segment cannot be read as one this implementation wrote.
 *
 * Raised for a bad footer, a block whose checksum does not match, a handle that points outside the
 * file, and a document whose Variant bytes do not decode. Unlike a torn log tail there is no benign
 * reading of any of these: a segment is written once, forced, and only then recorded in the
 * manifest, so nothing in it was ever half-written by a live writer. Damage here happened after the
 * fact.
 *
 * @property file name of the segment file that failed to read.
 * @property offset byte offset within it, or `-1` if no single byte is to blame.
 */
public class CorruptSegmentException(
    message: String,
    public val file: String,
    public val offset: Long = -1,
    cause: Throwable? = null,
) : StoreException(
    if (offset >= 0) "$message (in $file at byte offset $offset)" else "$message (in $file)",
    cause,
)

/**
 * The manifest cannot be read as one this implementation wrote.
 *
 * The manifest is the list of which segments are live and at which level, so losing it is losing
 * the shape of the tree rather than the data in it. An incomplete *final* record is not this: the
 * manifest is appended and forced before `CURRENT` names it, so a trailing partial record is a
 * writer that died and is dropped. Anything earlier is reported.
 *
 * @property file name of the manifest file that failed to read.
 * @property offset byte offset within it, or `-1` if no single byte is to blame.
 */
public class CorruptManifestException(
    message: String,
    public val file: String,
    public val offset: Long = -1,
    cause: Throwable? = null,
) : StoreException(
    if (offset >= 0) "$message (in $file at byte offset $offset)" else "$message (in $file)",
    cause,
)

/**
 * The directory holds files written by a newer format version.
 *
 * Deliberately distinct from [CorruptLogException]: the bytes are fine, this build is simply too
 * old to interpret them. Upgrading the library is the fix, and reporting it as corruption would
 * send the reader looking for a disk fault instead.
 */
public class UnsupportedFormatException(message: String) : StoreException(message)

/**
 * Another process — or another [DocumentStore] in this process — already holds the directory.
 *
 * The engine is single-writer by design, and the lock file is what makes that a guarantee rather
 * than a convention. Two writers over one LSM directory do not produce a merge conflict; they
 * produce two interleaved logs and an unrecoverable sequence space.
 */
public class StoreLockedException(
    message: String,
    cause: Throwable? = null,
) : StoreException(message, cause)

/** The store has been closed. A programming error, not a data error. */
public class StoreClosedException(message: String) : StoreException(message)

/**
 * A write failed against the filesystem, and the store has stopped accepting new ones.
 *
 * The reason it stops rather than carries on: a failed append may already have written part of a
 * record. Appending the next commit behind those bytes would leave a log whose readable prefix ends
 * at the failure, so every commit after it — all of them acknowledged — would be sitting behind
 * bytes recovery cannot get past. Refusing further writes keeps the damage to the commit that
 * actually failed.
 *
 * Reads keep working: the memtable is untouched by an IO fault, and a caller that has just lost its
 * writer usually needs to read the data out. [DocumentStore.close] also still works, and reopening
 * recovers the prefix that was written before the fault.
 *
 * The [cause] is the original filesystem failure, and it is the thing to look at.
 */
public class StoreFailedException(
    message: String,
    cause: Throwable,
) : StoreException(message, cause)
