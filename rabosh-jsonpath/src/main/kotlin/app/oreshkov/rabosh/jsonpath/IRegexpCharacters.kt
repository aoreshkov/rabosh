package app.oreshkov.rabosh.jsonpath

// RFC 9485 §2's character classes, as one membership test over Unicode **code points**.
//
// **A class is a predicate rather than a materialised set, and that is the decision here.** `\p{L}`
// covers roughly 140 000 code points and `[^a]` all but one of 1 114 112; enumerating either into
// ranges would make compiling a pattern cost more than matching with it, and a pattern that arrives
// in a *document* is compiled once per candidate node. So a class carries three things — literal
// ranges, the general categories `\p{…}` named, and the ones `\P{…}` named — and asks `getType` only
// when the ranges have already said no.
//
// **The category table is the ABNF's `IsCategory` production, not Unicode's list**, which is why it
// is written out rather than derived from `Character`'s constants. `Cs` (surrogate) is a general
// category and is *not* an I-Regexp one — the RFC follows XSD, which omits it — so a pattern naming
// `\p{Cs}` is refused by the same lookup that resolves `\p{Cc}`. A table that was generated would
// have accepted it.

/**
 * A set of code points: the leaves of a compiled I-Regexp.
 *
 * @property ranges literal code point ranges, sorted, disjoint and packed low-in-the-high-half.
 * @property categories the union of the general categories `\p{…}` named, as a bit per
 *   `Character.getType` value.
 * @property complements one mask per `\P{…}` the class named; a code point is a member when its
 *   category is *outside* any one of them.
 * @property negated whether `[^…]` inverted the whole thing. Kept as a flag rather than folded into
 *   the ranges because the two operations do not commute with [complements].
 */
internal class CharacterClass private constructor(
    private val ranges: LongArray,
    private val categories: Long,
    private val complements: LongArray,
    private val negated: Boolean,
) {

    /** Whether [codePoint] is in this class. Called once per code point per live NFA thread. */
    fun contains(codePoint: Int): Boolean = holds(codePoint) != negated

    private fun holds(codePoint: Int): Boolean {
        if (inRanges(codePoint)) return true
        if (categories == 0L && complements.isEmpty()) return false
        val category = 1L shl Character.getType(codePoint)
        if (category and categories != 0L) return true
        for (complement in complements) {
            if (category and complement == 0L) return true
        }
        return false
    }

    /**
     * Binary search over the packed ranges.
     *
     * They are merged at build time so that no two overlap or abut, which is what makes one
     * comparison per probe enough — an unmerged list would need a scan to the left after a miss.
     */
    private fun inRanges(codePoint: Int): Boolean {
        var low = 0
        var high = ranges.size - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val range = ranges[middle]
            when {
                codePoint < (range ushr LOW_SHIFT).toInt() -> high = middle - 1
                codePoint > (range and HIGH_MASK).toInt() -> low = middle + 1
                else -> return true
            }
        }
        return false
    }

    /** Accumulates the members of one class, in the order the pattern writes them. */
    class Builder {
        private var packed = LongArray(INITIAL_RANGES)
        private var count = 0
        private var categories = 0L
        private var complements = LongArray(0)

        /** How many members have been added. `[]` is not an I-Regexp, so a caller checks this. */
        var members: Int = 0
            private set

        fun addCodePoint(codePoint: Int): Builder = addRange(codePoint, codePoint)

        fun addRange(low: Int, high: Int): Builder {
            if (count == packed.size) packed = packed.copyOf(packed.size * 2)
            packed[count++] = pack(low, high)
            members++
            return this
        }

        /** A `\p{…}` or `\P{…}` member, already resolved to a category mask. */
        fun addCategory(mask: Long, complemented: Boolean): Builder {
            if (complemented) complements += mask else categories = categories or mask
            members++
            return this
        }

        fun build(negated: Boolean): CharacterClass =
            CharacterClass(merge(packed.copyOf(count)), categories, complements, negated)
    }

    companion object {
        /** The low half of a packed range starts here; the high half is the low 32 bits. */
        private const val LOW_SHIFT = 32
        private const val HIGH_MASK = 0xFFFFFFFFL
        private const val INITIAL_RANGES = 8

        private val EMPTY_MASKS = LongArray(0)

        private fun pack(low: Int, high: Int): Long = (low.toLong() shl LOW_SHIFT) or high.toLong()

        /** Sorts and coalesces, so [inRanges] can decide a probe with one comparison. */
        private fun merge(unsorted: LongArray): LongArray {
            if (unsorted.isEmpty()) return unsorted
            unsorted.sort()
            var write = 0
            var low = (unsorted[0] ushr LOW_SHIFT).toInt()
            var high = (unsorted[0] and HIGH_MASK).toInt()
            for (index in 1 until unsorted.size) {
                val nextLow = (unsorted[index] ushr LOW_SHIFT).toInt()
                val nextHigh = (unsorted[index] and HIGH_MASK).toInt()
                // `nextLow <= high + 1` rather than `<= high`: two ranges that merely touch are one
                // range, and leaving them apart would cost a probe without changing an answer.
                if (nextLow <= high + 1) {
                    if (nextHigh > high) high = nextHigh
                } else {
                    unsorted[write++] = pack(low, high)
                    low = nextLow
                    high = nextHigh
                }
            }
            unsorted[write++] = pack(low, high)
            return unsorted.copyOf(write)
        }

        /** One code point, written in the pattern as itself or as a `SingleCharEsc`. */
        fun of(codePoint: Int): CharacterClass =
            CharacterClass(longArrayOf(pack(codePoint, codePoint)), 0L, EMPTY_MASKS, negated = false)

        /**
         * `.` — every code point but LF and CR.
         *
         * RFC 9485 §5 defines the dot by the mapping it prescribes for every other dialect, `[^\n\r]`,
         * and the compliance suite pins the consequence that catches an implementation which reached
         * for ECMAScript's dot instead: U+2028 and U+2029 **are** matched here and are not there.
         */
        val DOT: CharacterClass = CharacterClass(
            longArrayOf(pack('\n'.code, '\n'.code), pack('\r'.code, '\r'.code)),
            0L,
            EMPTY_MASKS,
            negated = true,
        )

        /**
         * The mask [spelling] names, or `null` if `IsCategory` has no such production.
         *
         * The lookup **is** the grammar rule: `charProp = IsCategory` admits exactly the 37 spellings
         * in [CATEGORY_MASKS] and nothing else, so a `\p{Sc}` that is valid and a `\p{Cs}` that is not
         * are separated here rather than by a second table that could disagree with this one.
         */
        fun categoryMask(spelling: String): Long? = CATEGORY_MASKS[spelling]

        private fun maskOf(vararg generalCategories: Byte): Long =
            generalCategories.fold(0L) { mask, category -> mask or (1L shl category.toInt()) }

        /**
         * RFC 9485's `IsCategory`, spelled out.
         *
         * The seven single-letter entries are the unions of their own subcategories and are written as
         * such rather than as `Character` group constants, because the two disagree exactly where it
         * matters: `C` here is `Cc | Cf | Cn | Co`, **without** `Cs`, since the ABNF gives `Others` no
         * `s` alternative and a JSON string holds scalar values rather than surrogates anyway.
         */
        private val CATEGORY_MASKS: Map<String, Long> = buildMap {
            val upper = maskOf(Character.UPPERCASE_LETTER)
            val lower = maskOf(Character.LOWERCASE_LETTER)
            val title = maskOf(Character.TITLECASE_LETTER)
            val modifierLetter = maskOf(Character.MODIFIER_LETTER)
            val otherLetter = maskOf(Character.OTHER_LETTER)
            put("Lu", upper)
            put("Ll", lower)
            put("Lt", title)
            put("Lm", modifierLetter)
            put("Lo", otherLetter)
            put("L", upper or lower or title or modifierLetter or otherLetter)

            val nonSpacing = maskOf(Character.NON_SPACING_MARK)
            val spacingCombining = maskOf(Character.COMBINING_SPACING_MARK)
            val enclosing = maskOf(Character.ENCLOSING_MARK)
            put("Mn", nonSpacing)
            put("Mc", spacingCombining)
            put("Me", enclosing)
            put("M", nonSpacing or spacingCombining or enclosing)

            val decimal = maskOf(Character.DECIMAL_DIGIT_NUMBER)
            val letterNumber = maskOf(Character.LETTER_NUMBER)
            val otherNumber = maskOf(Character.OTHER_NUMBER)
            put("Nd", decimal)
            put("Nl", letterNumber)
            put("No", otherNumber)
            put("N", decimal or letterNumber or otherNumber)

            val connector = maskOf(Character.CONNECTOR_PUNCTUATION)
            val dash = maskOf(Character.DASH_PUNCTUATION)
            val open = maskOf(Character.START_PUNCTUATION)
            val close = maskOf(Character.END_PUNCTUATION)
            val initialQuote = maskOf(Character.INITIAL_QUOTE_PUNCTUATION)
            val finalQuote = maskOf(Character.FINAL_QUOTE_PUNCTUATION)
            val otherPunctuation = maskOf(Character.OTHER_PUNCTUATION)
            put("Pc", connector)
            put("Pd", dash)
            put("Ps", open)
            put("Pe", close)
            put("Pi", initialQuote)
            put("Pf", finalQuote)
            put("Po", otherPunctuation)
            put("P", connector or dash or open or close or initialQuote or finalQuote or otherPunctuation)

            val space = maskOf(Character.SPACE_SEPARATOR)
            val line = maskOf(Character.LINE_SEPARATOR)
            val paragraph = maskOf(Character.PARAGRAPH_SEPARATOR)
            put("Zs", space)
            put("Zl", line)
            put("Zp", paragraph)
            put("Z", space or line or paragraph)

            val math = maskOf(Character.MATH_SYMBOL)
            val currency = maskOf(Character.CURRENCY_SYMBOL)
            val modifierSymbol = maskOf(Character.MODIFIER_SYMBOL)
            val otherSymbol = maskOf(Character.OTHER_SYMBOL)
            put("Sm", math)
            put("Sc", currency)
            put("Sk", modifierSymbol)
            put("So", otherSymbol)
            put("S", math or currency or modifierSymbol or otherSymbol)

            val control = maskOf(Character.CONTROL)
            val format = maskOf(Character.FORMAT)
            val unassigned = maskOf(Character.UNASSIGNED)
            val privateUse = maskOf(Character.PRIVATE_USE)
            put("Cc", control)
            put("Cf", format)
            put("Cn", unassigned)
            put("Co", privateUse)
            put("C", control or format or unassigned or privateUse)
        }
    }
}
