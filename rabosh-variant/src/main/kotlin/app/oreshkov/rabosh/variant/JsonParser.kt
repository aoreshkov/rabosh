package app.oreshkov.rabosh.variant

/**
 * Nesting depth a document may reach before the parser gives up.
 *
 * The parser is recursive descent, so depth is bounded by the JVM stack; `[[[[…` a million deep is
 * a two-megabyte file that would otherwise take the process down with a `StackOverflowError`. A
 * thousand is far past anything a real document reaches and far short of anything that hurts.
 */
public const val DEFAULT_MAX_JSON_DEPTH: Int = 1000

/**
 * Parses JSON text straight into the Variant encoding.
 *
 * The parser reads UTF-8 **bytes**, not a decoded `String`, for two reasons that both matter to an
 * ingest path: a string with no escapes is copied into the value byte-for-byte with no decode and
 * no re-encode, and every error carries an exact byte offset into the input the caller handed over.
 *
 * Input is validated strictly. Malformed UTF-8, unescaped control characters, unpaired surrogate
 * escapes, leading zeros and trailing content are all rejected with a position — an ingest engine
 * that quietly repairs its input is one that stores something the caller never wrote.
 *
 * ```kotlin
 * val variant = Variant.fromJson("""{"id":7,"tags":["a","b"]}""")
 *
 * // Segment ingest: one dictionary, one builder, many documents.
 * val builder = VariantBuilder(dictionary)
 * for (document in documents) {
 *     builder.reset()
 *     parser.parseInto(builder, document)
 *     store(builder.build())
 * }
 * ```
 *
 * Instances are stateless and safe to reuse; the parse itself is single-threaded.
 */
public class JsonParser(private val maxDepth: Int = DEFAULT_MAX_JSON_DEPTH) {
    init {
        require(maxDepth > 0) { "maxDepth must be positive, was $maxDepth" }
    }

    /** Parses [json] into a self-contained Variant with a dictionary of its own. */
    public fun parse(json: ByteArray): Variant {
        val builder = VariantBuilder()
        parseInto(builder, json)
        return builder.buildVariant()
    }

    /** Parses [json] into a self-contained Variant with a dictionary of its own. */
    public fun parse(json: String): Variant = parse(json.toUtf8("JSON input"))

    /**
     * Parses [json] into [builder], which supplies the dictionary and the duplicate-field policy.
     *
     * The builder is *not* reset first — that is the caller's decision, because reusing one builder
     * across a segment's documents is the whole point of passing one in.
     */
    public fun parseInto(builder: VariantBuilder, json: ByteArray) {
        JsonReader(json, builder, maxDepth).parseDocument()
    }

    /** See the [ByteArray] overload. */
    public fun parseInto(builder: VariantBuilder, json: String) {
        parseInto(builder, json.toUtf8("JSON input"))
    }
}

private const val TAB = 0x09
private const val NEWLINE = 0x0A
private const val CARRIAGE_RETURN = 0x0D
private const val SPACE = 0x20
private const val QUOTE = '"'.code
private const val BACKSLASH = '\\'.code
private const val SLASH = '/'.code
private const val OBJECT_OPEN = '{'.code
private const val OBJECT_CLOSE = '}'.code
private const val ARRAY_OPEN = '['.code
private const val ARRAY_CLOSE = ']'.code
private const val COLON = ':'.code
private const val COMMA = ','.code
private const val MINUS = '-'.code
private const val PLUS = '+'.code
private const val POINT = '.'.code
private const val ZERO = '0'.code
private const val NINE = '9'.code

/** Digits an integer literal may have before it stops fitting in `Long` without a check. */
private const val SAFE_LONG_DIGITS = 18

private class JsonReader(
    private val input: ByteArray,
    private val builder: VariantBuilder,
    private val maxDepth: Int,
) {
    private var position = 0
    private var line = 1
    private var lineStart = 0

    /** Holds a string's bytes only when it contains escapes; otherwise the input is used in place. */
    private val scratch = GrowableBytes()
    private var tokenSource: ByteArray = input
    private var tokenStart = 0
    private var tokenLength = 0

    fun parseDocument() {
        rejectByteOrderMark()
        skipWhitespace()
        parseValue(0)
        skipWhitespace()
        if (position < input.size) fail("unexpected trailing content")
    }

    private fun parseValue(depth: Int) {
        if (position >= input.size) fail("unexpected end of input, expected a value")
        when (val byte = byteAt(position)) {
            OBJECT_OPEN -> parseObject(depth)
            ARRAY_OPEN -> parseArray(depth)
            QUOTE -> {
                readString()
                builder.appendUtf8String(tokenSource, tokenStart, tokenLength)
            }

            't'.code -> {
                expectKeyword("true")
                builder.appendBoolean(true)
            }

            'f'.code -> {
                expectKeyword("false")
                builder.appendBoolean(false)
            }

            'n'.code -> {
                expectKeyword("null")
                builder.appendNull()
            }

            else ->
                if (byte == MINUS || byte in ZERO..NINE) parseNumber() else fail("unexpected ${describe(byte)}")
        }
    }

    private fun parseObject(depth: Int) {
        if (depth >= maxDepth) fail("nesting deeper than $maxDepth")
        position++
        builder.startObject()
        skipWhitespace()
        if (peek() == OBJECT_CLOSE) {
            position++
            builder.endObject()
            return
        }
        while (true) {
            skipWhitespace()
            if (peek() != QUOTE) fail("expected a field name in double quotes")
            readString()
            builder.field(String(tokenSource, tokenStart, tokenLength, Charsets.UTF_8))
            skipWhitespace()
            if (peek() != COLON) fail("expected ':' after a field name")
            position++
            skipWhitespace()
            parseValue(depth + 1)
            skipWhitespace()
            when (peek()) {
                COMMA -> position++
                OBJECT_CLOSE -> {
                    position++
                    builder.endObject()
                    return
                }

                else -> fail("expected ',' or '}'")
            }
        }
    }

    private fun parseArray(depth: Int) {
        if (depth >= maxDepth) fail("nesting deeper than $maxDepth")
        position++
        builder.startArray()
        skipWhitespace()
        if (peek() == ARRAY_CLOSE) {
            position++
            builder.endArray()
            return
        }
        while (true) {
            skipWhitespace()
            parseValue(depth + 1)
            skipWhitespace()
            when (peek()) {
                COMMA -> position++
                ARRAY_CLOSE -> {
                    position++
                    builder.endArray()
                    return
                }

                else -> fail("expected ',' or ']'")
            }
        }
    }

    /**
     * Reads a quoted string into [tokenSource]/[tokenStart]/[tokenLength].
     *
     * The common case — no escapes — leaves the bytes where they are and points the token at the
     * input, so a field value travels from the network buffer into the encoded document without a
     * single character being decoded.
     */
    private fun readString() {
        val open = position
        position++
        val start = position
        while (position < input.size) {
            when (val byte = byteAt(position)) {
                QUOTE -> {
                    validateUtf8(start, position)
                    tokenSource = input
                    tokenStart = start
                    tokenLength = position - start
                    position++
                    return
                }

                BACKSLASH -> return readEscapedString(open, start)
                else -> {
                    if (byte < SPACE) fail("unescaped control character ${describe(byte)} in a string")
                    position++
                }
            }
        }
        fail("unterminated string", open)
    }

    /** The slow path, entered at the first backslash: literal runs and escapes are joined in [scratch]. */
    private fun readEscapedString(open: Int, start: Int) {
        scratch.clear()
        var runStart = start
        while (true) {
            if (position >= input.size) fail("unterminated string", open)
            when (val byte = byteAt(position)) {
                QUOTE -> {
                    appendRun(runStart)
                    position++
                    tokenSource = scratch.backing
                    tokenStart = 0
                    tokenLength = scratch.size
                    return
                }

                BACKSLASH -> {
                    appendRun(runStart)
                    position++
                    readEscape()
                    runStart = position
                }

                else -> {
                    if (byte < SPACE) fail("unescaped control character ${describe(byte)} in a string")
                    position++
                }
            }
        }
    }

    private fun appendRun(runStart: Int) {
        validateUtf8(runStart, position)
        scratch.write(input, runStart, position - runStart)
    }

    private fun readEscape() {
        if (position >= input.size) fail("unterminated escape sequence")
        val escape = byteAt(position)
        position++
        when (escape) {
            QUOTE -> scratch.writeByte(QUOTE)
            BACKSLASH -> scratch.writeByte(BACKSLASH)
            SLASH -> scratch.writeByte(SLASH)
            'b'.code -> scratch.writeByte(0x08)
            'f'.code -> scratch.writeByte(0x0C)
            'n'.code -> scratch.writeByte(NEWLINE)
            'r'.code -> scratch.writeByte(CARRIAGE_RETURN)
            't'.code -> scratch.writeByte(TAB)
            'u'.code -> readUnicodeEscape()
            else -> fail("invalid escape \\${describe(escape)}", position - 1)
        }
    }

    /**
     * Decodes `\uXXXX`, joining a surrogate pair into one code point.
     *
     * An unpaired surrogate is rejected rather than substituted. It has no UTF-8 encoding, so
     * accepting one would mean storing something that is not the string the caller wrote.
     */
    private fun readUnicodeEscape() {
        val escapeStart = position - 2
        val first = readHex4(escapeStart)
        val codePoint = when {
            first in HIGH_SURROGATES -> {
                if (position + 1 >= input.size || byteAt(position) != BACKSLASH || byteAt(position + 1) != 'u'.code) {
                    fail("unpaired high surrogate \\u${first.toString(16).padStart(4, '0')}", escapeStart)
                }
                position += 2
                val second = readHex4(escapeStart)
                if (second !in LOW_SURROGATES) {
                    fail("high surrogate is followed by a non-surrogate escape", escapeStart)
                }
                0x10000 + ((first - 0xD800) shl 10) + (second - 0xDC00)
            }

            first in LOW_SURROGATES ->
                fail("unpaired low surrogate \\u${first.toString(16).padStart(4, '0')}", escapeStart)

            else -> first
        }
        writeUtf8(codePoint)
    }

    private fun readHex4(escapeStart: Int): Int {
        if (position + 4 > input.size) fail("truncated \\u escape", escapeStart)
        var value = 0
        repeat(4) {
            val digit = when (val byte = byteAt(position)) {
                in ZERO..NINE -> byte - ZERO
                in 'a'.code..'f'.code -> byte - 'a'.code + 10
                in 'A'.code..'F'.code -> byte - 'A'.code + 10
                else -> fail("'${describe(byte)}' is not a hexadecimal digit")
            }
            value = (value shl 4) or digit
            position++
        }
        return value
    }

    private fun writeUtf8(codePoint: Int) {
        when {
            codePoint < 0x80 -> scratch.writeByte(codePoint)
            codePoint < 0x800 -> {
                scratch.writeByte(0xC0 or (codePoint ushr 6))
                scratch.writeByte(0x80 or (codePoint and 0x3F))
            }

            codePoint < 0x10000 -> {
                scratch.writeByte(0xE0 or (codePoint ushr 12))
                scratch.writeByte(0x80 or ((codePoint ushr 6) and 0x3F))
                scratch.writeByte(0x80 or (codePoint and 0x3F))
            }

            else -> {
                scratch.writeByte(0xF0 or (codePoint ushr 18))
                scratch.writeByte(0x80 or ((codePoint ushr 12) and 0x3F))
                scratch.writeByte(0x80 or ((codePoint ushr 6) and 0x3F))
                scratch.writeByte(0x80 or (codePoint and 0x3F))
            }
        }
    }

    /**
     * Scans a number to the JSON grammar exactly: no leading `+`, no leading zeros, no bare `.5`,
     * no trailing `1.`, and at least one digit after an exponent marker.
     *
     * Integers of up to 18 digits are accumulated during the scan and appended without ever
     * building a `String`, which is the shape most numbers in machine-generated JSON have.
     */
    private fun parseNumber() {
        val start = position
        var negative = false
        if (peek() == MINUS) {
            negative = true
            position++
        }
        if (position >= input.size) fail("unexpected end of input in a number")

        var magnitude = 0L
        var digits = 0
        when (val first = byteAt(position)) {
            ZERO -> {
                position++
                digits = 1
            }

            in (ZERO + 1)..NINE -> {
                while (position < input.size && byteAt(position) in ZERO..NINE) {
                    if (digits < SAFE_LONG_DIGITS) magnitude = magnitude * 10 + (byteAt(position) - ZERO)
                    digits++
                    position++
                }
            }

            else -> fail("expected a digit, found ${describe(first)}")
        }

        var integral = true
        if (peek() == POINT) {
            integral = false
            position++
            requireDigits("a fraction")
        }
        if (peek() == 'e'.code || peek() == 'E'.code) {
            integral = false
            position++
            if (peek() == PLUS || peek() == MINUS) position++
            requireDigits("an exponent")
        }
        if (peek() in ZERO..NINE || peek() == POINT) {
            fail("invalid number: a digit may not follow ${String(input, start, position - start, Charsets.US_ASCII)}")
        }

        if (integral && digits <= SAFE_LONG_DIGITS) {
            builder.appendLong(if (negative) -magnitude else magnitude)
        } else {
            builder.appendNumberLiteral(String(input, start, position - start, Charsets.US_ASCII))
        }
    }

    private fun requireDigits(what: String) {
        if (peek() !in ZERO..NINE) fail("expected at least one digit in $what")
        while (position < input.size && byteAt(position) in ZERO..NINE) position++
    }

    private fun expectKeyword(keyword: String) {
        val start = position
        for (index in keyword.indices) {
            if (position >= input.size || byteAt(position) != keyword[index].code) {
                fail("expected '$keyword'", start)
            }
            position++
        }
    }

    private fun skipWhitespace() {
        while (position < input.size) {
            when (byteAt(position)) {
                SPACE, TAB, CARRIAGE_RETURN -> position++
                NEWLINE -> {
                    position++
                    line++
                    lineStart = position
                }

                else -> return
            }
        }
    }

    private fun rejectByteOrderMark() {
        if (input.size >= 3 && byteAt(0) == 0xEF && byteAt(1) == 0xBB && byteAt(2) == 0xBF) {
            fail("input begins with a UTF-8 byte order mark, which JSON does not allow", 0)
        }
    }

    /**
     * Validates `[from, to)` as strict UTF-8.
     *
     * Overlong forms, encoded surrogates and code points above U+10FFFF are all rejected. They have
     * to be: those bytes are how a payload smuggles a string past one validator that a second
     * validator reads differently, and this engine has to hand out exactly the bytes it was given.
     */
    private fun validateUtf8(from: Int, to: Int) {
        var index = from
        while (index < to) {
            val byte = byteAt(index)
            val continuations = when {
                byte < 0x80 -> 0
                byte in 0xC2..0xDF -> 1
                byte in 0xE0..0xEF -> 2
                byte in 0xF0..0xF4 -> 3
                else -> fail("invalid UTF-8 start byte ${describe(byte)}", index)
            }
            if (index + continuations >= to) fail("truncated UTF-8 sequence", index)
            // The ranges below exclude overlong encodings (0xE0/0xF0), UTF-16 surrogates encoded as
            // UTF-8 (0xED), and code points beyond U+10FFFF (0xF4).
            val firstContinuationRange = when (byte) {
                0xE0 -> 0xA0..0xBF
                0xED -> 0x80..0x9F
                0xF0 -> 0x90..0xBF
                0xF4 -> 0x80..0x8F
                else -> 0x80..0xBF
            }
            for (step in 1..continuations) {
                val continuation = byteAt(index + step)
                val allowed = if (step == 1) firstContinuationRange else 0x80..0xBF
                if (continuation !in allowed) fail("invalid UTF-8 continuation byte", index + step)
            }
            index += continuations + 1
        }
    }

    private fun byteAt(index: Int): Int = input[index].toInt() and 0xFF

    /** The byte at the cursor, or `-1` at end of input, so callers can compare without bounds checks. */
    private fun peek(): Int = if (position < input.size) byteAt(position) else -1

    private fun describe(byte: Int): String = when {
        byte < 0 -> "end of input"
        byte in SPACE..0x7E -> "'${byte.toChar()}'"
        else -> "byte 0x${byte.toString(16).uppercase().padStart(2, '0')}"
    }

    private fun fail(message: String, at: Int = position): Nothing =
        throw JsonParseException(message, at, lineOf(at), at - lineStartOf(at) + 1)

    // Positions before the cursor are only used for error reporting, so recovering their line is
    // allowed to be a scan; positions at the cursor use the counters maintained during the parse.
    private fun lineOf(at: Int): Int =
        if (at >= lineStart) line else 1 + (0 until at).count { byteAt(it) == NEWLINE }

    private fun lineStartOf(at: Int): Int =
        if (at >= lineStart) lineStart else ((0 until at).lastOrNull { byteAt(it) == NEWLINE }?.plus(1) ?: 0)

    private companion object {
        val HIGH_SURROGATES = 0xD800..0xDBFF
        val LOW_SURROGATES = 0xDC00..0xDFFF
    }
}
