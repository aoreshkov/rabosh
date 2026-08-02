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
        )) {
            assertEquals(expression, CatalogPath.parse(expression).toString(), expression)
        }
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
