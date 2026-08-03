package app.oreshkov.rabosh.variant

// RFC 9535 §2.7's spelling for a location: the interchange form, beside this engine's own.
//
// `VariantPath.toString` and `VariantPath.parse` are the engine's spelling and they are *written to
// disk* — a path is persisted by the index registry and the sketch sidecar as its rendered text and
// read back by `parse` — so nothing in this file may change either of them. What lives here is the
// second rendering, the one another implementation can read, in its own file for the reason
// `VariantSummary.kt` is not inside `VariantJson.kt`: two renderings of one value in one file is how
// the wrong one gets called.
//
// The grammar is §2.7's, transcribed rather than interpreted:
//
//     normalized-path       = root-identifier *(normal-index-segment)
//     normal-index-segment  = "[" normal-selector "]"
//     normal-selector       = normal-name-selector / normal-index-selector
//     normal-name-selector  = %x27 *normal-single-quoted %x27          ; 'name'
//     normal-single-quoted  = normal-unescaped / ESC normal-escapable
//     normal-unescaped      = %x20-26 / %x28-5B / %x5D-D7FF / %xE000-10FFFF
//     normal-escapable      = %x62 / %x66 / %x6E / %x72 / %x74         ; b f n r t
//                           / %x27 / %x5C                              ; ' \
//                           / (%x75 normal-hexchar)                    ; uXXXX
//     normal-hexchar        = "0" "0" ( ("0" %x30-37)                  ; 00-07
//                                     / ("0" %x62)                     ; 0b
//                                     / ("0" %x65-66)                  ; 0e-0f
//                                     / ("1" normal-HEXDIG) )          ; 10-1f
//     normal-HEXDIG         = DIGIT / %x61-66                          ; lowercase only
//     normal-index-selector = "0" / (DIGIT1 *DIGIT)
//
// Three consequences are worth stating here, because each is something a reader would otherwise
// "fix". A double quote is **not** escaped — 0x22 sits inside `%x20-26` — and neither is a solidus.
// `normal-hexchar` reaches exactly the C0 controls that have no named escape, and only in lowercase.
// And an unpaired surrogate has no production at all: `normal-unescaped` stops at D7FF and
// `normal-hexchar` cannot climb above 0x1f, so there is nothing to render one as.
//
// All three are one property said three ways — **one spelling per name** — which is what makes this
// the interchange direction, and what `toString`, with its dot-or-bracket fork, cannot offer.

/** U+000C. Kotlin has no character escape for it, so it is named once rather than written raw. */
private const val FORM_FEED: Char = '\u000C'

/** Lowercase, because `normal-HEXDIG` is. */
private const val HEX_DIGITS: String = "0123456789abcdef"

/** Digits in a `\u` escape. Always four: `normal-hexchar` has no short form. */
private const val HEX_ESCAPE_DIGITS: Int = 4

/** What `a` is worth as a hexadecimal digit. */
private const val HEX_LETTER_BASE: Int = 10

/** Renders [steps] in §2.7's form. See [VariantPath.toNormalizedPath] for the contract. */
internal fun StringBuilder.appendNormalizedPath(steps: List<VariantPathStep>) {
    append('$')
    for ((position, step) in steps.withIndex()) {
        when (step) {
            is VariantPathStep.Field -> {
                append('[').append('\'')
                appendNormalizedName(step.name, position)
                append('\'').append(']')
            }

            // Non-negative by construction: `VariantPathStep.Index` requires it, which is what
            // `normal-index-selector` requires too. There is nothing left to check here.
            is VariantPathStep.Index -> append('[').append(step.index).append(']')
        }
    }
}

private fun StringBuilder.appendNormalizedName(name: String, step: Int) {
    var position = 0
    while (position < name.length) {
        when (val character = name[position]) {
            '\'' -> append("\\'")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            FORM_FEED -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")

            else -> when {
                character < ' ' -> appendControlEscape(character)

                character.isHighSurrogate() -> {
                    val low = name.getOrNull(position + 1)
                    if (low == null || !low.isLowSurrogate()) unpairedSurrogate(character, position, step)
                    append(character).append(low)
                    position++
                }

                character.isLowSurrogate() -> unpairedSurrogate(character, position, step)

                else -> append(character)
            }
        }
        position++
    }
}

/** The C0 controls `normal-escapable` does not name: `\u00xx`, lowercase, always four digits. */
private fun StringBuilder.appendControlEscape(character: Char) {
    val code = character.code
    append("\\u00")
    append(HEX_DIGITS[(code shr 4) and 0xF])
    append(HEX_DIGITS[code and 0xF])
}

private fun unpairedSurrogate(character: Char, position: Int, step: Int): Nothing {
    val code = character.code.toString(16).uppercase().padStart(HEX_ESCAPE_DIGITS, '0')
    throw IllegalArgumentException(
        "a normalized path cannot spell the unpaired surrogate U+$code at position $position of the " +
            "field name in step $step: RFC 9535 §2.7 has no production for one, and a field name " +
            "decoded from UTF-8 cannot hold one",
    )
}

/** Parses §2.7's form. See [VariantPath.parseNormalized] for the contract and for the strictness. */
internal fun parseNormalizedPath(expression: String): VariantPath {
    var position = 0
    fun fail(message: String): Nothing =
        throw IllegalArgumentException("$message at position $position in normalized path '$expression'")

    fun expect(character: Char, message: String) {
        if (position >= expression.length || expression[position] != character) fail(message)
        position++
    }

    /** One `normal-single-quoted` run, the opening quote already consumed. */
    fun readName(): String = buildString {
        while (true) {
            if (position >= expression.length) fail("unterminated member name")
            when (val character = expression[position]) {
                '\'' -> {
                    position++
                    return@buildString
                }

                '\\' -> {
                    position++
                    if (position >= expression.length) fail("unterminated escape")
                    when (val escape = expression[position]) {
                        '\'' -> append('\'')
                        '\\' -> append('\\')
                        'b' -> append('\b')
                        'f' -> append(FORM_FEED)
                        'n' -> append('\n')
                        'r' -> append('\r')
                        't' -> append('\t')

                        'u' -> {
                            position++
                            if (position + HEX_ESCAPE_DIGITS > expression.length) {
                                fail("a '\\u' escape needs four hexadecimal digits")
                            }
                            val digits = expression.substring(position, position + HEX_ESCAPE_DIGITS)
                            append(
                                normalizedHexchar(digits) ?: fail(
                                    "'\\u$digits' is not one of RFC 9535's escapes: only a control " +
                                        "character without a named escape is spelled '\\u00xx', and " +
                                        "only in lowercase",
                                ),
                            )
                            // The trailing increment below covers the fourth digit.
                            position += HEX_ESCAPE_DIGITS - 1
                        }

                        else -> fail("'\\$escape' is not one of RFC 9535's escapes")
                    }
                    position++
                }

                else -> when {
                    character < ' ' ->
                        fail("a control character is escaped in a normalized path, never written raw")

                    character.isHighSurrogate() -> {
                        val low = expression.getOrNull(position + 1)
                        if (low == null || !low.isLowSurrogate()) {
                            fail("an unpaired surrogate is not a normalized path character")
                        }
                        append(character).append(low)
                        position += 2
                    }

                    character.isLowSurrogate() ->
                        fail("an unpaired surrogate is not a normalized path character")

                    else -> {
                        append(character)
                        position++
                    }
                }
            }
        }
    }

    /** One `normal-index-selector`. */
    fun readIndex(): Int {
        val start = position
        while (position < expression.length && expression[position] in '0'..'9') position++
        val digits = expression.substring(start, position)
        if (digits.length > 1 && digits[0] == '0') {
            position = start
            fail("an array index in a normalized path has no leading zero")
        }
        return digits.toIntOrNull() ?: run {
            position = start
            fail("an array index above ${Int.MAX_VALUE} is not representable as a VariantPath step")
        }
    }

    expect('$', "a normalized path starts with '$'")

    val steps = mutableListOf<VariantPathStep>()
    while (position < expression.length) {
        if (expression[position] == '.') {
            fail("a normalized path has no dot step: every member name is written as ['name']")
        }
        expect('[', "expected '[': a normalized path is bracket notation throughout")
        if (position >= expression.length) fail("unterminated '['")

        when (val opening = expression[position]) {
            '\'' -> {
                position++
                steps += VariantPathStep.Field(readName())
            }

            in '0'..'9' -> steps += VariantPathStep.Index(readIndex())

            '*' -> fail("a normalized path has no wildcard: it names exactly one location")

            '"' -> fail("a member name in a normalized path is single-quoted")

            else -> fail("expected a single-quoted member name or an array index, found '$opening'")
        }

        expect(']', "expected ']'")
    }
    return VariantPath(steps)
}

/**
 * The character `\u`[digits] spells, or `null` if §2.7 does not spell it that way.
 *
 * Rejecting is the whole job. `A` is a perfectly good JSON escape and a perfectly good way for
 * another implementation to write `A`, which is exactly why it is refused: a reader that accepted
 * both would give one location two spellings and make `parseNormalized(a) == parseNormalized(b)`
 * stop meaning what it says.
 */
private fun normalizedHexchar(digits: String): Char? {
    if (digits[0] != '0' || digits[1] != '0') return null
    val high = hexValue(digits[2]) ?: return null
    val low = hexValue(digits[3]) ?: return null
    val code = (high shl 4) or low
    return when {
        code >= ' '.code -> null
        // The five with a named escape are spelled with it, and only with it.
        code == '\b'.code || code == '\t'.code || code == '\n'.code -> null
        code == FORM_FEED.code || code == '\r'.code -> null
        else -> code.toChar()
    }
}

/** Uppercase is deliberately not accepted: `normal-HEXDIG` is `DIGIT / %x61-66`. */
private fun hexValue(character: Char): Int? = when (character) {
    in '0'..'9' -> character - '0'
    in 'a'..'f' -> character - 'a' + HEX_LETTER_BASE
    else -> null
}
