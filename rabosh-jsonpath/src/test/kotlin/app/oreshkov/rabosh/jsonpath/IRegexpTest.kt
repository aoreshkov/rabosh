package app.oreshkov.rabosh.jsonpath

import java.util.regex.Pattern
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The I-Regexp matcher: what it accepts, what it answers, what it costs, and what it refuses.
 *
 * The compliance suite already covers `match` and `search` end to end over 56 cases, so nothing here
 * repeats that. This is the part the suite has no opinion about — **the sub-language it does not
 * exercise, the guarantee it cannot observe, and the one place this implementation knowingly departs
 * from RFC 9485's ABNF.**
 */
class IRegexpTest {

    /**
     * RFC 9485 is XSD's grammar with the interoperability hazards taken out, and this is that list.
     *
     * Each refusal is a thing a *plausible* implementation would have accepted: `\d` because every
     * other regexp dialect has it, `\u0041` because the JSON string reader beside this one does,
     * `[a-\p{L}]` because a category looks like a character, `(?:a)` because a group looks like a
     * group. Accepting any of them would make this matcher agree with itself and with nothing else,
     * which is the failure I-Regexp exists to prevent.
     */
    @Test
    fun `the grammar is RFC 9485's and not a regular expression dialect's`() {
        val accepted = listOf(
            "", "a", "a|", "|a", "()", "(a|b)c", "a*", "a+", "a?", "a{2}", "a{2,}", "a{0,3}", "a{0}",
            ".", "\\.", "\\\\", "\\n", "\\t", "\\{", "[-a]", "[a-]", "[--]", "[-]", "[\\]]", "[^ab]",
            "\\p{Lu}", "\\P{Lu}", "[\\p{Nd}x]", "[a-z0-9_]", "[\\^]", "a\\-b",
        )
        for (pattern in accepted) {
            assertNotNull(IRegexp.compileOrNull(pattern), "'$pattern' is an I-Regexp")
        }

        val refused = listOf(
            "\\d", "\\s", "\\w", "\\D", "\\S", "\\W", "\\b", "\\A", "\\z", "\\Q", "\\u0041",
            "(?:a)", "(?=a)", "(a)\\1", "[a-\\p{L}]", "[]", "[^]", "[a-\\d]", "[[a-z]-[aeiou]]",
            "a**", "*a", "+a", "?a", "{2}", "(a", "a)", "[a", "a\\", "\\p{L", "\\p{}",
            "\\p{Cs}", "\\p{Lc}", "\\p{IsGreek}", "\\p{Basic Latin}", "a{2,1}", "[b-a]", "a{,2}",
        )
        for (pattern in refused) {
            assertNull(IRegexp.compileOrNull(pattern), "'$pattern' is not an I-Regexp")
        }
    }

    /**
     * **`^` and `$` are anchors here, and RFC 9485's `NormalChar` says they are ordinary characters.**
     *
     * The one knowing departure in this module, pinned so that nobody "fixes" it back. `NormalChar`
     * admits %x5E and %x24, so a literal reading makes `^ab` match only a string beginning with a
     * caret. But §5.3 and §5.4 — the RFC's own recipe for realising I-Regexp on ECMAScript, PCRE, RE2
     * and Ruby — escape neither and prescribe wrapping the pattern in `^(?:` and `)$`, so every
     * implementation built that way reads both as anchors. The compliance suite's `explicit caret` and
     * `explicit dollar` cases pin *that* reading, and interoperability is the entire purpose of
     * I-Regexp — so the mapping wins over the syntax rule, and this test is the record of the choice
     * rather than of a preference.
     *
     * Inside a character class, where the mapping has nothing to say, both are literal — which is
     * what makes `[\^]` and `[$]` mean what they look like.
     */
    @Test
    fun `caret and dollar are anchors outside a character class and literals inside one`() {
        val anchored = IRegexp.compileOrNull("^ab.*")!!
        assertTrue(anchored.matches("abc"), "a leading '^' is a no-op under an anchored match")
        assertFalse(anchored.matches("^abc"), "and is therefore not the character it looks like")
        assertFalse(anchored.search("xab"), "under search it is what stops a later start")

        val ending = IRegexp.compileOrNull(".*bc$")!!
        assertTrue(ending.matches("abc"))
        assertFalse(ending.matches("abcx"))
        assertTrue(ending.search("abc"))
        assertFalse(ending.search("abcx"))

        assertTrue(IRegexp.compileOrNull("a^b")!!.let { !it.matches("a^b") && !it.matches("ab") })
        assertTrue(IRegexp.compileOrNull("[\\^$]")!!.matches("^"), "inside a class both are literal")
        assertTrue(IRegexp.compileOrNull("[\\^$]")!!.matches("$"))
    }

    /**
     * `.` is `[^\n\r]` and nothing else, which is not what ECMAScript's dot is.
     *
     * U+2028 and U+2029 are the difference, and the compliance suite carries two cases for exactly
     * them. An implementation that translated to ECMAScript **and forgot §5.3's dot replacement**
     * passes every other test in the suite and fails those two.
     */
    @Test
    fun `the dot excludes only LF and CR`() {
        val dot = IRegexp.compileOrNull(".")!!
        assertTrue(dot.matches(" "))
        assertTrue(dot.matches("\u2028"), "the line separator is a character like any other here")
        assertTrue(dot.matches("\u2029"))
        assertTrue(dot.matches("\u0085"), "and so is NEL, which some dialects also treat as a break")
        assertFalse(dot.matches("\n"))
        assertFalse(dot.matches("\r"))

        // A negated class is not the dot: `[^a]` is total over everything but `a`.
        assertTrue(IRegexp.compileOrNull("[^a]")!!.matches("\n"))
    }

    /**
     * The matcher counts **code points**, which is the unit RFC 9535 measures a JSON string in.
     *
     * A matcher written over UTF-16 units answers `false` to the first assertion here and `true` to
     * the second, and would pass every ASCII test in the suite on the way.
     */
    @Test
    fun `a supplementary character is one character`() {
        val emoji = "\uD83D\uDE00"
        assertTrue(IRegexp.compileOrNull(".")!!.matches(emoji))
        assertFalse(IRegexp.compileOrNull("..")!!.matches(emoji))
        assertTrue(IRegexp.compileOrNull("a.b")!!.matches("a${emoji}b"))

        val astral = IRegexp.compileOrNull("[\uD83D\uDE00-\uD83D\uDE0F]")!!
        assertTrue(astral.matches(emoji), "a class range whose endpoints are outside the basic plane")
        assertFalse(astral.matches("a"))
    }

    /**
     * The general categories, including the two the ABNF deliberately has no production for.
     *
     * `Cs` is a Unicode general category and is **not** an I-Regexp one — RFC 9485 follows XSD, which
     * omits it — so `\p{C}` here is `Cc | Cf | Cn | Co`. A table generated from `Character`'s own
     * constants would have accepted `\p{Cs}` and quietly widened `\p{C}`, which is why the table is
     * written out rather than derived.
     */
    @Test
    fun `the category table is RFC 9485's IsCategory and not Unicode's category list`() {
        assertTrue(IRegexp.compileOrNull("\\p{Lu}")!!.matches("\u0416"))
        assertFalse(IRegexp.compileOrNull("\\p{Lu}")!!.matches("\u0436"))
        assertTrue(IRegexp.compileOrNull("\\p{L}+")!!.matches("caf\u00E9"))
        assertFalse(IRegexp.compileOrNull("\\p{L}+")!!.matches("caf3"))
        assertTrue(IRegexp.compileOrNull("\\p{Nd}")!!.matches("\u0661"), "an Arabic-Indic digit is Nd")
        assertTrue(IRegexp.compileOrNull("\\p{Zs}")!!.matches("\u00A0"))
        assertTrue(IRegexp.compileOrNull("\\p{Sc}")!!.matches("\u20AC"))
        assertTrue(IRegexp.compileOrNull("\\p{Cc}")!!.matches("\u0007"))
        assertTrue(IRegexp.compileOrNull("\\P{Lu}")!!.matches("\u0436"))

        // A class may hold several members of both kinds, and a `\P{…}` inside one is a member rather
        // than a negation of the class — which is the case a single "negated" flag would have lost.
        val mixed = IRegexp.compileOrNull("[\\p{Nd}\\-x]")!!
        assertTrue(mixed.matches("7"))
        assertTrue(mixed.matches("-"))
        assertTrue(mixed.matches("x"))
        assertFalse(mixed.matches("y"))
    }

    /** `match` is the whole subject and `search` is any substring; everything else is shared. */
    @Test
    fun `match is anchored and search is not`() {
        val regexp = IRegexp.compileOrNull("b.?b")!!
        assertTrue(regexp.matches("bab"))
        assertFalse(regexp.matches("bbab"))
        assertTrue(regexp.search("bbab"))
        assertFalse(regexp.search("abc"))

        // The empty pattern, which is a legal I-Regexp: `branch = *piece`.
        val empty = IRegexp.compileOrNull("")!!
        assertTrue(empty.matches(""))
        assertFalse(empty.matches("a"))
        assertTrue(empty.search("a"), "every string contains the empty substring")

        // `$` under search anchors to the subject's end rather than to where the search started, so a
        // thread list that emptied early would answer this wrongly.
        assertTrue(IRegexp.compileOrNull("$")!!.search("ab"))
        assertFalse(IRegexp.compileOrNull("a$")!!.search("ab"))
    }

    /**
     * **The guarantee this matcher exists for, asserted in transitions rather than on a clock.**
     *
     * `(a|aa)+b` against a run of `a`s is the shape that makes a backtracking engine take exponential
     * time — the reason `java.util.regex` was not translated to, because the pattern may arrive from
     * the *document* and the subject is somebody's data. A Thompson simulation visits each instruction
     * at most once per input position, so the work is bounded by `instructions × (code points + 1)`
     * twice over: once for the epsilon closure, once for offering each live thread a character.
     *
     * Asserted against that bound rather than against a remembered number, and paired with the two
     * checks that stop it being vacuous — the subject really does grow, and the cost really does grow
     * with it, so a matcher that answered without looking would fail this too.
     */
    @Test
    fun `matching is linear in the subject, and the bound is in transitions`() {
        val regexp = IRegexp.compileOrNull("(a|aa)+b")!!
        val lengths = listOf(64, 128, 256, 512, 1024)
        val measured = lengths.map { length ->
            val counter = TransitionCounter()
            assertFalse(regexp.matches("a".repeat(length), counter), "the subject holds no 'b'")
            val ceiling = 2L * regexp.instructionCount * (length + 1)
            assertTrue(
                counter.transitions <= ceiling,
                "$length code points cost ${counter.transitions} transitions against a ceiling of $ceiling",
            )
            counter.transitions
        }
        for (index in 1 until measured.size) {
            assertTrue(measured[index] > measured[index - 1], "the cost must grow with the subject")
            assertTrue(
                measured[index] <= 2 * measured[index - 1] + regexp.instructionCount,
                "doubling the subject must at most double the work: $measured",
            )
        }
    }

    /**
     * The two resource bounds, which RFC 9485 §7 asks for by name.
     *
     * A refusal is the *right* answer rather than a compromise: §7 says an implementation may reject
     * a pattern whose range quantifiers it will not spend the resources on, and RFC 9535 §2.4.6 turns
     * a refused pattern into `LogicalFalse` rather than an error — so a pattern from a hostile
     * document costs a comparison, not a hang. Nesting is bounded for a second reason the RFC does not
     * have to state: a pattern is *data* here, and a recursive-descent parser meets a deep enough one
     * as a stack overflow.
     */
    @Test
    fun `a pattern that would cost too much is refused rather than run`() {
        assertNotNull(IRegexp.compileOrNull("(a{100}){50}"), "5 000 instructions is inside the bound")
        assertNull(IRegexp.compileOrNull("(a{1000}){1000}"), "a million is not")
        assertNull(IRegexp.compileOrNull("a{99999999999}"), "a bound that does not fit an Int")
        assertNull(IRegexp.compileOrNull("(){1000000000}"), "an empty body repeated is still repetition")

        assertNotNull(IRegexp.compileOrNull("(".repeat(64) + "a" + ")".repeat(64)))
        assertNull(IRegexp.compileOrNull("(".repeat(65) + "a" + ")".repeat(65)))
    }

    /**
     * A differential against `java.util.regex`, over the sub-language both can spell.
     *
     * The reference is the implementation this module **declined** — §7 of the plan chose to write the
     * matcher rather than translate to it — so running the two against each other is the strongest
     * available check that declining cost no correctness. The translation is RFC 9485 §5.4's own:
     * replace an unescaped `.` outside a character class with `[^\n\r]`, then anchor with `\A(?:` and
     * `)\z` for a match and leave it unanchored for a search.
     *
     * `^` and `$` are deliberately absent from the patterns: Java's `$` matches before a *final* line
     * terminator as well as at the end of input, so the two disagree there for a reason that is
     * Java's rather than either specification's. They are pinned in their own test above.
     */
    @Test
    fun `the answers agree with the engine this module declined to use`() {
        val patterns = listOf(
            "a", "a.*", "a.c", "a\\.c", "b.?b", "[a-z]+", "[^a-z]+", "[a-z0-9_]{2,4}", "(ab|cd)+",
            "a{0,3}b", "x*", "(a|b)*c", "\\p{Lu}", "\\P{Lu}", "\\p{L}+", "\\p{Nd}{2}", "[\\p{Nd}x]",
            "a[.b]c", "a[\\].]c", "a\\[.c", "()", "|a", "(a?)*b", "[\\-a]", "\\t\\n",
        )
        val subjects = listOf(
            "", "a", "ab", "abc", "a.c", "axc", "a]c", "a[ c", "bab", "bbab", "x abc y", "AB", "ab12",
            "\u0416", "\u0436\u0416", "caf\u00E9", "\u0661\u0662", "-", "a\tb", "a\nb", "\r", "\u2028",
            "the end is ab", "abcabcabc", "aaaaab",
        )
        var checked = 0
        for (pattern in patterns) {
            val regexp = assertNotNull(IRegexp.compileOrNull(pattern), "'$pattern' must compile here")
            val reference = Pattern.compile(toJavaRegexp(pattern))
            for (subject in subjects) {
                assertEquals(
                    reference.matcher(subject).matches(),
                    regexp.matches(subject),
                    "match('$pattern', ${subject.quoted()})",
                )
                assertEquals(
                    reference.matcher(subject).find(),
                    regexp.search(subject),
                    "search('$pattern', ${subject.quoted()})",
                )
                checked += 2
            }
        }
        assertEquals(2 * patterns.size * subjects.size, checked, "the sweep must have run")
    }

    /** RFC 9485 §5.4's mapping, and only the half a `Pattern` needs — the anchoring is the caller's. */
    private fun toJavaRegexp(pattern: String): String {
        val translated = StringBuilder()
        var inClass = false
        var index = 0
        while (index < pattern.length) {
            val character = pattern[index]
            when {
                character == '\\' -> {
                    translated.append(character).append(pattern[index + 1])
                    index++
                }

                character == '[' && !inClass -> {
                    inClass = true
                    translated.append(character)
                }

                character == ']' && inClass -> {
                    inClass = false
                    translated.append(character)
                }

                character == '.' && !inClass -> translated.append("[^\\n\\r]")

                else -> translated.append(character)
            }
            index++
        }
        return translated.toString()
    }

    /** A subject as something a failure message can carry, with the invisible characters visible. */
    private fun String.quoted(): String = "'" + replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "'"
}
