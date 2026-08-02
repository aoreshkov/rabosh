package app.oreshkov.rabosh.core

import java.lang.foreign.Arena
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Appends commits to one write-ahead log file.
 *
 * `java.nio.channels.FileChannel` rather than `kotlinx-io`, for one reason that decides it:
 * kotlinx-io 0.9.1 has no `fsync`. `RawSink.flush` pushes bytes at the operating system, which is
 * [Durability.BUFFERED]; there is no way to ask it for [Durability.SYNC]. A durability guarantee
 * cannot be built on an API that cannot express it.
 *
 * Writes are positional, so the channel carries no shared cursor and [position] is the single
 * authority on how long the file is.
 *
 * Not thread-safe: serialised by the store's write lock, because the engine has one writer.
 */
internal class LogWriter private constructor(
    private val channel: FileChannel,
    val path: Path,
    val number: Long,
    val firstSequence: Long,
    private var position: Long,
) : AutoCloseable {

    /**
     * Off-heap staging for the record being written.
     *
     * A direct buffer, because a heap buffer would be copied into one by the channel on every
     * write. Allocated from an [Arena] rather than with `ByteBuffer.allocateDirect` so it is
     * released when this writer closes instead of whenever the collector next notices it — a test
     * suite that opens a few thousand stores would otherwise sit on every buffer it ever staged.
     *
     * Shared rather than confined: writes are serialised by a lock, but not necessarily onto one
     * thread, and a confined arena would reject the second thread outright.
     */
    private val arena: Arena = Arena.ofShared()
    private var staging: ByteBuffer = allocateStaging(INITIAL_STAGING_BYTES)

    /** Bytes in the file, which is also the offset the next record will start at. */
    val bytesWritten: Long get() = position

    /**
     * Appends [operations] as one record, numbering them from [firstSequence].
     *
     * Returns without forcing: whether the record has to be on the platter before the caller
     * continues is the store's decision, not the log's. See [sync].
     */
    fun append(operations: List<Operation>, firstSequence: Long) {
        require(operations.isNotEmpty()) { "an empty commit is never written" }

        val payloadBytes = LogFormat.payloadSize(operations)
        val recordBytes = payloadBytes + LogFormat.FRAME_HEADER_BYTES
        if (recordBytes > LogFormat.MAX_RECORD_BYTES) {
            throw IllegalArgumentException(
                "commit of $recordBytes bytes exceeds the ${LogFormat.MAX_RECORD_BYTES}-byte record limit",
            )
        }
        val size = recordBytes.toInt()
        if (size > staging.capacity()) staging = allocateStaging(size)

        val buffer = staging
        buffer.clear().limit(size)
        buffer.putInt(payloadBytes.toInt())
        // Placeholder: the checksum covers the length field and the payload, so it can only be
        // computed once both are in the buffer.
        buffer.putInt(0)
        LogFormat.encodePayload(buffer, firstSequence, operations)

        val checksum = LogFormat.checksum(
            buffer.duplicate().position(0).limit(Int.SIZE_BYTES),
            buffer.duplicate().position(LogFormat.FRAME_HEADER_BYTES).limit(size),
        )
        buffer.putInt(Int.SIZE_BYTES, checksum)

        buffer.position(0).limit(size)
        writeFully(buffer)
    }

    /** Forces this log's bytes to stable storage. */
    fun sync() {
        // `false`: the file's data and its length, but not its access time. The length is metadata
        // the platform must flush anyway for the data to be findable, so this is the whole
        // guarantee at none of the cost of a full metadata flush.
        channel.force(false)
    }

    override fun close() {
        try {
            channel.close()
        } finally {
            arena.close()
        }
    }

    private fun allocateStaging(capacity: Int): ByteBuffer =
        arena.allocate(capacity.toLong()).asByteBuffer().order(ByteOrder.LITTLE_ENDIAN)

    private fun writeFully(buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            // A channel may write fewer bytes than asked for; treating a short write as complete is
            // how a log ends up with a hole in it.
            position += channel.write(buffer, position)
        }
    }

    companion object {
        private const val INITIAL_STAGING_BYTES = 64 * 1024

        /**
         * Creates log [number], whose first record will use sequence [firstSequence].
         *
         * The header is forced and the directory entry is forced before this returns, so a log the
         * store has switched to is always a log recovery can read. `CREATE_NEW` rather than
         * `CREATE`: reusing a log number would overwrite data, and if the number is already taken
         * the bookkeeping is wrong in a way that must not be papered over.
         */
        fun create(directory: Path, number: Long, firstSequence: Long): LogWriter {
            val path = directory.resolve(logFileName(number))
            val channel = FileChannel.open(
                path,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
            val writer = LogWriter(channel, path, number, firstSequence, 0)
            try {
                writer.writeFully(LogFormat.encodeHeader(number, firstSequence))
                channel.force(true)
                syncDirectory(directory)
            } catch (failure: Throwable) {
                writer.closeQuietly(failure)
                throw failure
            }
            return writer
        }

        /**
         * Reopens an existing log for appending, discarding everything past [validLength].
         *
         * Truncating is not tidying. Whatever follows the last good record was never acknowledged,
         * and leaving it in place would put every future record behind bytes that recovery already
         * decided it cannot read — so the log would be permanently stuck at this offset.
         */
        fun openForAppend(path: Path, number: Long, firstSequence: Long, validLength: Long): LogWriter {
            val channel = FileChannel.open(path, StandardOpenOption.WRITE)
            val writer = LogWriter(channel, path, number, firstSequence, validLength)
            try {
                if (channel.size() > validLength) {
                    channel.truncate(validLength)
                    channel.force(true)
                }
            } catch (failure: Throwable) {
                writer.closeQuietly(failure)
                throw failure
            }
            return writer
        }
    }
}

/** Closes on a failed construction path, keeping the original failure as the one reported. */
private fun LogWriter.closeQuietly(primary: Throwable) {
    try {
        close()
    } catch (secondary: Throwable) {
        primary.addSuppressed(secondary)
    }
}
