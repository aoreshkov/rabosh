package app.oreshkov.rabosh.jsonpath

// RFC 9485's ABNF, transcribed into recursive descent — the same shape as `JsonPathParser`, for a
// grammar an order of magnitude smaller and with one thing that grammar does not have: **the input
// may be a value of the document.**
//
// That is why the two files do not share their error discipline. A JSONPath query is written by the
// caller, so `JsonPathParser` throws and names a position. An I-Regexp arrives as the second argument
// of `match`, which RFC 9535 §2.4.6 lets be any `ValueType` — so a pattern is data, and RFC 9535's
// rule for a pattern that is not an I-Regexp is `LogicalFalse` rather than an error. [NotAnIRegexp]
// therefore never escapes `IRegexp.compileOrNull`, carries no stack trace, and is a control transfer
// rather than a report.
//
// **Three things here are decisions and the rest is transcription.**
//
// `^` and `$` are **anchors**, and the ABNF says they are ordinary characters. `NormalChar` admits
// %x5E and %x24, so a literal reading makes `^ab` match only strings that begin with a caret. But
// RFC 9485 §5.3 and §5.4 — the same document's own recipe for realising I-Regexp on ECMAScript, PCRE,
// RE2 and Ruby — escape neither, and prescribe enveloping the pattern in `^(?:` and `)$`; every
// implementation that follows it therefore reads both as anchors, and the compliance suite pins that
// reading in its `explicit caret` and `explicit dollar` cases. The syntax rule and the mapping rule
// contradict each other, one of the two is what the corpus tests, and interoperability is the entire
// purpose of I-Regexp — so this file follows the mapping. Inside a character class, where the mapping
// has nothing to say and `^` already means negation in first position, both are literal.
//
// **A quantifier's bounds are checked here and its cost is checked in the emitter.** RFC 9485 §7
// singles out range quantifiers as the resource risk, and there are two of them: `a{9999999999}`,
// which is a number this parser refuses because it does not fit an `Int`, and `(a{1000}){1000}`,
// which is a *program* this parser cannot see. Only the emitter can price the second, so the second
// bound lives there and this one does not try to anticipate it.
//
// **The recursion is bounded because the input is data.** `((((…))))` nests as deeply as the pattern
// is long, and a pattern read from a 46 MB document would meet a recursive-descent parser as a
// `StackOverflowError`. The limit is on the *pattern* and so cannot truncate an answer the way a
// bound on a document would — it refuses the pattern outright, which RFC 9485 §7 explicitly permits.

/**
 * A pattern that is not an I-Regexp this build will run.
 *
 * Carries a message for a reader of a failing test and nothing else: it is caught unconditionally by
 * [IRegexp.compileOrNull], and RFC 9535 turns it into `LogicalFalse`. Built with no stack trace,
 * because a pattern arriving from a document can be malformed once per candidate node.
 */
internal class NotAnIRegexp(message: String) : Exception(message, null, false, false)

/** How deeply one pattern may nest `(…)`. See the note above: this is a bound on data. */
private const val MAX_GROUP_DEPTH = 64

/** Every position the pattern has no code point at. */
private const val END_OF_PATTERN = -1

/** The shape a parsed I-Regexp is. No positions: nothing downstream reports one. */
internal sealed interface RegexNode {

    /** `branch *( "|" branch )`, with two or more branches. */
    class Alternation(val branches: List<RegexNode>) : RegexNode

    /** `*piece`. An empty list is the empty branch, which matches the empty string. */
    class Sequence(val pieces: List<RegexNode>) : RegexNode

    /** `piece = atom [ quantifier ]`, with [max] equal to [UNBOUNDED] for `*`, `+` and `{n,}`. */
    class Repeat(val node: RegexNode, val min: Int, val max: Int) : RegexNode

    /** One code point drawn from a set: a `NormalChar`, a `charClass` or a `charClassExpr`. */
    class Characters(val characters: CharacterClass) : RegexNode

    /** `^`, as RFC 9485 §5's mapping reads it: true only at the start of the subject. */
    object AtStart : RegexNode

    /** `$`, likewise: true only at the end of the subject. */
    object AtEnd : RegexNode

    companion object {
        /** `{n,}` has no upper bound, and neither do `*` and `+`. */
        const val UNBOUNDED: Int = Int.MAX_VALUE
    }
}

internal class IRegexpParser(private val pattern: String) {

    private var position = 0
    private var depth = 0

    /** The whole pattern. Throws [NotAnIRegexp] for anything RFC 9485's grammar does not admit. */
    fun parse(): RegexNode {
        val node = parseAlternation()
        // Only reachable through an unbalanced `)`, which `parseBranch` stops at and nothing consumes.
        if (position != pattern.length) fail("unbalanced ')'")
        return node
    }

    /** `i-regexp = branch *( "|" branch )`. */
    private fun parseAlternation(): RegexNode {
        val branches = mutableListOf(parseBranch())
        while (peek() == BAR) {
            advance()
            branches += parseBranch()
        }
        return if (branches.size == 1) branches[0] else RegexNode.Alternation(branches)
    }

    /**
     * `branch = *piece`.
     *
     * A branch may be empty — `a|` and `()` are both legal I-Regexps — so this ends on `|`, on `)`
     * and on the end of the pattern rather than requiring anything of what it finds.
     */
    private fun parseBranch(): RegexNode {
        val pieces = mutableListOf<RegexNode>()
        while (true) {
            val next = peek()
            if (next == END_OF_PATTERN || next == BAR || next == CLOSE_PAREN) break
            pieces += parsePiece()
        }
        return if (pieces.size == 1) pieces[0] else RegexNode.Sequence(pieces)
    }

    /**
     * `piece = atom [ quantifier ]`.
     *
     * A quantifier cannot follow a quantifier, and that falls out of the grammar rather than needing
     * a check: `a**` reads the second `*` as the start of the next piece, and `*` is not an atom.
     */
    private fun parsePiece(): RegexNode {
        val atom = parseAtom()
        return when (peek()) {
            STAR -> {
                advance()
                RegexNode.Repeat(atom, 0, RegexNode.UNBOUNDED)
            }

            PLUS -> {
                advance()
                RegexNode.Repeat(atom, 1, RegexNode.UNBOUNDED)
            }

            QUESTION -> {
                advance()
                RegexNode.Repeat(atom, 0, 1)
            }

            OPEN_BRACE -> parseRangeQuantifier(atom)

            else -> atom
        }
    }

    /** `"{" QuantExact [ "," [ QuantExact ] ] "}"`. */
    private fun parseRangeQuantifier(atom: RegexNode): RegexNode {
        advance()
        val min = parseQuantExact()
        var max = min
        if (peek() == COMMA) {
            advance()
            max = if (peek() == CLOSE_BRACE) RegexNode.UNBOUNDED else parseQuantExact()
        }
        expect(CLOSE_BRACE, "expected '}' to close a range quantifier")
        if (max < min) fail("a range quantifier's upper bound is below its lower bound")
        return RegexNode.Repeat(atom, min, max)
    }

    /**
     * `QuantExact = 1*DIGIT`, refused above [Int.MAX_VALUE].
     *
     * Refused rather than clamped, because clamping would make `a{4294967296}` mean something other
     * than what it says. Everything that fits is the emitter's problem, and it *prices* repetition
     * rather than counting it.
     */
    private fun parseQuantExact(): Int {
        val start = position
        var value = 0L
        while (peek() in ZERO..NINE) {
            value = value * DECIMAL + (peek() - ZERO)
            if (value > Int.MAX_VALUE) fail("a range quantifier's bound does not fit an Int")
            advance()
        }
        if (position == start) fail("expected a decimal bound in a range quantifier")
        return value.toInt()
    }

    /** `atom = NormalChar / charClass / ( "(" i-regexp ")" )`, plus the two anchors §5 introduces. */
    private fun parseAtom(): RegexNode {
        val next = peek()
        return when {
            next == OPEN_PAREN -> parseGroup()

            next == OPEN_BRACKET -> RegexNode.Characters(parseCharacterClassExpression())

            next == DOT -> {
                advance()
                RegexNode.Characters(CharacterClass.DOT)
            }

            next == BACKSLASH -> RegexNode.Characters(parseEscape())

            // The two deviations from `NormalChar`, argued at the top of this file.
            next == CARET -> {
                advance()
                RegexNode.AtStart
            }

            next == DOLLAR -> {
                advance()
                RegexNode.AtEnd
            }

            isNormalChar(next) -> {
                advance()
                RegexNode.Characters(CharacterClass.of(next))
            }

            else -> fail("'${describe(next)}' cannot begin an atom")
        }
    }

    private fun parseGroup(): RegexNode {
        advance()
        depth++
        if (depth > MAX_GROUP_DEPTH) fail("a pattern may nest groups at most $MAX_GROUP_DEPTH deep")
        val inner = parseAlternation()
        expect(CLOSE_PAREN, "expected ')' to close a group")
        depth--
        return inner
    }

    /**
     * `charClass`'s two escaped forms, outside a character class expression.
     *
     * `\p{Lu}` is an atom in its own right rather than something only a `[…]` may hold, which is what
     * makes the compliance suite's bare `'\p{Lu}'` a pattern rather than a syntax error.
     */
    private fun parseEscape(): CharacterClass {
        advance()
        if (peek() == LOWER_P || peek() == UPPER_P) {
            val builder = CharacterClass.Builder()
            parseCategoryEscape(builder)
            return builder.build(negated = false)
        }
        return CharacterClass.of(parseSingleCharEscape())
    }

    /**
     * `catEsc = "\p{" charProp "}"` and `complEsc = "\P{" charProp "}"`, which differ by one bit.
     *
     * Entered with the backslash consumed and the `p` or `P` under [position], because a class
     * expression reaches this the same way and the two must not drift apart.
     */
    private fun parseCategoryEscape(builder: CharacterClass.Builder) {
        val complemented = peek() == UPPER_P
        advance()
        expect(OPEN_BRACE, "expected '{' after a category escape")
        val start = position
        while (peek() != CLOSE_BRACE && peek() != END_OF_PATTERN) advance()
        val spelling = pattern.substring(start, position)
        expect(CLOSE_BRACE, "expected '}' to close a category escape")
        val mask = CharacterClass.categoryMask(spelling)
            ?: fail("'$spelling' is not one of RFC 9485's general categories")
        builder.addCategory(mask, complemented)
    }

    /**
     * `SingleCharEsc`, with the backslash already consumed.
     *
     * The set is closed: RFC 9485 has no `\d`, `\s`, `\w` or `\uXXXX`, because a multi-character
     * escape is one of the things it removed from XSD to make two implementations agree. So an
     * unknown escape is a **refusal** rather than the escaped character, which is the lenient reading
     * a JSON string reader would have brought with it.
     */
    private fun parseSingleCharEscape(): Int {
        val escape = peek()
        if (escape == END_OF_PATTERN) fail("a pattern does not end in a backslash")
        val value = when (escape) {
            LOWER_N -> '\n'.code
            LOWER_R -> '\r'.code
            LOWER_T -> '\t'.code
            in OPEN_PAREN..PLUS, DASH, DOT, QUESTION, in OPEN_BRACKET..CARET, in OPEN_BRACE..CLOSE_BRACE -> escape
            else -> fail("'\\${describe(escape)}' is not an escape RFC 9485 allows")
        }
        advance()
        return value
    }

    /** `charClassExpr = "[" [ "^" ] ( "-" / CCE1 ) *CCE1 [ "-" ] "]"`. */
    private fun parseCharacterClassExpression(): CharacterClass {
        advance()
        val negated = peek() == CARET
        if (negated) advance()
        val builder = CharacterClass.Builder()

        // The leading `-`, which the ABNF gives its own alternative because `-` is not a `CCchar`.
        if (peek() == DASH) {
            advance()
            builder.addCodePoint(DASH)
        }
        while (peek() != CLOSE_BRACKET) {
            if (peek() == END_OF_PATTERN) fail("a character class expression is not closed")
            // A `-` here can only be the optional trailing one: a range's `-` is consumed by the
            // member that opened it, and `-` begins no member.
            if (peek() == DASH) {
                advance()
                if (peek() != CLOSE_BRACKET) fail("'-' is not a character class member")
                builder.addCodePoint(DASH)
                break
            }
            parseClassMember(builder)
        }
        expect(CLOSE_BRACKET, "expected ']' to close a character class expression")
        if (builder.members == 0) fail("a character class expression holds at least one member")
        return builder.build(negated)
    }

    /** `CCE1 = ( CCchar [ "-" CCchar ] ) / charClassEsc`. */
    private fun parseClassMember(builder: CharacterClass.Builder) {
        if (peek() == BACKSLASH && (peekNext() == LOWER_P || peekNext() == UPPER_P)) {
            advance()
            parseCategoryEscape(builder)
            return
        }
        val low = parseClassChar()
        // A range's right-hand side is a `CCchar` and never a category, so `[a-\p{L}]` is refused
        // here — by `parseSingleCharEscape`, which has no `p` — rather than read as three members.
        if (peek() == DASH && peekNext() != CLOSE_BRACKET && peekNext() != END_OF_PATTERN) {
            advance()
            val high = parseClassChar()
            if (high < low) fail("a character class range runs backwards")
            builder.addRange(low, high)
        } else {
            builder.addCodePoint(low)
        }
    }

    /** `CCchar = ( %x00-2C / %x2E-5A / %x5E-D7FF / %xE000-10FFFF ) / SingleCharEsc`. */
    private fun parseClassChar(): Int {
        if (peek() == BACKSLASH) {
            advance()
            return parseSingleCharEscape()
        }
        val next = peek()
        if (!isClassChar(next)) fail("'${describe(next)}' is not a character class member")
        advance()
        return next
    }

    // --- primitives ------------------------------------------------------------------------------

    private fun peek(): Int = if (position < pattern.length) pattern.codePointAt(position) else END_OF_PATTERN

    /** The code point after the one under [position]. One character of lookahead is all this needs. */
    private fun peekNext(): Int {
        if (position >= pattern.length) return END_OF_PATTERN
        val after = position + Character.charCount(pattern.codePointAt(position))
        return if (after < pattern.length) pattern.codePointAt(after) else END_OF_PATTERN
    }

    private fun advance() {
        position += Character.charCount(pattern.codePointAt(position))
    }

    private fun expect(codePoint: Int, message: String) {
        if (peek() != codePoint) fail(message)
        advance()
    }

    private fun fail(message: String): Nothing = throw NotAnIRegexp("$message in I-Regexp '$pattern'")

    /**
     * A code point as something a message can hold.
     *
     * A lone surrogate reaches here — `codePointAt` reports one as itself — and appending it to a
     * message would produce a string that cannot be encoded, so anything outside the scalar values is
     * named by its number instead.
     */
    private fun describe(codePoint: Int): String = when {
        codePoint == END_OF_PATTERN -> "end of pattern"
        codePoint in SURROGATE_FIRST..SURROGATE_LAST -> "U+" + Integer.toHexString(codePoint).uppercase()
        else -> String(Character.toChars(codePoint))
    }

    private companion object {
        const val DOLLAR = '$'.code
        const val OPEN_PAREN = '('.code
        const val CLOSE_PAREN = ')'.code
        const val STAR = '*'.code
        const val PLUS = '+'.code
        const val COMMA = ','.code
        const val DASH = '-'.code
        const val DOT = '.'.code
        const val ZERO = '0'.code
        const val NINE = '9'.code
        const val QUESTION = '?'.code
        const val OPEN_BRACKET = '['.code
        const val BACKSLASH = '\\'.code
        const val CLOSE_BRACKET = ']'.code
        const val CARET = '^'.code
        const val LOWER_N = 'n'.code
        const val LOWER_P = 'p'.code
        const val UPPER_P = 'P'.code
        const val LOWER_R = 'r'.code
        const val LOWER_T = 't'.code
        const val OPEN_BRACE = '{'.code
        const val BAR = '|'.code
        const val CLOSE_BRACE = '}'.code

        const val DECIMAL = 10L
        const val SURROGATE_FIRST = 0xD800
        const val SURROGATE_LAST = 0xDFFF

        /**
         * `NormalChar`: everything but the twelve syntax characters and the surrogates.
         *
         * Written as the ABNF's ranges rather than as a negated set of twelve, so that a reader can
         * check it against the document line by line — and so that the surrogate hole, which a
         * negated set would have lost, is visible. An unpaired surrogate in a pattern is therefore a
         * refusal rather than a code point that matches nothing.
         */
        fun isNormalChar(codePoint: Int): Boolean = codePoint in 0x00..0x27 ||
            codePoint in 0x2C..0x2D ||
            codePoint in 0x2F..0x3E ||
            codePoint in 0x40..0x5A ||
            codePoint in 0x5E..0x7A ||
            codePoint in 0x7E..0xD7FF ||
            codePoint in 0xE000..0x10FFFF

        /** `CCchar`'s unescaped half: everything but `-`, `[`, `\`, `]` and the surrogates. */
        fun isClassChar(codePoint: Int): Boolean = codePoint in 0x00..0x2C ||
            codePoint in 0x2E..0x5A ||
            codePoint in 0x5E..0xD7FF ||
            codePoint in 0xE000..0x10FFFF
    }
}
