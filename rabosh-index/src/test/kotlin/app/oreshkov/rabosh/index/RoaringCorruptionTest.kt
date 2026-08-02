package app.oreshkov.rabosh.index

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Damaged, truncated and unrepresentable portable-format streams, every one of them reported.
 *
 * These bytes come from somewhere else, which makes this the one decoder in the engine whose input is
 * not something rabosh wrote. The rule is the engine's usual one — unrecognised data becomes a
 * signalled failure naming where it was found, never a default — and there are two ways to fail it
 * here. The obvious one is trusting a length and reading off the end. The quieter one is treating
 * *unrepresentable* as *damaged*: a Roaring bitmap over values above [BitmapFormat.MAX_ORDINAL] is
 * perfectly well formed and simply cannot be held by an engine whose ordinals are a signed `Int`, and
 * reporting that as corruption would send somebody looking for a disk fault.
 *
 * The fixture is built to hold all three container encodings, a run bitmap and an offset header at
 * once, because damage to a run bitmap is only reachable in a stream that has one.
 */
class RoaringCorruptionTest {

    private val source = "damaged.roaring"

    /** Four containers: an array, a run, a bitset and a two-run container, in that order. */
    private val healthy: ByteArray = RoaringPortable.encode(
        Bitmap().also { bitmap ->
            bitmap.add(1)
            bitmap.add(3)
            bitmap.add(9)
            bitmap.addAll(65_536 until 65_536 + 5_000)
            for (ordinal in scatteredOrdinals(4_200, 131_072)) bitmap.add(ordinal)
            bitmap.addAll(196_608 until 196_608 + 40)
            bitmap.addAll(196_608 + 100 until 196_608 + 140)
        },
    )

    private val stream = PortableStream(healthy)

    private fun damaged(apply: (ByteArray) -> Unit): ByteArray = healthy.copyOf().also(apply)

    private fun assertCorrupt(bytes: ByteArray, note: String) {
        val failure = assertFailsWith<CorruptBitmapException>(note) { RoaringPortable.decode(bytes, source) }
        assertEquals(source, failure.file, "$note: the failure does not name where the bytes came from")
    }

    @Test
    fun `the fixture is healthy and holds all three encodings`() {
        assertEquals(4, stream.count, "container count")
        assertTrue(stream.anyRun, "the fixture has no run bitmap to damage")
        assertTrue(stream.hasOffsets, "the fixture has no offset header to damage")
        assertEquals(BitmapFormat.KIND_ARRAY, stream.derivedKindAt(0), "container 0")
        assertEquals(BitmapFormat.KIND_RUN, stream.derivedKindAt(1), "container 1")
        assertEquals(BitmapFormat.KIND_BITSET, stream.derivedKindAt(2), "container 2")
        assertEquals(BitmapFormat.KIND_RUN, stream.derivedKindAt(3), "container 3")
        RoaringPortable.decode(healthy, source)
    }

    @Test
    fun `an unrecognised cookie is not damage`() {
        for (cookie in listOf(0, 1, 12_345, 12_348, -1)) {
            val bytes = damaged { writeU32(it, 0, cookie) }
            assertFailsWith<UnsupportedBitmapFormatException>("cookie $cookie") {
                RoaringPortable.decode(bytes, source)
            }
        }
    }

    @Test
    fun `a stream too short to hold a cookie is refused`() {
        for (length in 0 until 4) {
            assertCorrupt(healthy.copyOf(length), "$length byte(s)")
        }
    }

    /**
     * Truncation at **every** offset, and every one of them a signalled failure.
     *
     * The whole stream is tiled by its containers, so any prefix short of the last byte leaves a
     * container that does not fit — which means "throws" is the right assertion here rather than "throws
     * or decodes". What is being tested is that it throws the engine's own failure and not an
     * `IndexOutOfBoundsException` from a length nobody checked, which is what an unchecked read off the
     * end of a mapped array produces.
     */
    @Test
    fun `every truncation of a stream is reported`() {
        for (limit in 0 until healthy.size) {
            assertFailsWith<IndexException>("truncated to $limit of ${healthy.size}") {
                RoaringPortable.decode(healthy.copyOf(limit), source)
            }
        }
    }

    /**
     * The same sweep over the two files another implementation wrote, stepped rather than exhaustive.
     *
     * 120 KB of fixture at one decode per byte would dominate the module's test time for a claim the
     * exhaustive sweep above already makes about the decoder. What these add is the decoder meeting
     * *foreign* container layouts while truncated — a 72 KB bitset stream and a run-optimised one — so
     * the step is small enough to land inside every container of both files, and every offset in the
     * headers is visited exactly.
     */
    @Test
    fun `every truncation of a foreign stream is reported`() {
        for (name in listOf("bitmapwithoutruns.bin", "bitmapwithruns.bin")) {
            val bytes = requireNotNull(RoaringCorruptionTest::class.java.classLoader.getResource("roaring/$name"))
                .openStream().use { it.readBytes() }
            val limits = (0 until 256) + (256 until bytes.size step 293)
            for (limit in limits) {
                assertFailsWith<IndexException>("$name truncated to $limit of ${bytes.size}") {
                    RoaringPortable.decode(bytes.copyOf(limit), name)
                }
            }
        }
    }

    /**
     * The third container's key, not the second's.
     *
     * The first container is keyed zero, so "a key below its predecessor's" is only expressible from
     * the third onwards — below zero wraps to 65535, which is refused as *unrepresentable* before the
     * ordering is ever considered, and the test would then be asserting the wrong failure while looking
     * like it passed for the right reason.
     */
    @Test
    fun `container keys must ascend`() {
        val entry = stream.descriptiveAt + RoaringPortableFormat.DESCRIPTIVE_ENTRY_BYTES * 2
        assertCorrupt(damaged { writeU16(it, entry, stream.keyAt(1)) }, "a repeated key")
        assertCorrupt(damaged { writeU16(it, entry, stream.keyAt(0)) }, "a descending key")
    }

    @Test
    fun `an array container's values must ascend`() {
        val body = stream.offsetAt(0)
        assertCorrupt(damaged { writeU16(it, body + 2, readU16(healthy, body)) }, "a repeated value")
        assertCorrupt(damaged { writeU16(it, body + 2, 0) }, "a descending value")
    }

    /**
     * A bitset whose bits do not add up to the cardinality its header declared.
     *
     * The one check here that [BitmapView.open] deliberately does *not* make for rabosh's own format,
     * because that reader answers in place and a population count over 1024 words would be paid on
     * every open. This decoder copies the words onto the heap regardless, so the count is already in
     * hand — and these bytes have no checksum in front of them the way a sidecar's do.
     */
    @Test
    fun `a bitset container's population count must be its cardinality`() {
        val body = stream.offsetAt(2)
        assertCorrupt(damaged { it[body] = 0xFF.toByte() }, "bits added")
        assertCorrupt(damaged { it[body] = 0 }, "bits removed")
    }

    @Test
    fun `a run container must hold at least one run`() {
        assertCorrupt(damaged { writeU16(it, stream.offsetAt(3), 0) }, "no runs")
    }

    @Test
    fun `a run must not leave its block`() {
        val body = stream.offsetAt(3)
        assertCorrupt(
            damaged {
                writeU16(it, body + 2, 60_000)
                writeU16(it, body + 4, 0xFFFF)
            },
            "a run past the end of its block",
        )
    }

    /**
     * Two runs written adjacently, which describe the right values in the wrong number of runs.
     *
     * The same claim `RunBlock.verify` makes about rabosh's own format, and it matters for the same
     * reason: adjacent runs decode to a perfectly good set of values, so nothing downstream would ever
     * notice — but they are not the encoding either format's writer produces, and accepting them would
     * mean two streams holding the same values whose bytes differ.
     */
    @Test
    fun `runs must be separated`() {
        val body = stream.offsetAt(3)
        val firstLast = readU16(healthy, body + 2) + readU16(healthy, body + 4)
        assertCorrupt(damaged { writeU16(it, body + 6, firstLast + 1) }, "adjacent runs")
        assertCorrupt(damaged { writeU16(it, body + 6, firstLast) }, "overlapping runs")
    }

    @Test
    fun `a run container's runs must sum to its cardinality`() {
        val body = stream.offsetAt(3)
        assertCorrupt(damaged { writeU16(it, body + 4, 0) }, "a run shorter than declared")
    }

    @Test
    fun `an offset outside the stream is reported`() {
        val field = stream.offsetsAt
        assertCorrupt(damaged { writeU32(it, field, healthy.size + 100) }, "past the end")
        assertCorrupt(damaged { writeU32(it, field, healthy.size - 1) }, "too close to the end")
    }

    /**
     * A well-formed bitmap this engine cannot hold, in both of the two ways it can happen.
     *
     * The portable format is over unsigned 32-bit values; an ordinal here stops at
     * [BitmapFormat.MAX_ORDINAL], one short of `Int.MAX_VALUE`. So a container keyed above 32767 is out
     * of reach entirely, and *inside* key 32767 the single remainder 65535 is too — one value, and it is
     * exactly the one this engine gave up to keep a cardinality an `Int`. Both are
     * [UnsupportedBitmapFormatException] rather than corruption: the bytes are intact and the repair is
     * to use something else, not to fetch them again.
     */
    @Test
    fun `a value above the ordinal domain is unsupported rather than damaged`() {
        val topKey = BitmapFormat.high(BitmapFormat.MAX_ORDINAL)

        val unreachableKey = arrayStream(topKey + 1, listOf(0))
        assertFailsWith<UnsupportedBitmapFormatException>("a container above the domain") {
            RoaringPortable.decode(unreachableKey, source)
        }

        val unreachableValue = arrayStream(topKey, listOf(0xFFFF))
        assertFailsWith<UnsupportedBitmapFormatException>("the one value above the domain") {
            RoaringPortable.decode(unreachableValue, source)
        }

        // And the value immediately below it is fine, which is what makes the one above a boundary
        // rather than a range this engine simply refuses.
        val representable = RoaringPortable.decode(arrayStream(topKey, listOf(0xFFFE)), source)
        assertEquals(BitmapFormat.MAX_ORDINAL, representable.first(), "the largest representable ordinal")
    }

    /**
     * A minimal run-free stream holding one array container, built by hand from the specification.
     *
     * By hand because [RoaringPortable.encode] cannot produce these: a bitmap this engine can hold is
     * exactly a bitmap whose values are representable, so the streams this test needs are the ones its
     * own writer is incapable of writing.
     */
    private fun arrayStream(key: Int, values: List<Int>): ByteArray {
        val bodyAt = RoaringPortableFormat.PLAIN_HEADER_BYTES +
            RoaringPortableFormat.DESCRIPTIVE_ENTRY_BYTES + RoaringPortableFormat.OFFSET_BYTES
        val bytes = ByteArray(bodyAt + values.size * 2)
        writeU32(bytes, 0, RoaringPortableFormat.SERIAL_COOKIE_NO_RUNCONTAINER)
        writeU32(bytes, 4, 1)
        writeU16(bytes, 8, key)
        writeU16(bytes, 10, values.size - 1)
        writeU32(bytes, 12, bodyAt)
        values.forEachIndexed { index, value -> writeU16(bytes, bodyAt + index * 2, value) }
        return bytes
    }
}
