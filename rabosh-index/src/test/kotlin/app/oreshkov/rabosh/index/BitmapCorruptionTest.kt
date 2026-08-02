package app.oreshkov.rabosh.index

import java.lang.foreign.MemorySegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Damaged and non-canonical bitmaps, every one of them reported rather than guessed at.
 *
 * The engine's rule is that unrecognised data is a signalled failure naming where it was found, never a
 * default. A bitmap makes that easy to get wrong, because almost any byte pattern *decodes*: a corrupt
 * offset still points somewhere, and a corrupt cardinality still counts. So the structure has to be
 * checked rather than trusted, and the two failure types have to stay distinct — a version this build does
 * not know is not damage, and reporting it as damage would send somebody looking for a disk fault.
 *
 * The split between the two passes is deliberate and tested as such: [BitmapView.open] checks everything
 * that decides *where a byte is*, and [BitmapView.verify] walks the values. A file that fails the second
 * but passes the first still answers questions safely — it just answers them about a file this writer
 * would not have produced.
 */
class BitmapCorruptionTest {

    private val file = "damaged.idx"

    private val healthy: ByteArray = Bitmap.of(1, 3, 65_536 + 7, 131_072 + 9).encode()

    @Test
    fun `a healthy bitmap is accepted`() {
        BitmapView.open(healthy, file).verify()
    }

    @Test
    fun `a newer format version is not damage`() {
        val damaged = healthy.copyOf()
        damaged[0] = 2
        val failure = assertFailsWith<UnsupportedBitmapFormatException> { BitmapView.open(damaged, file) }
        assertTrue(failure.message!!.contains("version 2"), failure.message!!)
        assertTrue(failure.message!!.contains(file), failure.message!!)
    }

    @Test
    fun `an unknown container kind is not damage either`() {
        val damaged = healthy.copyOf()
        damaged[BitmapFormat.HEADER_BYTES + 2] = 9
        val failure = assertFailsWith<UnsupportedBitmapFormatException> { BitmapView.open(damaged, file) }
        assertTrue(failure.message!!.contains("kind 9"), failure.message!!)
    }

    /**
     * Truncated at every offset, which is the sweep the log and the manifest get for the same reason.
     *
     * A bitmap has no torn-tail story — it is written whole inside a sidecar that is written whole — so
     * unlike the log there is nothing here to tolerate: every short read is a failure, and the point of
     * sweeping every offset is that not one of them may be mistaken for a smaller valid bitmap.
     */
    @Test
    fun `truncation at any offset is reported`() {
        for (limit in 0 until healthy.size) {
            val truncated = healthy.copyOf(limit)
            val failure = runCatching { BitmapView.open(truncated, file).verify() }.exceptionOrNull()
            assertTrue(
                failure is IndexException,
                "truncating to $limit byte(s) of ${healthy.size} produced $failure",
            )
        }
    }

    @Test
    fun `container keys that do not ascend are reported`() {
        val damaged = healthy.copyOf()
        writeU16(damaged, BitmapFormat.HEADER_BYTES, 5)
        writeU16(damaged, BitmapFormat.HEADER_BYTES + BitmapFormat.ENTRY_BYTES, 1)
        assertCorrupt(damaged, "do not ascend")
    }

    /**
     * A prefix cardinality that fails to advance is caught as the empty block it implies.
     *
     * There is no separate monotonicity check and there does not need to be one, which is the pay-off of
     * storing exclusive prefixes rather than per-block counts: a block's cardinality is the difference
     * between two of them, so the two quantities cannot disagree. This test pins that reasoning, because
     * the obvious "improvement" is to add the redundant check back.
     */
    @Test
    fun `a prefix cardinality that does not advance is reported`() {
        val damaged = healthy.copyOf()
        writeU32(damaged, BitmapFormat.HEADER_BYTES + BitmapFormat.ENTRY_BYTES + 4, 0)
        assertCorrupt(damaged, "block 0 holds 0 ordinal(s)")
    }

    @Test
    fun `a prefix cardinality that goes backwards is reported`() {
        // Three blocks, so a prefix has somewhere to go backwards to: the second block's prefix is 2 and
        // the third's is set below it, which makes the second block's cardinality negative.
        val damaged = healthy.copyOf()
        writeU32(damaged, BitmapFormat.HEADER_BYTES + BitmapFormat.ENTRY_BYTES * 2 + 4, 1)
        assertCorrupt(damaged, "holds -1 ordinal(s)")
    }

    @Test
    fun `a first block that does not start at zero is reported`() {
        val damaged = healthy.copyOf()
        writeU32(damaged, BitmapFormat.HEADER_BYTES + 4, 1)
        assertCorrupt(damaged, "the first block's prefix cardinality")
    }

    @Test
    fun `a block at the wrong offset is reported`() {
        val damaged = healthy.copyOf()
        val offsetField = BitmapFormat.HEADER_BYTES + 8
        writeU32(damaged, offsetField, readU32(damaged, offsetField) + 2)
        assertCorrupt(damaged, "rather than")
    }

    @Test
    fun `trailing bytes are reported`() {
        val padded = healthy + byteArrayOf(0x00)
        assertCorrupt(padded, "end at")
    }

    @Test
    fun `a container count the directory cannot hold is reported`() {
        val damaged = healthy.copyOf()
        writeU16(damaged, 2, 1_000)
        assertCorrupt(damaged, "directory")
    }

    @Test
    fun `a container count beyond the ordinal domain is reported`() {
        val damaged = healthy.copyOf()
        writeU16(damaged, 2, 0xFFFF)
        assertCorrupt(damaged, "at most ${BitmapFormat.MAX_CONTAINERS}")
    }

    @Test
    fun `a cardinality that leaves a block empty is reported`() {
        val damaged = healthy.copyOf()
        writeU32(damaged, 4, 3)
        assertCorrupt(damaged, "ordinal(s)")
    }

    @Test
    fun `blocks with no ordinals between them are reported`() {
        // Two blocks whose prefixes do not advance: the second holds nothing, which the format cannot
        // express and a reader must not invent a meaning for.
        val damaged = craft(
            cardinality = 1,
            Crafted(key = 0, kind = BitmapFormat.KIND_ARRAY, cardinality = 1, payload = arrayPayload(listOf(4))),
            Crafted(key = 1, kind = BitmapFormat.KIND_ARRAY, cardinality = 0, payload = arrayPayload(emptyList())),
        )
        assertCorrupt(damaged, "ordinal(s)")
    }

    @Test
    fun `an array block claiming more than an array can hold is reported`() {
        val damaged = craft(
            cardinality = BitmapFormat.ARRAY_MAX_CARDINALITY + 1,
            Crafted(
                key = 0,
                kind = BitmapFormat.KIND_ARRAY,
                cardinality = BitmapFormat.ARRAY_MAX_CARDINALITY + 1,
                payload = arrayPayload(scatteredOrdinals(BitmapFormat.ARRAY_MAX_CARDINALITY + 1)),
            ),
        )
        assertCorrupt(damaged, "array encoding")
    }

    @Test
    fun `a run block with no runs is reported`() {
        val damaged = craft(
            cardinality = 1,
            Crafted(key = 0, kind = BitmapFormat.KIND_RUN, cardinality = 1, payload = runPayload(emptyList())),
        )
        assertCorrupt(damaged, "no runs")
    }

    /** Values out of order decode into something; `verify` is what refuses to accept it. */
    @Test
    fun `an array block whose values descend is reported by verify`() {
        val damaged = craft(
            cardinality = 3,
            Crafted(
                key = 0,
                kind = BitmapFormat.KIND_ARRAY,
                cardinality = 3,
                payload = arrayPayload(listOf(9, 4, 20)),
            ),
        )
        val view = BitmapView.open(damaged, file)
        assertCorrupt(view, "holds 4 after 9")
    }

    @Test
    fun `runs that touch are reported by verify`() {
        // 0..3 and 4..7 describe the right ordinals in the wrong number of runs: one run, not two.
        val damaged = craft(
            cardinality = 8,
            Crafted(
                key = 0,
                kind = BitmapFormat.KIND_RUN,
                cardinality = 8,
                payload = runPayload(listOf(0 to 3, 4 to 3)),
            ),
        )
        assertCorrupt(BitmapView.open(damaged, file), "not separated")
    }

    @Test
    fun `a bitset block whose population disagrees with the directory is reported by verify`() {
        val damaged = craft(
            cardinality = 5_000,
            Crafted(
                key = 0,
                kind = BitmapFormat.KIND_BITSET,
                cardinality = 5_000,
                payload = bitsetPayload(scatteredOrdinals(4_999)),
            ),
        )
        assertCorrupt(BitmapView.open(damaged, file), "the directory claims")
    }

    /**
     * A block encoded larger than it needed to be is reported, though every answer it gives is right.
     *
     * This is the check that keeps "equal ordinals encode to identical bytes" true of *files* and not only
     * of this writer. Without it the format would have two spellings for one answer, and the property the
     * canonical-form test asserts would hold by convention rather than by rule.
     */
    @Test
    fun `a block in a wasteful encoding is reported by verify`() {
        val damaged = craft(
            cardinality = 2,
            Crafted(
                key = 0,
                kind = BitmapFormat.KIND_BITSET,
                cardinality = 2,
                payload = bitsetPayload(listOf(3, 9)),
            ),
        )
        val view = BitmapView.open(damaged, file)
        // It decodes, and it decodes correctly.
        assertEquals(listOf(3, 9), view.toIntArray().toList())
        assertCorrupt(view, "rather than array")
    }

    @Test
    fun `a slice that does not fit its segment is reported`() {
        val failure = assertFailsWith<CorruptBitmapException> {
            BitmapView.open(MemorySegment.ofArray(healthy), 4, healthy.size, file)
        }
        assertEquals(file, failure.file)
    }

    @Test
    fun `a bitmap shorter than a header is reported`() {
        assertCorrupt(ByteArray(BitmapFormat.HEADER_BYTES - 1), "at least")
    }

    // --- helpers --------------------------------------------------------------------------------

    private fun assertCorrupt(encoded: ByteArray, fragment: String) {
        val failure = assertFailsWith<CorruptBitmapException> { BitmapView.open(encoded, file).verify() }
        assertTrue(failure.message!!.contains(fragment), "expected \"$fragment\" in: ${failure.message}")
        assertEquals(file, failure.file)
    }

    private fun assertCorrupt(view: BitmapView, fragment: String) {
        val failure = assertFailsWith<CorruptBitmapException> { view.verify() }
        assertTrue(failure.message!!.contains(fragment), "expected \"$fragment\" in: ${failure.message}")
        assertEquals(file, failure.file)
        assertTrue(failure.offset >= 0, "a damaged block should name an offset")
    }

    /** One block of a hand-built bitmap, whose declared cardinality need not match its contents. */
    private class Crafted(val key: Int, val kind: Int, val cardinality: Int, val payload: ByteArray)

    /**
     * Builds a bitmap file from explicit blocks.
     *
     * The writer cannot produce a non-canonical file, which is the whole point of it — so the cases above
     * have to be assembled by hand. This lays out a structurally sound directory over whatever payloads it
     * is given, so each test damages exactly one thing.
     */
    private fun craft(cardinality: Int, vararg blocks: Crafted): ByteArray {
        val directoryEnd = BitmapFormat.HEADER_BYTES + BitmapFormat.ENTRY_BYTES * blocks.size
        val out = ByteArray(directoryEnd + blocks.sumOf { it.payload.size })
        out[0] = BitmapFormat.VERSION.toByte()
        writeU16(out, 2, blocks.size)
        writeU32(out, 4, cardinality)

        var offset = directoryEnd
        var before = 0
        blocks.forEachIndexed { index, block ->
            val entry = BitmapFormat.HEADER_BYTES + BitmapFormat.ENTRY_BYTES * index
            writeU16(out, entry, block.key)
            out[entry + 2] = block.kind.toByte()
            writeU32(out, entry + 4, before)
            writeU32(out, entry + 8, offset)
            block.payload.copyInto(out, offset)
            offset += block.payload.size
            before += block.cardinality
        }
        return out
    }

    private fun arrayPayload(values: List<Int>): ByteArray = ByteArray(values.size * 2).also { out ->
        values.forEachIndexed { index, value -> writeU16(out, index * 2, value) }
    }

    private fun bitsetPayload(values: List<Int>): ByteArray = ByteArray(BitmapFormat.BITSET_BYTES).also { out ->
        for (value in values) {
            val byte = value ushr 3
            out[byte] = (out[byte].toInt() or (1 shl (value and 7))).toByte()
        }
    }

    private fun runPayload(runs: List<Pair<Int, Int>>): ByteArray =
        ByteArray(BitmapFormat.runBytes(runs.size)).also { out ->
            writeU32(out, 0, runs.size)
            runs.forEachIndexed { index, (start, lengthMinusOne) ->
                writeU16(out, 4 + index * 4, start)
                writeU16(out, 4 + index * 4 + 2, lengthMinusOne)
            }
        }
}
