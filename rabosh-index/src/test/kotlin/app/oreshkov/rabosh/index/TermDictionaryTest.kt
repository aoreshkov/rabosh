package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.testkit.property.Gen
import app.oreshkov.rabosh.testkit.property.forAll
import app.oreshkov.rabosh.testkit.property.list
import app.oreshkov.rabosh.testkit.property.string
import java.lang.foreign.MemorySegment
import java.util.Arrays
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two dictionary layouts, compared against each other.
 *
 * Phase 17 gave `.pst` a second way to store its terms, so there are now two implementations of "the
 * *i*-th term" and "where is this term". `IndexFormat`'s KDoc claims they differ in *how a term is
 * found and in nothing else* — same order, same bytes, same `-(insertionPoint + 1)` convention on a
 * miss — and that is a claim a comment cannot make. This makes it, over the same terms, in both
 * layouts, for every index and every probe.
 *
 * It is the phase-8 differential rule applied to a format change: an index may change query speed,
 * never query answers, and a *dictionary* may change how many bytes a term costs, never which term
 * comes back. The instrument is the same one the bitmap uses against `java.util.BitSet` — a second
 * implementation, not a second run of the first.
 *
 * The threshold cases are arranged rather than hoped for, because
 * [IndexFormat.POSTING_TERM_RESTART_INTERVAL] is exactly the kind of boundary the repo's rule is
 * about: 15, 16 and 17 terms are three different shapes of restart array, and a test that only checked
 * "a few" and "lots" would not be testing the interval at all.
 */
class TermDictionaryTest {

    private fun terms(values: List<String>): List<ByteArray> =
        values.distinct().map { IndexTerm.ofString(it).bytes }.sortedWith(Arrays::compareUnsigned)

    /** The same postings in both layouts, opened and ready to be asked the same questions. */
    private fun both(values: List<ByteArray>): Pair<PostingFile, PostingFile> {
        val documentCount = maxOf(values.size, 1)
        val modern = PostingBuilder(maxTerms = 1 shl 16)
        val legacy = LegacyPostingBuilder()
        values.forEachIndexed { ordinal, term ->
            modern.add(term, ordinal)
            legacy.add(term, ordinal)
        }
        if (values.isEmpty()) {
            // A dictionary with no terms is a real state — a path whose every value was a null or a
            // container — and its restart array is empty rather than absent.
            modern.addPresenceOnly(0)
            legacy.addPresenceOnly(0)
        }
        val a = modern.build(7, 2, "$.p", documentCount, 400)
        val b = legacy.build(7, 2, "$.p", documentCount, 400)
        assertEquals(
            IndexFormat.POSTING_VERSION,
            readU32(a, 8),
            "the engine must write the current version and only the current version",
        )
        assertEquals(IndexFormat.POSTING_VERSION_FLAT, readU32(b, 8), "the fixture must write version 1")
        return open(a) to open(b)
    }

    private fun open(bytes: ByteArray): PostingFile =
        PostingFile.open(MemorySegment.ofArray(bytes), bytes.size, "0000000007.0002.pst", 7, 2, "$.p", 400)

    /**
     * Every question either dictionary can be asked, asked of both.
     *
     * The misses matter as much as the hits and are easier to get subtly wrong: a bisect that returns
     * the right answer for a present term can still disagree about *where an absent one would go*, and
     * `PostingFile.postings` only looks at the sign — so a wrong insertion point would be invisible
     * from the outside until something needed a range, which is exactly how a latent format bug
     * survives a release.
     */
    private fun assertAgree(values: List<ByteArray>) {
        val (modern, legacy) = both(values)
        assertEquals(legacy.termCount, modern.termCount, "term counts")
        assertEquals(values.size, modern.termCount)

        for (index in 0 until modern.termCount) {
            assertContentEquals(legacy.termAt(index), modern.termAt(index), "term $index")
            assertContentEquals(values[index], modern.termAt(index), "term $index is the one that went in")
        }

        for (term in values) {
            assertContentEquals(
                legacy.postings(term)?.toIntArray(),
                modern.postings(term)?.toIntArray(),
                "postings for ${term.decodeToString()}",
            )
        }

        // Absent terms, including ones that sort before everything, after everything, and into the
        // middle — the three places an insertion point can land.
        val absent = buildList {
            add(IndexTerm.ofString("").bytes)
            add(IndexTerm.ofNumber(0L).bytes)
            add(IndexTerm.ofBoolean(true).bytes)
            add(byteArrayOf(0xFF.toByte(), 0xFF.toByte()))
            for (term in values) add(term + byteArrayOf(0))
        }
        for (term in absent.filterNot { candidate -> values.any { it.contentEquals(candidate) } }) {
            assertEquals(
                dictionarySearch(legacy, term),
                dictionarySearch(modern, term),
                "insertion point for an absent term",
            )
            assertTrue(modern.postings(term) == null, "an absent term has no postings")
        }

        modern.verify()
        legacy.verify()
    }

    /** `search` is not on `PostingFile`'s surface, so it is reached the way a reader reaches it. */
    private fun dictionarySearch(file: PostingFile, term: ByteArray): Int {
        // A bisect over the public `termAt` reproduces the convention exactly, and comparing *that*
        // against both files is what makes the assertion about the convention rather than about one
        // implementation.
        var low = 0
        var high = file.termCount - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val comparison = Arrays.compareUnsigned(file.termAt(middle), term)
            when {
                comparison < 0 -> low = middle + 1
                comparison > 0 -> high = middle - 1
                else -> return middle
            }
        }
        return -(low + 1)
    }

    @Test
    fun `the restart interval is tested at the value, not near it`() {
        // 15 is one short group, 16 is exactly one full group, 17 is a full group and a group of one —
        // three different restart arrays, and the third is the one an off-by-one produces an empty
        // final group for.
        for (count in listOf(0, 1, 2, 15, 16, 17, 31, 32, 33, 255, 256, 257)) {
            assertAgree(terms((0 until count).map { "term-%04d".format(it) }))
        }
    }

    @Test
    fun `a term sharing everything with its predecessor and one sharing nothing`() {
        assertAgree(
            terms(
                listOf(
                    // A strict prefix of the next: shared is the whole of the shorter term.
                    "a", "aa", "aaa", "aaaa",
                    // Nothing in common with what precedes it.
                    "zzzzzzzzzzzzzzzz",
                    // And a pair differing only in the last byte, across a restart boundary once the
                    // list above has pushed the count past sixteen.
                    "prefix-0000000000", "prefix-0000000001",
                ) + (0 until 20).map { "filler-%03d".format(it) },
            ),
        )
    }

    @Test
    fun `an empty dictionary agrees, and is not confused with a missing one`() {
        val (modern, legacy) = both(emptyList())
        assertEquals(0, modern.termCount)
        assertEquals(0, legacy.termCount)
        assertTrue(modern.postings(IndexTerm.ofString("anything").bytes) == null)
        // Presence is still real: a path every one of whose values was a null has no terms and is
        // very much present, which is the distinction `addPresenceOnly` exists for.
        assertContentEquals(intArrayOf(0), modern.presence().toIntArray())
        modern.verify()
    }

    @Test
    fun `the two dictionaries agree over generated terms`() {
        forAll(Gen.list(Gen.string(lengths = 0..12), sizes = 0..80)) { values ->
            assertAgree(terms(values))
        }
    }

    /**
     * Front-coding is a pure function of the sorted sequence, so insertion order cannot reach it.
     *
     * The same claim `PostingEncodingTest` makes about the singleton encoding, and it has to be made
     * again here for a different reason: that one is a property of each posting *list*, this one is a
     * property of each term's *neighbour*. A builder that emitted shared prefixes against the last
     * term it happened to see rather than against the last term in order would still answer every
     * query correctly, and would break byte identity between a flush-written sidecar and a
     * backfill-rebuilt one — silently, and only on some corpora.
     */
    @Test
    fun `front-coding does not depend on insertion order`() {
        val values = terms((0 until 100).map { "sku-%05d".format(it * 7 % 100) })

        val forwards = PostingBuilder(maxTerms = 1 shl 16)
        values.forEachIndexed { ordinal, term -> forwards.add(term, ordinal) }

        val backwards = PostingBuilder(maxTerms = 1 shl 16)
        for (ordinal in values.indices.reversed()) backwards.add(values[ordinal], ordinal)

        assertContentEquals(
            forwards.build(7, 2, "$.p", values.size, 400),
            backwards.build(7, 2, "$.p", values.size, 400),
            "the same terms must front-code to the same bytes whatever order they arrived in",
        )
    }
}
