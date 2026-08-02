package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.catalog.IndexKind
import app.oreshkov.rabosh.testkit.property.Gen
import app.oreshkov.rabosh.testkit.property.RandomSource
import app.oreshkov.rabosh.testkit.property.forAll
import app.oreshkov.rabosh.variant.Variant
import java.lang.foreign.MemorySegment
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A column built without a store, so the encoding can be checked directly. */
internal class ColumnFixture(
    /** `ordinal to json` for every value, in ordinal order. */
    values: List<Pair<Int, String>>,
    documentCount: Int = (values.maxOfOrNull { it.first } ?: -1) + 1,
    options: IndexOptions = IndexOptions.DEFAULT,
) {
    val bytes: ByteArray?
    val file: ColumnFile?

    init {
        val builder = ColumnBuilder(options)
        for ((ordinal, json) in values) builder.add(ordinal, Variant.fromJson(json))
        bytes = builder.build(7, 3, "$.p", documentCount, 900)
        file = bytes?.let {
            ColumnFile.open(MemorySegment.ofArray(it), it.size, "0000000007.0003.col", 7, 3, "$.p", 900)
        }
    }

    fun require(): ColumnFile = file ?: error("the fixture produced no column")
}

/**
 * The column file, checked without a store.
 *
 * Two properties here are the ones whose violation is a **wrong answer rather than a fault**, and
 * neither is visible to a differential test: a bound that narrows, and a null filler that leaks into
 * a bound. Both get direct assertions.
 */
class ColumnFileTest {

    private fun numbers(count: Int, at: (Int) -> String): List<Pair<Int, String>> =
        (0 until count).map { it to at(it) }

    // --- the quadrant ------------------------------------------------------------------------------

    @Test
    fun `presence, residual, starts and nulls describe the quadrant`() {
        val column = ColumnFixture(
            listOf(
                0 to "1",
                1 to "null",
                2 to "\"text\"", // not shreddable into a numeric column: ordinal 2 is residual
                3 to "4",
                3 to "5", // one ordinal, two values
                // ordinal 4 has no value at all: absent
            ),
            documentCount = 5,
        ).require()

        assertEquals(listOf(0, 1, 2, 3), column.presence().toIntArray().toList())
        assertEquals(listOf(2), column.residual().toIntArray().toList())
        assertEquals(listOf(0, 1, 3), column.shredded().toIntArray().toList())
        // presence = shredded + residual, exactly. The partition §7's quadrant depends on.
        assertEquals(
            column.presence().cardinality,
            column.shredded().cardinality + column.residual().cardinality,
        )

        // Four values are stored: ordinals 0, 1 and 3's two. Ordinal 2 contributes none.
        assertEquals(4, column.valueCount)
        assertEquals(listOf(0, 1, 2), column.starts().toIntArray().toList())
        assertEquals(listOf(1), column.nulls().toIntArray().toList(), "a null takes a slot")

        assertEquals(0 until 1, column.valueRange(0))
        assertEquals(1 until 2, column.valueRange(1))
        assertNull(column.valueRange(2), "a residual ordinal has no values here")
        assertEquals(2 until 4, column.valueRange(3))
        assertNull(column.valueRange(4), "an absent ordinal has none either")
        column.verify()
    }

    @Test
    fun `repetition is exercised at its boundaries`() {
        // 0, 1, 2 and many values per ordinal. The one-ordinal-with-two case is what turns a
        // run-encoded STARTS bitmap from a single run into three, so it is tested at the value.
        for (extra in listOf(0, 1, 2, 16)) {
            val values = ArrayList<Pair<Int, String>>()
            values.add(0 to "1")
            repeat(extra) { values.add(1 to "${it + 2}") }
            values.add(2 to "99")
            val column = ColumnFixture(values, documentCount = 3).require()
            column.verify()
            assertEquals(if (extra == 0) 2 else 3, column.shredded().cardinality)
            assertEquals(2 + extra, column.valueCount)
        }
    }

    @Test
    fun `a column with nothing shreddable is not built at all`() {
        // Not covered rather than covered and empty: writing an empty column would claim coverage it
        // does not have, which is the one thing derived data must never do.
        assertNull(ColumnFixture(listOf(0 to "null", 1 to "{\"x\":1}")).bytes)
        assertNull(ColumnFixture(emptyList(), documentCount = 4).bytes)
    }

    // --- bounds ------------------------------------------------------------------------------------

    @Test
    fun `a null filler never reaches a bound`() {
        // The trap this exists for: a null slot holds the type's zero, and a zero leaking into a
        // block's minimum widens it. The result is still *correct* — a wider bound skips less — so no
        // differential test would ever notice; the column would simply stop pruning, quietly.
        val column = ColumnFixture(numbers(40) { if (it < 39) "null" else "1000" }).require()
        column.verify()
        assertTrue(
            column.blockMayContainNumeric(0, BigDecimal("999"), BigDecimal("1001")),
            "the one real value must be inside the block bound",
        )
        assertTrue(
            !column.blockMayContainNumeric(0, BigDecimal("-5"), BigDecimal("5")),
            "the null filler's zero must not have widened the bound down to 0",
        )
    }

    @Test
    fun `every value lies inside the segment and block bounds it is covered by`() {
        forAll(columnCorpus()) { corpus ->
            val fixture = ColumnFixture(corpus)
            val column = fixture.file ?: return@forAll
            column.verify()

            // Bounds must never narrow. A bound that excluded a value it covers would let a query
            // decide a segment cannot hold a match it does hold — the one failure a statistic must
            // never cause. Residual values count too: they are outside the *column* but inside the
            // *segment*, and it is the segment bound a query prunes on first.
            for ((_, json) in corpus) {
                val value = Variant.fromJson(json)
                when (value.kind) {
                    app.oreshkov.rabosh.variant.VariantKind.INTEGER ->
                        assertNumericInside(column, BigDecimal.valueOf(value.longValue()), json)

                    app.oreshkov.rabosh.variant.VariantKind.DECIMAL ->
                        assertNumericInside(column, value.decimalValue(), json)

                    app.oreshkov.rabosh.variant.VariantKind.STRING -> {
                        val bytes = value.stringValue().encodeToByteArray()
                        assertTrue(
                            column.bounds.mayContainText(bytes, bytes),
                            "the segment text bound excludes $json: ${column.bounds}",
                        )
                    }

                    else -> Unit
                }
            }
        }
    }

    private fun assertNumericInside(column: ColumnFile, value: BigDecimal, note: String) {
        assertTrue(
            column.bounds.mayContainNumeric(value, value),
            "the segment numeric bound excludes $note: ${column.bounds}",
        )
    }

    // --- identity ----------------------------------------------------------------------------------

    @Test
    fun `a column whose identity disagrees with the caller is refused`() {
        val bytes = ColumnFixture(numbers(8) { "$it" }).bytes!!
        fun open(segment: Long, id: Int, path: String, sequence: Long) =
            ColumnFile.open(MemorySegment.ofArray(bytes), bytes.size, "x.col", segment, id, path, sequence)

        assertFailsWithMessage("describes segment 7") { open(8, 3, "$.p", 900) }
        assertFailsWithMessage("describes index #3") { open(7, 4, "$.p", 900) }
        assertFailsWithMessage("the registry says") { open(7, 3, "$.other", 900) }
        assertFailsWithMessage("its base sidecar says") { open(7, 3, "$.p", 901) }
    }

    @Test
    fun `the same values always produce the same bytes`() {
        // Byte identity depends on the type choice being a pure function of the value multiset. A
        // decision that leaned on iteration order would break a flush against a backfill without
        // breaking any equality assertion.
        val corpus = numbers(60) { if (it % 4 == 0) "$it.25" else "$it" }
        assertTrue(ColumnFixture(corpus).bytes!!.contentEquals(ColumnFixture(corpus).bytes!!))
    }
}

/** Column format ids, pinned. Every one is on disk and `CLAUDE.md`'s rule is add, never renumber. */
class ColumnFormatIdTest {

    @Test
    fun `the magic is JKDB-COL and distinct from every other`() {
        assertEquals("JKDB-COL", ColumnFormat.MAGIC.decodeToString())
        val all = listOf("JKDB-WAL", "JKDB-SEG", "JKDB-MAN", "JKDB-CAT", "JKDB-IXR", "JKDB-IDX", "JKDB-PST", "JKDB-COL")
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun `section kinds are this file's own namespace, starting at one`() {
        assertEquals(1, ColumnFormat.SECTION_META)
        assertEquals(2, ColumnFormat.SECTION_PRESENCE)
        assertEquals(3, ColumnFormat.SECTION_RESIDUAL)
        assertEquals(4, ColumnFormat.SECTION_STARTS)
        assertEquals(5, ColumnFormat.SECTION_NULLS)
        assertEquals(6, ColumnFormat.SECTION_VALUES)
        assertEquals(7, ColumnFormat.SECTION_STATS)
        // Added in phase 12 as a new kind on a format that already existed, which is why VERSION is
        // still 1 below: the directory is fixed-width and carries each extent, so a build that does
        // not know this kind skips it rather than refusing the file.
        assertEquals(8, ColumnFormat.SECTION_FIDELITY)
        assertEquals("FIDELITY", ColumnFormat.sectionName(8))
        assertEquals(1, ColumnFormat.FIDELITY_EXACT_VALUES)
        assertNull(ColumnFormat.sectionName(9))
        // Deliberately *not* a continuation of the base sidecar's, whose reserved slot 4 still means
        // what it always meant. The framing is shared; the vocabulary is not.
        assertEquals("COLUMN", IndexFormat.sectionKindName(IndexFormat.SECTION_KIND_COLUMN))
    }

    @Test
    fun `column types and stats encodings are permanent`() {
        assertEquals(1, ColumnFormat.COLUMN_TYPE_INT64)
        assertEquals(2, ColumnFormat.COLUMN_TYPE_DECIMAL32)
        assertEquals(3, ColumnFormat.COLUMN_TYPE_DECIMAL64)
        assertEquals(4, ColumnFormat.COLUMN_TYPE_DECIMAL128)
        assertEquals(5, ColumnFormat.COLUMN_TYPE_BOOLEAN)
        assertEquals(6, ColumnFormat.COLUMN_TYPE_STRING)
        assertEquals(7, ColumnFormat.COLUMN_TYPE_DOUBLE)
        // Reserved means reserved: this build must refuse them rather than guess.
        assertTrue(!ColumnFormat.isSupported(ColumnFormat.COLUMN_TYPE_DECIMAL128))
        assertTrue(!ColumnFormat.isSupported(ColumnFormat.COLUMN_TYPE_DOUBLE))
        assertNull(ColumnFormat.columnTypeName(8))

        assertEquals(1, ColumnFormat.STATS_TYPED)
        assertEquals(2, ColumnFormat.STATS_PREFIX)
        assertNull(ColumnFormat.statsEncodingName(3))
    }

    @Test
    fun `the block shift is a constant, and the bound tags match the sketch sidecar's`() {
        assertEquals(13, ColumnFormat.COLUMN_BLOCK_SHIFT)
        assertEquals(8192, ColumnFormat.COLUMN_BLOCK_VALUES)
        assertEquals(0, ColumnFormat.blockCount(0))
        assertEquals(1, ColumnFormat.blockCount(1))
        assertEquals(1, ColumnFormat.blockCount(8192))
        assertEquals(2, ColumnFormat.blockCount(8193))

        assertEquals(0, ColumnFormat.BOUND_NONE)
        assertEquals(1, ColumnFormat.BOUND_NUMERIC)
        assertEquals(2, ColumnFormat.BOUND_TEXT)
    }

    @Test
    fun `column filenames round-trip and do not claim other names`() {
        assertEquals("0000000042.0007.col", columnFileName(42, 7))
        assertEquals(42L to 7, columnNumbers(columnFileName(42, 7)))
        assertNull(columnNumbers(postingFileName(42, 7)))
        assertNull(columnNumbers(baseFileName(42)))
        assertNull(postingNumbers(columnFileName(42, 7)))
    }

    @Test
    fun `the index kind is the one the registry already reserved`() {
        assertEquals(2, IndexFormat.indexKindId(IndexKind.SHREDDED_COLUMN))
        assertEquals(IndexKind.SHREDDED_COLUMN, IndexFormat.indexKindOfId(2))
    }
}

/** Mixed-type corpora: mostly numbers, with decimals, strings, nulls and repeats scattered through. */
private fun columnCorpus(): Gen<List<Pair<Int, String>>> = object : Gen<List<Pair<Int, String>>> {
    override fun generate(source: RandomSource): List<Pair<Int, String>> {
        val ordinals = source.nextInt(1..60)
        val out = ArrayList<Pair<Int, String>>()
        for (ordinal in 0 until ordinals) {
            val repeats = if (source.nextInt(0..9) == 0) source.nextInt(2..4) else 1
            repeat(repeats) {
                val roll = source.nextInt(0..99)
                val json = when {
                    roll < 55 -> source.nextLong(-100_000L..100_000L).toString()
                    roll < 80 -> BigDecimal.valueOf(source.nextLong(-100_000L..100_000L), 2).toPlainString()
                    roll < 90 -> "\"s${source.nextInt(0..999)}\""
                    roll < 96 -> "null"
                    else -> "true"
                }
                out.add(ordinal to json)
            }
        }
        return out
    }

    override fun shrink(value: List<Pair<Int, String>>): Sequence<List<Pair<Int, String>>> = sequence {
        if (value.size > 1) yield(value.take(1))
        if (value.size > 2) yield(value.take(value.size / 2))
        if (value.size > 1) yield(value.dropLast(1))
    }

    override val edgeCases: List<List<Pair<Int, String>>> = listOf(
        listOf(0 to "0"),
        listOf(0 to "null", 1 to "1"),
        listOf(0 to "\"a\"", 1 to "1"),
        listOf(0 to "1", 0 to "\"a\""),
        listOf(0 to "1.5", 1 to "2", 2 to "3.25"),
    )

    override fun render(value: List<Pair<Int, String>>): String = "${value.size} value(s): " +
        value.take(8).joinToString { "${it.first}=${it.second}" }
}
