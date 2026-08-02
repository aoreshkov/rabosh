package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.testkit.property.forAll
import java.lang.foreign.Arena
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * The serialized layout, pinned from outside the implementation.
 *
 * Everything else in the module is self-consistent — encode, decode, compare — and a misreading of the
 * format would survive all of it happily. So the anchors here are **byte vectors**: hand-computed bytes
 * for a bitmap of each encoding, asserted literally. That is the instrument phase 2 used against the
 * Variant specification, and it is the only kind of test that fails when the format changes rather than
 * when the code disagrees with itself.
 *
 * The rest of the file is about where the bytes may live: at an unaligned offset inside a larger file, and
 * in a real memory-mapped file through an `Arena` — which is how phase 7's sidecars will be read.
 */
class BitmapFormatTest {

    /**
     * Two array blocks, laid out by hand.
     *
     * `{1, 3}` in block 0 and `{65543}` in block 1. Two values two apart are two runs, so a run list would
     * cost twelve bytes against the array's four: the array wins, which is what the directory must say.
     */
    @Test
    fun `two array blocks encode to exactly these bytes`() {
        val bitmap = Bitmap.of(1, 3, 65_536 + 7)
        val expected = byteArrayOf(
            // header: version 1, reserved, containerCount 2, cardinality 3
            0x01, 0x00, 0x02, 0x00, 0x03, 0x00, 0x00, 0x00,
            // entry 0: key 0, kind ARRAY, reserved, cardinalityBefore 0, offset 32
            0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x20, 0x00, 0x00, 0x00,
            // entry 1: key 1, kind ARRAY, reserved, cardinalityBefore 2, offset 36
            0x01, 0x00, 0x01, 0x00, 0x02, 0x00, 0x00, 0x00, 0x24, 0x00, 0x00, 0x00,
            // block 0: remainders 1 and 3
            0x01, 0x00, 0x03, 0x00,
            // block 1: remainder 7
            0x07, 0x00,
        )
        assertContentEquals(expected, bitmap.encode())
        assertContentEquals(expected, BitmapView.open(expected, "vector.idx").encode())
        assertSameBitmap(bitmap, BitmapView.open(expected, "vector.idx"))
    }

    /** One run block, laid out by hand. Note `lengthMinusOne`: ten values are stored as a nine. */
    @Test
    fun `a run block encodes to exactly these bytes`() {
        val bitmap = Bitmap.ofRange(0..9)
        val expected = byteArrayOf(
            0x01, 0x00, 0x01, 0x00, 0x0A, 0x00, 0x00, 0x00,
            // entry 0: key 0, kind RUN, reserved, cardinalityBefore 0, offset 20
            0x00, 0x00, 0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x14, 0x00, 0x00, 0x00,
            // block 0: one run, starting at 0, of length 9 + 1
            0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x09, 0x00,
        )
        assertContentEquals(expected, bitmap.encode())
        assertSameBitmap(bitmap, BitmapView.open(expected, "vector.idx"))
    }

    /**
     * A bitset block: the header and directory by hand, the words by their pattern.
     *
     * 4097 ordinals two apart cannot be an array — one past the tie — and are far too scattered for runs,
     * so this is the only block in the format whose size is fixed rather than derived.
     */
    @Test
    fun `a bitset block encodes to a fixed-size payload`() {
        val bitmap = bitmapOf(scatteredOrdinals(BitmapFormat.ARRAY_MAX_CARDINALITY + 1))
        val encoded = bitmap.encode()

        assertEquals(1, readU16(encoded, 2), "one block")
        assertEquals(4097, readU32(encoded, 4), "cardinality")
        assertEquals(BitmapFormat.KIND_BITSET, encoded[BitmapFormat.HEADER_BYTES + 2].toInt())
        assertEquals(0, readU32(encoded, BitmapFormat.HEADER_BYTES + 4), "prefix cardinality")
        assertEquals(20, readU32(encoded, BitmapFormat.HEADER_BYTES + 8), "block offset")
        assertEquals(
            BitmapFormat.HEADER_BYTES + BitmapFormat.ENTRY_BYTES + BitmapFormat.BITSET_BYTES,
            encoded.size,
        )
        // Every other bit set, little-endian, so the first word is 0x5555555555555555.
        for (index in 0 until 8) {
            assertEquals(0x55.toByte(), encoded[20 + index], "word 0, byte $index")
        }
    }

    /** An empty bitmap is a header and nothing else. */
    @Test
    fun `an empty bitmap is eight bytes`() {
        val encoded = Bitmap().encode()
        assertContentEquals(byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00), encoded)
        val view = BitmapView.open(encoded, "empty.idx")
        view.verify()
        assertTrue(view.isEmpty)
        assertEquals(0, view.cardinality)
        assertEquals(0, view.toIntArray().size)
        assertEquals(0, view.rank(0))
        assertTrue(view.and(Bitmap.of(1)).isEmpty)
        assertEquals(Bitmap(), view.toBitmap())
    }

    /** The largest ordinal a bitmap may hold, which is one short of `Int.MAX_VALUE` on purpose. */
    @Test
    fun `the highest ordinal roundtrips`() {
        val bitmap = Bitmap.of(0, BitmapFormat.MAX_ORDINAL)
        assertEquals(2, bitmap.cardinality)
        assertEquals(BitmapFormat.MAX_ORDINAL, bitmap.last())
        assertEquals(listOf(0, BitmapFormat.MAX_CONTAINERS - 1), encodedKeys(bitmap))

        val view = BitmapView.open(bitmap.encode(), "highest.idx")
        view.verify()
        assertTrue(view.contains(BitmapFormat.MAX_ORDINAL))
        assertEquals(2, view.rank(BitmapFormat.MAX_ORDINAL))
        assertEquals(BitmapFormat.MAX_ORDINAL, view.select(1))
    }

    @Test
    fun `an ordinal outside the domain is refused`() {
        val bitmap = Bitmap()
        assertTrue(runCatching { bitmap.add(-1) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { bitmap.add(Int.MAX_VALUE) }.exceptionOrNull() is IllegalArgumentException)
        // But asking about one is a fair question with a plain answer.
        assertEquals(false, bitmap.contains(-1))
        assertEquals(false, bitmap.contains(Int.MAX_VALUE))
    }

    /**
     * A bitmap read at an unaligned offset, which is the ordinary case inside a sidecar.
     *
     * Every field is declared with an `*_UNALIGNED` layout precisely so that a bitset block's `u64` words
     * can start at an odd address. An aligned layout would throw here instead of reading slowly, so this
     * is the test that would catch a `JAVA_LONG` where a `JAVA_LONG_UNALIGNED` belongs.
     */
    @Test
    fun `a bitmap reads correctly at any offset`() {
        forAll(IndexGens.ordinals) { ordinals ->
            val bitmap = bitmapOf(ordinals)
            val encoded = bitmap.encode()
            for (offset in listOf(0, 1, 3, 7, 8, 13)) {
                val view = viewAtOffset(encoded, offset)
                view.verify()
                assertSameBitmap(bitmap, view, "a view at offset $offset")
                assertContentEquals(encoded, view.encode(), "re-encoding from offset $offset")
            }
        }
    }

    /**
     * And out of a real mapped file, through an `Arena`.
     *
     * This is how a `.idx` sidecar will actually be read in phase 7 — mapped through the FFM API, with the
     * arena owning the unmapping — so the roundtrip is asserted against the thing rather than against
     * `MemorySegment.ofArray`.
     */
    @Test
    fun `a bitmap reads out of a mapped file`(@TempDir directory: Path) {
        val bitmap = Bitmap.ofRange(0..70_000).also {
            it.addAll(200_000..200_010)
            it.remove(500)
        }
        val encoded = bitmap.encode()
        val file = directory.resolve("bitmap.idx")
        // Written behind a header, so the bitmap does not begin at offset zero — the same shape a sidecar
        // holding several bitmaps has.
        val prefix = "JKDB-IDX".encodeToByteArray()
        java.nio.file.Files.write(file, prefix + encoded)

        Arena.ofConfined().use { arena ->
            val segment = FileChannel.open(file, StandardOpenOption.READ).use { channel ->
                channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), arena)
            }
            val view = BitmapView.open(segment, prefix.size.toLong(), encoded.size, "bitmap.idx")
            view.verify()
            assertSameBitmap(bitmap, view, "a bitmap read out of a mapped file")
            assertEquals(bitmap.cardinality, view.cardinality)
            assertContentEquals(bitmap.toIntArray(), view.toIntArray())
            assertContentEquals(encoded, view.encode())
            assertEquals(encoded.size, view.byteSize)
        }
    }

    @Test
    fun `encodedByteSize is what encode produces`() {
        forAll(IndexGens.ordinals) { ordinals ->
            val bitmap = bitmapOf(ordinals)
            assertEquals(bitmap.encodedByteSize(), bitmap.encode().size)
            val view = BitmapView.open(bitmap.encode(), "size.idx")
            assertEquals(view.encodedByteSize(), view.encode().size)
            assertEquals(view.byteSize, view.encodedByteSize())
        }
    }
}
