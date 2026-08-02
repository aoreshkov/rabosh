package app.oreshkov.rabosh.core

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
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
        fun acquire(directory: Path): DirectoryLock {
            val path = directory.resolve(LOCK_FILE_NAME)
            val channel = FileChannel.open(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            )
            val lock = try {
                channel.tryLock()
            } catch (alreadyHeldHere: OverlappingFileLockException) {
                // The JVM refuses to lock a file this process already locks, rather than blocking.
                // For a caller that opened the same directory twice, that is the same condition as
                // a second process holding it, and it deserves the same report.
                channel.close()
                throw StoreLockedException("$directory is already open in this process", alreadyHeldHere)
            } catch (failure: Throwable) {
                channel.close()
                throw failure
            }
            if (lock == null) {
                channel.close()
                throw StoreLockedException("$directory is locked by another process")
            }
            return DirectoryLock(channel, lock)
        }
    }
}
