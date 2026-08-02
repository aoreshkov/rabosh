package app.oreshkov.rabosh.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32C

/**
 * The record framing shared by the write-ahead log and the manifest.
 *
 * ```
 * record := payloadLength:u32 crc32c:u32 payload
 * ```
 *
 * Both files are append-only sequences of self-describing records that a dying process may leave
 * half-written, so both want the same three properties and it would be a mistake to derive them
 * twice:
 *
 * - **The checksum covers the length field.** A checksum over the payload alone leaves unprotected
 *   the one field that decides how much to read, and a corrupt length is exactly the fault that
 *   becomes a wild read instead of a report.
 * - **CRC32C, not CRC32.** A hardware instruction on every CPU this engine targets, intrinsified by
 *   the JDK, and the same choice as LevelDB, RocksDB, Parquet and Iceberg — which matters the first
 *   time someone verifies a file with a tool that is not this one.
 * - **Little-endian**, matching the Variant encoding, so the whole engine has one byte order.
 *
 * What is deliberately *not* here is the policy for a record that fails to validate. The log's four
 * checks — a checksum failure with a readable record behind it, a sealed log with an incomplete
 * tail, an unreadable header in any log but the newest, a gap in the sequence numbers — are about
 * acknowledged commits and belong to the log. Generalising them into something that fits both files
 * would blunt each of them.
 */
internal object Frames {
    /** `payloadLength:u32 crc32c:u32`. */
    const val HEADER_BYTES: Int = 8

    fun littleEndian(capacity: Int): ByteBuffer =
        ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN)

    /**
     * CRC32C of `[offset, offset + length)`.
     *
     * Returned as `Int` because that is what is stored: the value is 32 unsigned bits, and keeping
     * it in a `Long` would only invite a comparison against a sign-extended one.
     */
    fun crc32c(bytes: ByteArray, offset: Int, length: Int): Int {
        val crc = CRC32C()
        crc.update(bytes, offset, length)
        return crc.value.toInt()
    }

    /** CRC32C of the remaining bytes of each buffer in turn, which need not be contiguous. */
    fun crc32c(vararg regions: ByteBuffer): Int {
        val crc = CRC32C()
        for (region in regions) crc.update(region)
        return crc.value.toInt()
    }
}
