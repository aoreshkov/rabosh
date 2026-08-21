package app.oreshkov.rabosh.variant

// RFC 9535's `singular-query`, read as a location.
//
// This is the third reader in this module and the one that exists for a *consumer* rather than for
// the engine. `VariantPath.parse` reads the engine's own spelling — the one written to disk — and
// `parseNormalizedPath` reads §2.7's, which has exactly one spelling per location and is what
// `toNormalizedPath` emits. Neither reads what a person types, and the gap between those two facts
// is what this file closes: an expression handed to a JSONPath evaluator is spelled in §2.3.1.1's
// grammar, with either quoting and RFC escapes, and a caller wanting to know whether it names one
// location had no way to ask.
//
// The grammar is a sub-language of `jsonpath-query`, and the sub-language is exactly §2.3.5.1's
// `singular-query` without the current-node form:
//
//     singular-location  = root-identifier *singular-segment
//     singular-segment   = ("." member-name-shorthand) / ("[" S singular-selector S "]")
//     singular-selector  = name-selector / non-negative-index
//     name-selector      = string-literal                          ; either quoting, §2.3.1.1
//
// **Everything else answers `null`, and so does anything malformed.** The two are one answer on
// purpose: the question this reader is asked is "does this expression name exactly one location",
// and a caller who gets `null` falls back to whatever it did before. A wildcard does not name one
// location, and neither does a typo. Reporting them apart would be a second contract nobody asked
// for — and it is `CatalogPath.parseJsonPath` one module up, which does exactly that, that a caller
// wanting the distinction should reach for.
//
// **A negative index is `null` rather than an error.** RFC 9535 has `[-1]`, and it *is* a singular
// query — but which location it names depends on the array's length, so it names one location per
// document rather than one location. `VariantPathStep.Index` cannot hold it and should not.

/** U+000C. Kotlin has no character escape for it, so it is named once rather than written raw. */
private const val FORM_FEED: Char = '\u000C'

/** Below this, a character is a C0 control and cannot stand raw inside a string literal. */
private const val CONTROL_LIMIT: Int = 0x20

/** Digits in a `\u` escape. Always four. */
private const val HEX_ESCAPE_DIGITS: Int = 4

/** What `a` is worth as a hexadecimal digit. */
private const val HEX_LETTER_BASE: Int = 10

/** `name-first` and `name-char` admit %x80-D7FF and %xE000-10FFFF; this is where that range starts. */
private const val NON_ASCII_NAME_START: Int = 0x80

/** Parses the sub-language above. See [VariantPath.parseJsonPathOrNull] for the contract. */
internal fun parseSingularJsonPathOrNull(expression: String): VariantPath? =
    SingularJsonPathReader(expression).read()

/**
 * The reader.
 *
 * Failure is a `null` return threaded through every method rather than an exception caught at the
 * top, because the contract is that this function throws nothing at all: an exception used as
 * control flow here would be one `catch` away from escaping, and the whole point of the nullable
 * return is that a caller does not have to write that `catch`.
 */
private class SingularJsonPathReader(private val text: String) {

    private var position = 0

    fun read(): VariantPath? {
        if (peek() != '$') return null
        position++

        val steps = mutableListOf<VariantPathStep>()
        while (position < text.length) {
            steps += when (peek()) {
                '.' -> readShorthandStep() ?: return null
                '[' -> readBracketedStep() ?: return null
                else -> return null
            }
        }
        return VariantPath(steps)
    }

    /** After `.`: a member name. `.*` is a wildcard and `..` a descendant; neither names one location. */
    private fun readShorthandStep(): VariantPathStep.Field? {
        position++
        val start = position
        if (!consumeNameCharacter(first = true)) return null
        while (consumeNameCharacter(first = false)) {
            // Consumed by the call in the condition.
        }
        return VariantPathStep.Field(text.substring(start, position))
    }

    private fun consumeNameCharacter(first: Boolean): Boolean {
        val character = peek() ?: return false
        return when {
            character in 'A'..'Z' || character in 'a'..'z' || character == '_' -> {
                position++
                true
            }

            !first && character in '0'..'9' -> {
                position++
                true
            }

            character.isHighSurrogate() -> {
                val low = peekAt(1)
                if (low == null || !low.isLowSurrogate()) {
                    false
                } else {
                    position += 2
                    true
                }
            }

            character.isLowSurrogate() -> false

            character.code >= NON_ASCII_NAME_START -> {
                position++
                true
            }

            else -> false
        }
    }

    /** `"[" S singular-selector S "]"`. */
    private fun readBracketedStep(): VariantPathStep? {
        position++
        skipBlanks()
        val step = when (val character = peek() ?: return null) {
            '\'', '"' -> readQuotedName(character)?.let { VariantPathStep.Field(it) }
            in '0'..'9' -> readIndex()?.let { VariantPathStep.Index(it) }
            else -> null
        } ?: return null
        skipBlanks()
        if (peek() != ']') return null
        position++
        return step
    }

    /**
     * `int = "0" / (["-"] DIGIT1 *DIGIT)`, restricted to what a step can hold.
     *
     * A leading zero is refused because RFC 9535 refuses it, and a value above `Int.MAX_VALUE`
     * because `VariantPathStep.Index` is an `Int` — a location this engine cannot name is not one
     * this reader may round to.
     */
    private fun readIndex(): Int? {
        val start = position
        while (position < text.length && text[position] in '0'..'9') position++
        val digits = text.substring(start, position)
        if (digits.length > 1 && digits[0] == '0') return null
        return digits.toIntOrNull()
    }

    /** `string-literal`, either quoting, with RFC 9535 §2.3.1.1's escapes and not this engine's. */
    private fun readQuotedName(quote: Char): String? {
        position++
        val name = StringBuilder()
        while (true) {
            when (val character = peek() ?: return null) {
                quote -> {
                    position++
                    return name.toString()
                }

                '\\' -> if (!readEscape(quote, name)) return null

                // The *other* quote is an ordinary character: `unescaped` excludes only the one that
                // opened the literal.
                '\'', '"' -> {
                    name.append(character)
                    position++
                }

                else -> when {
                    character.code < CONTROL_LIMIT -> return null

                    character.isHighSurrogate() -> {
                        val low = peekAt(1)
                        if (low == null || !low.isLowSurrogate()) return null
                        name.append(character).append(low)
                        position += 2
                    }

                    character.isLowSurrogate() -> return null

                    else -> {
                        name.append(character)
                        position++
                    }
                }
            }
        }
    }

    private fun readEscape(quote: Char, name: StringBuilder): Boolean {
        position++
        when (peek() ?: return false) {
            'b' -> name.append('\b')
            'f' -> name.append(FORM_FEED)
            'n' -> name.append('\n')
            'r' -> name.append('\r')
            't' -> name.append('\t')
            '/' -> name.append('/')
            '\\' -> name.append('\\')

            // Only the quote that opened the literal may be escaped: `escapable` names neither, and
            // each quoting adds back exactly its own.
            quote -> name.append(quote)

            'u' -> return readUnicodeEscape(name)

            else -> return false
        }
        position++
        return true
    }

    /** `hexchar`, including the surrogate pairing rule §2.3.1.1 states structurally. */
    private fun readUnicodeEscape(name: StringBuilder): Boolean {
        position++
        val first = readHexEscape() ?: return false
        return when {
            first.toChar().isHighSurrogate() -> {
                if (peek() != '\\' || peekAt(1) != 'u') return false
                position += 2
                val second = readHexEscape() ?: return false
                if (!second.toChar().isLowSurrogate()) return false
                name.append(first.toChar()).append(second.toChar())
                true
            }

            first.toChar().isLowSurrogate() -> false

            else -> {
                name.append(first.toChar())
                true
            }
        }
    }

    private fun readHexEscape(): Int? {
        if (position + HEX_ESCAPE_DIGITS > text.length) return null
        var value = 0
        repeat(HEX_ESCAPE_DIGITS) {
            val digit = hexValue(text[position]) ?: return null
            value = (value shl 4) or digit
            position++
        }
        return value
    }

    private fun hexValue(character: Char): Int? = when (character) {
        in '0'..'9' -> character - '0'
        in 'a'..'f' -> character - 'a' + HEX_LETTER_BASE
        in 'A'..'F' -> character - 'A' + HEX_LETTER_BASE
        else -> null
    }

    /**
     * `S = *B`, and deliberately not `Char.isWhitespace`, which is true of a form feed and of every
     * Unicode space separator — none of which RFC 9535 allows between a bracket and a selector.
     */
    private fun skipBlanks() {
        while (position < text.length) {
            when (text[position]) {
                ' ', '\t', '\n', '\r' -> position++
                else -> return
            }
        }
    }

    private fun peek(): Char? = text.getOrNull(position)

    private fun peekAt(ahead: Int): Char? = text.getOrNull(position + ahead)
}
