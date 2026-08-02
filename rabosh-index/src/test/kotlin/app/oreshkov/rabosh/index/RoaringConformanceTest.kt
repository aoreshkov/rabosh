package app.oreshkov.rabosh.index

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Interop against bytes another implementation produced.
 *
 * This is the only test in the module whose fixtures rabosh did not write, and that is its whole
 * point: every other bitmap test compares this engine's writer against this engine's reader, which can
 * confirm they agree with each other and nothing more. "Compatible with the portable format" would
 * otherwise mean "compatible with our reading of the specification".
 *
 * The two fixtures are the cross-implementation conformance files CRoaring, the Java library and the Go
 * port all test against, committed unmodified — see `src/test/resources/roaring/README.md` for their
 * provenance. The set they hold is built here **from the specification's own recipe** rather than from a
 * remembered cardinality, so a fixture that changed would fail against the recipe instead of quietly
 * redefining what is expected.
 *
 * The three claims, in the order they get stronger:
 *
 * 1. both fixtures decode to the recipe set — the import direction, against two different headers;
 * 2. encoding the recipe set reproduces `bitmapwithruns.bin` **byte for byte** — the export direction,
 *    which is a much stronger statement than "another implementation can read it", and the only reason
 *    it is available is that the encoding choice is canonical on both sides;
 * 3. decoding the run-free fixture and re-encoding it produces the run-optimised one, which is claims 1
 *    and 2 composed across the format's two header forms.
 */
class RoaringConformanceTest {

    /**
     * The set both fixtures hold, as the specification's test case builds it.
     *
     * Written out rather than asserted as a number: the fixtures are the evidence, and a test that
     * agreed with them by construction would be evidence of nothing.
     */
    private val recipe: List<Int> = buildList {
        for (value in 0 until 100_000 step 1000) add(value)
        for (value in 100_000 until 200_000) add(3 * value)
        for (value in 700_000 until 800_000) add(value)
    }.distinct().sorted()

    private fun fixture(name: String): ByteArray {
        val resource = "roaring/$name"
        val source = requireNotNull(RoaringConformanceTest::class.java.classLoader.getResource(resource)) {
            "the conformance fixture $resource is missing from the test resources"
        }
        return source.openStream().use { it.readBytes() }
    }

    @Test
    fun `the fixtures are the committed ones`() {
        assertEquals(72_616, fixture("bitmapwithoutruns.bin").size, "bitmapwithoutruns.bin")
        assertEquals(48_056, fixture("bitmapwithruns.bin").size, "bitmapwithruns.bin")
        assertEquals(200_100, recipe.size, "the recipe set")
    }

    @Test
    fun `a bitmap serialized by another implementation decodes to the same ordinals`() {
        for (name in listOf("bitmapwithoutruns.bin", "bitmapwithruns.bin")) {
            val decoded = RoaringPortable.decode(fixture(name), name)
            assertContentEquals(recipe.toIntArray(), decoded.toIntArray(), name)
            assertSameBitmap(bitmapOf(recipe), decoded, name)
        }
    }

    /**
     * The two fixtures hold the same values in different container encodings, and both survive the
     * trip through rabosh's own format.
     *
     * Not a restatement of the test above: this one goes out through [ReadableBitmap.encode] and back
     * through [BitmapView], so it says that a bitmap that arrived from another implementation is an
     * ordinary bitmap here — indexable, mappable and canonically encoded — rather than a special one
     * that merely answers the same questions.
     */
    @Test
    fun `an imported bitmap is an ordinary one`() {
        val decoded = RoaringPortable.decode(fixture("bitmapwithruns.bin"), "bitmapwithruns.bin")
        val encoded = decoded.encode()
        val view = BitmapView.open(encoded, "bitmapwithruns.idx")
        view.verify()
        assertSameBitmap(decoded, view)
        assertContentEquals(bitmapOf(recipe).encode(), encoded, "rabosh's encoding of the imported set")
    }

    /**
     * Export reproduces another implementation's bytes exactly.
     *
     * The strongest form the interop claim can take. It holds because both sides choose each container's
     * encoding by size — `RoaringBitmap.runOptimize()` on theirs, [RoaringPortableFormat.kindFor] on
     * ours — so there is one smallest encoding of this set and both arrive at it. A failure here is
     * either a layout mistake or the selection rule drifting, and the two look nothing alike in the
     * diff: a layout mistake moves the offset header, a selection drift moves one container's kind.
     */
    @Test
    fun `exporting reproduces the run-optimised fixture byte for byte`() {
        assertContentEquals(fixture("bitmapwithruns.bin"), RoaringPortable.encode(bitmapOf(recipe)))
    }

    /**
     * The run-free fixture re-exports as the run-optimised one.
     *
     * `RoaringPortable.encode` always writes the smallest encoding, so it cannot produce the run-free
     * form at all — that is what a stream written without `runOptimize()` looks like, and reading it is
     * the only thing that fixture is for. Re-exporting it therefore *shrinks* it, from 72 616 bytes to
     * 48 056, and the result has to be the other committed file.
     */
    @Test
    fun `re-exporting the run-free fixture produces the run-optimised one`() {
        val decoded = RoaringPortable.decode(fixture("bitmapwithoutruns.bin"), "bitmapwithoutruns.bin")
        val exported = RoaringPortable.encode(decoded)
        assertTrue(exported.size < fixture("bitmapwithoutruns.bin").size, "re-exporting did not shrink it")
        assertContentEquals(fixture("bitmapwithruns.bin"), exported)
    }

    /**
     * The headers really are the two different forms, read straight out of the fixtures.
     *
     * Without this the tests above would pass just as well if both files happened to use one header,
     * and the run-bitmap path — the part of the format most likely to be got wrong, since it is a bitmap
     * inside a bitmap — would be untested while looking covered.
     */
    @Test
    fun `the two fixtures use the two header forms`() {
        val plain = fixture("bitmapwithoutruns.bin")
        assertEquals(RoaringPortableFormat.SERIAL_COOKIE_NO_RUNCONTAINER, readU32(plain, 0), "plain cookie")
        assertEquals(11, readU32(plain, 4), "plain container count")

        val withRuns = fixture("bitmapwithruns.bin")
        val cookie = readU32(withRuns, 0)
        assertEquals(RoaringPortableFormat.SERIAL_COOKIE, cookie and 0xFFFF, "run cookie")
        assertEquals(11, (cookie ushr 16) + 1, "run container count")
        // Two bytes of run bitmap for eleven containers, with the last three set: the contiguous
        // 700 000..799 999 range, and nothing else in the set is dense enough in runs to earn it.
        assertEquals(0x00, withRuns[4].toInt() and 0xFF, "run bitmap byte 0")
        assertEquals(0x07, withRuns[5].toInt() and 0xFF, "run bitmap byte 1")
    }
}
