package app.oreshkov.rabosh.index

import java.lang.foreign.MemorySegment
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Damaged columns are reported, never guessed at — plus a failure class phase 7 did not have.
 *
 * Every corruption in the inverted index makes a file **unreadable**. A column has one that does not:
 * a flipped byte in `STATS` leaves a file that decodes perfectly and silently over-prunes, returning
 * fewer documents than a scan with nothing anywhere looking like a fault. That is the reason the
 * statistics carry their own directory-entry checksum and are verified before first use, and it is a
 * new *reason* for the two-level scheme rather than another instance of it.
 */
class ColumnCorruptionTest {

    private fun column(): ByteArray =
        ColumnFixture((0 until 60).map { it to if (it % 5 == 0) "$it.25" else "$it" }).bytes!!

    private fun stringColumn(): ByteArray =
        ColumnFixture((0 until 40).map { it to "\"value-${"%04d".format(it)}\"" }).bytes!!

    private fun open(bytes: ByteArray) =
        ColumnFile.open(MemorySegment.ofArray(bytes), bytes.size, "0000000007.0003.col", 7, 3, "$.p", 900)

    private fun assertReported(bytes: ByteArray, note: String, body: (ByteArray) -> Unit) {
        val failure = runCatching { body(bytes) }.exceptionOrNull()
        assertTrue(
            failure is IndexException,
            "$note gave ${failure?.let { it::class.qualifiedName }}: ${failure?.message}",
        )
    }

    /**
     * A newer *version* is unsupported, and that is not the same claim as the unsupported *type* below.
     *
     * A column already asserted that a type id it does not know is reported rather than guessed at. The
     * version had no such test, and the two fail at different places for different reasons: a type is an
     * id inside a file this build can read, a version says the layout itself is one it cannot. Both are
     * promised in `COMPATIBILITY.md`, so both are held to it.
     */
    @Test
    fun `a newer format version is unsupported rather than damaged`() {
        val bytes = column()
        writeU32(bytes, 8, ColumnFormat.VERSION + 1)
        val failure = runCatching { open(bytes) }.exceptionOrNull()
        assertTrue(
            failure is UnsupportedIndexFormatException,
            "a newer column must say this build is too old, not that the file is broken: $failure",
        )
    }

    @Test
    fun `a truncated column is reported at every offset`() {
        val complete = column()
        // Every offset, not a sample: the interesting failures leave a plausible header pointing at
        // bytes that are not what it says they are, and those live at offsets nobody would pick.
        for (limit in 0 until complete.size) {
            assertReported(complete.copyOf(limit), "truncated to $limit byte(s)") { open(it).verify() }
        }
    }

    @Test
    fun `a truncated string column is reported at every offset`() {
        val complete = stringColumn()
        for (limit in 0 until complete.size) {
            assertReported(complete.copyOf(limit), "truncated to $limit byte(s)") { open(it).verify() }
        }
    }

    @Test
    fun `trailing bytes are reported rather than ignored`() {
        assertReported(column() + byteArrayOf(0), "a trailing byte") { open(it).verify() }
    }

    @Test
    fun `a flipped bit anywhere is reported`() {
        val complete = column()
        for (offset in complete.indices) {
            val damaged = complete.copyOf()
            damaged[offset] = (damaged[offset].toInt() xor 0x40).toByte()
            assertReported(damaged, "byte $offset flipped") { open(it).verify() }
        }
    }

    @Test
    fun `damaged statistics are caught before they can over-prune`() {
        // The readable-and-wrong class. Find the STATS section, flip a byte inside it, and confirm the
        // file still *opens* — the header checksum covers only the directory — but that touching the
        // statistics is refused rather than answered with a silently narrowed bound.
        val complete = column()
        val sections = SectionDirectory.open(
            MemorySegment.ofArray(complete),
            complete.size,
            "c.col",
            ColumnFormat.MAGIC,
            intArrayOf(ColumnFormat.VERSION),
            "column",
            ColumnFormat::sectionName,
        )
        val stats = sections.require(ColumnFormat.SECTION_STATS, "STATS")
        val statsAt = stats.bytes.sourceOffset.toInt()
        assertTrue(stats.bytes.length > 8, "the fixture must have statistics to damage")

        val damaged = complete.copyOf()
        val victim = statsAt + 8
        damaged[victim] = (damaged[victim].toInt() xor 0xFF).toByte()

        // Opening is still fine: nothing has read a value yet, which is what makes opening a huge
        // column cheap.
        val file = runCatching { open(damaged) }
        assertTrue(file.isSuccess, "a stats flip must not stop the file opening: ${file.exceptionOrNull()}")
        // Asking a pruning question is where it is caught.
        assertReported(damaged, "a flip inside STATS") {
            open(it).blockMayContainNumeric(0, BigDecimal.ZERO, BigDecimal.ONE)
        }
    }

    @Test
    fun `a column claiming an unsupported type is reported as unsupported, not as damage`() {
        // The bytes are intact. Calling them damaged would send somebody looking for a disk fault,
        // when the truth is that a newer build wrote a type this one has only reserved.
        val complete = column()
        val sections = SectionDirectory.open(
            MemorySegment.ofArray(complete),
            complete.size,
            "c.col",
            ColumnFormat.MAGIC,
            intArrayOf(ColumnFormat.VERSION),
            "column",
            ColumnFormat::sectionName,
        )
        val metaAt = sections.require(ColumnFormat.SECTION_META, "META").bytes.sourceOffset.toInt()

        for (reserved in listOf(ColumnFormat.COLUMN_TYPE_DECIMAL128, ColumnFormat.COLUMN_TYPE_DOUBLE, 99)) {
            val damaged = complete.copyOf()
            damaged[metaAt + 32] = reserved.toByte()
            // The META checksum catches the edit first, which is correct — but the *type* check is
            // what a genuinely newer file would hit, so it is exercised through a rebuilt checksum.
            val patched = repairMetaChecksum(damaged, sections, metaAt)
            val failure = runCatching { open(patched) }.exceptionOrNull()
            assertTrue(
                failure is UnsupportedIndexFormatException,
                "type $reserved gave ${failure?.let { it::class.simpleName }}: ${failure?.message}",
            )
        }
    }

    /** Rewrites META's directory checksum so a deliberate field edit reaches the field's own check. */
    private fun repairMetaChecksum(
        bytes: ByteArray,
        sections: SectionDirectory.Sections,
        metaAt: Int,
    ): ByteArray {
        val length = sections.require(ColumnFormat.SECTION_META, "META").bytes.length
        val body = bytes.copyOfRange(metaAt, metaAt + length)
        val checksum = sectionChecksum(ColumnFormat.SECTION_META, body)
        // The META entry is the first in the directory; its checksum is the last field of the entry.
        val entryAt = SectionDirectory.HEADER_BYTES
        val patched = bytes.copyOf()
        for (index in 0 until 4) patched[entryAt + 16 + index] = (checksum ushr (8 * index)).toByte()
        // …and the header checksum covers the directory, so that has to be rebuilt too. The section
        // count is read out of the header rather than remembered: a phase that adds a section would
        // otherwise make this helper silently checksum the wrong range and the test fail for a reason
        // that has nothing to do with what it is asserting.
        val crc = java.util.zip.CRC32C()
        crc.update(patched, IndexFormat.MAGIC_BYTES, 8)
        val sectionCount = readU32(patched, 12)
        val directoryEnd = SectionDirectory.HEADER_BYTES + sectionCount * SectionDirectory.ENTRY_BYTES
        crc.update(patched, SectionDirectory.HEADER_BYTES, directoryEnd - SectionDirectory.HEADER_BYTES)
        val header = crc.value.toInt()
        for (index in 0 until 4) patched[16 + index] = (header ushr (8 * index)).toByte()
        return patched
    }
}
