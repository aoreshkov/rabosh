package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.testkit.property.forAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The properties of the portable format's import and export, over generated bitmaps.
 *
 * `RoaringConformanceTest` holds the interop claim, against bytes another implementation produced.
 * What is here is everything that claim needs to be true of more than two files: that the round trip
 * is lossless over every shape [IndexGens] reaches, that the output is canonical, and that a reader of
 * this format — which *derives* a container's encoding rather than reading it — infers exactly what the
 * writer chose.
 *
 * That last one is worth stating as a property rather than trusting. rabosh's own format stores a
 * `kind` byte, so a writer and a reader that disagreed would be caught by any round trip. This format
 * stores nothing: the encoding follows from the run bit and the cardinality, so a writer that emitted
 * an 8 KB bitset for a container of 4096 values would produce a stream *every other implementation*
 * reads as a 8 KB array — and our own reader, deriving the same way, would be equally wrong in the same
 * direction and equally quiet about it.
 */
class RoaringPortableTest {

    @Test
    fun `a bitmap survives the round trip`() {
        forAll(IndexGens.ordinals) { ordinals ->
            val bitmap = bitmapOf(ordinals)
            val decoded = RoaringPortable.decode(RoaringPortable.encode(bitmap))
            assertContentEquals(ordinals.toIntArray(), decoded.toIntArray(), "ordinals")
            assertSameBitmap(bitmap, decoded)
        }
    }

    /**
     * The export is canonical: the same ordinals produce the same bytes however they were built.
     *
     * The same claim `BitmapCanonicalTest` makes about rabosh's own format, and it has to be made
     * separately because it rests on a *different* selection rule — [RoaringPortableFormat.kindFor]
     * rather than [BitmapFormat.smallestKind]. It is also what makes byte-for-byte comparison against
     * another implementation's fixture a meaningful test rather than a lucky one.
     */
    @Test
    fun `the export does not depend on how the bitmap was built`() {
        forAll(IndexGens.ordinals) { ordinals ->
            val expected = RoaringPortable.encode(bitmapOf(ordinals))
            assertContentEquals(expected, RoaringPortable.encode(bitmapOf(ordinals.reversed())), "descending")
            assertContentEquals(
                expected,
                RoaringPortable.encode(bitmapOf(ordinals.shuffled(java.util.Random(11)))),
                "shuffled",
            )
            val halves = ordinals.withIndex().partition { it.index % 2 == 0 }
            val union = bitmapOf(halves.first.map { it.value })
            union.orWith(bitmapOf(halves.second.map { it.value }))
            assertContentEquals(expected, RoaringPortable.encode(union), "by union")
        }
    }

    /**
     * Every container is where the reader's own derivation says it is, and the stream ends where the
     * last one does.
     *
     * Walking the stream with the *reader's* rule — run bit, else cardinality against 4096 — and
     * requiring the containers to tile it exactly is what catches a writer that chose a different
     * encoding from the one it described. Nothing else would: the round trip decodes with the same
     * derivation, so it would agree with the mistake.
     */
    @Test
    fun `the containers tile the stream under the reader's own derivation`() {
        forAll(IndexGens.ordinals) { ordinals ->
            val stream = PortableStream(RoaringPortable.encode(bitmapOf(ordinals)))
            var position = stream.bodiesAt
            for (index in 0 until stream.count) {
                if (stream.hasOffsets) assertEquals(position, stream.offsetAt(index), "container $index offset")
                position += stream.extentAt(index, position)
            }
            assertEquals(stream.bytes.size, position, "the containers do not tile the stream")
        }
    }

    /**
     * The two selection rules diverge at exactly one shape, and the divergence is the point.
     *
     * A run costs six bytes here and eight in rabosh's format, because this one counts its runs in
     * sixteen bits. So a block of four consecutive values is a run to the portable format and an array
     * to ours, a block of three is an array to both, and there is no other disagreement to find. Pinned
     * *at* the value rather than either side of it, which is the only way a boundary is tested at all.
     */
    @Test
    fun `the portable rule differs from rabosh's only where two bytes decide it`() {
        assertEquals(BitmapFormat.KIND_ARRAY, RoaringPortableFormat.kindFor(3, 1), "portable, a run of 3")
        assertEquals(BitmapFormat.KIND_ARRAY, BitmapFormat.smallestKind(3, 1), "rabosh, a run of 3")

        assertEquals(BitmapFormat.KIND_RUN, RoaringPortableFormat.kindFor(4, 1), "portable, a run of 4")
        assertEquals(BitmapFormat.KIND_ARRAY, BitmapFormat.smallestKind(4, 1), "rabosh, a run of 4")

        assertEquals(BitmapFormat.KIND_RUN, RoaringPortableFormat.kindFor(5, 1), "portable, a run of 5")
        assertEquals(BitmapFormat.KIND_RUN, BitmapFormat.smallestKind(5, 1), "rabosh, a run of 5")

        // Everywhere else the two agree, which is what makes "two bytes" the whole of the difference.
        for (cardinality in 1..BitmapFormat.CONTAINER_VALUES) {
            for (runCount in setOf(1, 2, cardinality / 2, (cardinality + 1) / 2).filter { it >= 1 }) {
                val portable = RoaringPortableFormat.kindFor(cardinality, runCount)
                val ours = BitmapFormat.smallestKind(cardinality, runCount)
                if (portable != ours) {
                    assertEquals(BitmapFormat.KIND_RUN, portable, "$cardinality value(s) in $runCount run(s)")
                    assertEquals(
                        BitmapFormat.runBytes(runCount) - 2,
                        RoaringPortableFormat.runBytes(runCount),
                        "the difference is not the two bytes of a run count",
                    )
                }
            }
        }
    }

    /**
     * The array/bitset boundary, at the value and on both sides of it.
     *
     * 4096 scattered values cost 8192 bytes either way and the format's derivation puts them in an
     * array; 4097 cannot be an array at all. A writer that got this wrong by one would produce a stream
     * whose containers still tile — a bitset is a fixed size — but which every other implementation
     * would read as an array of 4097 values running two bytes past its end.
     */
    @Test
    fun `the array and bitset encodings change over at four thousand and ninety-six values`() {
        for (cardinality in listOf(4095, 4096, 4097)) {
            val bitmap = bitmapOf(scatteredOrdinals(cardinality))
            val stream = PortableStream(RoaringPortable.encode(bitmap))
            assertEquals(1, stream.count, "$cardinality value(s): container count")
            assertFalse(stream.isRunAt(0), "$cardinality value(s): scattered values are not runs")
            val expected =
                if (cardinality <= BitmapFormat.ARRAY_MAX_CARDINALITY) BitmapFormat.KIND_ARRAY
                else BitmapFormat.KIND_BITSET
            assertEquals(expected, stream.derivedKindAt(0), "$cardinality value(s): encoding")
            assertSameBitmap(bitmap, RoaringPortable.decode(stream.bytes), "$cardinality value(s)")
        }
    }

    /**
     * A container holding every one of its 65 536 values, which is the one cardinality the descriptive
     * header cannot state directly.
     *
     * It is stored one short, exactly as a run stores `lengthMinusOne`, and a writer that forgot the
     * adjustment would describe a container of one value and still tile the stream, because the body is
     * a single run either way.
     */
    @Test
    fun `a full container round trips through the cardinality-minus-one field`() {
        val bitmap = Bitmap.ofRange(0 until BitmapFormat.CONTAINER_VALUES)
        val stream = PortableStream(RoaringPortable.encode(bitmap))
        assertEquals(1, stream.count, "container count")
        assertEquals(0xFFFF, readU16(stream.bytes, stream.descriptiveAt + 2), "the stored cardinality")
        assertEquals(BitmapFormat.CONTAINER_VALUES, stream.cardinalityAt(0), "the decoded cardinality")
        assertSameBitmap(bitmap, RoaringPortable.decode(stream.bytes))
    }

    /** An empty bitmap is the run-free header, a zero count and an empty offset header: eight bytes. */
    @Test
    fun `an empty bitmap is eight bytes`() {
        val encoded = RoaringPortable.encode(Bitmap())
        assertEquals(RoaringPortableFormat.PLAIN_HEADER_BYTES, encoded.size, "size")
        assertEquals(RoaringPortableFormat.SERIAL_COOKIE_NO_RUNCONTAINER, readU32(encoded, 0), "cookie")
        assertEquals(0, readU32(encoded, 4), "container count")
        assertTrue(RoaringPortable.decode(encoded).isEmpty, "it did not decode as empty")
    }

    /**
     * The offset header appears below four containers only when there are no run containers.
     *
     * The format's one genuinely conditional field, and the reason §9.6 of the design plan declined this
     * layout as a storage form. Asserted *at* the threshold: three run containers and four, which are
     * different answers from each other, and a run-free stream of one container, which has the header
     * regardless.
     */
    @Test
    fun `the offset header follows the format's threshold`() {
        for (containers in listOf(3, 4)) {
            val bitmap = Bitmap.ofRange(0 until containers * BitmapFormat.CONTAINER_VALUES)
            val stream = PortableStream(RoaringPortable.encode(bitmap))
            assertEquals(containers, stream.count, "$containers run container(s): count")
            assertTrue(stream.isRunAt(0), "$containers run container(s): a full block is a run")
            assertEquals(
                containers >= RoaringPortableFormat.NO_OFFSET_THRESHOLD,
                stream.hasOffsets,
                "$containers run container(s): offset header",
            )
            assertSameBitmap(bitmap, RoaringPortable.decode(stream.bytes), "$containers run container(s)")
        }

        val sparse = PortableStream(RoaringPortable.encode(bitmapOf(scatteredOrdinals(8))))
        assertEquals(1, sparse.count, "a run-free stream of one container")
        assertTrue(sparse.hasOffsets, "a run-free stream carries an offset header at any size")
    }

    /**
     * The largest ordinal this engine can hold survives the trip.
     *
     * [BitmapFormat.MAX_ORDINAL] is one short of `Int.MAX_VALUE`, so it sits in the top container at
     * remainder 65534 — one below the highest the portable format can express there. Both halves of
     * that sentence are load-bearing, and the value below is the one that proves the export side of it;
     * `RoaringCorruptionTest` covers the value above, which arrives only from another implementation.
     */
    @Test
    fun `the largest representable ordinal round trips`() {
        val bitmap = Bitmap.of(0, BitmapFormat.MAX_ORDINAL - 1, BitmapFormat.MAX_ORDINAL)
        val stream = PortableStream(RoaringPortable.encode(bitmap))
        assertEquals(BitmapFormat.high(BitmapFormat.MAX_ORDINAL), stream.keyAt(stream.count - 1), "top key")
        assertSameBitmap(bitmap, RoaringPortable.decode(stream.bytes))
    }
}

/**
 * A portable-format stream, read by the test with the format's own rules rather than with the code
 * under test.
 *
 * Deliberately a second implementation of the header arithmetic. Asking [RoaringPortable] where a
 * container is would make every structural assertion in this file self-fulfilling — the assertions are
 * about the *layout*, so the layout has to be recomputed from the specification, which for a header
 * this small is thirty lines.
 */
internal class PortableStream(val bytes: ByteArray) {
    val count: Int
    val anyRun: Boolean
    val hasOffsets: Boolean
    val descriptiveAt: Int

    init {
        val cookie = readU32(bytes, 0)
        if (cookie and 0xFFFF == RoaringPortableFormat.SERIAL_COOKIE) {
            count = (cookie ushr 16) + 1
            anyRun = true
            descriptiveAt = RoaringPortableFormat.RUN_HEADER_BYTES + (count + 7) / 8
            hasOffsets = count >= RoaringPortableFormat.NO_OFFSET_THRESHOLD
        } else {
            check(cookie == RoaringPortableFormat.SERIAL_COOKIE_NO_RUNCONTAINER) { "cookie $cookie" }
            count = readU32(bytes, 4)
            anyRun = false
            descriptiveAt = RoaringPortableFormat.PLAIN_HEADER_BYTES
            hasOffsets = true
        }
    }

    val offsetsAt: Int get() = descriptiveAt + RoaringPortableFormat.DESCRIPTIVE_ENTRY_BYTES * count

    val bodiesAt: Int
        get() = offsetsAt + if (hasOffsets) RoaringPortableFormat.OFFSET_BYTES * count else 0

    fun keyAt(index: Int): Int =
        readU16(bytes, descriptiveAt + RoaringPortableFormat.DESCRIPTIVE_ENTRY_BYTES * index)

    fun cardinalityAt(index: Int): Int =
        readU16(bytes, descriptiveAt + RoaringPortableFormat.DESCRIPTIVE_ENTRY_BYTES * index + 2) + 1

    fun isRunAt(index: Int): Boolean = anyRun &&
        (bytes[RoaringPortableFormat.RUN_HEADER_BYTES + index / 8].toInt() and (1 shl (index % 8))) != 0

    fun offsetAt(index: Int): Int = readU32(bytes, offsetsAt + RoaringPortableFormat.OFFSET_BYTES * index)

    fun derivedKindAt(index: Int): Int = RoaringPortableFormat.derivedKind(cardinalityAt(index), isRunAt(index))

    /** How many bytes the container at [position] occupies, under the reader's own derivation. */
    fun extentAt(index: Int, position: Int): Int = when (derivedKindAt(index)) {
        BitmapFormat.KIND_ARRAY -> BitmapFormat.arrayBytes(cardinalityAt(index))
        BitmapFormat.KIND_BITSET -> BitmapFormat.BITSET_BYTES
        else -> RoaringPortableFormat.runBytes(readU16(bytes, position))
    }
}
