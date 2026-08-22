package app.oreshkov.rabosh.jsonpath

import app.oreshkov.rabosh.catalog.CatalogPath
import app.oreshkov.rabosh.catalog.PathConstruct
import app.oreshkov.rabosh.catalog.PathNotRepresentableException
import app.oreshkov.rabosh.catalog.nodesIn
import app.oreshkov.rabosh.variant.Variant
import app.oreshkov.rabosh.variant.VariantPath
import app.oreshkov.rabosh.variant.toJsonString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The three readers decode a name the same way, and the reference is the one that implements the
 * whole RFC.**
 *
 * `CatalogPath.parseJsonPath` and `VariantPath.parseJsonPathOrNull` each read a sub-language of
 * RFC 9535, in the module their result type lives in, and neither can call the other: `rabosh-variant`
 * is below `rabosh-catalog`, and `rabosh-jsonpath` sits beside the chain where nothing may depend on
 * it. So §2.3.1.1's escapes are implemented three times in this repository, and two of those three
 * copies exist because the alternative is publishing a string decoder as permanent public API across
 * a module boundary — the argument `ColumnBounds` makes about the bound codec, applied to a grammar.
 *
 * **That duplication is only safe if something checks it, and comparing the two copies with each
 * other would not.** Two readers that agree with each other and disagree with the RFC would pass
 * such a test and still send a filter to a different field than the extraction beside it. So both
 * are compared against `JsonPathQuery` — the module that implements the whole grammar, is tested
 * against the vendored compliance suite, and knows nothing about either of them.
 *
 * The comparison is by *nodelist* rather than by decoded name, because a name is not observable
 * through `JsonPathQuery` and inventing an accessor to make it so would be testing a test seam. If
 * three readers select the same node out of a document whose object holds every awkward name at
 * once, they decoded the name identically.
 *
 * It lives here for the reason `NodeWalkDifferentialTest` does: the module making the claim should
 * be the one that fails when it stops being true, and the dependency edge onto `rabosh-catalog` is
 * test-only and points the way `settings.gradle.kts` forbids in `main`.
 */
class PathReaderDifferentialTest {

    @Test
    fun `all three readers select the same node for every spelling of every awkward name`() {
        var compared = 0
        for ((spelling, expected) in SPELLINGS) {
            val reference = JsonPathQuery.compile(spelling).nodesIn(DOCUMENT)
            assertEquals(1, reference.size, "the fixture must make '$spelling' name exactly one node")
            assertEquals(expected, reference.single().value.toJsonString(), "the fixture is wrong for '$spelling'")

            val shape = CatalogPath.parseJsonPath(spelling).nodesIn(DOCUMENT)
            assertEquals(
                reference.map { it.location.toNormalizedPath() },
                shape.map { it.location.toNormalizedPath() },
                "CatalogPath.parseJsonPath disagrees with RFC 9535 about '$spelling'",
            )

            val location = assertNotNull(
                VariantPath.parseJsonPathOrNull(spelling),
                "VariantPath.parseJsonPathOrNull refused the singular query '$spelling'",
            )
            val selected = assertNotNull(
                DOCUMENT.select(location),
                "VariantPath.parseJsonPathOrNull decoded '$spelling' to a location that is not there",
            )
            assertEquals(
                expected,
                selected.toJsonString(),
                "VariantPath.parseJsonPathOrNull disagrees with RFC 9535 about '$spelling'",
            )
            compared++
        }
        assertTrue(compared > 0, "no spelling was compared; the differential proved nothing")
    }

    @Test
    fun `an indexed location agrees with the reference, and is refused as a shape`() {
        for ((spelling, expected) in INDEXED) {
            val reference = JsonPathQuery.compile(spelling).nodesIn(DOCUMENT)
            assertEquals(1, reference.size, "the fixture must make '$spelling' name exactly one node")
            assertEquals(expected, reference.single().value.toJsonString(), "the fixture is wrong for '$spelling'")

            val location = assertNotNull(VariantPath.parseJsonPathOrNull(spelling), spelling)
            assertEquals(reference.single().location, location, spelling)

            val failure = assertFailsWith<PathNotRepresentableException>(spelling) {
                CatalogPath.parseJsonPath(spelling)
            }
            assertEquals(PathConstruct.INDEX, failure.construct, spelling)
        }
    }

    @Test
    fun `what one reader refuses, the other refuses too, and for the construct the RFC gave it`() {
        // Both readers narrow the same grammar and must narrow it consistently: an expression that
        // does not name one location cannot be a `VariantPath`, and the catalog's refusals are a
        // subset of that — it keeps the wildcard, which is the one construct the two disagree about
        // on purpose.
        for (expression in NOT_SINGULAR) {
            // Compiles here or the row proves nothing: the claim is about a query the RFC accepts.
            JsonPathQuery.compile(expression)
            assertNull(VariantPath.parseJsonPathOrNull(expression), expression)
            assertFailsWith<PathNotRepresentableException>(expression) { CatalogPath.parseJsonPath(expression) }
        }

        // The one place the two readers differ on purpose. A wildcard is a shape and not a location,
        // so the catalog takes what the variant refuses — and `[0]` is the mirror image, a location
        // that is not a shape.
        for (expression in WILDCARDS) {
            assertNull(VariantPath.parseJsonPathOrNull(expression), "'$expression' names more than one location")
            CatalogPath.parseJsonPath(expression)
        }
        assertNotNull(VariantPath.parseJsonPathOrNull("$.items[0]"))
        assertFailsWith<PathNotRepresentableException> { CatalogPath.parseJsonPath("$.items[0]") }
    }

    @Test
    fun `a malformed expression is refused by all three, and the reference agrees it is malformed`() {
        for (expression in MALFORMED) {
            // The reference must agree these are typos, or the row below is asserting nothing: a
            // construct the RFC does not have is not the distinction R4 exists to make.
            assertFailsWith<IllegalArgumentException>("'$expression' must be malformed to RFC 9535") {
                JsonPathQuery.compile(expression)
            }
            assertNull(VariantPath.parseJsonPathOrNull(expression), expression)
            val failure = assertFailsWith<IllegalArgumentException>(expression) {
                CatalogPath.parseJsonPath(expression)
            }
            assertTrue(
                failure !is PathNotRepresentableException,
                "'$expression' is a typo and must not be reported as a construct this grammar declines",
            )
        }
    }

    @Test
    fun `the rendering a catalog path emits is a query the reference reads back`() {
        // R1 and R2 as a round trip through a third party: whatever `toJsonPath` writes must be a
        // valid RFC 9535 query — checked by compiling it — and must read back as the same shape.
        for (path in SHAPES) {
            val rendered = path.toJsonPath()
            val reference = JsonPathQuery.compile(rendered).nodesIn(DOCUMENT)
            assertEquals(
                path.nodesIn(DOCUMENT).map { it.location.toNormalizedPath() },
                reference.map { it.location.toNormalizedPath() },
                "'$rendered' does not mean what $path means",
            )
            assertEquals(path, CatalogPath.parseJsonPath(rendered), rendered)
        }
    }

    private companion object {
        const val BACKSLASH: Char = '\\'

        /**
         * One object holding every name the three readers could decode differently, so that a
         * disagreement about escaping shows up as the wrong *node* rather than as nothing.
         */
        val DOCUMENT: Variant = Variant.fromJson(
            """
            {
              "plain": "P",
              "@type": "T",
              "a'b": "Q",
              "a\"b": "D",
              "a\\b": "B",
              "a\nb": "N",
              "a\tb": "TAB",
              "a/b": "S",
              "A": "U",
              "": "E",
              "日本語": "J",
              "😀": "EMOJI",
              "items": [{ "sku": "one" }, { "sku": "two" }]
            }
            """.trimIndent(),
        )

        /** Spellings that name exactly one location, and the value each must find. */
        val SPELLINGS: List<Pair<String, String>> = listOf(
            "$.plain" to "\"P\"",
            """$['plain']""" to "\"P\"",
            """$["plain"]""" to "\"P\"",
            """$[ 'plain' ]""" to "\"P\"",
            """$['@type']""" to "\"T\"",
            """$["@type"]""" to "\"T\"",
            """$['a${BACKSLASH}'b']""" to "\"Q\"",
            """$["a'b"]""" to "\"Q\"",
            """$['a"b']""" to "\"D\"",
            """$["a${BACKSLASH}"b"]""" to "\"D\"",
            """$['a${BACKSLASH}${BACKSLASH}b']""" to "\"B\"",
            """$['a${BACKSLASH}nb']""" to "\"N\"",
            """$['a${BACKSLASH}u000ab']""" to "\"N\"",
            """$['a${BACKSLASH}u000Ab']""" to "\"N\"",
            """$['a${BACKSLASH}tb']""" to "\"TAB\"",
            """$['a/b']""" to "\"S\"",
            """$['a${BACKSLASH}/b']""" to "\"S\"",
            """$['${BACKSLASH}u0041']""" to "\"U\"",
            """$['A']""" to "\"U\"",
            """$['']""" to "\"E\"",
            """$['日本語']""" to "\"J\"",
            """$['😀']""" to "\"EMOJI\"",
            """$['${BACKSLASH}ud83d${BACKSLASH}ude00']""" to "\"EMOJI\"",
        )

        /**
         * Locations that carry an index, which only two of the three readers can hold.
         *
         * Separate from [SPELLINGS] because `CatalogPath` has no index step by design — collapsing
         * `[0]` into `[*]` would answer a different question — so these exercise the variant reader
         * against the reference and are asserted to be *refused* by the catalog one.
         */
        val INDEXED: List<Pair<String, String>> = listOf(
            "$.items[0].sku" to "\"one\"",
            """$['items'][1]['sku']""" to "\"two\"",
            """$["items"][ 0 ]["sku"]""" to "\"one\"",
        )

        /** Valid queries that name more than one location and are not a shape either. */
        val NOT_SINGULAR: List<String> = listOf(
            "$.items[1:2]",
            "$.items[::2]",
            """$.items[?@.sku == 'one']""",
            """$['plain','@type']""",
        )

        /**
         * Where the two readers differ on purpose: a shape may say *every* and a location may not.
         *
         * Two constructs now, not one, and the second arrived later — `..` was a refusal here until
         * `CatalogStep.AnyDescendant` existed, and this row **moved** out of [NOT_SINGULAR] rather
         * than being deleted from it. That is the difference between a grammar gaining a step and a
         * test being relaxed, and it is why the two lists are separate rather than one list of
         * things that fail.
         */
        val WILDCARDS: List<String> = listOf(
            "$.items[*].sku",
            """$['items'][:]['sku']""",
            "$.items.*.sku",
            "$..sku",
            """$..['sku']""",
            "$..[*].sku",
        )


        /** Not JSONPath at all. Every reader must refuse these, and none as a construct. */
        val MALFORMED: List<String> = listOf(
            "",
            "plain",
            "$.",
            "$[",
            "$.@type",
            "$[a]",
            """$['unterminated""",
            """$['a${BACKSLASH}qb']""",
            """$['a${BACKSLASH}"b']""",
            """$["a${BACKSLASH}'b"]""",
            """$['${BACKSLASH}ud83d']""",
        )

        val SHAPES: List<CatalogPath> = listOf(
            CatalogPath.ROOT,
            CatalogPath.parse("$.plain"),
            CatalogPath.parse("$.items[*]"),
            CatalogPath.parse("$.items[*].sku"),
            CatalogPath.parse("""$["@type"]"""),
            CatalogPath.parse("""$["a.b"]"""),
        )
    }
}
