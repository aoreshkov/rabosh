package app.oreshkov.rabosh.variant

/** One step of a [VariantPath]: into an object field, or into an array element. */
public sealed interface VariantPathStep {
    /** Into the field called [name]. */
    public data class Field(val name: String) : VariantPathStep

    /** Into the element at [index]. */
    public data class Index(val index: Int) : VariantPathStep {
        init {
            require(index >= 0) { "array index must not be negative, was $index" }
        }
    }
}

/**
 * A location inside a document, as a list of steps from the root.
 *
 * Paths are the unit everything above this module names data by: the catalog counts observations
 * per path, an index is built for a path, and a query predicate applies to a path. Keeping them as
 * a parsed list of steps rather than as a string means that comparison, prefixing and grouping are
 * exact rather than textual — `$["a.b"]` and `$.a.b` are different locations and must never
 * compare equal.
 *
 * **`PATHS.md` compares this grammar with the other three**, which is where to look before handing
 * one expression to two of them: [parse] reads `"` and a backslash literally, §2.7 reads `'` and
 * seven escapes, and RFC 9535 reads both quotings and its own escapes. [parseJsonPathOrNull] is the
 * reader for an expression that came from outside the engine, and the one to reach for instead of
 * comparing `parse(e).toString()` with `e`.
 */
public class VariantPath(public val steps: List<VariantPathStep>) {
    /** `true` for the path that selects the document itself. */
    public val isRoot: Boolean get() = steps.isEmpty()

    /** This path extended by [step]. */
    public operator fun plus(step: VariantPathStep): VariantPath = VariantPath(steps + step)

    /** This path extended by [other]'s steps. */
    public operator fun plus(other: VariantPath): VariantPath = VariantPath(steps + other.steps)

    override fun equals(other: Any?): Boolean = this === other || (other is VariantPath && steps == other.steps)

    override fun hashCode(): Int = steps.hashCode()

    /** The canonical expression for this path; [parse] round-trips it. */
    override fun toString(): String = buildString {
        append('$')
        for (step in steps) {
            when (step) {
                is VariantPathStep.Field ->
                    if (step.name.isSimple()) append('.').append(step.name) else appendQuoted(step.name)

                is VariantPathStep.Index -> append('[').append(step.index).append(']')
            }
        }
    }

    /**
     * This location in RFC 9535 §2.7's **Normalized Path** form: `$['items'][0]['sku']`.
     *
     * The interchange spelling, and deliberately not the engine's. [toString] writes `$.items[0].sku`
     * and falls back to a bracket only for a name that is not a bare identifier; §2.7 has no dot form
     * and no fork, so **one location has exactly one normalized rendering**. That is the property
     * which makes two of these comparable as text, and it is the one [toString] cannot claim.
     *
     * Member names are single-quoted, with the seven escapes §2.7 names — `\'`, `\\`, `\b`, `\f`,
     * `\n`, `\r`, `\t` — and `\u00xx` in lowercase for the remaining control characters. A double
     * quote is *not* escaped, and neither is a solidus: inside single quotes both are ordinary.
     * [parseNormalized] is the inverse and round-trips this exactly.
     *
     * **This is not what the engine writes to disk.** A path is persisted as [toString] and read back
     * by [parse]. Reach for this to hand a location to something outside the engine, and for
     * [toString] everywhere else.
     *
     * @throws IllegalArgumentException if a field name holds an unpaired surrogate, which §2.7 has no
     *   production for. A name decoded from a stored document cannot hold one — UTF-8 has no encoding
     *   for a lone surrogate — so this is reachable only from a path built in memory.
     */
    public fun toNormalizedPath(): String = buildString { appendNormalizedPath(steps) }

    public companion object {
        /** The path that selects the document itself. */
        public val ROOT: VariantPath = VariantPath(emptyList())

        /**
         * Parses an expression in the subset of JSONPath the engine uses:
         *
         * ```
         * $                    the document
         * $.user.name          a field of a field
         * $.items[0]           an array element
         * $["odd name"]        a field whose name is not a bare identifier
         * ```
         *
         * Deliberately not full JSONPath. Wildcards, slices and filters are query concerns; a path
         * here identifies exactly one location, which is what makes it usable as an index key.
         *
         * **A field name that is not `[A-Za-z0-9_]+` requires the bracket form**, and `$["odd name"]`
         * above understates how ordinary that is. `$.@type` does not parse — the dot form takes an
         * identifier — so in a protobuf-JSON corpus, where `@type` is on every message, the bracket
         * form is not an edge case but the rule. In Kotlin the readable spelling is a raw string:
         * `VariantPath.parse("""$["@type"]""")`.
         *
         * Inside the quotes a **backslash escapes the next character literally**, so `$["a\nb"]` is
         * the three-character name `anb` and not `a`, newline, `b`; a real newline in a name is
         * written raw and round-trips. Self-consistent, and deliberately **not** RFC 9535 §2.7's
         * escaping — [toNormalizedPath] and [parseNormalized] are that grammar, and they are the pair
         * to reach for when the path is going somewhere outside the engine.
         *
         * @throws IllegalArgumentException with the offending position, for malformed input.
         */
        public fun parse(expression: String): VariantPath {
            var position = 0
            fun fail(message: String): Nothing =
                throw IllegalArgumentException("$message at position $position in path '$expression'")

            if (position >= expression.length || expression[position] != '$') fail("path must start with '$'")
            position++

            val steps = mutableListOf<VariantPathStep>()
            while (position < expression.length) {
                when (expression[position]) {
                    '.' -> {
                        position++
                        val start = position
                        while (position < expression.length && expression[position].isIdentifierPart()) position++
                        if (position == start) fail("expected a field name after '.'")
                        steps += VariantPathStep.Field(expression.substring(start, position))
                    }

                    '[' -> {
                        position++
                        if (position >= expression.length) fail("unterminated '['")
                        if (expression[position] == '"') {
                            position++
                            val name = StringBuilder()
                            while (true) {
                                if (position >= expression.length) fail("unterminated quoted field name")
                                when (val character = expression[position]) {
                                    '"' -> {
                                        position++
                                        break
                                    }

                                    '\\' -> {
                                        position++
                                        if (position >= expression.length) fail("unterminated escape")
                                        name.append(expression[position])
                                        position++
                                    }

                                    else -> {
                                        name.append(character)
                                        position++
                                    }
                                }
                            }
                            steps += VariantPathStep.Field(name.toString())
                        } else {
                            val start = position
                            while (position < expression.length && expression[position].isDigit()) position++
                            if (position == start) fail("expected an array index or a quoted field name")
                            val index = expression.substring(start, position).toIntOrNull()
                                ?: fail("array index does not fit in an Int")
                            steps += VariantPathStep.Index(index)
                        }
                        if (position >= expression.length || expression[position] != ']') fail("expected ']'")
                        position++
                    }

                    else -> fail("expected '.' or '['")
                }
            }
            return VariantPath(steps)
        }

        /**
         * Parses an RFC 9535 §2.7 Normalized Path, and **only** a Normalized Path.
         *
         * The inverse of [VariantPath.toNormalizedPath], strict on purpose. This is the direction in
         * which a location arrives from somewhere else, and a lenient reader is what makes two
         * implementations disagree later: accepting `$.a` beside `$['a']`, or `A` beside `a`,
         * would give one location several spellings and quietly undo the single-rendering property
         * the normalized form exists for.
         *
         * So each of these is rejected rather than understood — a dot step, a wildcard, a slice, a
         * filter, a double-quoted name, a raw control character, an unpaired surrogate, an escape
         * §2.7 does not name, an uppercase hexadecimal digit, a `\u` escape for a character that has
         * a named escape, and an index carrying a sign or a leading zero. [parse] is the lenient
         * sibling, and it is the one that reads the engine's own spelling.
         *
         * An index is additionally bounded by `Int.MAX_VALUE` rather than by §2.7's 2^53-1, because
         * that is what a [VariantPathStep.Index] holds.
         *
         * @throws IllegalArgumentException with the offending position, for anything else.
         */
        public fun parseNormalized(expression: String): VariantPath = parseNormalizedPath(expression)

        /**
         * The location an RFC 9535 query names, or `null` when it does not name exactly one.
         *
         * The reader for an expression that came from outside the engine. [parse] reads the
         * engine's own spelling and [parseNormalized] reads §2.7's; neither reads what a person
         * types, which is §2.3.1.1's grammar — either quoting, RFC escapes, and the shorthand
         * `.name`. So `$['content'][108]`, `$["content"][108]` and `$.content[108]` are all this
         * one location, and all three answer.
         *
         * **`null` is an answer and not a failure, which is why this throws nothing at all.** A
         * wildcard, a slice, a descendant, a filter and a negative index each name something other
         * than one location; so does a typo; and the caller's response to both is the same — do
         * whatever it did before. The distinction between them is a different question, and
         * `CatalogPath.parseJsonPath` one module up is where it is asked and answered by name.
         *
         * **The use this exists for is deciding whether a projection can be pushed down**, and the
         * check a caller writes without it is `parse(e).toString() == e`, which fails closed on
         * every valid expression the two grammars spell differently: `$["response"]["body"]`
         * round-trips to `$.response.body`, misses the equality, and silently costs the caller its
         * column projection. That is a stringly-typed test of a semantic property. This is the
         * property.
         *
         * ```kotlin
         * VariantPath.parseJsonPathOrNull("""$['a']['b']""")   // = $.a.b
         * VariantPath.parseJsonPathOrNull("$.a[0]")            // = $.a[0]
         * VariantPath.parseJsonPathOrNull("$.a[*]")            // null: not one location
         * VariantPath.parseJsonPathOrNull("$.a[-1]")           // null: one location per document
         * ```
         */
        public fun parseJsonPathOrNull(expression: String): VariantPath? =
            parseSingularJsonPathOrNull(expression)
    }
}

/** Convenience for building a path from field names: `variantPathOf("user", "name")`. */
public fun variantPathOf(vararg fields: String): VariantPath =
    VariantPath(fields.map { VariantPathStep.Field(it) })

private fun Char.isIdentifierPart(): Boolean = this == '_' || this.isLetterOrDigit()

private fun String.isSimple(): Boolean = isNotEmpty() && all { it.isIdentifierPart() }

private fun StringBuilder.appendQuoted(name: String) {
    append('[').append('"')
    for (character in name) {
        if (character == '"' || character == '\\') append('\\')
        append(character)
    }
    append('"').append(']')
}
