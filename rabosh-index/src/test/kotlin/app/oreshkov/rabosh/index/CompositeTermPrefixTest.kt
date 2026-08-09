package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.catalog.CatalogPath
import app.oreshkov.rabosh.variant.Variant
import java.lang.foreign.MemorySegment
import java.util.Arrays
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Whether a composite term can be scanned by prefix. It cannot, and this is why.**
 *
 * A conjecture worth checking and worth recording as refused: a composite term writes its fields in
 * declaration order, so a query fixing a *prefix* of the declared fields looks answerable by a range
 * scan over the term dictionary — no new index kind, no id, no version, exactly the shape element
 * decomposition turned out to have. Two premises were named for it. Both are **true**. The feature is
 * still unsound, because of a third nobody wrote down.
 *
 * | premise | holds? | pinned by |
 * |---|---|---|
 * | a sub-tuple is a byte prefix of the tuple extending it | **yes** | the first test |
 * | the dictionary can find that prefix's run without walking every term | **yes** | the second |
 * | the tuples are a *complete* record of the sub-tuple | **no** | the third |
 * | the next field could carry a range | **no, for numbers** | the fourth |
 *
 * **The third is the one that decides it, and it is the exactness argument arriving from behind.** A
 * composite term exists only for an element carrying *every* declared field — which is precisely what
 * makes a full-tuple lookup exact, and precisely what makes a partial one lossy. An element with
 * `sku = "A"` and no `qty` satisfies `elemMatch(p, sku eq "A")` and contributes no term at all, so the
 * prefix run is a **subset** of the answer rather than a superset. An index may be wider than the
 * truth and never narrower; a source that returns a subset cannot be used as candidates, cannot be
 * rescued by a recheck, and is a document silently missing from a result.
 *
 * These tests are therefore assertions about a feature that will not be built. They are here because
 * the conjecture is a natural one to have twice, and because each of the four facts is load-bearing
 * for something that *does* exist: the first two describe the dictionary a lookup already uses, the
 * third is the exactness `elemMatch` rests on, and the fourth is why an inverted index cannot answer
 * a range.
 */
class CompositeTermPrefixTest {

    /**
     * Premise one: the leading fields of a tuple are a byte prefix of it, and cannot alias.
     *
     * Self-delimiting is the half that matters and the half a hash would have destroyed. Each field
     * is `index | length | signature` with both header fields fixed-width, so a term carrying a given
     * prefix carries *exactly* those leading values — `sku = "A"` is not a prefix of a tuple whose
     * `sku` is `"AB"`, because the length is in the way. Without that, a prefix scan would not merely
     * be lossy, it would be wrong in the other direction too.
     */
    @Test
    fun `a sub-tuple is a byte prefix of the tuple that extends it, and does not alias`() {
        val leading = term(text("A"))
        val whole = term(text("A"), number(5))

        assertTrue(whole.size > leading.size, "the tuple extends its own prefix")
        assertContentEquals(
            leading.toList(),
            whole.copyOf(leading.size).toList(),
            "the leading field's record is written first and unchanged, which is what makes it a prefix",
        )

        assertFalse(
            startsWith(term(text("AB"), number(5)), leading),
            "a longer value must not carry a shorter one's prefix; the length header is what stops it",
        )
        assertFalse(
            startsWith(term(text("B"), number(5)), leading),
            "and neither must a different value",
        )
    }

    /**
     * Premise two: the run sharing a prefix is contiguous, and a bisect finds it.
     *
     * Contiguity is a property of unsigned lexicographic order and the dictionary is sorted in it —
     * `PostingBuilder`'s `TreeMap` uses `Arrays::compareUnsigned` and `PostingFile.verify` reports any
     * term that does not follow its predecessor. The cost claim is asserted in **probes** rather than
     * on a clock, for the reason every cost claim in this repository is: a bisect over 128 terms takes
     * eight probes and a walk takes 128, and only one of those two numbers is a fact about the
     * algorithm.
     */
    @Test
    fun `the terms sharing a prefix are contiguous and a bisect finds where they start`() {
        val file = build(SKUS.flatMap { sku -> QUANTITIES.map { qty -> term(text(sku), number(qty)) } })
        assertEquals(SKUS.size * QUANTITIES.size, file.termCount, "the fixture must be worth bisecting")

        val prefix = term(text("B"))
        val matching = (0 until file.termCount).filter { startsWith(file.termAt(it), prefix) }
        assertEquals(QUANTITIES.size, matching.size, "every tuple whose leading field is B, and no others")
        assertEquals(
            matching.first()..matching.last(),
            matching.first()..matching.first() + matching.size - 1,
            "and they are one contiguous run, which is what a range scan needs",
        )

        var probes = 0
        var low = 0
        var high = file.termCount
        while (low < high) {
            val middle = (low + high) ushr 1
            probes++
            if (Arrays.compareUnsigned(file.termAt(middle), prefix) < 0) low = middle + 1 else high = middle
        }
        assertEquals(matching.first(), low, "the bisect lands on the first term of the run")
        assertTrue(
            probes <= CEILING_LOG2_OF_FIXTURE,
            "finding the run must cost a bisect and not a walk: $probes probes over ${file.termCount} terms",
        )
    }

    /**
     * **The premise nobody named, and the one that refuses the feature.**
     *
     * Three elements, one of them missing the second declared field. It contributes no term, so it is
     * absent from the prefix run — and from the presence bitmap too, which is what rules out the
     * obvious repair of unioning the run with presence. The document it belongs to nevertheless
     * satisfies a predicate over the leading field alone, so a plan built on the run would return a
     * subset of the answer.
     *
     * **The three documents go through the writer's own two steps** — `ElementExtractor` to reach the
     * elements, a nested `TermExtractor` to pull the declared fields out of each, `CompositeTerm.of`
     * to key them — rather than being asserted into the builder by hand. That is the difference
     * between demonstrating the loss and stipulating it: the middle document is dropped by the code
     * that writes every composite index, and this test would stop failing the moment that stopped
     * being true.
     */
    @Test
    fun `an element missing a later declared field is absent from the prefix run entirely`() {
        assertNull(
            CompositeTerm.of(listOf(text("A"), null), OPTIONS),
            "a partial tuple is not keyed — which is exactly what makes a full lookup exact",
        )

        val builder = PostingBuilder(1 shl 20)
        var keyed = 0
        for ((ordinal, json) in DOCUMENTS.withIndex()) {
            // `IndexCatalog.observeElements`, transcribed: every element of the path, every declared
            // field of the element, and a term only where the tuple is whole.
            elements.extract(Variant.fromJson(json)) { _, element ->
                val values = arrayOfNulls<Variant>(2)
                fields.extract(element) { index, value -> values[index] = value }
                val term = CompositeTerm.of(values.asList(), OPTIONS)
                if (term != null) {
                    builder.add(term, ordinal)
                    keyed++
                }
            }
        }
        assertEquals(
            DOCUMENTS.size - 1,
            keyed,
            "exactly one of the three elements must be dropped, or this test is about a fixture rather " +
                "than about the writer",
        )
        val file = open(
            builder.build(SEGMENT, INDEX_ID, PATH, DOCUMENT_COUNT, SEQUENCE, IndexFormat.INDEX_KIND_COMPOSITE_TERM),
        )

        val prefix = term(text("A"))
        val run = Bitmap()
        for (index in 0 until file.termCount) {
            if (startsWith(file.termAt(index), prefix)) run.orWith(file.postingAt(index))
        }

        assertTrue(run.contains(COMPLETE_ORDINAL), "the element with the whole tuple is found")
        assertFalse(
            run.contains(PARTIAL_ORDINAL),
            "and the one with only the leading field is not — so the run is a SUBSET of the documents " +
                "whose element carries sku = A, which is a missing answer rather than a slow one",
        )
        assertFalse(
            file.presence().contains(PARTIAL_ORDINAL),
            "presence cannot repair it either: a composite index's presence means *a complete tuple*, " +
                "so unioning the run with it would still miss this ordinal",
        )
    }

    /**
     * The other half of the conjecture, and it fails for a reason already written down.
     *
     * "A prefix by equality plus a **range** on the next field" needs the signatures to sort by value.
     * They do not: a number's signature is its tag and its plain decimal text, so `10` precedes `9`.
     * `IndexTerm`'s own documentation says so, and this is the assertion behind that sentence. Text
     * does order by code point, which is why the failure is a family at a time rather than wholesale —
     * and a range answered for strings and silently wrong for numbers is worse than one answered for
     * neither.
     */
    @Test
    fun `the term order is a lookup order, so the next field could not carry a numeric range`() {
        assertTrue(
            IndexTerm.ofNumber(10L) < IndexTerm.ofNumber(9L),
            "a numeric signature is decimal text, so byte order is not value order",
        )
        assertTrue(IndexTerm.ofString("a") < IndexTerm.ofString("b"), "text does order by code point")
        assertTrue(
            IndexTerm.ofNumber(2L) < IndexTerm.ofString("a"),
            "and the tag orders the families apart, which is what type bracketing reads",
        )
    }

    // --- fixture ----------------------------------------------------------------------------------

    private fun term(vararg values: Variant): ByteArray = checkNotNull(CompositeTerm.of(values.toList(), OPTIONS))

    private fun text(value: String): Variant = Variant.fromJson("\"$value\"")

    private fun number(value: Int): Variant = Variant.fromJson("$value")

    private fun startsWith(term: ByteArray, prefix: ByteArray): Boolean =
        term.size >= prefix.size && Arrays.equals(term, 0, prefix.size, prefix, 0, prefix.size)

    private fun build(terms: List<ByteArray>): PostingFile {
        val builder = PostingBuilder(1 shl 20)
        for ((ordinal, term) in terms.withIndex()) builder.add(term, ordinal)
        return open(
            builder.build(SEGMENT, INDEX_ID, PATH, terms.size, SEQUENCE, IndexFormat.INDEX_KIND_COMPOSITE_TERM),
        )
    }

    private fun open(bytes: ByteArray): PostingFile =
        PostingFile.open(MemorySegment.ofArray(bytes), bytes.size, FILE, SEGMENT, INDEX_ID, PATH, SEQUENCE)

    private companion object {
        val OPTIONS = IndexOptions.DEFAULT

        const val SEGMENT = 4L
        const val INDEX_ID = 2
        const val PATH = "$.items[*]"
        const val SEQUENCE = 500L
        const val FILE = "0000000004.0002.pst"

        val SKUS = listOf("A", "B", "C", "D", "E", "F", "G", "H")
        val QUANTITIES = (1..16).toList()

        /** `ceil(log2(128)) + 1`, which is what a bisect over the fixture is allowed to cost. */
        const val CEILING_LOG2_OF_FIXTURE = 8

        /** The writer's two walks, over the path and the fields a composite index would declare. */
        val elements = ElementExtractor(listOf(CatalogPath.parse(PATH)), OPTIONS)
        val fields = TermExtractor(listOf(CatalogPath.parse("$.sku"), CatalogPath.parse("$.qty")), OPTIONS)

        /**
         * Ordinal 1 is the whole point: its element satisfies `sku = "A"` and carries no `qty`.
         *
         * Ordinal 0 is the same `sku` with a complete tuple, so the run is not empty; ordinal 2 is a
         * different `sku`, so the prefix is doing work rather than matching everything.
         */
        val DOCUMENTS = listOf(
            """{"items":[{"sku":"A","qty":1}]}""",
            """{"items":[{"sku":"A"}]}""",
            """{"items":[{"sku":"B","qty":1}]}""",
        )

        const val COMPLETE_ORDINAL = 0
        const val PARTIAL_ORDINAL = 1
        const val DOCUMENT_COUNT = 3
    }
}
