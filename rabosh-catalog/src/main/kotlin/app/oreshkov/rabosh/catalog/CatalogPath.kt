package app.oreshkov.rabosh.catalog

/** One step of a [CatalogPath]: into an object field, or into the elements of an array. */
public sealed interface CatalogStep {
    /** Into the field called [name]. */
    public data class Field(val name: String) : CatalogStep

    /**
     * Into every element of an array, without distinguishing which.
     *
     * This is the step [CatalogPath] has and [app.oreshkov.rabosh.variant.VariantPath] does not,
     * and it is the whole reason the two types are separate. See the class documentation.
     */
    public data object AnyElement : CatalogStep
}

/**
 * A location *shape* inside a document: the same list of steps as a
 * [app.oreshkov.rabosh.variant.VariantPath], except that array indices are collapsed into a single
 * [CatalogStep.AnyElement].
 *
 * ```
 * {"items":[{"sku":"a"},{"sku":"b"}]}
 *
 *   $                 object
 *   $.items           array
 *   $.items[*]        object, twice
 *   $.items[*].sku    string, twice
 * ```
 *
 * **Why this is not `VariantPath`.** That type's contract is that it names *exactly one* location,
 * which is what makes it usable as an index key and what lets `Variant.select` return one value.
 * Adding a wildcard to it would break that guarantee for every existing caller. So the catalog gets
 * its own type, and the relationship is one-way: every `VariantPath` has a `CatalogPath` shape, and
 * a `CatalogPath` describes a set of `VariantPath`s.
 *
 * **Why the indices are collapsed at all.** A ten-thousand-element array would otherwise produce ten
 * thousand paths, exhaust the path budget on its own, and push everything genuinely worth modelling
 * into the overflow bucket. Collapsing also produces the path an inverted index over array elements
 * actually wants — `$.tags[*]` is the thing somebody queries, `$.tags[7]` is not. ClickHouse's JSON
 * type and the Variant shredding specification both make the same choice.
 *
 * Paths are ordered so that a report is stable between runs: field before element at the same
 * position, then by field name, then shorter first.
 */
public class CatalogPath(public val steps: List<CatalogStep>) : Comparable<CatalogPath> {

    /** `true` for the path that selects the document itself. */
    public val isRoot: Boolean get() = steps.isEmpty()

    /** How many steps from the root. */
    public val depth: Int get() = steps.size

    /** This path extended by [step]. */
    public operator fun plus(step: CatalogStep): CatalogPath = CatalogPath(steps + step)

    /** Whether this path is [other] or lies underneath it. */
    public fun startsWith(other: CatalogPath): Boolean =
        steps.size >= other.steps.size && steps.subList(0, other.steps.size) == other.steps

    override fun compareTo(other: CatalogPath): Int {
        val shared = minOf(steps.size, other.steps.size)
        for (index in 0 until shared) {
            val comparison = compareSteps(steps[index], other.steps[index])
            if (comparison != 0) return comparison
        }
        return steps.size.compareTo(other.steps.size)
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is CatalogPath && steps == other.steps)

    override fun hashCode(): Int = steps.hashCode()

    /** The canonical expression for this path; [parse] round-trips it. */
    override fun toString(): String = buildString {
        append('$')
        for (step in steps) {
            when (step) {
                is CatalogStep.Field ->
                    if (step.name.isSimple()) append('.').append(step.name) else appendQuoted(step.name)

                CatalogStep.AnyElement -> append("[*]")
            }
        }
    }

    public companion object {
        /** The path that selects the document itself. */
        public val ROOT: CatalogPath = CatalogPath(emptyList())

        /**
         * Parses an expression such as `$.items[*].sku` or `$["odd name"]`.
         *
         * The same grammar [app.oreshkov.rabosh.variant.VariantPath.parse] accepts, with `[*]` in
         * place of a numeric index. A numeric index is **rejected** rather than silently collapsed:
         * `$.items[0]` means something this type cannot represent, and quietly widening it to
         * `$.items[*]` would answer a question the caller did not ask.
         *
         * @throws IllegalArgumentException with the offending position, for malformed input.
         */
        public fun parse(expression: String): CatalogPath {
            var position = 0
            fun fail(message: String): Nothing =
                throw IllegalArgumentException("$message at position $position in path '$expression'")

            if (position >= expression.length || expression[position] != '$') fail("path must start with '$'")
            position++

            val steps = mutableListOf<CatalogStep>()
            while (position < expression.length) {
                when (expression[position]) {
                    '.' -> {
                        position++
                        val start = position
                        while (position < expression.length && expression[position].isIdentifierPart()) position++
                        if (position == start) fail("expected a field name after '.'")
                        steps += CatalogStep.Field(expression.substring(start, position))
                    }

                    '[' -> {
                        position++
                        if (position >= expression.length) fail("unterminated '['")
                        when (expression[position]) {
                            '*' -> {
                                position++
                                steps += CatalogStep.AnyElement
                            }

                            '"' -> {
                                position++
                                steps += CatalogStep.Field(readQuoted(expression, position) { position = it })
                            }

                            else -> fail("expected '*' or a quoted field name; a catalog path has no indices")
                        }
                        if (position >= expression.length || expression[position] != ']') fail("expected ']'")
                        position++
                    }

                    else -> fail("expected '.' or '['")
                }
            }
            return CatalogPath(steps)
        }

        private inline fun readQuoted(expression: String, from: Int, advance: (Int) -> Unit): String {
            var position = from
            val name = StringBuilder()
            while (true) {
                require(position < expression.length) { "unterminated quoted field name in path '$expression'" }
                when (val character = expression[position]) {
                    '"' -> {
                        position++
                        advance(position)
                        return name.toString()
                    }

                    '\\' -> {
                        position++
                        require(position < expression.length) { "unterminated escape in path '$expression'" }
                        name.append(expression[position])
                        position++
                    }

                    else -> {
                        name.append(character)
                        position++
                    }
                }
            }
        }

        /**
         * Field before element, so `$.a` and `$[*]` order the same way in every report.
         *
         * A total order matters more than which order: an inferred schema is something people diff
         * between runs, and a set iterated in hash order is a diff full of noise.
         */
        private fun compareSteps(left: CatalogStep, right: CatalogStep): Int = when {
            left is CatalogStep.Field && right is CatalogStep.Field -> left.name.compareTo(right.name)
            left is CatalogStep.Field -> -1
            right is CatalogStep.Field -> 1
            else -> 0
        }
    }
}

/** Convenience for building a path from field names: `catalogPathOf("user", "name")`. */
public fun catalogPathOf(vararg fields: String): CatalogPath =
    CatalogPath(fields.map { CatalogStep.Field(it) })

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
