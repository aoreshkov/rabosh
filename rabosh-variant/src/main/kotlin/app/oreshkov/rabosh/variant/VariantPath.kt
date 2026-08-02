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
