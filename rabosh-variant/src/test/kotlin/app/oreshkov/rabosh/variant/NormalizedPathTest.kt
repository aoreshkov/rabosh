package app.oreshkov.rabosh.variant

import app.oreshkov.rabosh.testkit.property.forAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * RFC 9535 §2.7, from this side: the properties, the escape table, and what the parser refuses.
 *
 * The bytes another implementation wrote are in `JsonPathCtsTest`, and the two are not
 * interchangeable — the compliance suite has no case for the `\u00xx` form, because a raw control
 * character in a *selector* is invalid and so never reaches a result. Everything about that table
 * therefore lives here, checked against the ABNF rather than against a second opinion.
 */
class NormalizedPathTest {

    @Test
    fun `a normalized path round-trips`() {
        forAll(PathGens.path()) { path ->
            assertEquals(
                path,
                VariantPath.parseNormalized(path.toNormalizedPath()),
                "rendered as ${path.toNormalizedPath()}",
            )
        }
    }

    @Test
    fun `two locations are equal exactly when their normalized renderings are`() {
        forAll(PathGens.path(), PathGens.path()) { left, right ->
            assertEquals(
                left == right,
                left.toNormalizedPath() == right.toNormalizedPath(),
                "${left.toNormalizedPath()} against ${right.toNormalizedPath()}",
            )
        }
    }

    /**
     * The biconditional above is nearly vacuous on random pairs, which are almost never equal. These
     * are the pairs a reader would expect to collide: a field named like an index, a dotted name
     * against two steps, an empty name against no step at all.
     */
    @Test
    fun `locations a rendering could confuse stay distinct`() {
        val confusable = listOf(
            VariantPath.ROOT,
            VariantPath(listOf(VariantPathStep.Field(""))),
            VariantPath(listOf(VariantPathStep.Field("0"))),
            VariantPath(listOf(VariantPathStep.Index(0))),
            VariantPath(listOf(VariantPathStep.Field("a.b"))),
            VariantPath(listOf(VariantPathStep.Field("a"), VariantPathStep.Field("b"))),
            VariantPath(listOf(VariantPathStep.Field("a"), VariantPathStep.Index(1))),
            VariantPath(listOf(VariantPathStep.Field("a"), VariantPathStep.Field("1"))),
            VariantPath(listOf(VariantPathStep.Field("a1"))),
            VariantPath(listOf(VariantPathStep.Field("a'b"))),
            VariantPath(listOf(VariantPathStep.Field("a" + BACKSLASH + "'b"))),
        )
        val rendered = confusable.map { it.toNormalizedPath() }

        assertEquals(
            confusable.size,
            rendered.toSet().size,
            "two distinct locations share a normalized rendering: $rendered",
        )
        for (path in confusable) {
            assertEquals(path, VariantPath.parseNormalized(path.toNormalizedPath()), "for $path")
        }
    }

    @Test
    fun `the control escape table is the one in the ABNF`() {
        for (code in 0..LAST_C0_CONTROL) {
            val expected = when (code) {
                BACKSPACE -> BACKSLASH + "b"
                TAB -> BACKSLASH + "t"
                LINE_FEED -> BACKSLASH + "n"
                FORM_FEED -> BACKSLASH + "f"
                CARRIAGE_RETURN -> BACKSLASH + "r"
                else -> BACKSLASH + "u00" + "%02x".format(code)
            }
            val name = code.toChar().toString()
            assertEquals(
                "$['$expected']",
                fieldPath(name).toNormalizedPath(),
                "U+%04X is not spelled the way normal-escapable spells it".format(code),
            )
            assertEquals(name, VariantPath.parseNormalized("$['$expected']").steps.single().name())
        }
    }

    /**
     * Inside single quotes these are ordinary characters — `normal-unescaped` is `%x20-26 / %x28-5B /
     * %x5D-D7FF / %xE000-10FFFF`, which excludes only the quote, the backslash and the control
     * characters. An implementation that reused a JSON string writer would escape the first two of
     * these and produce a path no other reader accepts.
     */
    @Test
    fun `a double quote, a solidus and DEL are written raw`() {
        assertEquals("$['\"']", fieldPath("\"").toNormalizedPath())
        assertEquals("$['/']", fieldPath("/").toNormalizedPath())
        assertEquals("$['" + DELETE.toChar() + "']", fieldPath(DELETE.toChar().toString()).toNormalizedPath())
    }

    @Test
    fun `a quote and a backslash are the two that are escaped`() {
        assertEquals("$['" + BACKSLASH + "'']", fieldPath("'").toNormalizedPath())
        assertEquals("$['" + BACKSLASH + BACKSLASH + "']", fieldPath(BACKSLASH).toNormalizedPath())
    }

    @Test
    fun `an index is written as digits, up to the largest a step can hold`() {
        assertEquals("$[0]", VariantPath(listOf(VariantPathStep.Index(0))).toNormalizedPath())
        assertEquals(
            "$[${Int.MAX_VALUE}]",
            VariantPath(listOf(VariantPathStep.Index(Int.MAX_VALUE))).toNormalizedPath(),
        )
    }

    /**
     * §2.7 has no production for a lone surrogate: `normal-unescaped` is defined around the surrogate
     * block and `normal-hexchar` cannot climb above `00 1f`. Reporting that is the only honest answer
     * — rendering it anyway would emit a path this parser, and every other, then refuses.
     */
    @Test
    fun `an unpaired surrogate has no normalized spelling`() {
        for (surrogate in listOf(0xD834, 0xDD1E)) {
            val failure = assertFailsWith<IllegalArgumentException>("U+%04X".format(surrogate)) {
                fieldPath(surrogate.toChar().toString()).toNormalizedPath()
            }
            assertTrue(
                "unpaired surrogate" in failure.message.orEmpty(),
                "the message should name the problem, was ${failure.message}",
            )
        }

        // The pair, however, is an ordinary code point and is written raw.
        val musicalG = String(Character.toChars(0x1D11E))
        assertEquals("$['$musicalG']", fieldPath(musicalG).toNormalizedPath())
    }

    @Test
    fun `parseNormalized rejects everything §2_7 does not produce`() {
        val rejected = listOf(
            "" to "starts with '$'",
            "a" to "starts with '$'",
            "$.a" to "no dot step",
            "$['a'].b" to "no dot step",
            "$[*]" to "no wildcard",
            "$[\"a\"]" to "single-quoted",
            "$[?@.a]" to "expected a single-quoted member name or an array index",
            "$[0:1]" to "expected ']'",
            "$[01]" to "leading zero",
            "$[-1]" to "expected a single-quoted member name or an array index",
            "$[2147483648]" to "not representable",
            "$[]" to "expected a single-quoted member name or an array index",
            "$[" to "unterminated '['",
            "$['a" to "unterminated member name",
            "$['a'" to "expected ']'",
            "$['a']x" to "expected '['",
            "$['a" + BACKSLASH to "unterminated escape",
            "$['a" + BACKSLASH + "q']" to "is not one of RFC 9535's escapes",
            "$['" + BACKSLASH + "/']" to "is not one of RFC 9535's escapes",
            "$['" + BACKSLASH + "u0" to "needs four hexadecimal digits",
            // A character that has a named escape may not also be written as a hex escape,
            // an uppercase digit is not `normal-HEXDIG`, and 0x41 is not a control character.
            "$['" + BACKSLASH + "u000a']" to "is not one of RFC 9535's escapes",
            "$['" + BACKSLASH + "u000A']" to "is not one of RFC 9535's escapes",
            "$['" + BACKSLASH + "u001F']" to "is not one of RFC 9535's escapes",
            "$['" + BACKSLASH + "u0041']" to "is not one of RFC 9535's escapes",
            "$['" + 1.toChar() + "']" to "control character",
            "$['" + 0xD834.toChar() + "']" to "unpaired surrogate",
            "$['" + 0xDD1E.toChar() + "']" to "unpaired surrogate",
        )

        for ((expression, fragment) in rejected) {
            val failure = assertFailsWith<IllegalArgumentException>("'$expression' should not parse") {
                VariantPath.parseNormalized(expression)
            }
            val message = failure.message.orEmpty()
            assertTrue(fragment in message, "'$expression' should say '$fragment', said '$message'")
            assertTrue("position" in message, "'$expression' should name a position, said '$message'")
        }
    }

    @Test
    fun `parseNormalized accepts what the grammar does produce`() {
        val accepted = mapOf(
            "$" to VariantPath.ROOT,
            "$['a']" to fieldPath("a"),
            "$['']" to fieldPath(""),
            "$[0]" to VariantPath(listOf(VariantPathStep.Index(0))),
            "$['a'][0]['b']" to VariantPath(
                listOf(VariantPathStep.Field("a"), VariantPathStep.Index(0), VariantPathStep.Field("b")),
            ),
        )
        for ((expression, path) in accepted) {
            assertEquals(path, VariantPath.parseNormalized(expression), "for $expression")
            assertEquals(expression, path.toNormalizedPath(), "for $expression")
        }
    }

    /**
     * The engine's spelling is a separate one and stays a separate one.
     *
     * `toString` and `parse` are what a path is *stored* as — the index registry and the sketch
     * sidecar write the rendered text and read it back — so changing either is a format change.
     * This asserts the two renderings side by side rather than trusting that nobody will conflate
     * them, and `@type` is here because it is the case where the engine's own fork fires.
     */
    @Test
    fun `the normalized rendering sits beside the engine's, not in place of it`() {
        val path = VariantPath.parse("$.items[0].sku")
        assertEquals("$.items[0].sku", path.toString())
        assertEquals("$['items'][0]['sku']", path.toNormalizedPath())
        assertEquals(path, VariantPath.parse(path.toString()))
        assertEquals(path, VariantPath.parseNormalized(path.toNormalizedPath()))

        val atType = VariantPath.parse("""$["@type"]""")
        assertEquals("""$["@type"]""", atType.toString())
        assertEquals("$['@type']", atType.toNormalizedPath())
    }

    private fun fieldPath(name: String) = VariantPath(listOf(VariantPathStep.Field(name)))

    private fun VariantPathStep.name(): String = (this as VariantPathStep.Field).name

    private companion object {
        const val BACKSLASH = "\\"

        const val LAST_C0_CONTROL = 0x1F
        const val BACKSPACE = 0x08
        const val TAB = 0x09
        const val LINE_FEED = 0x0A
        const val FORM_FEED = 0x0C
        const val CARRIAGE_RETURN = 0x0D
        const val DELETE = 0x7F
    }
}
