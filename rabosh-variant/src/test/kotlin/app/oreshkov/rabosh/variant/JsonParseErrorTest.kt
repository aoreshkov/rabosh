package app.oreshkov.rabosh.variant

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Malformed input is rejected, and the report says where.
 *
 * "Invalid JSON" without a position is useless against a payload of any size, so the offset is
 * part of the contract and is asserted here rather than left to chance.
 *
 * `kotlinx-serialization` is deliberately *not* used as a negative oracle. Its tree reader accepts
 * unquoted literals such as `01` and only validates them on conversion, so agreement on rejection
 * cannot be assumed — it is a good oracle for what valid JSON *means*, and a poor one for what is
 * valid at all.
 */
class JsonParseErrorTest {

    private fun rejects(json: String, expectedOffset: Int, expectedMessage: String) {
        val failure = assertFailsWith<JsonParseException>("expected '$json' to be rejected") {
            Variant.fromJson(json)
        }
        assertEquals(expectedOffset, failure.offset, "wrong offset for '$json': ${failure.message}")
        assertTrue(
            expectedMessage in failure.message.orEmpty(),
            "expected '$expectedMessage' in: ${failure.message}",
        )
    }

    @Test
    fun `empty and blank input`() {
        rejects("", 0, "unexpected end of input")
        rejects("   ", 3, "unexpected end of input")
    }

    @Test
    fun `unclosed containers`() {
        rejects("{", 1, "expected a field name")
        rejects("[", 1, "unexpected end of input")
        rejects("""{"a":1""", 6, "expected ',' or '}'")
        rejects("[1", 2, "expected ',' or ']'")
    }

    @Test
    fun `trailing and doubled commas`() {
        rejects("""{"a":1,}""", 7, "expected a field name")
        rejects("[1,]", 3, "unexpected ']'")
        rejects("[1,,2]", 3, "unexpected ','")
        rejects("""{,"a":1}""", 1, "expected a field name")
    }

    @Test
    fun `missing separators`() {
        rejects("""{"a" 1}""", 5, "expected ':'")
        rejects("""{"a":1 "b":2}""", 7, "expected ',' or '}'")
        rejects("[1 2]", 3, "expected ',' or ']'")
    }

    @Test
    fun `field names must be quoted strings`() {
        rejects("{a:1}", 1, "expected a field name in double quotes")
        rejects("{1:2}", 1, "expected a field name in double quotes")
    }

    @Test
    fun `unterminated and control-laden strings`() {
        rejects("\"unterminated", 0, "unterminated string")
        rejects("\"a\nb\"", 2, "unescaped control character")
        rejects("\"tab\there\"", 4, "unescaped control character")
    }

    @Test
    fun `invalid escapes`() {
        rejects("\"bad\\x\"", 5, "invalid escape")
        rejects("\"trailing\\", 10, "unterminated escape")
        rejects("\"\\uZZZZ\"", 3, "not a hexadecimal digit")
        rejects("\"\\u12\"", 1, "truncated \\u escape")
    }

    /**
     * A lone surrogate has no UTF-8 encoding. Substituting `U+FFFD` for it, which is the usual
     * default, would mean storing a string the caller never wrote.
     */
    @Test
    fun `unpaired surrogate escapes`() {
        rejects("\"\\uD800\"", 1, "unpaired high surrogate")
        rejects("\"\\uDC00\"", 1, "unpaired low surrogate")
        rejects("\"\\uD800\\u0041\"", 1, "non-surrogate escape")
        rejects("\"\\uD800a\"", 1, "unpaired high surrogate")
    }

    @Test
    fun `a correctly paired surrogate escape is accepted`() {
        assertEquals("😀", Variant.fromJson("\"\\uD83D\\uDE00\"").stringValue())
        assertEquals("\u0000", Variant.fromJson("\"\\u0000\"").stringValue())
        assertEquals("é", Variant.fromJson("\"\\u00e9\"").stringValue())
    }

    @Test
    fun `malformed UTF-8 in the input`() {
        fun rejectsBytes(bytes: ByteArray, expectedOffset: Int, expectedMessage: String) {
            val failure = assertFailsWith<JsonParseException> { Variant.fromJson(bytes) }
            assertEquals(expectedOffset, failure.offset, failure.message)
            assertTrue(expectedMessage in failure.message.orEmpty(), failure.message.orEmpty())
        }

        val quote = '"'.code.toByte()
        // A two-byte lead followed by an ASCII byte.
        rejectsBytes(byteArrayOf(quote, 0xC3.toByte(), 0x28, quote), 2, "continuation byte")
        // Overlong encoding of U+0000.
        rejectsBytes(byteArrayOf(quote, 0xC0.toByte(), 0x80.toByte(), quote), 1, "start byte")
        // U+D800 encoded as UTF-8, which UTF-8 forbids.
        rejectsBytes(byteArrayOf(quote, 0xED.toByte(), 0xA0.toByte(), 0x80.toByte(), quote), 2, "continuation byte")
        // Beyond U+10FFFF.
        rejectsBytes(byteArrayOf(quote, 0xF5.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), quote), 1, "start byte")
        // A continuation byte with no lead.
        rejectsBytes(byteArrayOf(quote, 0x80.toByte(), quote), 1, "start byte")
    }

    @Test
    fun `numbers follow the JSON grammar exactly`() {
        rejects("01", 1, "invalid number")
        rejects("-01", 2, "invalid number")
        rejects("1.", 2, "expected at least one digit in a fraction")
        rejects("1e", 2, "expected at least one digit in an exponent")
        rejects("1e+", 3, "expected at least one digit in an exponent")
        rejects("-", 1, "unexpected end of input in a number")
        rejects(".5", 0, "unexpected '.'")
        rejects("+1", 0, "unexpected '+'")
        rejects("1.2.3", 3, "invalid number")
        rejects("0x10", 1, "unexpected trailing content")
    }

    @Test
    fun `keywords must be spelled exactly`() {
        rejects("nul", 0, "expected 'null'")
        rejects("True", 0, "unexpected 'T'")
        rejects("tru", 0, "expected 'true'")
        rejects("NaN", 0, "unexpected 'N'")
        rejects("Infinity", 0, "unexpected 'I'")
    }

    @Test
    fun `trailing content is rejected`() {
        rejects("truex", 4, "unexpected trailing content")
        rejects("{} {}", 3, "unexpected trailing content")
        rejects("[1] junk", 4, "unexpected trailing content")
        rejects("1 2", 2, "unexpected trailing content")
    }

    @Test
    fun `a byte order mark is not JSON`() {
        val failure = assertFailsWith<JsonParseException> {
            Variant.fromJson(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), '1'.code.toByte()))
        }
        assertEquals(0, failure.offset)
        assertTrue("byte order mark" in failure.message.orEmpty())
    }

    /**
     * Depth is bounded because the parser is recursive descent. Without the guard, a two-megabyte
     * file of `[` takes the process down with a `StackOverflowError` — which is not an error an
     * ingest path can report on.
     */
    @Test
    fun `nesting is bounded`() {
        val deep = "[".repeat(2000) + "]".repeat(2000)
        val failure = assertFailsWith<JsonParseException> { Variant.fromJson(deep) }
        assertTrue("nesting deeper than 1000" in failure.message.orEmpty(), failure.message.orEmpty())

        // The bound is configurable, and just below it the document parses.
        assertEquals(1, JsonParser(maxDepth = 3).parse("[[[1]]]").element(0).element(0).elementCount)
        assertFailsWith<JsonParseException> { JsonParser(maxDepth = 3).parse("[[[[1]]]]") }
    }

    @Test
    fun `positions are reported as line and column`() {
        val json = "{\n  \"a\": 1,\n  \"b\": tru\n}"
        val failure = assertFailsWith<JsonParseException> { Variant.fromJson(json) }
        assertEquals(3, failure.line)
        assertEquals(8, failure.column)
        assertTrue("line 3, column 8" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `a field name is held to the same string rules as a value`() {
        rejects("{\"\\uD800\":1}", 2, "unpaired high surrogate")
    }
}
