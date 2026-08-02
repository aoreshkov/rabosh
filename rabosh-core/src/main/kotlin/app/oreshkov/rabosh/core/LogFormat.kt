package app.oreshkov.rabosh.core

import java.nio.ByteBuffer

/**
 * The on-disk layout of a write-ahead log.
 *
 * ```
 * file    := header record*
 * header  := magic[8] version:u32 firstSequence:u64 logNumber:u64 crc32c:u32     (32 bytes)
 * record  := payloadLength:u32 crc32c:u32 payload
 * payload := firstSequence:u64 operationCount:u32 operation*
 * put     := 1:u8 keyLength:u32 key metadataLength:u32 metadata valueLength:u32 value
 * delete  := 2:u8 keyLength:u32 key
 * ```
 *
 * Little-endian throughout, matching the Variant encoding so the whole engine has one byte order.
 * **These constants are permanent**: they are written to files that later versions must still read.
 * Add, never renumber.
 *
 * The record frame — its length, its CRC32C, and the fact that the checksum covers the length
 * field — is [Frames], shared with the manifest; the reasoning for each is there.
 *
 * The decision that belongs to the log alone:
 *
 * **Every record carries its own first sequence number.** It costs eight bytes and it buys the one
 * check a checksum cannot make: continuity. A checksum proves a record is intact; only the sequence
 * numbers prove that no record in between has gone missing, which is exactly what a hole in a
 * partially written file looks like.
 */
internal object LogFormat {
    /** `JKDB-WAL` in ASCII. Legible in a hex dump, and distinct from the segment magic to come. */
    val MAGIC: ByteArray = "JKDB-WAL".encodeToByteArray()

    /** The only log format version this build writes, and the only one it reads. */
    const val VERSION: Int = 1

    const val HEADER_BYTES: Int = 32

    /** The record frame is [Frames]'; the log and the manifest share it. */
    const val FRAME_HEADER_BYTES: Int = Frames.HEADER_BYTES

    /** `firstSequence:u64` + `operationCount:u32`; the smallest payload that can be well formed. */
    const val MIN_PAYLOAD_BYTES: Int = 12

    /**
     * Ceiling on one record, so a corrupt length is rejected rather than allocated.
     *
     * A record is staged in a single buffer and addressed with `Int` offsets, so the real limit is
     * the addressable one; 1 GiB leaves the arithmetic obviously safe and is far beyond any batch
     * that makes sense to commit atomically.
     */
    const val MAX_RECORD_BYTES: Int = 1 shl 30

    private const val VERSION_AT = 8
    private const val FIRST_SEQUENCE_AT = 12
    private const val LOG_NUMBER_AT = 20
    private const val HEADER_CRC_AT = 28

    /** The first sequence number ever handed out. Zero means "no sequence", never a real commit. */
    const val FIRST_SEQUENCE: Long = 1

    fun littleEndian(capacity: Int): ByteBuffer = Frames.littleEndian(capacity)

    /** Encodes the 32-byte file header. */
    fun encodeHeader(logNumber: Long, firstSequence: Long): ByteBuffer {
        val buffer = littleEndian(HEADER_BYTES)
        buffer.put(MAGIC)
        buffer.putInt(VERSION)
        buffer.putLong(firstSequence)
        buffer.putLong(logNumber)
        buffer.putInt(checksum(buffer.array(), 0, HEADER_CRC_AT))
        return buffer.flip()
    }

    /** A decoded file header. */
    class Header(val logNumber: Long, val firstSequence: Long)

    /**
     * Decodes a file header, or returns `null` when [bytes] is not one.
     *
     * `null` rather than an exception because the caller has to distinguish two cases that are not
     * both faults: the newest log may legitimately have a half-written header, because it may have
     * been created by a process that died before it wrote anything. An unreadable header in any
     * *other* log is corruption, and that judgement belongs to the caller, which knows the file's
     * position in the sequence.
     *
     * @throws UnsupportedFormatException when the magic is right but the version is from the future.
     *   That is not an unreadable file; it is a readable file this build is too old for, and it must
     *   never be mistaken for a torn header and overwritten.
     */
    fun decodeHeader(bytes: ByteArray, file: String): Header? {
        if (bytes.size < HEADER_BYTES) return null
        for (index in MAGIC.indices) {
            if (bytes[index] != MAGIC[index]) return null
        }
        if (checksum(bytes, 0, HEADER_CRC_AT) != readInt(bytes, HEADER_CRC_AT)) return null

        val version = readInt(bytes, VERSION_AT)
        if (version != VERSION) {
            throw UnsupportedFormatException(
                "$file was written with log format version $version; this build reads version $VERSION",
            )
        }
        return Header(
            logNumber = readLong(bytes, LOG_NUMBER_AT),
            firstSequence = readLong(bytes, FIRST_SEQUENCE_AT),
        )
    }

    /** Encoded size of the payload for [operations], as a `Long` so the check below cannot wrap. */
    fun payloadSize(operations: List<Operation>): Long {
        var total = MIN_PAYLOAD_BYTES.toLong()
        for (operation in operations) {
            total += 1L + Int.SIZE_BYTES + operation.key.size
            if (operation.kind == OperationKind.PUT) {
                total += 2L * Int.SIZE_BYTES + operation.metadata.size + operation.value.size
            }
        }
        return total
    }

    /**
     * Writes the payload for [operations] at the buffer's current position.
     *
     * The buffer must already hold room for [payloadSize] bytes; the caller sized it.
     */
    fun encodePayload(buffer: ByteBuffer, firstSequence: Long, operations: List<Operation>) {
        buffer.putLong(firstSequence)
        buffer.putInt(operations.size)
        for (operation in operations) {
            buffer.put(operation.kind.id.toByte())
            buffer.putInt(operation.key.size)
            buffer.put(operation.key.raw)
            if (operation.kind == OperationKind.PUT) {
                buffer.putInt(operation.metadata.size)
                buffer.put(operation.metadata)
                buffer.putInt(operation.value.size)
                buffer.put(operation.value)
            }
        }
    }

    /** See [Frames.crc32c]. */
    fun checksum(bytes: ByteArray, offset: Int, length: Int): Int = Frames.crc32c(bytes, offset, length)

    /** See [Frames.crc32c]. */
    fun checksum(vararg regions: ByteBuffer): Int = Frames.crc32c(*regions)

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun readLong(bytes: ByteArray, offset: Int): Long =
        (readInt(bytes, offset).toLong() and 0xFFFF_FFFFL) or
            (readInt(bytes, offset + 4).toLong() shl 32)
}
