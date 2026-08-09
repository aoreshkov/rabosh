package app.oreshkov.rabosh.jsonpath

import app.oreshkov.rabosh.variant.Variant
import app.oreshkov.rabosh.variant.VariantBuilder
import app.oreshkov.rabosh.variant.VariantKind

// RFC 9535's ABNF, transcribed into recursive descent with explicit lookahead.
//
// **Recursive descent rather than a PEG, and that is why the errata list can be read and set aside.**
// All five errata against RFC 9535 — 8343, 8352, 8353, 8354 and 8779, every one still *Held for
// Document Update* — are about prioritised-choice **ordering** in the grammar: a PEG parser trying
// `literal` before `singular-query` in `comparable`, or `logical-expr` before `filter-query` in
// `function-argument`, commits to the wrong alternative and cannot back out. A parser that chooses on
// the next character has no such failure mode. 8779 is a counter-proposal to 8354, so the ordering is
// still being argued eighteen months on; nothing in it changes a semantic, and nothing in it changes
// this file.
//
// The one place a decision genuinely cannot be made from the next character is a function argument,
// where `@.a` and `@.a == 1` start alike and mean different types. That one backtracks, explicitly,
// once, and `Mark` is what makes the backtrack complete — position **and** the two counters, because
// a re-parse that double-counted selectors would reject a legal query at a limit it had not reached.
//
// Errors are `IllegalArgumentException` carrying a position, matching `CatalogPath.parse` and
// `VariantPath.parseNormalized`. Rich errors are still not in the language — KEEP-0441 remains a
// motivation document as of Kotlin 2.4 — so there is nothing better to return, and a parser that
// throws on the first fault is what a *compile* step should do anyway.

/** Widest integer RFC 9535 §2.1 admits in a query: I-JSON's, which is 2^53 - 1. */
private const val MAX_JSON_INT: Long = 9007199254740991L

/**
 * How deeply one query may nest filters, parentheses and function calls.
 *
 * A bound on the *query*, not on the walk — the caller wrote the query, so refusing an absurd one
 * costs no answer, whereas a bound on the document would truncate a nodelist and a truncated
 * nodelist is a wrong answer with no signal. It is checked while parsing rather than afterwards,
 * because the parser is what recurses.
 */
private const val MAX_NESTING_DEPTH: Int = 64

/** How many selectors one query may hold. Bounds the evaluator's per-segment recursion. */
private const val MAX_SELECTORS: Int = 1024

/** Hexadecimal digits in a `\u` escape. */
private const val HEX_ESCAPE_DIGITS: Int = 4

/** The parser's saved state, for the one place that backtracks. */
private class Mark(val position: Int, val selectors: Int, val singularSpelling: Boolean)

/** What a `comparable`-or-`test-expr` position parsed to, before its type is known to be right. */
private sealed interface Operand {
    class Literal(val value: Variant) : Operand

    class Query(val query: QueryExpression) : Operand

    class Call(val call: FunctionCall) : Operand
}

/** A function argument, before it is matched against the declared parameter type. */
private sealed interface RawArgument {
    class Literal(val value: Variant) : RawArgument

    class Query(val query: QueryExpression) : RawArgument

    class Call(val call: FunctionCall) : RawArgument

    class Logical(val expression: FilterExpression) : RawArgument
}

internal class JsonPathParser(private val text: String) {

    private var position = 0
    private var depth = 0
    private var selectors = 0

    /**
     * Whether everything parsed since the enclosing query began is spelled as a `singular-query`.
     *
     * Saved and restored around each nested query rather than kept per query, so that a filter deep
     * inside one query cannot decide the singularity of another. See [QueryExpression.singularSpelling].
     */
    private var singularSpelling = true

    /** The whole query. */
    fun parse(): List<Segment> {
        expect('$', "a JSONPath query starts with '$'")
        val segments = parseSegments()
        if (position != text.length) fail("expected a segment or the end of the query")
        return segments
    }

    // --- segments ----------------------------------------------------------------------------

    /**
     * `segments = *(S segment)`.
     *
     * The blanks are consumed *speculatively*: `S` may only precede a segment, so when no segment
     * follows the position is restored to before them. That is what makes `$ ` invalid — trailing
     * whitespace is not part of a query — and it is what lets this loop be reused inside a filter,
     * where `@.a && …` ends the query at the space rather than swallowing it.
     */
    private fun parseSegments(): List<Segment> {
        val segments = mutableListOf<Segment>()
        while (true) {
            val mark = position
            skipBlanks()
            val segment = parseSegmentOrNull()
            if (segment == null) {
                position = mark
                break
            }
            segments += segment
        }
        return segments
    }

    private fun parseSegmentOrNull(): Segment? = when (peek()) {
        '[' -> Segment.Child(parseBracketedSelection())

        '.' -> if (peekAt(1) == '.') {
            position += 2
            singularSpelling = false
            Segment.Descendant(parseDescendantSelection())
        } else {
            position++
            Segment.Child(listOf(parseShorthandSelector()))
        }

        else -> null
    }

    /** After `.`: `*` or a member name. No blanks: `member-name-shorthand` follows the dot directly. */
    private fun parseShorthandSelector(): Selector {
        countSelector()
        if (peek() == '*') {
            position++
            singularSpelling = false
            return Selector.Wildcard
        }
        val name = parseMemberNameShorthand() ?: fail("expected a member name or '*' after '.'")
        return Selector.Name(name)
    }

    /** After `..`: a bracketed selection, `*`, or a member name — and, again, no blanks before it. */
    private fun parseDescendantSelection(): List<Selector> = when {
        peek() == '[' -> parseBracketedSelection()

        peek() == '*' -> {
            countSelector()
            position++
            listOf(Selector.Wildcard)
        }

        else -> {
            countSelector()
            val name = parseMemberNameShorthand() ?: fail("expected '[', '*' or a member name after '..'")
            listOf(Selector.Name(name))
        }
    }

    /** `bracketed-selection = "[" S selector *(S "," S selector) S "]"`. */
    private fun parseBracketedSelection(): List<Selector> {
        expect('[', "expected '['")
        val selection = mutableListOf<Selector>()
        if (skipBlanksAny()) singularSpelling = false
        selection += parseSelector()
        while (true) {
            if (skipBlanksAny()) singularSpelling = false
            if (peek() != ',') break
            position++
            singularSpelling = false
            skipBlanks()
            selection += parseSelector()
        }
        expect(']', "expected ',' or ']'")
        return selection
    }

    private fun parseSelector(): Selector {
        countSelector()
        val character = peek() ?: fail("expected a selector")
        return when {
            character == '\'' || character == '"' -> Selector.Name(parseStringLiteral())

            character == '*' -> {
                position++
                singularSpelling = false
                Selector.Wildcard
            }

            character == '?' -> {
                position++
                singularSpelling = false
                skipBlanks()
                Selector.Filter(nested { parseLogicalExpression() })
            }

            character == ':' -> {
                singularSpelling = false
                parseSlice(start = null)
            }

            character == '-' || character.isAsciiDigit() -> {
                val value = parseInt()
                val mark = position
                skipBlanks()
                if (peek() == ':') {
                    singularSpelling = false
                    parseSlice(start = value)
                } else {
                    // Hand the blanks back so the caller sees them: `[0 ]` is a perfectly good
                    // bracketed selection and *not* a `singular-query`'s index segment.
                    position = mark
                    Selector.Index(value)
                }
            }

            else -> fail("expected a selector")
        }
    }

    /** `slice-selector = [start S] ":" S [end S] [":" [S step]]`, the start already consumed. */
    private fun parseSlice(start: Long?): Selector {
        expect(':', "expected ':'")
        skipBlanks()
        val end = if (atIntStart()) parseInt() else null
        if (end != null) skipBlanks()
        var step: Long? = null
        if (peek() == ':') {
            position++
            skipBlanks()
            if (atIntStart()) step = parseInt()
        }
        return Selector.Slice(start, end, step)
    }

    // --- filter expressions ------------------------------------------------------------------

    /** `logical-or-expr = logical-and-expr *(S "||" S logical-and-expr)`. */
    private fun parseLogicalExpression(): FilterExpression {
        val operands = mutableListOf(parseAndExpression())
        while (true) {
            val mark = position
            skipBlanks()
            if (!text.startsWith("||", position)) {
                position = mark
                break
            }
            position += 2
            skipBlanks()
            operands += parseAndExpression()
        }
        return if (operands.size == 1) operands[0] else FilterExpression.Or(operands)
    }

    /** `logical-and-expr = basic-expr *(S "&&" S basic-expr)`. */
    private fun parseAndExpression(): FilterExpression {
        val operands = mutableListOf(parseBasicExpression())
        while (true) {
            val mark = position
            skipBlanks()
            if (!text.startsWith("&&", position)) {
                position = mark
                break
            }
            position += 2
            skipBlanks()
            operands += parseBasicExpression()
        }
        return if (operands.size == 1) operands[0] else FilterExpression.And(operands)
    }

    /**
     * `basic-expr = paren-expr / comparison-expr / test-expr`.
     *
     * `!` attaches to a parenthesised expression or to a test, and to nothing else — `!@.a == 1` is
     * invalid rather than being read as `!(@.a == 1)`, because a comparison is not a `test-expr`.
     */
    private fun parseBasicExpression(): FilterExpression {
        if (peek() == '!') {
            position++
            skipBlanks()
            val operand = if (peek() == '(') parseParenExpression() else parseTestExpression()
            return FilterExpression.Not(operand)
        }
        if (peek() == '(') return parseParenExpression()
        return parseComparisonOrTest()
    }

    /** `paren-expr = "(" S logical-expr S ")"`. */
    private fun parseParenExpression(): FilterExpression = nested {
        expect('(', "expected '('")
        skipBlanks()
        val expression = parseLogicalExpression()
        skipBlanks()
        expect(')', "expected ')'")
        expression
    }

    /** A query or a function in a position where only a logical answer is admissible. */
    private fun parseTestExpression(): FilterExpression {
        val start = position
        return asTest(parseOperand(), start)
    }

    /**
     * The one place two productions start alike: `@.a` is a test, `@.a == 1` is a comparison.
     *
     * Resolved by parsing the left side once and then looking for an operator, rather than by trying
     * a comparison and backing out — which is the same reason this file is recursive descent at all.
     */
    private fun parseComparisonOrTest(): FilterExpression {
        val leftStart = position
        val left = parseOperand()
        val mark = position
        skipBlanks()
        val operator = parseComparisonOperatorOrNull()
        if (operator == null) {
            position = mark
            return asTest(left, leftStart)
        }
        skipBlanks()
        val rightStart = position
        val right = parseOperand()
        return FilterExpression.Comparison(asComparable(left, leftStart), operator, asComparable(right, rightStart))
    }

    private fun parseComparisonOperatorOrNull(): ComparisonOperator? {
        for (candidate in TWO_CHARACTER_OPERATORS) {
            if (text.startsWith(candidate.spelling, position)) {
                position += candidate.spelling.length
                return candidate
            }
        }
        return when (peek()) {
            '<' -> {
                position++
                ComparisonOperator.LESS
            }

            '>' -> {
                position++
                ComparisonOperator.GREATER
            }

            else -> null
        }
    }

    private fun parseOperand(): Operand {
        val character = peek() ?: fail("expected a literal, a query or a function")
        return when {
            character == '@' -> {
                position++
                Operand.Query(parseFilterQuery(QueryRoot.CURRENT))
            }

            character == '$' -> {
                position++
                Operand.Query(parseFilterQuery(QueryRoot.ROOT))
            }

            character == '\'' || character == '"' -> {
                val start = position
                Operand.Literal(literalVariant(start) { appendString(parseStringLiteral()) })
            }

            character == '-' || character.isAsciiDigit() -> {
                val start = position
                val literal = parseNumberLiteral()
                Operand.Literal(literalVariant(start) { appendNumberLiteral(literal) })
            }

            character in 'a'..'z' -> parseWordOperand()

            else -> fail("expected a literal, a query or a function")
        }
    }

    /**
     * A run of `function-name-char`s: one of the three keyword literals, or a function call.
     *
     * The fork is on the character *immediately* after the name, because `function-expr` puts no `S`
     * between a function name and its `(` — so `length (@.a)` is not a call, and having read the
     * name as a keyword the parser then reports that `length` is not a literal.
     */
    private fun parseWordOperand(): Operand {
        val start = position
        while (peek()?.isFunctionNameCharacter() == true) position++
        val word = text.substring(start, position)
        if (peek() == '(') return Operand.Call(parseFunctionCall(word, start))
        return when (word) {
            "true" -> Operand.Literal(literalVariant(start) { appendBoolean(true) })
            "false" -> Operand.Literal(literalVariant(start) { appendBoolean(false) })
            "null" -> Operand.Literal(literalVariant(start) { appendNull() })
            else -> {
                position = start
                fail("expected a literal, a query or a function")
            }
        }
    }

    /** `filter-query`, the general form: `@` or `$` followed by any segments at all. */
    private fun parseFilterQuery(root: QueryRoot): QueryExpression {
        val enclosing = singularSpelling
        singularSpelling = true
        val segments = parseSegments()
        val singular = singularSpelling
        singularSpelling = enclosing
        return QueryExpression(root, segments, singular)
    }

    // --- functions ---------------------------------------------------------------------------

    /** `function-expr = function-name "(" S [argument *(S "," S argument)] S ")"`. */
    private fun parseFunctionCall(name: String, nameStart: Int): FunctionCall = nested {
        val function = JsonPathFunction.ofSpelling(name) ?: run {
            position = nameStart
            fail("'$name' is not one of the function extensions RFC 9535 registers")
        }
        expect('(', "expected '('")
        skipBlanks()

        val raw = mutableListOf<Pair<RawArgument, Int>>()
        if (peek() != ')') {
            raw += parseFunctionArgument()
            while (true) {
                skipBlanks()
                if (peek() != ',') break
                position++
                skipBlanks()
                raw += parseFunctionArgument()
            }
            skipBlanks()
        }
        expect(')', "expected ',' or ')'")

        if (raw.size != function.parameters.size) {
            position = nameStart
            fail("'$name' takes ${function.parameters.size} argument(s), not ${raw.size}")
        }
        val arguments = raw.mapIndexed { index, (argument, start) ->
            checkArgument(function, index, argument, start)
        }
        FunctionCall(function, arguments, patternSourceOf(function, arguments))
    }

    /**
     * Compiles `match`'s and `search`'s I-Regexp, if the query wrote one down.
     *
     * Reached only after the arity check above, so `arguments[1]` exists whenever [function] takes a
     * pattern. A literal that is not a string, or is a string RFC 9485 does not admit, becomes
     * [PatternSource.Fixed] with no regexp rather than a parse failure — see its own documentation
     * for why refusing here would be wrong.
     */
    private fun patternSourceOf(function: JsonPathFunction, arguments: List<FunctionArgument>): PatternSource? {
        if (!function.takesPattern) return null
        val comparable = (arguments[1] as? FunctionArgument.Value)?.comparable
        val literal = (comparable as? ComparableExpression.Literal)?.value ?: return PatternSource.PerNode
        if (literal.kind != VariantKind.STRING) return PatternSource.Fixed(null)
        return PatternSource.Fixed(IRegexp.compileOrNull(literal.stringValue()))
    }

    /**
     * One argument, together with where it began so a type failure can point at it.
     *
     * **The one backtrack in this parser.** `function-argument` admits a bare query, a bare function
     * and a whole logical expression, and the first two are prefixes of the third. So a bare operand
     * is parsed and accepted only if what follows it closes the argument; otherwise the position and
     * both counters are restored and the argument is re-read as a logical expression. A PEG would
     * have had to order these alternatives, which is precisely what erratum 8354 is about.
     */
    private fun parseFunctionArgument(): Pair<RawArgument, Int> {
        val start = position
        val character = peek() ?: fail("expected an argument")
        if (character == '\'' || character == '"') {
            return RawArgument.Literal(literalVariant(start) { appendString(parseStringLiteral()) }) to start
        }
        if (character == '-' || character.isAsciiDigit()) {
            val literal = parseNumberLiteral()
            return RawArgument.Literal(literalVariant(start) { appendNumberLiteral(literal) }) to start
        }

        val mark = mark()
        val bare = parseBareArgumentOrNull()
        if (bare != null) {
            val afterOperand = position
            skipBlanks()
            if (peek() == ',' || peek() == ')') {
                position = afterOperand
                return bare to start
            }
        }
        reset(mark)
        return RawArgument.Logical(parseLogicalExpression()) to start
    }

    private fun parseBareArgumentOrNull(): RawArgument? = when {
        peek() == '@' -> {
            position++
            RawArgument.Query(parseFilterQuery(QueryRoot.CURRENT))
        }

        peek() == '$' -> {
            position++
            RawArgument.Query(parseFilterQuery(QueryRoot.ROOT))
        }

        peek()?.let { it in 'a'..'z' } == true -> when (val operand = parseWordOperand()) {
            is Operand.Call -> RawArgument.Call(operand.call)
            is Operand.Literal -> RawArgument.Literal(operand.value)
            is Operand.Query -> null
        }

        else -> null
    }

    /** RFC 9535 §2.4.3's well-typedness rules, one parameter at a time. */
    private fun checkArgument(
        function: JsonPathFunction,
        index: Int,
        argument: RawArgument,
        start: Int,
    ): FunctionArgument {
        val declared = function.parameters[index]
        val ordinal = index + 1
        return when (declared) {
            JsonPathType.VALUE -> when (argument) {
                is RawArgument.Literal -> FunctionArgument.Value(ComparableExpression.Literal(argument.value))

                is RawArgument.Query -> FunctionArgument.Value(
                    ComparableExpression.Singular(
                        argument.query.asSingularOrNull() ?: run {
                            position = start
                            fail(
                                "argument $ordinal of '${function.spelling}' is declared ValueType, so it " +
                                    "must be a literal, a singular query or a ValueType function",
                            )
                        },
                    ),
                )

                is RawArgument.Call -> if (argument.call.function.result == JsonPathType.VALUE) {
                    FunctionArgument.Value(ComparableExpression.Call(argument.call))
                } else {
                    position = start
                    fail(
                        "argument $ordinal of '${function.spelling}' is declared ValueType and " +
                            "'${argument.call.function.spelling}' returns ${argument.call.function.result}",
                    )
                }

                is RawArgument.Logical -> {
                    position = start
                    fail("argument $ordinal of '${function.spelling}' is declared ValueType, not LogicalType")
                }
            }

            // NodesType converts to LogicalType, and that is the only conversion RFC 9535 allows.
            JsonPathType.LOGICAL -> when (argument) {
                is RawArgument.Logical -> FunctionArgument.Logical(argument.expression)

                is RawArgument.Query -> FunctionArgument.Logical(FilterExpression.Existence(argument.query))

                is RawArgument.Call -> if (argument.call.function.result == JsonPathType.VALUE) {
                    position = start
                    fail(
                        "argument $ordinal of '${function.spelling}' is declared LogicalType and " +
                            "'${argument.call.function.spelling}' returns ValueType",
                    )
                } else {
                    FunctionArgument.Logical(FilterExpression.Call(argument.call))
                }

                is RawArgument.Literal -> {
                    position = start
                    fail("argument $ordinal of '${function.spelling}' is declared LogicalType, not a literal")
                }
            }

            JsonPathType.NODES -> when (argument) {
                is RawArgument.Query -> FunctionArgument.Nodes(argument.query)

                is RawArgument.Call -> if (argument.call.function.result == JsonPathType.NODES) {
                    position = start
                    // Unreachable while the registry holds no NodesType function; stated rather than
                    // assumed, so that adding one is a change here and not a silent miscompile.
                    fail("a NodesType function argument is not yet supported")
                } else {
                    position = start
                    fail(
                        "argument $ordinal of '${function.spelling}' is declared NodesType and " +
                            "'${argument.call.function.spelling}' returns ${argument.call.function.result}",
                    )
                }

                is RawArgument.Literal, is RawArgument.Logical -> {
                    position = start
                    fail("argument $ordinal of '${function.spelling}' is declared NodesType, so it must be a query")
                }
            }
        }
    }

    // --- typing an operand -------------------------------------------------------------------

    private fun asComparable(operand: Operand, start: Int): ComparableExpression = when (operand) {
        is Operand.Literal -> ComparableExpression.Literal(operand.value)

        is Operand.Query -> ComparableExpression.Singular(
            operand.query.asSingularOrNull() ?: run {
                position = start
                fail(
                    "only a singular query — name and index segments alone, with no blanks inside the " +
                        "brackets — may be compared, because a comparison is over one value",
                )
            },
        )

        is Operand.Call -> if (operand.call.function.result == JsonPathType.VALUE) {
            ComparableExpression.Call(operand.call)
        } else {
            position = start
            fail("'${operand.call.function.spelling}' returns ${operand.call.function.result} and cannot be compared")
        }
    }

    private fun asTest(operand: Operand, start: Int): FilterExpression = when (operand) {
        is Operand.Query -> FilterExpression.Existence(operand.query)

        is Operand.Call -> if (operand.call.function.result == JsonPathType.VALUE) {
            position = start
            fail(
                "'${operand.call.function.spelling}' returns ValueType, so it is a comparison operand " +
                    "rather than a test on its own",
            )
        } else {
            FilterExpression.Call(operand.call)
        }

        is Operand.Literal -> {
            position = start
            fail("a literal on its own is not a logical expression")
        }
    }

    /** The same query read as a `singular-query`, or `null` if it was not spelled as one. */
    private fun QueryExpression.asSingularOrNull(): SingularQuery? {
        if (!singularSpelling) return null
        val steps = segments.map { segment ->
            when (val selector = segment.selectors.single()) {
                is Selector.Name -> SingularStep.Name(selector.name)
                is Selector.Index -> SingularStep.Index(selector.index)
                // `singularSpelling` is false for every other selector, so this is unreachable; it is
                // a `when` with no `else` so that a new selector has to be classified here too.
                is Selector.Slice, is Selector.Filter, Selector.Wildcard -> return null
            }
        }
        return SingularQuery(root, steps)
    }

    // --- literals ----------------------------------------------------------------------------

    /**
     * `string-literal`, either quoting.
     *
     * Escaping is RFC 9535 §2.3.1.1's and deliberately **not** `VariantPath.parse`'s, where a
     * backslash escapes the next character literally. The two grammars are different languages that
     * happen to share a bracket, which is exactly why this module is not inside `rabosh-variant`.
     */
    private fun parseStringLiteral(): String {
        val quote = peek() ?: fail("expected a string literal")
        if (quote != '\'' && quote != '"') fail("expected a string literal")
        position++
        val name = StringBuilder()
        while (true) {
            val character = peek() ?: fail("unterminated string literal")
            when {
                character == quote -> {
                    position++
                    return name.toString()
                }

                character == '\\' -> parseEscape(quote, name)

                // The *other* quote is an ordinary character: `unescaped` excludes only the one that
                // opened the literal, which is what makes `'a"b'` and `"a'b"` legal and unremarkable.
                character == '\'' || character == '"' -> {
                    name.append(character)
                    position++
                }

                character.code < CONTROL_LIMIT ->
                    fail("a control character is escaped in a string literal, never written raw")

                character.isHighSurrogate() -> {
                    val low = peekAt(1)
                    if (low == null || !low.isLowSurrogate()) fail("an unpaired surrogate is not a string character")
                    name.append(character).append(low)
                    position += 2
                }

                character.isLowSurrogate() -> fail("an unpaired surrogate is not a string character")

                else -> {
                    name.append(character)
                    position++
                }
            }
        }
    }

    private fun parseEscape(quote: Char, name: StringBuilder) {
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
                parseUnicodeEscape(name)
                return
            }

            else -> fail("'\\$escape' is not an escape RFC 9535 allows in a string literal")
        }
        position++
    }

    /**
     * `hexchar`, including the surrogate pairing rule.
     *
     * A high surrogate must be followed by a second `\u` escape carrying a low surrogate, and a low
     * surrogate may not stand alone. The ABNF says so structurally rather than as prose, and it is
     * the half an implementation reusing a JSON string reader would not have.
     */
    private fun parseUnicodeEscape(name: StringBuilder) {
        position++
        val first = readHexEscape()
        when {
            Character.isHighSurrogate(first.toChar()) -> {
                if (peek() != '\\' || peekAt(1) != 'u') {
                    fail("a high surrogate escape must be followed by a low surrogate escape")
                }
                position += 2
                val second = readHexEscape()
                if (!Character.isLowSurrogate(second.toChar())) {
                    fail("a high surrogate escape must be followed by a low surrogate escape")
                }
                name.append(first.toChar()).append(second.toChar())
            }

            Character.isLowSurrogate(first.toChar()) ->
                fail("a low surrogate escape must follow a high surrogate escape")

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

    /** `int = "0" / (["-"] DIGIT1 *DIGIT)`, bounded by I-JSON's range. */
    private fun parseInt(): Long {
        val start = position
        val negative = peek() == '-'
        if (negative) position++
        val digitsAt = position
        val first = peek()
        when {
            first == '0' -> {
                position++
                if (negative) {
                    position = start
                    fail("'-0' is not an integer: RFC 9535 spells zero as '0'")
                }
                if (peek()?.isAsciiDigit() == true) {
                    position = start
                    fail("an integer has no leading zero")
                }
                return 0
            }

            first == null || !first.isAsciiDigit() -> {
                position = start
                fail("expected an integer")
            }
        }
        while (peek()?.isAsciiDigit() == true) position++

        val digits = text.substring(digitsAt, position)
        val magnitude = digits.toLongOrNull()
        if (magnitude == null || magnitude > MAX_JSON_INT) {
            position = start
            fail("an integer must lie within ±$MAX_JSON_INT, which RFC 9535 §2.1 takes from I-JSON")
        }
        return if (negative) -magnitude else magnitude
    }

    /**
     * `number = (int / "-0") [ frac ] [ exp ]`.
     *
     * Returned as the text rather than as a value, because [VariantBuilder.appendNumberLiteral] is
     * what decides which physical type a JSON number literal lands in — and a literal in a query must
     * land in the same one as the same literal in a document, or `$[?@.a == 1e2]` would stop matching
     * a document holding `100`.
     */
    private fun parseNumberLiteral(): String {
        val start = position
        if (peek() == '-') position++
        when {
            // `-0` is admissible in a number and not in an `int`, which is why the two are read by
            // different functions rather than one with a flag.
            peek() == '0' -> position++

            peek()?.isAsciiDigit() == true -> while (peek()?.isAsciiDigit() == true) position++

            else -> {
                position = start
                fail("expected a number")
            }
        }
        // Only reachable after a lone `0`: the loop above already consumed every following digit.
        if (peek()?.isAsciiDigit() == true) {
            position = start
            fail("a number has no leading zero")
        }
        if (peek() == '.') {
            position++
            if (peek()?.isAsciiDigit() != true) fail("a fraction needs at least one digit")
            while (peek()?.isAsciiDigit() == true) position++
        }
        if (peek() == 'e' || peek() == 'E') {
            position++
            if (peek() == '+' || peek() == '-') position++
            if (peek()?.isAsciiDigit() != true) fail("an exponent needs at least one digit")
            while (peek()?.isAsciiDigit() == true) position++
        }
        return text.substring(start, position)
    }

    /**
     * A literal, encoded once at compile time.
     *
     * [start] is where the literal began, so that a number the encoder refuses — one whose exponent
     * `BigDecimal` cannot hold — is reported against the literal rather than against wherever the
     * parser happens to have reached.
     */
    private fun literalVariant(start: Int, append: VariantBuilder.() -> Unit): Variant = try {
        VariantBuilder().apply(append).buildVariant()
    } catch (failure: IllegalArgumentException) {
        position = start
        fail("this literal is not a value the encoder can hold: ${failure.message}")
    }

    /**
     * `member-name-shorthand = name-first *name-char`, or `null` when none is there.
     *
     * `name-first = ALPHA / "_" / %x80-D7FF / %xE000-10FFFF` and `name-char` adds `DIGIT`, so the
     * shorthand reaches almost every non-ASCII name — `$.données` parses and `$.@type` does not.
     * A lone surrogate is in neither range and simply ends the name, which leaves the caller to
     * report the character as unexpected where it stands.
     */
    private fun parseMemberNameShorthand(): String? {
        val start = position
        if (!consumeNameCharacter(first = true)) return null
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

            !first && character.isAsciiDigit() -> {
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

    // --- primitives --------------------------------------------------------------------------

    private fun peek(): Char? = text.getOrNull(position)

    private fun peekAt(offset: Int): Char? = text.getOrNull(position + offset)

    private fun expect(character: Char, message: String) {
        if (peek() != character) fail(message)
        position++
    }

    /** `S = *B`, and `B` is exactly these four. Vertical tab and form feed are not blanks here. */
    private fun skipBlanks() {
        while (true) {
            when (peek()) {
                ' ', '\t', '\n', '\r' -> position++
                else -> return
            }
        }
    }

    private fun skipBlanksAny(): Boolean {
        val mark = position
        skipBlanks()
        return position != mark
    }

    private fun atIntStart(): Boolean = peek()?.let { it == '-' || it.isAsciiDigit() } == true

    private fun countSelector() {
        selectors++
        if (selectors > MAX_SELECTORS) fail("a query may hold at most $MAX_SELECTORS selectors")
    }

    private fun <T> nested(body: () -> T): T {
        depth++
        if (depth > MAX_NESTING_DEPTH) fail("a query may nest filters, parentheses and calls at most $MAX_NESTING_DEPTH deep")
        try {
            return body()
        } finally {
            depth--
        }
    }

    private fun mark(): Mark = Mark(position, selectors, singularSpelling)

    private fun reset(mark: Mark) {
        position = mark.position
        selectors = mark.selectors
        singularSpelling = mark.singularSpelling
    }

    private fun fail(message: String): Nothing =
        throw IllegalArgumentException("$message at position $position in JSONPath query '$text'")

    private companion object {
        /** U+000C. Kotlin has no character escape for it, so it is named once rather than written raw. */
        const val FORM_FEED: Char = '\u000C'

        /** Below this, `unescaped` has no production: a control character is written as an escape. */
        const val CONTROL_LIMIT: Int = 0x20

        /** Where `name-first`'s non-ASCII half begins: everything from here up is a name character. */
        const val NON_ASCII_NAME_START: Int = 0x80

        /** Tried before the one-character forms, so `<=` is never read as `<` followed by `=`. */
        val TWO_CHARACTER_OPERATORS = listOf(
            ComparisonOperator.EQUAL,
            ComparisonOperator.NOT_EQUAL,
            ComparisonOperator.LESS_OR_EQUAL,
            ComparisonOperator.GREATER_OR_EQUAL,
        )
    }
}

private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

/** `function-name-char = LCALPHA / "_" / DIGIT`; the first character is checked by the caller. */
private fun Char.isFunctionNameCharacter(): Boolean = this in 'a'..'z' || this in '0'..'9' || this == '_'

private fun hexValue(character: Char): Int? = when (character) {
    in '0'..'9' -> character - '0'
    // ABNF string literals are case-insensitive, so `\uD83D` and `\ud83d` are one production.
    in 'a'..'f' -> character - 'a' + 10
    in 'A'..'F' -> character - 'A' + 10
    else -> null
}
