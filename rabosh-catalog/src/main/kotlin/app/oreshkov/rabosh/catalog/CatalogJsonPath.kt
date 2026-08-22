package app.oreshkov.rabosh.catalog

// RFC 9535's spelling for a location *shape*, beside this engine's own.
//
// `CatalogPath.toString` and `CatalogPath.parse` are the engine's spelling and they are *written to
// disk* — the index registry persists a path as its rendered text and reads it back with `parse` —
// so nothing in this file may change either of them. What lives here is the second rendering, the
// one another implementation can read, in its own file for the reason `NormalizedPath.kt` is not
// inside `VariantPath.kt`: two renderings of one value in one file is how the wrong one gets called.
//
// The grammar accepted here is a **sub-language** of RFC 9535's `jsonpath-query`, transcribed rather
// than interpreted:
//
//     catalog-query      = root-identifier *catalog-segment
//     catalog-segment    = ("." (member-name-shorthand / "*")) / ("[" S catalog-selector S "]")
//     catalog-selector   = name-selector / "*" / ":"
//     name-selector      = string-literal                          ; either quoting, §2.3.1.1
//     member-name-shorthand = name-first *name-char
//
// Everything RFC 9535 has and this does not — `[0]`, `..`, `[?…]`, a slice with bounds, two
// selectors in one segment — is a `PathNotRepresentableException` naming the construct, and never an
// approximation. Widening `$.items[0]` to `$.items[*]` would answer a question the caller did not
// ask, which is the rule `CatalogPath.parse` already keeps.
//
// **The two directions are deliberately asymmetric about the wildcard, and the asymmetry is the
// whole design.** `AnyElement` selects array elements; RFC 9535's `*` selects every child, of an
// object as well as an array. So:
//
//   - reading, `[*]` and `.*` are accepted **as `AnyElement`** — that is what a consumer types, and
//     refusing it would make the interchange useless for the common case;
//   - writing, `AnyElement` is emitted as the slice `[:]`, which §2.3.4.2.2 defines over arrays
//     alone — "It selects no nodes from a node that is not an array" — so the rendering means
//     exactly what the walk means, over every document shape.
//
// Postel's rule, applied where the meanings genuinely differ. `NodeWalkDifferentialTest` is where
// the equality between `[:]` and `AnyElement` is pinned rather than asserted here in prose, and it
// is also where the divergence of `[*]` is pinned. Do not "fix" this into a symmetry.
//
// **The accepted slice is `[:]` and only `[:]`.** `[::]` and `[::1]` select the same nodes and are
// refused all the same: `[::-1]` reverses, so accepting a step at all means reasoning about which
// steps preserve order, and one literal spelling has no such reasoning in it. Widening later is
// compatible; narrowing later is not.

/** U+000C. Kotlin has no character escape for it, so it is named once rather than written raw. */
private const val FORM_FEED: Char = '\u000C'

/** Below this, a character is a C0 control and cannot stand raw inside a string literal. */
private const val CONTROL_LIMIT: Int = 0x20

/** Digits in a `\u` escape. Always four. */
private const val HEX_ESCAPE_DIGITS: Int = 4

/** Lowercase: `hexchar` allows either case on input, and one case is fewer branches on output. */
private const val HEX_DIGITS: String = "0123456789abcdef"

/** What `a` is worth as a hexadecimal digit. */
private const val HEX_LETTER_BASE: Int = 10

/** `name-first` and `name-char` admit %x80-D7FF and %xE000-10FFFF; this is where that range starts. */
private const val NON_ASCII_NAME_START: Int = 0x80

/**
 * `B = %x20 / %x09 / %x0A / %x0D`, and deliberately not `Char.isWhitespace`.
 *
 * The standard predicate is true of a form feed, a vertical tab and every Unicode space separator,
 * none of which RFC 9535 allows between a bracket and a selector. A reader that accepted them would
 * take expressions the reference parser one module over rejects, which is the one thing an
 * interchange reader may not do.
 */
private fun Char.isRfcBlank(): Boolean = this == ' ' || this == '\t' || this == '\n' || this == '\r'

/** Renders [steps] as an RFC 9535 query. See [CatalogPath.toJsonPath] for the contract. */
internal fun StringBuilder.appendJsonPath(steps: List<CatalogStep>) {
    append('$')
    for ((position, step) in steps.withIndex()) {
        when (step) {
            is CatalogStep.Field -> {
                append('[').append('\'')
                appendJsonPathName(step.name, position)
                append('\'').append(']')
            }

            CatalogStep.AnyElement -> append("[:]")

            // `..` and the step after it are one `descendant-segment` there, so nothing is emitted
            // here and the next step writes the selector — `[:]` or `['name']`, both of which the
            // production admits. A descendant with no step after it is not a query, and `toJsonPath`
            // says why rather than approximating it.
            CatalogStep.AnyDescendant -> {
                if (position == steps.size - 1) danglingDescendant()
                append("..")
            }
        }
    }
}

private fun danglingDescendant(): Nothing = throw IllegalArgumentException(
    "a path ending in '..' has no JSONPath rendering: RFC 9535's descendant segment must carry a " +
        "selector, and the nearest expression — '\$..*' — selects every node below the root and not " +
        "the root itself, which is a different set",
)

/**
 * One `single-quoted` run.
 *
 * A control character is written `\uXXXX` even where a named escape exists, because both are
 * `escapable` and one form is fewer branches. That is allowed *here* and would not be in a
 * Normalized Path, where §2.7 gives each name exactly one spelling — which is the difference between
 * a query and an interchange identity, and the reason `VariantPath.toNormalizedPath` cannot be
 * reused for this.
 */
private fun StringBuilder.appendJsonPathName(name: String, step: Int) {
    var position = 0
    while (position < name.length) {
        when (val character = name[position]) {
            '\'' -> append("\\'")
            '\\' -> append("\\\\")

            else -> when {
                character.code < CONTROL_LIMIT -> appendControlEscape(character)

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

private fun StringBuilder.appendControlEscape(character: Char) {
    val code = character.code
    append("\\u00")
    append(HEX_DIGITS[(code shr 4) and 0xF])
    append(HEX_DIGITS[code and 0xF])
}

private fun unpairedSurrogate(character: Char, position: Int, step: Int): Nothing {
    val code = character.code.toString(16).uppercase().padStart(HEX_ESCAPE_DIGITS, '0')
    throw IllegalArgumentException(
        "a JSONPath query cannot spell the unpaired surrogate U+$code at position $position of the " +
            "field name in step $step: RFC 9535's `unescaped` stops at D7FF and `hexchar` pairs its " +
            "surrogates, and a field name decoded from UTF-8 cannot hold one",
    )
}

/** Parses the sub-language above. See [CatalogPath.parseJsonPath] for the contract. */
internal fun parseJsonPathQuery(expression: String): CatalogPath = JsonPathShapeReader(expression).read()

/**
 * The reader, as a class rather than a function with nested closures, because it has two failure
 * verbs rather than one and both need the cursor.
 */
private class JsonPathShapeReader(private val text: String) {

    private var position = 0

    fun read(): CatalogPath {
        if (peek() != '$') fail("a JSONPath query starts with '$'")
        position++

        val steps = mutableListOf<CatalogStep>()
        while (position < text.length) {
            when (peek()) {
                '.' -> readDotSegment(steps)
                '[' -> steps += readBracketedSegment()
                else -> fail("expected '.' or '['")
            }
        }
        return CatalogPath(steps)
    }

    /**
     * After `.`: a member name, `*`, or the second dot that makes it a descendant segment.
     *
     * **A descendant segment is two steps here and one segment there**, which is the whole of the
     * translation: RFC 9535 writes `..` *and its selector* as one segment, so `$..['a']` is
     * [CatalogStep.AnyDescendant] followed by a field. That is also why a bare `$..` is refused —
     * `descendant-segment` must carry a selector, so the expression this type spells `$..` is simply
     * not a JSONPath query, which is the same fact [CatalogPath.toJsonPath] states from the other
     * side by refusing to render one.
     */
    private fun readDotSegment(steps: MutableList<CatalogStep>) {
        position++
        if (peek() != '.') {
            steps += if (peek() == '*') {
                position++
                CatalogStep.AnyElement
            } else {
                CatalogStep.Field(readShorthandName())
            }
            return
        }
        position++
        steps += CatalogStep.AnyDescendant
        when (peek()) {
            null -> fail("'..' must carry a selector: '$..' is not a JSONPath query, though it is a catalog path")
            '[' -> steps += readBracketedSegment()
            '*' -> {
                position++
                steps += CatalogStep.AnyElement
            }

            else -> steps += CatalogStep.Field(readShorthandName())
        }
    }

    /** `member-name-shorthand = name-first *name-char`, which is why `$.@type` does not parse. */
    private fun readShorthandName(): String {
        val start = position
        if (!consumeNameCharacter(first = true)) {
            fail("expected a member name or '*' after '.'; a name outside [A-Za-z0-9_] needs the bracket form")
        }
        while (consumeNameCharacter(first = false)) {
            // Consumed by the call in the condition.
        }
        return text.substring(start, position)
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

    /** `"[" S catalog-selector S "]"`, and a named refusal for every selector that is not one. */
    private fun readBracketedSegment(): CatalogStep {
        val open = position
        position++
        skipBlanks()
        val step = when (val character = peek() ?: fail("unterminated '['")) {
            '\'', '"' -> CatalogStep.Field(readQuotedName(character))

            '*' -> {
                position++
                CatalogStep.AnyElement
            }

            ':' -> readWholeSlice(open)

            '?' -> {
                position = open
                refuse(
                    PathConstruct.FILTER,
                    "a filter selects candidate nodes and a predicate selects documents; the two " +
                        "are not the same question",
                )
            }

            else -> refuseNumericSelector(open)
        }
        skipBlanks()
        if (peek() == ',') {
            position = open
            refuse(PathConstruct.MULTIPLE_SELECTORS, "a segment selecting two things is not one path step")
        }
        if (peek() != ']') fail("expected ']'")
        position++
        return step
    }

    /**
     * `[:]` is `AnyElement`; every other slice is refused.
     *
     * The colon is already at the cursor. See the note at the top of this file for why the accepted
     * set is one literal spelling rather than "every slice that happens to select everything".
     */
    private fun readWholeSlice(open: Int): CatalogStep {
        position++
        skipBlanks()
        if (peek() != ']') {
            position = open
            refuse(
                PathConstruct.SLICE,
                "a slice with a bound or a step selects some elements; every element is spelled " +
                    "'[:]' or '[*]'",
            )
        }
        return CatalogStep.AnyElement
    }

    /**
     * An index, or a slice that started with one — told apart only to say which, since both are
     * refused. Anything that is neither is malformed rather than unrepresentable.
     */
    private fun refuseNumericSelector(open: Int): Nothing {
        var cursor = position
        var sawColon = false
        while (cursor < text.length) {
            val character = text[cursor]
            if (character == ':') sawColon = true
            if (character !in '0'..'9' && character != '-' && character != ':' && !character.isRfcBlank()) break
            cursor++
        }
        if (cursor == position) fail("expected a member name, '*' or ':'")
        position = open
        if (sawColon) {
            refuse(
                PathConstruct.SLICE,
                "a slice with a bound or a step selects some elements; every element is spelled " +
                    "'[:]' or '[*]'",
            )
        }
        refuse(
            PathConstruct.INDEX,
            "an index names one element by position and a catalog path collapses positions; every " +
                "element is spelled '[*]'",
        )
    }

    /** `string-literal`, either quoting, with RFC 9535 §2.3.1.1's escapes and not this engine's. */
    private fun readQuotedName(quote: Char): String {
        position++
        val name = StringBuilder()
        while (true) {
            when (val character = peek() ?: fail("unterminated member name")) {
                quote -> {
                    position++
                    return name.toString()
                }

                '\\' -> readEscape(quote, name)

                // The *other* quote is an ordinary character: `unescaped` excludes only the one that
                // opened the literal, which is what makes `'a"b'` and `"a'b"` legal and unremarkable.
                '\'', '"' -> {
                    name.append(character)
                    position++
                }

                else -> when {
                    character.code < CONTROL_LIMIT ->
                        fail("a control character is escaped in a member name, never written raw")

                    character.isHighSurrogate() -> {
                        val low = peekAt(1)
                        if (low == null || !low.isLowSurrogate()) fail("an unpaired surrogate is not a name character")
                        name.append(character).append(low)
                        position += 2
                    }

                    character.isLowSurrogate() -> fail("an unpaired surrogate is not a name character")

                    else -> {
                        name.append(character)
                        position++
                    }
                }
            }
        }
    }

    private fun readEscape(quote: Char, name: StringBuilder) {
        position++
        when (val escape = peek() ?: fail("unterminated escape")) {
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

            'u' -> {
                readUnicodeEscape(name)
                return
            }

            else -> fail("'\\$escape' is not an escape RFC 9535 allows in a member name")
        }
        position++
    }

    /**
     * `hexchar`, including the surrogate pairing rule: a high surrogate must be followed by a second
     * `\u` escape carrying a low one, and a low surrogate may not stand alone. The ABNF says so
     * structurally rather than as prose, and it is the half a reused JSON string reader would not
     * have.
     */
    private fun readUnicodeEscape(name: StringBuilder) {
        position++
        val first = readHexEscape()
        when {
            first.toChar().isHighSurrogate() -> {
                if (peek() != '\\' || peekAt(1) != 'u') {
                    fail("a high surrogate escape must be followed by a low surrogate escape")
                }
                position += 2
                val second = readHexEscape()
                if (!second.toChar().isLowSurrogate()) {
                    fail("a high surrogate escape must be followed by a low surrogate escape")
                }
                name.append(first.toChar()).append(second.toChar())
            }

            first.toChar().isLowSurrogate() -> fail("a low surrogate escape must follow a high surrogate escape")

            else -> name.append(first.toChar())
        }
    }

    private fun readHexEscape(): Int {
        if (position + HEX_ESCAPE_DIGITS > text.length) fail("a '\\u' escape needs four hexadecimal digits")
        var value = 0
        repeat(HEX_ESCAPE_DIGITS) {
            val digit = hexValue(text[position]) ?: fail("a '\\u' escape needs four hexadecimal digits")
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

    /** `S = *B`, which RFC 9535 allows inside a bracketed selection and nowhere in a shorthand. */
    private fun skipBlanks() {
        while (position < text.length && text[position].isRfcBlank()) position++
    }

    private fun peek(): Char? = text.getOrNull(position)

    private fun peekAt(ahead: Int): Char? = text.getOrNull(position + ahead)

    private fun fail(message: String): Nothing =
        throw IllegalArgumentException("$message at position $position in JSONPath query '$text'")

    private fun refuse(construct: PathConstruct, message: String): Nothing =
        throw PathNotRepresentableException(
            construct,
            "$message — at position $position in JSONPath query '$text'",
        )
}
