package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.catalog.CatalogPath
import app.oreshkov.rabosh.variant.Variant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a composite term commits to.
 *
 * Four claims, and the first is the one that fails **silently** if it is ever broken: a tuple carries
 * each value's declared position, so two text fields with their values swapped are two different
 * terms. An implementation that concatenated the signatures alone would pass every other test here
 * and would find the wrong elements on any corpus where two fields share a type.
 */
class CompositeTermTest {

    @Test
    fun `swapping two values changes the term`() {
        val forward = term(listOf(text("x"), text("y")))
        val swapped = term(listOf(text("y"), text("x")))

        assertNotEquals(
            forward.toList(),
            swapped.toList(),
            "without the field position in the term, (a=x, b=y) and (a=y, b=x) would be one key",
        )
    }

    @Test
    fun `the same tuple is the same bytes, whatever built it`() {
        // The writer's route: values pulled out of an element. The reader's route: literal terms the
        // planner lowered. They must produce one spelling, or a query could never find what a flush
        // wrote — and nothing but this compares the two.
        val element = Variant.fromJson("""{"sku":"A","qty":5,"note":"ignored"}""")
        val extractor = TermExtractor(listOf(CatalogPath.parse("$.sku"), CatalogPath.parse("$.qty")), OPTIONS)
        val values = arrayOfNulls<Variant>(2)
        extractor.extract(element) { index, value -> values[index] = value }

        val written = checkNotNull(CompositeTerm.of(values.asList(), OPTIONS))
        val queried = checkNotNull(IndexTerm.composite(listOf(IndexTerm.ofString("A"), IndexTerm.ofNumber(5L)), OPTIONS))

        assertEquals(IndexTerm.ofSignature(written), queried, "the writer's term and the planner's must be one term")
        assertContentEquals(
            term(listOf(text("A"), number(5))).toList(),
            written.toList(),
            "and building it from the values directly is the same bytes again",
        )
    }

    @Test
    fun `an element missing a declared field has no term at all`() {
        assertNull(
            CompositeTerm.of(listOf(text("A"), null), OPTIONS),
            "a partial tuple would make a lookup find elements that satisfy only part of the conjunction",
        )
        assertNull(
            CompositeTerm.of(listOf(text("A"), Variant.fromJson("""{"nested":1}""")), OPTIONS),
            "a container has no signature, so it cannot be part of a tuple",
        )
        assertNull(
            CompositeTerm.of(listOf(text("A"), Variant.fromJson("null")), OPTIONS),
            "and neither has a JSON null — the element is present, and it is not keyed",
        )
    }

    /**
     * A tuple too long to key is dropped, and dropped **on both sides**.
     *
     * The bound is what makes storing the tuple whole rather than hashing it a stated cost instead of
     * a hidden one. It is not a wrong answer, because the planner applies the same bound to the same
     * bytes and declines the index, but it is a limit and this is where it is visible.
     */
    @Test
    fun `a tuple above the term bound is not keyed`() {
        val tight = IndexOptions(maxTermBytes = 16)
        assertNull(CompositeTerm.of(listOf(text("a".repeat(64))), tight))
        assertTrue(CompositeTerm.of(listOf(text("ab")), tight) != null, "and a short one still is")
        assertNull(
            IndexTerm.composite(listOf(IndexTerm.ofString("a".repeat(64))), tight),
            "the planner declines exactly what the writer dropped",
        )
    }

    @Test
    fun `a field that is not single-valued within an element is refused`() {
        assertFailsWith<IllegalArgumentException> {
            CompositeTerm.requireSingleValued(listOf(CatalogPath.parse("$.tags[*]")))
        }
        assertFailsWith<IllegalArgumentException> { CompositeTerm.requireSingleValued(emptyList()) }
        assertFailsWith<IllegalArgumentException> { CompositeTerm.requireSingleValued(listOf(CatalogPath.ROOT)) }
        assertFailsWith<IllegalArgumentException> {
            val same = CatalogPath.parse("$.sku")
            CompositeTerm.requireSingleValued(listOf(same, same))
        }
        // The shape that is allowed, so the assertions above are not passing because everything is.
        CompositeTerm.requireSingleValued(listOf(CatalogPath.parse("$.sku"), CatalogPath.parse("$.meta.qty")))
    }

    private fun term(values: List<Variant>): ByteArray = checkNotNull(CompositeTerm.of(values, OPTIONS))

    private fun text(value: String): Variant = Variant.fromJson("\"$value\"")

    private fun number(value: Int): Variant = Variant.fromJson("$value")

    private companion object {
        val OPTIONS = IndexOptions.DEFAULT
    }
}
