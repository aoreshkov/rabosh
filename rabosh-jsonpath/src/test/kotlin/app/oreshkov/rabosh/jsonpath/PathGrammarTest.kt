package app.oreshkov.rabosh.jsonpath

import app.oreshkov.rabosh.catalog.CatalogPath
import app.oreshkov.rabosh.catalog.CatalogStep
import app.oreshkov.rabosh.catalog.PathNotRepresentableException
import app.oreshkov.rabosh.variant.VariantPath
import app.oreshkov.rabosh.variant.VariantPathStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * **`PATHS.md`'s tables, executed.**
 *
 * That document compares four grammars across six entry points, and a comparison table is exactly
 * the kind of documentation that is true when it is written and false a release later — quietly,
 * because nothing reads it but a person. So the tables are here as assertions, cell for cell, and
 * the document says they are.
 *
 * **This is not a second copy of the parser tests and must not grow into one.** `CatalogJsonPathTest`,
 * `SingularJsonPathTest` and `JsonPathQueryTest` each own their reader's behaviour in depth — every
 * escape, every position, every message. What is pinned here is only the *relationships between the
 * readers*, which is the thing `PATHS.md` claims and no single-module test can see: that one
 * expression is read by three of them and refused by three, that a backslash means opposite things
 * on either side of one boundary, and that two writers of the same path produce two spellings on
 * purpose. Add a cell here when the document gains a row, not when a reader gains a behaviour.
 *
 * It lives in `rabosh-jsonpath` for `PathReaderDifferentialTest`'s reason: the test-only edge onto
 * `rabosh-catalog` points the way `settings.gradle.kts` forbids in `main`, and this is the only
 * source set from which all four grammars are visible at once.
 */
class PathGrammarTest {

    // --- the reader table ---------------------------------------------------------------------

    /** What a reader did with an expression, in the vocabulary `PATHS.md`'s table uses. */
    private enum class Outcome {
        /** Read it. */
        ACCEPTED,

        /** `IllegalArgumentException` — the table's ❌, and the caller's typo. */
        MALFORMED,

        /** `PathNotRepresentableException` — well-formed, and not a question this type can ask. */
        REFUSED,

        /** `null` — an answer, and the reason `parseJsonPathOrNull` throws nothing at all. */
        NULL,
    }

    private fun outcome(read: () -> Any?): Outcome = try {
        if (read() == null) Outcome.NULL else Outcome.ACCEPTED
    } catch (refused: PathNotRepresentableException) {
        Outcome.REFUSED
    } catch (malformed: IllegalArgumentException) {
        Outcome.MALFORMED
    }

    private fun row(expression: String): List<Outcome> = listOf(
        outcome { VariantPath.parse(expression) },
        outcome { VariantPath.parseNormalized(expression) },
        outcome { VariantPath.parseJsonPathOrNull(expression) },
        outcome { CatalogPath.parse(expression) },
        outcome { CatalogPath.parseJsonPath(expression) },
        outcome { JsonPathQuery.compile(expression) },
    )

    private fun assertRow(expression: String, vararg expected: Outcome) {
        assertEquals(
            expected.toList(),
            row(expression),
            "PATHS.md's reader table, row `$expression` — columns are parse, parseNormalized, " +
                "parseJsonPathOrNull, CatalogPath.parse, parseJsonPath, JsonPathQuery.compile",
        )
    }

    @Test
    fun `the shorthand is read by four of the six`() {
        // `parseNormalized` refuses it because RFC 9535 §2.7 has one spelling per name and this is
        // not it — the point of a normalized path being that two of them compare as text.
        assertRow(
            "$.a",
            Outcome.ACCEPTED, Outcome.MALFORMED, Outcome.ACCEPTED,
            Outcome.ACCEPTED, Outcome.ACCEPTED, Outcome.ACCEPTED,
        )
    }

    @Test
    fun `the two quotings split the readers, which is the portability defect`() {
        // The engine's own two readers take `"` and not `'`; §2.7 takes `'` and not `"`. So the
        // spelling a Windows shell leaves intact is the one a filter used to reject outright, and
        // the boundary readers are what close that.
        assertRow(
            """$["a"]""",
            Outcome.ACCEPTED, Outcome.MALFORMED, Outcome.ACCEPTED,
            Outcome.ACCEPTED, Outcome.ACCEPTED, Outcome.ACCEPTED,
        )
        assertRow(
            """$['a']""",
            Outcome.MALFORMED, Outcome.ACCEPTED, Outcome.ACCEPTED,
            Outcome.MALFORMED, Outcome.ACCEPTED, Outcome.ACCEPTED,
        )
    }

    @Test
    fun `an index is a location and never a shape`() {
        // The two failures in that row are different failures, and telling them apart is the whole
        // of `PathNotRepresentableException`: `parse` cannot say more than "malformed", while
        // `parseJsonPath` knows the expression is well-formed and names the construct.
        assertRow(
            "$.a[0]",
            Outcome.ACCEPTED, Outcome.MALFORMED, Outcome.ACCEPTED,
            Outcome.MALFORMED, Outcome.REFUSED, Outcome.ACCEPTED,
        )
    }

    @Test
    fun `a wildcard is a shape and never a location`() {
        assertRow(
            "$.a[*]",
            Outcome.MALFORMED, Outcome.MALFORMED, Outcome.NULL,
            Outcome.ACCEPTED, Outcome.ACCEPTED, Outcome.ACCEPTED,
        )
    }

    /**
     * The slice is the row that says why `toJsonPath` emits `[:]`: RFC 9535 reads it, the catalog's
     * interchange reader reads it, and the engine's own spelling has never heard of it.
     */
    @Test
    fun `the slice is read by the interchange reader and by the RFC alone`() {
        assertRow(
            "$.a[:]",
            Outcome.MALFORMED, Outcome.MALFORMED, Outcome.NULL,
            Outcome.MALFORMED, Outcome.ACCEPTED, Outcome.ACCEPTED,
        )
    }

    @Test
    fun `a descendant and a filter reach only the module that implements the whole RFC`() {
        assertRow(
            "$..a",
            Outcome.MALFORMED, Outcome.MALFORMED, Outcome.NULL,
            Outcome.MALFORMED, Outcome.REFUSED, Outcome.ACCEPTED,
        )
        assertRow(
            "$.a[?@.b=='x']",
            Outcome.MALFORMED, Outcome.MALFORMED, Outcome.NULL,
            Outcome.MALFORMED, Outcome.REFUSED, Outcome.ACCEPTED,
        )
    }

    /** Every reader agrees about the root, which is the one cell of the table that is all ticks. */
    @Test
    fun `the root is read by all six`() {
        assertRow(
            "$",
            Outcome.ACCEPTED, Outcome.ACCEPTED, Outcome.ACCEPTED,
            Outcome.ACCEPTED, Outcome.ACCEPTED, Outcome.ACCEPTED,
        )
    }

    // --- the escaping column ------------------------------------------------------------------

    /**
     * **A backslash means opposite things on either side of the boundary**, and this is the column of
     * the table that is not about acceptance at all.
     *
     * Both sides read the same eight characters without complaint and produce **different field
     * names**. There is no diagnostic available for that and there cannot be one: each reader is
     * behaving exactly as documented. What the repository does instead is keep the two grammars at a
     * named boundary and assert, here, that the boundary is where it says it is.
     */
    @Test
    fun `the engine reads a backslash literally and the RFC reads an escape`() {
        assertEquals("anb", fieldOf(VariantPath.parse("""$["a\nb"]""")), "the engine's own location reader")
        assertEquals("anb", fieldOf(CatalogPath.parse("""$["a\nb"]""")), "the engine's own shape reader")

        assertEquals("a\nb", fieldOf(VariantPath.parseJsonPathOrNull("""$["a\nb"]""")), "the interchange reader")
        assertEquals("a\nb", fieldOf(CatalogPath.parseJsonPath("""$['a\nb']""")), "either quoting, same escapes")
        assertEquals("a\nb", fieldOf(VariantPath.parseNormalized("""$['a\nb']""")), "§2.7's own seven")
    }

    // --- the writer table ---------------------------------------------------------------------

    /**
     * **Two spellings per type, and the second is not a prettier first.**
     *
     * `toString` is what a path is persisted and logged as, so it is covered by the format claim
     * rather than by taste; `toJsonPath` and `toNormalizedPath` are what leaves the engine. A change
     * that made either pair converge would be a format change wearing a refactor's clothes.
     */
    @Test
    fun `each type writes its own spelling and an interchange spelling`() {
        val shape = CatalogPath.parse("$.items[*].sku")
        assertEquals("$.items[*].sku", shape.toString(), "the engine's spelling, which `parse` reads back")
        assertEquals("""$['items'][:]['sku']""", shape.toJsonPath(), "the interchange spelling, with the slice")

        val location = VariantPath.parse("$.items[0].sku")
        assertEquals("$.items[0].sku", location.toString(), "the engine's spelling, which `parse` reads back")
        assertEquals("""$['items'][0]['sku']""", location.toNormalizedPath(), "RFC 9535 §2.7")
    }

    /** The name that made this a portability problem rather than an aesthetic one. */
    @Test
    fun `a name needing brackets is written double-quoted by the engine and single-quoted for interchange`() {
        assertEquals("""$["@type"]""", CatalogPath.parse("""$["@type"]""").toString())
        assertEquals("""$['@type']""", CatalogPath.parse("""$["@type"]""").toJsonPath())
        assertEquals("""$["@type"]""", VariantPath.parse("""$["@type"]""").toString())
        assertEquals("""$['@type']""", VariantPath.parse("""$["@type"]""").toNormalizedPath())
    }

    /**
     * The recipe `PATHS.md` exists for, asserted as one fact: **one string, two questions, one
     * meaning.**
     *
     * Its value is that it fails when either boundary reader drifts, which is precisely when a
     * consumer's filter and extraction would start naming different fields without saying so.
     */
    @Test
    fun `one RFC 9535 expression reads as the same path through both boundary readers`() {
        val expression = """$['response']['body']['@type']"""
        val shape = CatalogPath.parseJsonPath(expression)
        val location = VariantPath.parseJsonPathOrNull(expression)

        assertEquals(listOf("response", "body", "@type"), shape.steps.map { fieldName(it) })
        assertEquals(listOf("response", "body", "@type"), assertPath(location).steps.map { fieldName(it) })
        // And the same expression in the spelling a Windows shell would leave alone reads identically.
        assertEquals(shape, CatalogPath.parseJsonPath("$.response.body['@type']"))
    }

    // --- helpers ------------------------------------------------------------------------------

    private fun fieldOf(path: CatalogPath): String = fieldName(path.steps.single())

    private fun fieldOf(path: VariantPath?): String = fieldName(assertPath(path).steps.single())

    private fun assertPath(path: VariantPath?): VariantPath = path ?: fail("expected one location, got null")

    private fun fieldName(step: CatalogStep): String = when (step) {
        is CatalogStep.Field -> step.name
        CatalogStep.AnyElement -> fail("expected a field step, got `[*]`")
    }

    private fun fieldName(step: VariantPathStep): String = when (step) {
        is VariantPathStep.Field -> step.name
        is VariantPathStep.Index -> fail("expected a field step, got `[${step.index}]`")
    }

    /** `assertNull` used where the table says `null`, so the import is not decoration. */
    @Test
    fun `an expression naming more than one location is null and not an exception`() {
        assertNull(VariantPath.parseJsonPathOrNull("$.a[*]"))
        assertNull(VariantPath.parseJsonPathOrNull("$.a[?@.b=='x']"))
    }
}
