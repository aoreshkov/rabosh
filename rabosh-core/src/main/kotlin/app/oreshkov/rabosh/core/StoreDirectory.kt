package app.oreshkov.rabosh.core

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Locale

/** Name of the lock file that enforces one writer per directory. */
internal const val LOCK_FILE_NAME: String = "LOCK"

private const val LOG_SUFFIX = ".wal"
private const val SEGMENT_SUFFIX = ".seg"

/** Prefix of a manifest file; the rest is its number, padded like every other. */
internal const val MANIFEST_PREFIX: String = "MANIFEST-"

/** Names the manifest that is currently in force. Swapped, never edited. */
internal const val CURRENT_FILE_NAME: String = "CURRENT"

/**
 * File names are zero-padded to ten digits so that name order is number order.
 *
 * Ten digits covers every file number a real store will ever reach, and keeping the two orders
 * identical means a directory listing needs no numeric sort to be read by a human — or by the next
 * tool someone writes against these files.
 */
internal fun logFileName(number: Long): String =
    String.format(Locale.ROOT, "%010d%s", number, LOG_SUFFIX)

internal fun segmentFileName(number: Long): String =
    String.format(Locale.ROOT, "%010d%s", number, SEGMENT_SUFFIX)

internal fun manifestFileName(number: Long): String =
    String.format(Locale.ROOT, "%s%010d", MANIFEST_PREFIX, number)

/**
 * What kind of file a name in a store directory is, and which file number it carries.
 *
 * Every file the engine writes is numbered out of one counter recorded in the manifest, which is
 * what makes orphan detection possible: a file whose number the live version does not know was
 * written by a process that died before it could be recorded, and can be deleted. A name that fits
 * none of these patterns is left alone — the engine owns this directory, but deleting something it
 * does not recognise is a worse failure than leaving it.
 */
internal enum class StoreFileKind { LOG, SEGMENT, MANIFEST, CURRENT, LOCK, UNKNOWN }

internal class StoreFile(val name: String, val kind: StoreFileKind, val number: Long)

internal fun classifyFile(name: String): StoreFile {
    fun numbered(text: String, kind: StoreFileKind): StoreFile {
        val number = text.toLongOrNull()
        return if (number == null || number < 0) StoreFile(name, StoreFileKind.UNKNOWN, -1) else StoreFile(name, kind, number)
    }
    return when {
        name == CURRENT_FILE_NAME -> StoreFile(name, StoreFileKind.CURRENT, -1)
        name == LOCK_FILE_NAME -> StoreFile(name, StoreFileKind.LOCK, -1)
        name.endsWith(LOG_SUFFIX) -> numbered(name.removeSuffix(LOG_SUFFIX), StoreFileKind.LOG)
        name.endsWith(SEGMENT_SUFFIX) -> numbered(name.removeSuffix(SEGMENT_SUFFIX), StoreFileKind.SEGMENT)
        name.startsWith(MANIFEST_PREFIX) -> numbered(name.removePrefix(MANIFEST_PREFIX), StoreFileKind.MANIFEST)
        else -> StoreFile(name, StoreFileKind.UNKNOWN, -1)
    }
}

/** Every file in [directory], classified. */
internal fun listStoreFiles(directory: Path): List<StoreFile> {
    val files = ArrayList<StoreFile>()
    Files.newDirectoryStream(directory).use { entries ->
        for (entry in entries) files += classifyFile(entry.fileName.toString())
    }
    return files
}

/**
 * Log numbers found in [directory], ascending.
 *
 * A file that ends in `.wal` but whose name is not a log number is a fault, not something to skip:
 * this directory belongs to the engine, and a file that looks like a log but is not means something
 * else has been writing here.
 */
internal fun listLogNumbers(directory: Path): List<Long> {
    val numbers = ArrayList<Long>()
    Files.newDirectoryStream(directory).use { entries ->
        for (entry in entries) {
            val name = entry.fileName.toString()
            if (!name.endsWith(LOG_SUFFIX)) continue
            val number = name.removeSuffix(LOG_SUFFIX).toLongOrNull()
            if (number == null || number < 0) {
                throw CorruptLogException("file name is not a log number", name)
            }
            numbers += number
        }
    }
    numbers.sort()
    return numbers
}

/** Whether this JVM is on Windows, which cannot open a directory as a channel. */
private val WINDOWS: Boolean =
    System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

/**
 * Forces [directory]'s own entries to stable storage.
 *
 * Creating a file and forcing its contents does not make the *name* durable — that is a change to
 * the directory, and on a POSIX filesystem it needs its own `fsync`. Without this, a power loss can
 * leave a log whose bytes are on the platter and whose name is not, which recovery sees as a missing
 * log rather than as an empty one.
 *
 * Windows cannot open a directory as a channel at all, and does not need to: the metadata
 * transaction for a file's creation is flushed with the file. So the failure is expected there and
 * ignored — but **only** there. On any other platform a directory `fsync` that fails is a durability
 * failure, and swallowing it everywhere, which is the usual shape of this function, would quietly
 * remove the guarantee it exists to provide.
 */
internal fun syncDirectory(directory: Path) {
    try {
        FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
    } catch (failure: IOException) {
        if (!WINDOWS) throw failure
    }
}

/**
 * An exclusive OS-level lock on a store directory, held for the life of the store.
 *
 * The engine is single-writer by design. Without the lock that is a convention, and two processes
 * that both believe they own the directory will interleave records into their own logs and leave a
 * sequence space that cannot be recovered — a failure that shows up long after the mistake.
 *
 * **Byte zero is the lock; everything after it is a diagnostic**, and the split is what makes the
 * diagnostic readable at all. `tryLock()` with no arguments locks `[0, Long.MAX_VALUE)`, and a
 * Windows file lock is *mandatory* — so a second process could not read a record written inside it,
 * which is exactly when it wants to. Locking one byte and writing the record after it leaves the
 * record outside the locked region on every platform.
 *
 * That change is compatible in both directions: `[0, 1)` and `[0, MAX)` overlap at byte zero, so a
 * build using either still excludes a build using the other. An older release wrote no record and
 * reads none, and a newer one meeting an empty `LOCK` reports no holder — which is the honest answer
 * and not a guess.
 */
internal class DirectoryLock private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {

    override fun close() {
        try {
            if (lock.isValid) lock.release()
        } finally {
            // The lock file itself is never deleted. Deleting it would let a second process create
            // a fresh one and lock that instead, while a third still holds this one.
            channel.close()
        }
    }

    companion object {
        /** Byte 0 is the locked one and is never read; the record starts after it. */
        private const val RECORD_OFFSET = 1L

        /** Generous for `pid=<19 digits> startedAt=<instant>`, and small enough to read in one go. */
        private const val RECORD_MAX_BYTES = 128

        fun acquire(directory: Path): DirectoryLock {
            val path = directory.resolve(LOCK_FILE_NAME)
            val channel = FileChannel.open(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
            )
            val lock = try {
                channel.tryLock(0L, RECORD_OFFSET, false)
            } catch (alreadyHeldHere: OverlappingFileLockException) {
                // The JVM refuses to lock a file this process already locks, rather than blocking.
                // For a caller that opened the same directory twice, that is the same condition as
                // a second process holding it, and it deserves the same report — with the record
                // still read, because here the holder is this very process and saying so is useful.
                val holder = readHolder(channel)
                channel.close()
                throw StoreLockedException(
                    describe("$directory is already open in this process", holder),
                    directory,
                    holder,
                    alreadyHeldHere,
                )
            } catch (failure: Throwable) {
                channel.close()
                throw failure
            }
            if (lock == null) {
                // Read before closing: the channel is ours, the region is not locked, and the holder
                // is whoever wrote it. A record that will not parse — because it is being written
                // right now, or because an older release wrote none — reads as `null`.
                val holder = readHolder(channel)
                channel.close()
                throw StoreLockedException(
                    describe("$directory is locked by another process", holder),
                    directory,
                    holder,
                )
            }
            writeHolder(channel)
            return DirectoryLock(channel, lock)
        }

        private fun describe(message: String, holder: LockHolder?): String = when {
            holder == null -> message
            holder.isRunning -> "$message (pid ${holder.pid}, started ${holder.startedAt})"
            // Named, and named as doubtful. The lock is genuinely held — this call failed — so the
            // record is simply out of date, and reporting a pid that now belongs to somebody else as
            // though it were the holder is how a user ends up killing a stranger's process.
            else -> "$message (the lock file names pid ${holder.pid}, which is no longer running, " +
                "so the record is stale and the holder is someone else)"
        }

        /**
         * Records who is holding the lock, for the next process that fails to take it.
         *
         * Not forced, and not part of any ordering rule: this is a diagnostic, so losing it to a
         * power failure costs a better error message and never a document. It is written *after* the
         * lock is taken, so two processes can never be writing it at once.
         */
        private fun writeHolder(channel: FileChannel) {
            val current = ProcessHandle.current()
            val startedAt = current.info().startInstant().orElse(null) ?: return
            val record = "\npid=${current.pid()} startedAt=$startedAt\n".toByteArray(Charsets.US_ASCII)
            try {
                channel.write(ByteBuffer.wrap(record), 0L)
                channel.truncate(record.size.toLong())
            } catch (ignored: IOException) {
                // A directory that can be locked but not written is odd and is not this call's
                // problem to solve: the lock is held, the store is about to open, and the only thing
                // lost is the next process's error message.
            }
        }

        private fun readHolder(channel: FileChannel): LockHolder? = try {
            val buffer = ByteBuffer.allocate(RECORD_MAX_BYTES)
            val read = channel.read(buffer, RECORD_OFFSET)
            if (read <= 0) null else parseHolder(String(buffer.array(), 0, read, Charsets.US_ASCII))
        } catch (ignored: IOException) {
            null
        }

        /**
         * `pid=<digits> startedAt=<instant>`, or `null` for anything else.
         *
         * Deliberately total: an empty file, a half-written record, a record from a future release
         * with a field this one has never heard of — all of them are "no holder known", because the
         * alternative is an error message asserting something false about a process id.
         */
        private fun parseHolder(record: String): LockHolder? {
            val line = record.lineSequence().firstOrNull { it.startsWith("pid=") } ?: return null
            val fields = line.trim().split(' ')
            val pid = fields.firstOrNull { it.startsWith("pid=") }?.removePrefix("pid=")?.toLongOrNull() ?: return null
            val startedAt = fields.firstOrNull { it.startsWith("startedAt=") }?.removePrefix("startedAt=") ?: return null
            val instant = try {
                Instant.parse(startedAt)
            } catch (malformed: DateTimeParseException) {
                return null
            }
            return LockHolder(pid, instant)
        }
    }
}
