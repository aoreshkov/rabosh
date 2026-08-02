package app.oreshkov.rabosh.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * What replaying one log file established.
 *
 * @property header the file header, or `null` when the header itself was never fully written.
 * @property nextSequence the sequence number the next commit after this log will use.
 * @property validLength bytes of the file that read back cleanly. Anything past this is a torn
 *   write, and the file is truncated to this length before it is appended to again.
 * @property fileLength the file's length on disk.
 * @property operationCount operations applied, for reporting.
 */
internal class LogReplay(
    val header: LogFormat.Header?,
    val nextSequence: Long,
    val validLength: Long,
    val fileLength: Long,
    val operationCount: Long,
) {
    /** Whether anything had to be discarded from the tail. */
    val truncated: Boolean get() = validLength < fileLength
}

/**
 * Replays one write-ahead log.
 *
 * The log is read **sequentially with a channel, not mapped**, which is the opposite of what
 * segments do — and deliberately. Mapping buys nothing here: every record is copied into the
 * memtable anyway, the file is read exactly once, and on Windows a mapped file cannot be truncated,
 * which is precisely the operation recovery has to perform on it afterwards.
 *
 * Reading stops at the first record that does not validate. Whether that is a torn tail or genuine
 * corruption is decided by [LogRecoveryMode] and by the two checks a checksum cannot make: whether
 * a valid record follows the bad one, and whether the sequence numbers are continuous.
 */
internal class LogReader private constructor(
    private val channel: FileChannel,
    private val file: String,
) : AutoCloseable {

    private val frameHeader = LogFormat.littleEndian(LogFormat.FRAME_HEADER_BYTES)
    private var payload = ByteArray(INITIAL_PAYLOAD_BYTES)

    override fun close() = channel.close()

    private fun replay(
        number: Long,
        expectedFirstSequence: Long?,
        isNewest: Boolean,
        mode: LogRecoveryMode,
        apply: (Long, Operation) -> Unit,
    ): LogReplay {
        val fileLength = channel.size()
        val header = readHeader(fileLength)
            ?: return tornHeader(number, expectedFirstSequence, isNewest, mode, fileLength)

        if (header.logNumber != number) {
            throw CorruptLogException(
                "log claims to be number ${header.logNumber}",
                file,
                offset = 0,
            )
        }
        // Continuity across files. The first log the store still holds may start anywhere — earlier
        // ones are dropped once their contents are in a segment — but every log after it must begin
        // exactly where the previous one ended, or records have gone missing between them.
        if (expectedFirstSequence != null && header.firstSequence != expectedFirstSequence) {
            throw CorruptLogException(
                "log starts at sequence ${header.firstSequence} but the previous log ended at " +
                    "${expectedFirstSequence - 1}",
                file,
                offset = 0,
            )
        }

        var position = LogFormat.HEADER_BYTES.toLong()
        var expected = header.firstSequence
        var operations = 0L

        while (true) {
            val record = readRecord(position, fileLength) ?: break
            if (record.checksumFailed) {
                // A checksum failure with a readable record behind it is not a torn tail: the writer
                // could not have got past this record without having written it. Something changed
                // these bytes after the fact, and later commits — commits that *were* acknowledged
                // — are behind it. That must never be silently discarded.
                if (frameLooksValid(position + LogFormat.FRAME_HEADER_BYTES + record.payloadLength, fileLength)) {
                    throw CorruptLogException(
                        "record failed its checksum but a readable record follows it",
                        file,
                        position,
                    )
                }
                break
            }

            val count = decodeAndApply(record.payloadLength.toInt(), position, expected, apply)
            operations += count
            expected += count
            position += LogFormat.FRAME_HEADER_BYTES + record.payloadLength
        }

        if (position < fileLength) {
            rejectTailIfRequired(isNewest, mode, position)
        }

        return LogReplay(header, expected, position, fileLength, operations)
    }

    /**
     * A header that is not fully there can only belong to a log that was being created when the
     * process died, and only the newest log can be that one.
     */
    private fun tornHeader(
        number: Long,
        expectedFirstSequence: Long?,
        isNewest: Boolean,
        mode: LogRecoveryMode,
        fileLength: Long,
    ): LogReplay {
        if (!isNewest) {
            throw CorruptLogException("log header is unreadable", file, offset = 0)
        }
        rejectTailIfRequired(isNewest, mode, 0)
        return LogReplay(
            header = null,
            nextSequence = expectedFirstSequence ?: LogFormat.FIRST_SEQUENCE,
            validLength = 0,
            fileLength = fileLength,
            operationCount = 0,
        )
    }

    private fun rejectTailIfRequired(isNewest: Boolean, mode: LogRecoveryMode, validLength: Long) {
        // A sealed log was forced before its successor was created, so it cannot have a torn tail;
        // if it has one, the file has been damaged rather than interrupted.
        if (!isNewest) {
            throw CorruptLogException("a sealed log cannot have an incomplete tail", file, validLength)
        }
        if (mode == LogRecoveryMode.STRICT) {
            throw CorruptLogException(
                "log has an incomplete tail, rejected by ${LogRecoveryMode.STRICT}",
                file,
                validLength,
            )
        }
    }

    private fun readHeader(fileLength: Long): LogFormat.Header? {
        if (fileLength < LogFormat.HEADER_BYTES) return null
        val bytes = ByteArray(LogFormat.HEADER_BYTES)
        if (!readFully(ByteBuffer.wrap(bytes), 0)) return null
        return LogFormat.decodeHeader(bytes, file)
    }

    /** A frame that was read; [checksumFailed] distinguishes "read it" from "trust it". */
    private class Frame(val payloadLength: Long, val checksumFailed: Boolean)

    /**
     * Reads the record at [position] into [payload], or returns `null` when there is no complete
     * record there — a header that does not fit, a length that cannot be right, or a payload that
     * runs past the end of the file.
     */
    private fun readRecord(position: Long, fileLength: Long): Frame? {
        val remaining = fileLength - position
        if (remaining < LogFormat.FRAME_HEADER_BYTES) return null

        frameHeader.clear()
        if (!readFully(frameHeader, position)) return null
        frameHeader.flip()
        val payloadLength = frameHeader.getInt().toLong() and 0xFFFF_FFFFL
        val storedChecksum = frameHeader.getInt()

        if (payloadLength < LogFormat.MIN_PAYLOAD_BYTES) return null
        if (payloadLength > LogFormat.MAX_RECORD_BYTES) return null
        if (payloadLength > remaining - LogFormat.FRAME_HEADER_BYTES) return null

        val length = payloadLength.toInt()
        if (payload.size < length) payload = ByteArray(length)
        if (!readFully(ByteBuffer.wrap(payload, 0, length), position + LogFormat.FRAME_HEADER_BYTES)) {
            return null
        }

        val checksum = LogFormat.checksum(
            frameHeader.duplicate().position(0).limit(Int.SIZE_BYTES),
            ByteBuffer.wrap(payload, 0, length),
        )
        return Frame(payloadLength, checksumFailed = checksum != storedChecksum)
    }

    /**
     * Whether a complete, checksum-valid record starts at [position].
     *
     * Used only to tell a torn tail from corruption. Random bytes do not pass CRC32C, so a positive
     * answer here is strong evidence that the log genuinely continues past a record that failed.
     */
    private fun frameLooksValid(position: Long, fileLength: Long): Boolean {
        val probe = LogReader(channel, file)
        val frame = probe.readRecord(position, fileLength)
        return frame != null && !frame.checksumFailed
    }

    /**
     * Decodes the payload in [payload] and applies it.
     *
     * Every fault in here is reported rather than tolerated. The checksum has already established
     * that these are the bytes that were written, so a payload that contradicts itself is not a torn
     * write — it is a file this build cannot interpret, and the project's rule is that such data is
     * signalled, never defaulted.
     */
    private fun decodeAndApply(
        length: Int,
        recordAt: Long,
        expectedSequence: Long,
        apply: (Long, Operation) -> Unit,
    ): Long {
        val buffer = ByteBuffer.wrap(payload, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        val firstSequence = buffer.getLong()
        val operationCount = buffer.getInt()

        if (firstSequence != expectedSequence) {
            throw CorruptLogException(
                "record starts at sequence $firstSequence, expected $expectedSequence",
                file,
                recordAt,
            )
        }
        if (operationCount <= 0) {
            throw CorruptLogException("record declares $operationCount operations", file, recordAt)
        }

        for (index in 0 until operationCount) {
            val kindId = buffer.get().toInt() and 0xFF
            val kind = OperationKind.ofId(kindId)
                ?: throw CorruptLogException("unknown operation id $kindId", file, recordAt)
            val key = Key.wrap(readBytes(buffer, recordAt, "key"))
            val operation = when (kind) {
                OperationKind.PUT -> Operation(
                    kind,
                    key,
                    readBytes(buffer, recordAt, "document metadata"),
                    readBytes(buffer, recordAt, "document value"),
                )

                OperationKind.DELETE -> Operation.delete(key)
            }
            apply(firstSequence + index, operation)
        }

        if (buffer.hasRemaining()) {
            throw CorruptLogException(
                "record has ${buffer.remaining()} unread byte(s) after its operations",
                file,
                recordAt,
            )
        }
        return operationCount.toLong()
    }

    private fun readBytes(buffer: ByteBuffer, recordAt: Long, what: String): ByteArray {
        if (buffer.remaining() < Int.SIZE_BYTES) {
            throw CorruptLogException("record ends before the $what length", file, recordAt)
        }
        val length = buffer.getInt()
        if (length < 0 || length > buffer.remaining()) {
            throw CorruptLogException(
                "$what claims $length byte(s), ${buffer.remaining()} remain in the record",
                file,
                recordAt,
            )
        }
        val bytes = ByteArray(length)
        buffer.get(bytes)
        return bytes
    }

    private fun readFully(target: ByteBuffer, position: Long): Boolean {
        var at = position
        while (target.hasRemaining()) {
            val read = channel.read(target, at)
            if (read < 0) return false
            at += read
        }
        return true
    }

    companion object {
        private const val INITIAL_PAYLOAD_BYTES = 64 * 1024

        /**
         * Replays [path], calling [apply] once per operation with the sequence number it was
         * committed under.
         *
         * @param expectedFirstSequence the sequence this log must begin at, or `null` for the oldest
         *   log the store still holds, whose header is taken at its word.
         * @param isNewest whether this is the highest-numbered log, and therefore the only one
         *   allowed to have an interrupted tail.
         */
        fun replay(
            path: Path,
            number: Long,
            expectedFirstSequence: Long?,
            isNewest: Boolean,
            mode: LogRecoveryMode,
            apply: (Long, Operation) -> Unit,
        ): LogReplay = FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            LogReader(channel, path.fileName.toString())
                .replay(number, expectedFirstSequence, isNewest, mode, apply)
        }
    }
}
