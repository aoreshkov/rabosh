package app.oreshkov.rabosh.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The path type, and in particular the two things it must never confuse: a name and an index. */
class CatalogPathTest {

    @Test
    fun `canonical text round-trips`() {
        for (expression in listOf(
            "$",
            "$.user",
            "$.user.name",
            "$.items[*]",
            "$.items[*].sku",
            "$[*][*]",
            """$["odd name"]""",
            """$["with \"quotes\""]""",
            """$["a.b"].c""",
            "$..",
            "$..sku",
            "$..items[*]",
            "$.a..b",
            "$.a..",
            """$..["@type"]""",
            "$..[*]",
        )) {
            assertEquals(expression, CatalogPath.parse(expression).toString(), expression)
        }
    }

    /**
     * The two dots are read before one, which is the whole of what keeps the spellings apart.
     *
     * `$..sku` is a descendant and a field; `$.a.sku` is two fields; and `$...sku` is neither, which
     * has to fail rather than being read as one of them. The name after a `..` carries **no** dot,
     * because that is RFC 9535's shorthand rule and because a dot there would be a third spelling of
     * a step that already has two.
     */
    @Test
    fun `a descendant is read greedily and its name carries no dot`() {
        assertEquals(
            listOf(CatalogStep.AnyDescendant, CatalogStep.Field("sku")),
            CatalogPath.parse("$..sku").steps,
        )
        assertEquals(
            listOf(CatalogStep.Field("a"), CatalogStep.Field("sku")),
            CatalogPath.parse("$.a.sku").steps,
        )
        assertEquals(listOf(CatalogStep.AnyDescendant), CatalogPath.parse("$..").steps)
        assertEquals(
            listOf(CatalogStep.Field("a"), CatalogStep.AnyDescendant, CatalogStep.AnyElement),
            CatalogPath.parse("$.a..[*]").steps,
        )
        assertFailsWith<IllegalArgumentException> { CatalogPath.parse("$...sku") }
    }

    /**
     * **Two `..` in a row are refused, and the reason is the registry rather than taste.**
     *
     * `..` is idempotent, so the second selects nothing the first does not — but the argument that
     * decides it is that a path is *persisted* as `toString` and read back by `parse`. `$....` is not
     * a spelling in either grammar, so a step list holding two adjacent descendants could be built
     * and could not be read back: a registry entry nothing could open. Refusing it at the
     * constructor is what makes "every step list round-trips" true rather than nearly true.
     */
    @Test
    fun `two descendants in a row are refused wherever they are built`() {
        assertFailsWith<IllegalArgumentException> { CatalogPath.parse("$....") }
        assertFailsWith<IllegalArgumentException> { CatalogPath.parse("$....sku") }
        assertFailsWith<IllegalArgumentException> {
            CatalogPath(listOf(CatalogStep.AnyDescendant, CatalogStep.AnyDescendant))
        }
        // One after another step, and two separated by one, are both ordinary.
        CatalogPath(listOf(CatalogStep.AnyDescendant, CatalogStep.Field("a"), CatalogStep.AnyDescendant))
    }

    @Test
    fun `a quoted name and a dotted path are different locations`() {
        // The trap `VariantPath` records too: these must never compare equal, or an index built for
        // one would answer for the other.
        assertNotEquals(CatalogPath.parse("""$["a.b"]"""), CatalogPath.parse("$.a.b"))
    }

    @Test
    fun `an array index is rejected rather than quietly collapsed`() {
        // `$.items[0]` means something this type cannot represent. Widening it to `$.items[*]`
        // would answer a question the caller did not ask.
        val failure = assertFailsWith<IllegalArgumentException> { CatalogPath.parse("$.items[0]") }
        assertTrue(failure.message!!.contains("no indices"), failure.message!!)
    }

    @Test
    fun `malformed expressions name their position`() {
        for (expression in listOf("", "user", "$.", "$[", "$[*", "$.a.", """$["unterminated""")) {
            assertFailsWith<IllegalArgumentException>("'$expression' should not parse") {
                CatalogPath.parse(expression)
            }
        }
    }

    @Test
    fun `ordering is total and stable`() {
        val sorted = listOf(
            "$",
            "$.a",
            "$.a.b",
            "$.a[*]",
            "$.b",
            "$[*]",
        ).map(CatalogPath::parse)

        assertEquals(sorted, sorted.shuffled().sorted(), "field before element, then by name")
        for (index in 1 until sorted.size) {
            assertTrue(sorted[index - 1] < sorted[index], "${sorted[index - 1]} < ${sorted[index]}")
        }
    }

    @Test
    fun `startsWith is a prefix test over steps, not over text`() {
        val items = CatalogPath.parse("$.item")
        assertTrue(CatalogPath.parse("$.item.sku").startsWith(items))
        assertTrue(!CatalogPath.parse("$.items.sku").startsWith(items), "'items' is not under 'item'")
        assertTrue(CatalogPath.parse("$.anything").startsWith(CatalogPath.ROOT))
    }

    @Test
    fun `catalogPathOf builds field paths`() {
        assertEquals(CatalogPath.parse("$.user.name"), catalogPathOf("user", "name"))
        assertEquals(CatalogPath.ROOT, catalogPathOf())
    }
}
