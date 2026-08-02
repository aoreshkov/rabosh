package app.oreshkov.rabosh.index

import java.lang.foreign.MemorySegment
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The singleton posting encoding, at the level the format defines it.
 *
 * A path with a distinct value per document produces one posting list per document, and a
 * `BitmapFormat` bitmap costs 22 bytes to hold one four-byte ordinal — measured at **52.4 bytes per
 * document** over 200 000 documents, against 2.5 for a low-cardinality index.
 * [IndexFormat.POSTING_ENCODING_SINGLE] puts the ordinal in the term entry instead, so such a term
 * costs no posting bytes at all.
 *
 * Three things are asserted here and each fails differently.
 *
 * The encoding is read **out of the directory byte** rather than inferred from behaviour, because
 * "the right ordinals came back" is true of both encodings and would pass whether the singleton path
 * ran or not. The choice is a **pure function of the posting list**, asserted by building the same
 * terms in two insertion orders and comparing *bytes* — an adaptive threshold would still return the
 * right answers while quietly breaking the byte identity between a flush-written sidecar and a
 * backfill-rebuilt one. And the saving is asserted as an **exact** figure derived from
 * [ReadableBitmap.encodedByteSize] rather than as "smaller", so a change that made singletons cost
 * four bytes instead of none would be visible here rather than only in a benchmark.
 */
class PostingEncodingTest {

    /** Ordinals 0 and 1 get a term each; ordinals 2..9 share one. */
    private fun mixed(): ByteArray {
        val builder = PostingBuilder(maxTerms = 1024)
        builder.add(IndexTerm.ofString("solo-a").bytes, 0)
        builder.add(IndexTerm.ofString("solo-b").bytes, 1)
        for (ordinal in 2 until 10) builder.add(IndexTerm.ofString("shared").bytes, ordinal)
        return builder.build(segmentNumber = 1, indexId = 1, path = "$.p", documentCount = 10, largestSequence = 99)
    }

    private fun open(bytes: ByteArray): PostingFile =
        PostingFile.open(MemorySegment.ofArray(bytes), bytes.size, "0000000001.0001.pst", 1, 1, "$.p", 99)

    @Test
    fun `a term matching one document is SINGLE and a term matching several is BITMAP`() {
        val bytes = mixed()
        val file = open(bytes)
        val byTerm = (0 until file.termCount).associate { file.termAt(it).decodeToString() to encodingAt(bytes, it) }

        assertEquals(
            mapOf(
                IndexTerm.ofString("solo-a").bytes.decodeToString() to IndexFormat.POSTING_ENCODING_SINGLE,
                IndexTerm.ofString("solo-b").bytes.decodeToString() to IndexFormat.POSTING_ENCODING_SINGLE,
                IndexTerm.ofString("shared").bytes.decodeToString() to IndexFormat.POSTING_ENCODING_BITMAP,
            ),
            byTerm,
        )

        // And the answers are the same answers, read back through the one accessor everything uses.
        assertContentEquals(intArrayOf(0), file.postings(IndexTerm.ofString("solo-a").bytes)!!.toIntArray())
        assertContentEquals(intArrayOf(1), file.postings(IndexTerm.ofString("solo-b").bytes)!!.toIntArray())
        assertContentEquals(
            (2 until 10).toList().toIntArray(),
            file.postings(IndexTerm.ofString("shared").bytes)!!.toIntArray(),
        )
        assertContentEquals((0 until 10).toList().toIntArray(), file.presence().toIntArray())
        file.verify()
    }

    @Test
    fun `the choice is a pure function of the posting list, not of insertion order`() {
        val forwards = PostingBuilder(maxTerms = 1024)
        for (ordinal in 0 until 40) forwards.add(IndexTerm.ofString("v${ordinal % 30}").bytes, ordinal)

        val backwards = PostingBuilder(maxTerms = 1024)
        for (ordinal in 39 downTo 0) backwards.add(IndexTerm.ofString("v${ordinal % 30}").bytes, ordinal)

        assertContentEquals(
            forwards.build(2, 1, "$.p", 40, 500),
            backwards.build(2, 1, "$.p", 40, 500),
            "the same posting lists must encode to the same bytes whatever order they arrived in",
        )
    }

    @Test
    fun `a singleton costs no posting bytes at all`() {
        // Every term matches exactly one document, which is the unique-valued index this phase is for.
        val builder = PostingBuilder(maxTerms = 1024)
        for (ordinal in 0 until 50) builder.add(IndexTerm.ofNumber(ordinal.toLong()).bytes, ordinal)
        val bytes = builder.build(3, 1, "$.id", 50, 700)
        val file = open3(bytes)
        file.verify()

        // The presence bitmap is the last thing in the file: there is no posting region after it.
        val presenceOffset = readU32(bytes, IndexFormat.POSTING_PRESENCE_OFFSET)
        val presenceLength = readU32(bytes, IndexFormat.POSTING_PRESENCE_OFFSET + 4)
        assertEquals(bytes.size, presenceOffset + presenceLength, "a file of singletons has no posting region")

        for (ordinal in 0 until 50) {
            assertContentEquals(
                intArrayOf(ordinal),
                file.postings(IndexTerm.ofNumber(ordinal.toLong()).bytes)!!.toIntArray(),
            )
        }
    }

    /**
     * The whole size of a unique-valued index, as arithmetic rather than as "smaller".
     *
     * Phase 11 removed the bitmap and left 24 bytes of entry plus an uncompressed term — 30.4 bytes
     * per document, and the *entire* residual cost of the worst index this engine builds. Phase 17
     * spent a version on both halves: the entry is 16 bytes because the term offset and length are
     * gone, and the term is front-coded behind restart points.
     *
     * Asserted **exactly**, because that is the only form in which the claim is falsifiable. A change
     * that made a singleton cost four bytes again, or that quietly stopped front-coding across a
     * restart, would return every right answer and show up only in a benchmark nobody ran.
     */
    @Test
    fun `a unique-valued index costs the entry and the front-coded term, and nothing else`() {
        // The 22 bytes phase 11's argument rested on: a header, one directory entry and a one-value
        // array container. Kept because every document that cites the figure is citing this.
        assertEquals(22, Bitmap.of(0).encodedByteSize(), "a bitmap over one ordinal")

        val terms = (0 until 50).map { IndexTerm.ofNumber(it.toLong()).bytes }
            .sortedWith(java.util.Arrays::compareUnsigned)
        val builder = PostingBuilder(maxTerms = 1024)
        terms.forEachIndexed { ordinal, term -> builder.add(term, ordinal) }
        val bytes = builder.build(3, 1, "$.id", 50, 700)

        val writer = IndexWriter()
        // The front-coded region, computed the way the format defines it and not the way the writer
        // happens to have written it: a restart shares nothing, everything else shares its prefix
        // with the term before it.
        var termRegion = 0
        var previous = ByteArray(0)
        terms.forEachIndexed { index, term ->
            val restart = index % IndexFormat.POSTING_TERM_RESTART_INTERVAL == 0
            val shared = if (restart) 0 else sharedPrefix(previous, term)
            termRegion += writer.varintSize(shared) + writer.varintSize(term.size - shared) + (term.size - shared)
            previous = term
        }

        val path = "$.id".encodeToByteArray().size
        val presenceLength = readU32(bytes, IndexFormat.POSTING_PRESENCE_OFFSET + 4)
        val expected = IndexFormat.POSTING_HEADER_BYTES + 4 + path +
            terms.size * IndexFormat.POSTING_V2_TERM_ENTRY_BYTES +
            IndexFormat.postingRestartCount(terms.size) * 4 +
            termRegion +
            presenceLength
        assertEquals(
            expected,
            bytes.size,
            "a file of singletons is its header, its directory, its restarts, its terms and its presence bitmap",
        )

        // The version-1 file, built beside it, is the control. This is the phase's claim measured on
        // a file rather than quoted from a document.
        val legacy = LegacyPostingBuilder()
        terms.forEachIndexed { ordinal, term -> legacy.add(term, ordinal) }
        assertTrue(
            bytes.size < legacy.build(3, 1, "$.id", 50, 700).size,
            "a version-2 dictionary must be smaller than the version-1 one it replaces",
        )
    }

    /**
     * Front-coding is not free, and the crossover is a fact about term length rather than a detail.
     *
     * A front-coded record costs `2 + (length - shared)` bytes at the lengths real terms have — two
     * varint headers where version 1 spent none, because version 1 put the length in the directory
     * entry it was paying 24 bytes for anyway. So the term *region* only shrinks once the average
     * shared prefix exceeds two bytes, and for a path whose values are two-byte terms it grows.
     *
     * That is worth pinning rather than hiding, for the reason `.claude/rules/index-sidecar-format.md`
     * gives about attributing a benchmark win to the wrong mechanism: **phase 17's saving is the
     * 8-byte entry, always, and the term region only sometimes.** A reader who believed the headline
     * was front-coding would predict
     * the wrong thing for a low-cardinality index over short values — and would be tempted to make the
     * choice adaptive, which is exactly what phase 11 established must not happen to a dictionary
     * whose byte identity the suites compare as files.
     *
     * Both directions are asserted, because a test of only the favourable one would let the
     * unfavourable one become silently much worse.
     */
    @Test
    fun `front-coding pays above short terms, and the entry narrowing pays regardless`() {
        // Terms of two and three bytes: the shared prefix cannot cover the two varint headers.
        val short = (0 until 50).map { IndexTerm.ofNumber(it.toLong()).bytes }
        // Terms with a long common prefix, which is the ordinary shape of an indexed identifier.
        val long = (0 until 50).map { IndexTerm.ofString("urn:example:catalogue:sku:%08d".format(it)).bytes }

        assertTrue(termRegionBytes(short) > short.sumOf { it.size }, "short terms: front-coding costs a little")
        assertTrue(termRegionBytes(long) < long.sumOf { it.size } / 2, "long terms: front-coding saves most of it")

        // And in both cases the file is smaller, because the entry lost eight bytes either way.
        for (terms in listOf(short, long)) {
            val modern = PostingBuilder(maxTerms = 1024)
            val legacy = LegacyPostingBuilder()
            terms.forEachIndexed { ordinal, term ->
                modern.add(term, ordinal)
                legacy.add(term, ordinal)
            }
            assertTrue(
                modern.build(3, 1, "$.id", 50, 700).size < legacy.build(3, 1, "$.id", 50, 700).size,
                "version 2 must be smaller whatever the terms look like",
            )
        }
    }

    /** What the front-coded region costs for [terms], computed from the format rather than the writer. */
    private fun termRegionBytes(terms: List<ByteArray>): Int {
        val writer = IndexWriter()
        val sorted = terms.sortedWith(java.util.Arrays::compareUnsigned)
        var total = 0
        var previous = ByteArray(0)
        sorted.forEachIndexed { index, term ->
            val shared =
                if (index % IndexFormat.POSTING_TERM_RESTART_INTERVAL == 0) 0 else sharedPrefix(previous, term)
            total += writer.varintSize(shared) + writer.varintSize(term.size - shared) + (term.size - shared)
            previous = term
        }
        return total
    }

    private fun open3(bytes: ByteArray): PostingFile =
        PostingFile.open(MemorySegment.ofArray(bytes), bytes.size, "0000000003.0001.pst", 3, 1, "$.id", 700)

    /** The encoding byte of term entry [index], read straight out of the directory. */
    private fun encodingAt(bytes: ByteArray, index: Int): Int {
        val directoryOffset = readU32(bytes, 44)
        return bytes[directoryOffset + index * IndexFormat.POSTING_V2_TERM_ENTRY_BYTES + 8].toInt() and 0xFF
    }
}
