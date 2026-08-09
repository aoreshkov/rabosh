package app.oreshkov.rabosh.jsonpath

/**
 * A compiled [RFC 9485](https://www.rfc-editor.org/info/rfc9485/) I-Regexp: the language `match()`
 * and `search()` are defined over.
 *
 * **Not published, and that is a decision rather than an oversight.** I-Regexp is somebody else's
 * grammar reached through somebody else's function extensions; exposing a matcher here would make
 * this module's surface two languages instead of one, and would invite a caller to use it as a
 * general regular-expression engine — which it deliberately is not. `\d`, `\s`, `\w`, lookaround,
 * backreferences, capture groups, character-class subtraction and Unicode blocks are all absent
 * because RFC 9485 removed them from XSD to make two implementations agree, and a caller reaching for
 * any of them wants `java.util.regex` and should say so in their own code.
 *
 * **A pattern that will not compile is not an error.** RFC 9535 §2.4.6 says that a second argument
 * which is not "a string conforming to RFC 9485" makes the result `LogicalFalse`, and the pattern may
 * come from the *document*, so there is nothing to report it to. [compileOrNull] therefore answers
 * `null` — for a syntax error and equally for a pattern that is valid and too large to run, which
 * RFC 9485 §7 explicitly permits an implementation to refuse.
 *
 * **Immutable and safe to share.** Compiling is the whole cost; [matches] and [search] allocate their
 * own state per call and write nothing back. That is what lets `JsonPathQuery` go on promising
 * immutability with a literal pattern compiled into it.
 */
internal class IRegexp private constructor(private val program: IRegexpProgram) {

    /** How many instructions this compiled to. Read by tests, which price the run against it. */
    val instructionCount: Int get() = program.size

    /**
     * RFC 9535 §2.4.6: whether **the whole** of [input] matches.
     *
     * @param counter a test seam; see [TransitionCounter]. Production callers pass nothing.
     */
    fun matches(input: String, counter: TransitionCounter? = null): Boolean =
        program.run(input, anchored = true, counter = counter)

    /**
     * RFC 9535 §2.4.7: whether **any substring** of [input] matches.
     *
     * The same program, run with a start seeded at every position rather than only at zero — so a
     * search costs what a match costs and not the length of the subject times it.
     */
    fun search(input: String, counter: TransitionCounter? = null): Boolean =
        program.run(input, anchored = false, counter = counter)

    companion object {
        /** [pattern] compiled, or `null` if it is not an I-Regexp this build will run. */
        fun compileOrNull(pattern: String): IRegexp? = try {
            IRegexp(IRegexpProgram.of(IRegexpParser(pattern).parse()))
        } catch (refused: NotAnIRegexp) {
            // The only thing thrown by either half, and the only place it is caught. Deliberately
            // swallowed: RFC 9535 turns a non-conforming pattern into `LogicalFalse`, and the
            // message exists for a reader of a failing test rather than for a caller.
            null
        }
    }
}
