package app.oreshkov.rabosh.core

import app.oreshkov.rabosh.testkit.property.Gen
import app.oreshkov.rabosh.testkit.property.forAll
import app.oreshkov.rabosh.testkit.property.long
import app.oreshkov.rabosh.testkit.property.pair
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The segment format's fixed parts, asserted against the bytes they are supposed to be.
 *
 * Phase 2's lesson applies again here: everything else in a codec is self-consistent — encode,
 * decode, compare — and a misreading of its own layout survives that happily. What catches it is a
 * test that names the offsets independently of the code that computes them.
 *
 * The other half of this file is the cross-check between the two key orders in the engine. The
 * memtable sorts [InternalKey] objects and a segment sorts encoded key bytes; a flush walks the
 * first and writes into the second, so a disagreement between them is not a slow path, it is a
 * segment whose entries are out of order and whose binary search silently misses.
 */
class SegmentFormatTest {

    @Test
    fun `the header is the bytes it claims to be`() {
        val header = SegmentFormat.encodeHeader()
        assertEquals(SegmentFormat.HEADER_BYTES, header.size)
        assertContentEquals("JKDB-SEG".encodeToByteArray(), header.copyOfRange(0, 8))
        assertEquals(1, readIntAt(header, 8), "format version")
        assertEquals(SegmentFormat.checksum(header, 0, 12), readIntAt(header, 12))

        SegmentFormat.checkHeader(segmentBytesOf(header))
    }

    @Test
    fun `the footer is 76 bytes with the magic at both ends of the file`() {
        val footer = SegmentFormat.encodeFooter(
            SegmentFormat.Footer(
                dictionary = BlockHandle(16, 32),
                index = BlockHandle(48, 64),
                bloom = BlockHandle(112, 128),
                entryCount = 9,
                smallestSequence = 3,
                largestSequence = 11,
            ),
        )
        assertEquals(76, footer.size)
        assertEquals(76, SegmentFormat.FOOTER_BYTES)

        // Field offsets, named here rather than derived, so a reordering of the writer is caught.
        assertEquals(16L, readLongAt(footer, 0)); assertEquals(32, readIntAt(footer, 8))
        assertEquals(48L, readLongAt(footer, 12)); assertEquals(64, readIntAt(footer, 20))
        assertEquals(112L, readLongAt(footer, 24)); assertEquals(128, readIntAt(footer, 32))
        assertEquals(9L, readLongAt(footer, 36))
        assertEquals(3L, readLongAt(footer, 44))
        assertEquals(11L, readLongAt(footer, 52))
        assertEquals(1, readIntAt(footer, 60), "format version")
        assertEquals(SegmentFormat.checksum(footer, 0, 64), readIntAt(footer, 64))
        assertContentEquals("JKDB-SEG".encodeToByteArray(), footer.copyOfRange(68, 76))
    }

    @Test
    fun `a footer round-trips through the reader`() {
        val file = SegmentFormat.encodeHeader() + ByteArray(240) + SegmentFormat.encodeFooter(
            SegmentFormat.Footer(BlockHandle(16, 32), BlockHandle(48, 64), BlockHandle(112, 128), 9, 3, 11),
        )
        val footer = SegmentFormat.readFooter(segmentBytesOf(file))
        assertEquals(16L, footer.dictionary.offset)
        assertEquals(32, footer.dictionary.length)
        assertEquals(48L, footer.index.offset)
        assertEquals(112L, footer.bloom.offset)
        assertEquals(9L, footer.entryCount)
        assertEquals(3L, footer.smallestSequence)
        assertEquals(11L, footer.largestSequence)
    }

    /**
     * Every byte of the footer is covered by its checksum.
     *
     * A footer is the only region a reader trusts before validating anything else, so a flipped bit
     * in it must be a report rather than three handles pointing at arbitrary offsets.
     */
    @Test
    fun `a flipped bit anywhere in the footer is reported`() {
        val base = SegmentFormat.encodeHeader() + ByteArray(240) + SegmentFormat.encodeFooter(
            SegmentFormat.Footer(BlockHandle(16, 32), BlockHandle(48, 64), BlockHandle(112, 128), 9, 3, 11),
        )
        val footerAt = base.size - SegmentFormat.FOOTER_BYTES
        for (offset in footerAt until base.size) {
            val damaged = base.copyOf()
            damaged[offset] = (damaged[offset].toInt() xor 1).toByte()
            val failure = runCatching { SegmentFormat.readFooter(segmentBytesOf(damaged)) }.exceptionOrNull()
            assertTrue(
                failure is CorruptSegmentException || failure is UnsupportedFormatException,
                "a flip at byte $offset of the footer produced $failure",
            )
        }
    }

    @Test
    fun `a segment from a newer format version is reported as such, not as damage`() {
        val header = SegmentFormat.encodeHeader()
        writeIntAt(header, 8, 2)
        writeIntAt(header, 12, SegmentFormat.checksum(header, 0, 12))
        val failure = assertFailsWith<UnsupportedFormatException> {
            SegmentFormat.checkHeader(segmentBytesOf(header))
        }
        assertTrue("version 2" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `a file too short to hold a footer is reported`() {
        assertFailsWith<CorruptSegmentException> { SegmentFormat.readFooter(segmentBytesOf(ByteArray(40))) }
    }

    // --- keys ---------------------------------------------------------------------------------

    @Test
    fun `a tag round-trips through its sequence and operation`() {
        forAll(Gen.long(0..SegmentFormat.MAX_SEQUENCE)) { sequence ->
            for (kind in OperationKind.entries) {
                val tag = SegmentFormat.tag(sequence, kind)
                assertEquals(sequence, SegmentFormat.sequenceOf(tag))
                assertEquals(kind, SegmentFormat.kindOf(tag, "test.seg", 0))
            }
        }
    }

    @Test
    fun `the largest representable sequence still round-trips`() {
        val tag = SegmentFormat.tag(SegmentFormat.MAX_SEQUENCE, OperationKind.DELETE)
        assertEquals(SegmentFormat.MAX_SEQUENCE, SegmentFormat.sequenceOf(tag))
        assertEquals(OperationKind.DELETE, SegmentFormat.kindOf(tag, "test.seg", 0))
        assertFailsWith<IllegalArgumentException> {
            SegmentFormat.tag(SegmentFormat.MAX_SEQUENCE + 1, OperationKind.PUT)
        }
    }

    /** An operation id this build does not know is unreadable data, never a default. */
    @Test
    fun `an unknown operation id in a tag is reported`() {
        val tag = (7L shl 8) or 99L
        assertFailsWith<CorruptSegmentException> { SegmentFormat.kindOf(tag, "test.seg", 128) }
    }

    @Test
    fun `an encoded key carries its user key back out`() {
        forAll(CoreGens.key, Gen.long(1L..1_000_000L)) { key, sequence ->
            val encoded = SegmentFormat.encodeKey(key, sequence, OperationKind.PUT)
            assertEquals(key.size + 8, encoded.size)
            assertEquals(key, SegmentFormat.userKeyOf(encoded))
            assertEquals(sequence, SegmentFormat.sequenceOf(SegmentFormat.readTag(encoded, key.size)))
        }
    }

    /**
     * The cross-check: the memtable's order and the segment's order are the same order.
     *
     * Both are exercised over the same pairs, including the ones that separate an unsigned
     * comparison from a signed one and a descending sequence from an ascending one.
     */
    @Test
    fun `encoded keys sort exactly as internal keys do`() {
        forAll(
            Gen.pair(CoreGens.key, Gen.long(1L..40L)),
            Gen.pair(CoreGens.key, Gen.long(1L..40L)),
        ) { left, right ->
            val internalOrder = InternalKey(left.first, left.second)
                .compareTo(InternalKey(right.first, right.second))
            val encodedOrder = compareEncodedKeys(
                SegmentFormat.encodeKey(left.first, left.second, OperationKind.PUT),
                SegmentFormat.encodeKey(right.first, right.second, OperationKind.PUT),
            )
            assertEquals(
                internalOrder.coerceIn(-1, 1),
                encodedOrder.coerceIn(-1, 1),
                "memtable and segment disagree on ${left.first}@${left.second} vs ${right.first}@${right.second}",
            )
        }
    }

    /**
     * A tombstone and a document written by the same commit still order deterministically.
     *
     * They cannot both exist for one key in practice — a batch holds one operation per key at a
     * given sequence — but the comparator has to be a total order regardless, or a sort is free to
     * produce a different segment on every run.
     */
    @Test
    fun `equal sequences order by operation, giving a total order`() {
        val key = Key.of("k")
        val put = SegmentFormat.encodeKey(key, 5, OperationKind.PUT)
        val delete = SegmentFormat.encodeKey(key, 5, OperationKind.DELETE)
        assertTrue(compareEncodedKeys(put, delete) < 0)
        assertTrue(compareEncodedKeys(delete, put) > 0)
        assertEquals(0, compareEncodedKeys(put, put.copyOf()))
    }

    @Test
    fun `a seek key sorts at or before every version of its user key`() {
        val key = Key.of("user:7")
        val probe = SegmentFormat.seekKey(key, 100)
        for (sequence in longArrayOf(1, 50, 99, 100)) {
            for (kind in OperationKind.entries) {
                assertTrue(
                    compareEncodedKeys(probe, SegmentFormat.encodeKey(key, sequence, kind)) <= 0,
                    "the seek key must not skip $key@$sequence/$kind",
                )
            }
        }
        // And after every version newer than the snapshot it names.
        assertTrue(compareEncodedKeys(probe, SegmentFormat.encodeKey(key, 101, OperationKind.PUT)) > 0)
    }

    private fun readIntAt(bytes: ByteArray, offset: Int): Int {
        var value = 0
        for (index in 0 until 4) value = value or ((bytes[offset + index].toInt() and 0xFF) shl (8 * index))
        return value
    }

    private fun readLongAt(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 0 until 8) value = value or ((bytes[offset + index].toLong() and 0xFF) shl (8 * index))
        return value
    }

    private fun writeIntAt(bytes: ByteArray, offset: Int, value: Int) {
        for (index in 0 until 4) bytes[offset + index] = (value ushr (8 * index)).toByte()
    }
}
