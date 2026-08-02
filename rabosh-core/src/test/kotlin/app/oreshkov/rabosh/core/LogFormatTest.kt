package app.oreshkov.rabosh.core

import app.oreshkov.rabosh.variant.Variant
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.zip.CRC32C
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Byte-level tests for the log format.
 *
 * Everything else in the module is self-consistent — write a record, read it back, agree — and a
 * layout mistake survives that happily. These tests assert the bytes themselves, so a change to the
 * format has to be deliberate: the constants here are written to files that later versions must
 * still read.
 */
class LogFormatTest {

    @TempDir
    lateinit var root: Path

    @Test
    fun `CRC32C matches the standard check value`() {
        // The published check value for CRC-32C (Castagnoli) over the ASCII digits "123456789".
        // Anchoring it here means a JDK or platform that computed a different CRC32C — or a
        // mistaken switch to CRC32 — fails one obvious test rather than a hundred obscure ones.
        val crc = CRC32C()
        crc.update("123456789".encodeToByteArray())
        assertEquals(0xE3069283L, crc.value)
    }

    @Test
    fun `file header has the documented layout`() {
        val header = LogFormat.encodeHeader(logNumber = 7, firstSequence = 42)
        val bytes = ByteArray(LogFormat.HEADER_BYTES).also { header.duplicate().get(it) }

        assertEquals(LogFormat.HEADER_BYTES, bytes.size)
        assertEquals("JKDB-WAL", bytes.decodeToString(0, 8))
        assertEquals(1, readInt(bytes, 8), "format version")
        assertEquals(42L, readLong(bytes, 12), "first sequence")
        assertEquals(7L, readLong(bytes, 20), "log number")

        val checksum = CRC32C().apply { update(bytes, 0, 28) }.value.toInt()
        assertEquals(checksum, readInt(bytes, 28), "header checksum covers the first 28 bytes")

        val decoded = assertNotNull(LogFormat.decodeHeader(bytes, "test.wal"))
        assertEquals(7L, decoded.logNumber)
        assertEquals(42L, decoded.firstSequence)
    }

    @Test
    fun `every single-bit change to the header is detected`() {
        val bytes = ByteArray(LogFormat.HEADER_BYTES).also {
            LogFormat.encodeHeader(logNumber = 1, firstSequence = 1).get(it)
        }
        for (index in 0 until LogFormat.HEADER_BYTES) {
            for (bit in 0 until 8) {
                val damaged = bytes.copyOf()
                damaged[index] = (damaged[index].toInt() xor (1 shl bit)).toByte()
                assertNull(
                    LogFormat.decodeHeader(damaged, "test.wal"),
                    "bit $bit of byte $index went undetected",
                )
            }
        }
    }

    @Test
    fun `a header from a newer format version is rejected as unsupported, not as corrupt`() {
        // A file this build is too old to read is not a damaged file, and the difference matters:
        // one is fixed by upgrading, the other by restoring a backup. The distinction also stops
        // recovery treating a future log as a torn header and overwriting it.
        val bytes = ByteArray(LogFormat.HEADER_BYTES).also {
            LogFormat.encodeHeader(logNumber = 1, firstSequence = 1).get(it)
        }
        writeInt(bytes, 8, 2)
        writeInt(bytes, 28, CRC32C().apply { update(bytes, 0, 28) }.value.toInt())

        val failure = assertFailsWith<UnsupportedFormatException> {
            LogFormat.decodeHeader(bytes, "future.wal")
        }
        assertTrue(failure.message!!.contains("version 2"))
    }

    @Test
    fun `a put record has the documented layout`() {
        val key = Key.of("a")
        val document = Variant.fromJson("""{"x":1}""")
        val batch = WriteBatch().put(key, document)
        val metadata = document.metadata.toByteArray()
        val value = document.toByteArray()

        val file = writeRecord(batch, firstSequence = 1)
        val bytes = readAllBytes(file)

        var at = LogFormat.HEADER_BYTES
        val payloadLength = readInt(bytes, at)
        assertEquals(bytes.size - LogFormat.HEADER_BYTES - LogFormat.FRAME_HEADER_BYTES, payloadLength)
        assertEquals(
            payloadLength.toLong(),
            LogFormat.payloadSize(batch.operations()),
            "payloadSize must predict exactly what the encoder writes",
        )

        val storedChecksum = readInt(bytes, at + 4)
        val expectedChecksum = CRC32C().apply {
            update(bytes, at, 4)
            update(bytes, at + LogFormat.FRAME_HEADER_BYTES, payloadLength)
        }.value.toInt()
        assertEquals(expectedChecksum, storedChecksum, "the checksum covers the length field too")

        at += LogFormat.FRAME_HEADER_BYTES
        assertEquals(1L, readLong(bytes, at), "first sequence")
        assertEquals(1, readInt(bytes, at + 8), "operation count")
        at += 12
        assertEquals(1, bytes[at].toInt(), "PUT is operation id 1, permanently")
        at += 1
        assertEquals(1, readInt(bytes, at), "key length")
        at += 4
        assertEquals("a", bytes.decodeToString(at, at + 1))
        at += 1
        assertEquals(metadata.size, readInt(bytes, at), "metadata length")
        at += 4 + metadata.size
        assertEquals(value.size, readInt(bytes, at), "value length")
        at += 4 + value.size
        assertEquals(bytes.size, at, "the record ends exactly where the file does")
    }

    @Test
    fun `a delete record carries only the key`() {
        val batch = WriteBatch().delete(Key.of("gone"))
        val bytes = readAllBytes(writeRecord(batch, firstSequence = 9))

        val at = LogFormat.HEADER_BYTES + LogFormat.FRAME_HEADER_BYTES
        assertEquals(9L, readLong(bytes, at))
        assertEquals(1, readInt(bytes, at + 8))
        assertEquals(2, bytes[at + 12].toInt(), "DELETE is operation id 2, permanently")
        assertEquals(4, readInt(bytes, at + 13))
        assertEquals("gone", bytes.decodeToString(at + 17, at + 21))
        assertEquals(bytes.size, at + 21, "no metadata or value follows a tombstone")
    }

    @Test
    fun `operation ids are stable and unknown ids are not guessed`() {
        assertEquals(1, OperationKind.PUT.id)
        assertEquals(2, OperationKind.DELETE.id)
        assertEquals(OperationKind.PUT, OperationKind.ofId(1))
        assertEquals(OperationKind.DELETE, OperationKind.ofId(2))
        assertNull(OperationKind.ofId(0))
        assertNull(OperationKind.ofId(3), "an unknown id must signal, never default")
    }

    private fun writeRecord(batch: WriteBatch, firstSequence: Long): Path {
        val directory = scratch(root, "format")
        java.nio.file.Files.createDirectories(directory)
        LogWriter.create(directory, 1, firstSequence).use { writer ->
            writer.append(batch.operations(), firstSequence)
            writer.sync()
        }
        return logPath(directory, 1)
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()

    private fun readLong(bytes: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN).getLong()

    private fun writeInt(bytes: ByteArray, offset: Int, value: Int) {
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
    }
}
