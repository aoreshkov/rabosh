package app.oreshkov.rabosh.catalog

/** One step of a [CatalogPath]: into an object field, into the elements of an array, or downwards. */
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

    /**
     * Down: the steps after this one apply at this node **and at every node below it**.
     *
     * Spelled `..`, and RFC 9535's descendant segment exactly — `$..["@type"]` is that field wherever
     * it sits, the root's own member included. Zero levels counts, which is what makes `$..a` and
     * `$.a` both match `{"a":1}`.
     *
     * **The step that is a pattern rather than a collapse, and the distinction is load-bearing.**
     * [AnyElement] exists because the data has positions this type will not distinguish: a document
     * *produces* it. Nothing produces this one — a sketch walks documents and can only ever emit
     * fields and elements, so `SchemaInferenceTest` asserts that no model over any corpus contains
     * one. A path carrying it came from a caller who wrote `..`, which is why it may name an index
     * and a predicate leaf and may never be a sketch key or a projection.
     *
     * **What it costs where it is used.** The walks that build an index narrow their candidates on
     * the way down and prune a subtree nothing can reach; a descendant candidate never narrows away,
     * so a store defining such an index walks every document whole during flush and compaction. That
     * is the price of the step and it is charged only to the stores that spell it.
     */
    public data object AnyDescendant : CatalogStep
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
 * That set is not abstract. [forEachNodeIn] enumerates it **against a document** — which is the only
 * way it can be enumerated, since `[*]` stands for as many locations as that document has elements.
 * The direction stays one-way: there is no `CatalogPath.toVariantPath`, and there cannot be.
 *
 * **And one step the data cannot produce.** [CatalogStep.AnyDescendant], spelled `..`, is a *pattern*
 * rather than a collapse: `$..["@type"]` names that field wherever it sits, at any depth, which is
 * the only sound way to ask about a corpus whose nesting is the content designer's rather than the
 * schema's. Enumerating the shapes instead was measured and refused — on one 46 MB corpus, 72% of
 * tagged elements belong to a type occupying more than one shape, one type occupies 49, and a shape
 * missing from the list is a document missing from a result with nothing to report it. A sketch
 * never emits this step, an index and a predicate leaf may carry it, and a projection still may not.
 *
 * **Why the indices are collapsed at all.** A ten-thousand-element array would otherwise produce ten
 * thousand paths, exhaust the path budget on its own, and push everything genuinely worth modelling
 * into the overflow bucket. Collapsing also produces the path an inverted index over array elements
 * actually wants — `$.tags[*]` is the thing somebody queries, `$.tags[7]` is not. ClickHouse's JSON
 * type and the Variant shredding specification both make the same choice.
 *
 * Paths are ordered so that a report is stable between runs: field before element at the same
 * position, then by field name, then shorter first.
 *
 * **`PATHS.md` compares this grammar with the other three**, which is where to look before spelling
 * one expression for a filter and an extraction at once: `[*]` means array elements here and every
 * child in RFC 9535, and a backslash is literal here and an escape there. Neither divergence has a
 * diagnostic, and both are what [toJsonPath] and [Companion.parseJsonPath] exist to cross.
 */
public class CatalogPath(public val steps: List<CatalogStep>) : Comparable<CatalogPath> {

    init {
        // `..` is idempotent — the steps after two of them apply exactly where the steps after one
        // of them do — so a second is a structure with no meaning of its own, and there is no
        // expression for it in either grammar: `$....` is not a spelling and RFC 9535's
        // descendant-segment must be followed by a selector. Rejecting it here is what keeps
        // **every** step list round-trippable through `toString` and `parse`, which is not a nicety:
        // `IndexRegistry` persists a path as `toString` and reads it back with `parse`, so a path
        // that could be built and not read back would be a registry entry nothing could open.
        // No previously valid path can fail this — none of them could hold a descendant at all.
        for (index in 1 until steps.size) {
            require(steps[index] !== CatalogStep.AnyDescendant || steps[index - 1] !== CatalogStep.AnyDescendant) {
                "a path may not hold two '..' steps in a row: the second selects nothing the first does not"
            }
        }
    }

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

    /**
     * The canonical expression for this path; [parse] round-trips it, for every step list this type
     * admits.
     *
     * A field after a `..` is written without its dot — `$..sku`, not `$...sku` — which is RFC 9535's
     * shorthand rule and the reason the two dots are read before anything else.
     */
    override fun toString(): String = buildString {
        append('$')
        var afterDescendant = false
        for (step in steps) {
            when (step) {
                is CatalogStep.Field -> when {
                    !step.name.isSimple() -> appendQuoted(step.name)
                    afterDescendant -> append(step.name)
                    else -> append('.').append(step.name)
                }

                CatalogStep.AnyElement -> append("[*]")
                CatalogStep.AnyDescendant -> append("..")
            }
            afterDescendant = step === CatalogStep.AnyDescendant
        }
    }

    /**
     * This shape as an RFC 9535 query: `$['items'][:]['sku']`.
     *
     * The interchange spelling, and deliberately not the engine's. [toString] writes `$.items[*]`,
     * which parses under RFC 9535 and **means something else** — `*` selects every child, of an
     * object as well as an array, while [CatalogStep.AnyElement] selects array elements. Over
     * `{"items":{"sku":"a"}}` the two nodelists differ, with nothing to say so. So `AnyElement` is
     * written here as the slice `[:]`, which §2.3.4.2.2 defines over arrays alone — *"It selects no
     * nodes from a node that is not an array"* — and the rendering means what the walk means over
     * every document shape. `NodeWalkDifferentialTest` is where that equality is checked rather than
     * claimed.
     *
     * Member names are single-quoted with §2.3.1.1's escapes, and a control character is written
     * `\uXXXX` even where a named escape exists.
     *
     * **This is a query, and never a Normalized Path.** RFC 9535 §2.7 admits name selectors and
     * non-negative index selectors only, so a shape carrying `AnyElement` has no normalized form at
     * all — there is no single location to name. Two of these must therefore not be compared as
     * text the way two `VariantPath.toNormalizedPath` strings can be; compare the paths.
     *
     * **This is not what the engine writes to disk.** A path is persisted as [toString] and read
     * back by [parse]. Reach for this to hand a shape to something outside the engine, and for
     * [toString] everywhere else.
     *
     * **A trailing `..` has no rendering at all**, and that is a fact about RFC 9535 rather than a
     * gap here: `$..` means the root and every node below it, `$..*` means every node below it and
     * *not* the root, and the grammar has no way to say the union. Rendering it as `$..*` would be
     * the `[*]`-for-`AnyElement` mistake a second time — an expression that parses, looks right, and
     * quietly drops a node. So it is refused, like the surrogate below.
     *
     * @throws IllegalArgumentException if a field name holds an unpaired surrogate, which RFC 9535
     *   has no production for — a name decoded from a stored document cannot hold one — or if the
     *   path ends in [CatalogStep.AnyDescendant].
     */
    public fun toJsonPath(): String = buildString { appendJsonPath(steps) }

    public companion object {
        /** The path that selects the document itself. */
        public val ROOT: CatalogPath = CatalogPath(emptyList())

        /**
         * Parses an expression such as `$.items[*].sku` or `$["odd name"]`.
         *
         * The same grammar [app.oreshkov.rabosh.variant.VariantPath.parse] accepts, with `[*]` in
         * place of a numeric index and `..` for a descendant. A numeric index is **rejected** rather
         * than silently collapsed: `$.items[0]` means something this type cannot represent, and
         * quietly widening it to `$.items[*]` would answer a question the caller did not ask.
         *
         * **`..` is read greedily and the name after it carries no dot**, which is what keeps the two
         * spellings apart: `$..sku` is [CatalogStep.AnyDescendant] then a field, `$.a.sku` is two
         * fields, and `$...sku` is neither and fails. `$..` alone is every node in the document, root
         * included — the shape an `elemMatch` over a subtree of unknown depth needs. Two `..` in a row
         * are refused, because the second selects nothing the first does not and there would be no
         * expression for it to round-trip through.
         *
         * **A field name that is not `[A-Za-z0-9_]+` requires the bracket form**, and the example
         * that matters is not an odd one. `$.@type` does not parse — the dot form takes an
         * identifier — so a protobuf-JSON corpus, where `@type` is on *every* message, is written
         * `$["@type"]` throughout. In Kotlin the readable spelling is a raw string:
         *
         * ```kotlin
         * CatalogPath.parse("""$["@type"]""")
         * CatalogPath.parse("""$.players[*]["@type"]""")
         * ```
         *
         * Inside the quotes a **backslash escapes the next character literally**, so `$["a\nb"]` is
         * the three-character name `anb` and not `a`, newline, `b`. That is self-consistent and it
         * is deliberately **not** RFC 9535 §2.7's escaping — see
         * [app.oreshkov.rabosh.variant.VariantPath.toNormalizedPath] for the interchange spelling,
         * which is the one to hand to something outside the engine.
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
                    // Two dots before one: `..` is a step of its own, and the name after it carries
                    // no dot, so `$..sku` is a descendant and a field where `$.a.sku` is two fields.
                    // Read greedily, which is what makes the two spellings unambiguous.
                    '.' -> if (position + 1 < expression.length && expression[position + 1] == '.') {
                        position += 2
                        steps += CatalogStep.AnyDescendant
                        // A dot cannot follow `..`: a second one would be a step that selects nothing
                        // the first does not and has no spelling to round-trip through, and a single
                        // one would be a *third* way to write a step that already has two. The name
                        // after a descendant is bare, which is RFC 9535's rule for the same reason.
                        if (position < expression.length && expression[position] == '.') {
                            fail(
                                if (position + 1 < expression.length && expression[position + 1] == '.') {
                                    "'..' twice in a row selects nothing a single one does not"
                                } else {
                                    "a name after '..' carries no dot: write '$..name'"
                                },
                            )
                        }
                        val start = position
                        while (position < expression.length && expression[position].isIdentifierPart()) position++
                        if (position > start) steps += CatalogStep.Field(expression.substring(start, position))
                    } else {
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

        /**
         * Reads an RFC 9535 query as a shape, over the sub-language this type can represent.
         *
         * The inverse of [toJsonPath], and the reader to reach for when the expression came from
         * outside the engine — a command line, a configuration file, or the same string a JSONPath
         * evaluation was handed. [parse] reads the engine's own spelling and accepts neither single
         * quotes nor RFC escapes; this one accepts both, which is what lets one expression be
         * written once and used for both a filter and an extraction.
         *
         * Accepted: `$`, the shorthand `.name` and `.*`, and a bracketed `['name']`, `["name"]`,
         * `[*]` or `[:]`, with blanks where §2.3.5's `S` allows them. Names carry §2.3.1.1's
         * escapes, so `$['a\nb']` is `a`, newline, `b` — and **not** what [parse] makes of the same
         * eight characters, where a backslash escapes the next character literally. The two
         * grammars are different languages that happen to share a bracket.
         *
         * **`[*]` is accepted as [CatalogStep.AnyElement] while [toJsonPath] emits `[:]`, and the
         * asymmetry is deliberate.** The two selectors do not mean the same thing — see
         * [toJsonPath] — so the lenient direction takes what a consumer will type and the strict
         * direction emits what cannot be misread. Postel's rule, applied where the meanings differ.
         * Do not "fix" this into a symmetry: making the reader refuse `[*]` would reject the
         * spelling every existing filter uses, and making the writer emit `[*]` would reintroduce a
         * rendering that is wrong over objects.
         *
         * **A construct this type has no step for is refused by name, never approximated.** `[0]`,
         * `..`, `[?…]`, a slice with a bound or a step, and two selectors in one segment each raise
         * [PathNotRepresentableException] carrying the [PathConstruct] — so a caller can tell *a
         * typo the operator can fix* from *a question this grammar does not ask*, which is the
         * distinction an `IllegalArgumentException` from [parse] cannot make. Widening `$.items[0]`
         * to `$.items[*]` would answer a question nobody asked.
         *
         * ```kotlin
         * CatalogPath.parseJsonPath("""$['response']['body']['@type']""")   // = $.response.body["@type"]
         * CatalogPath.parseJsonPath("$.items[*].sku")                       // = $.items[*].sku
         * ```
         *
         * @throws PathNotRepresentableException if [expression] is a valid JSONPath query naming
         *   something this type has no step for.
         * @throws IllegalArgumentException with the offending position, for malformed input.
         */
        public fun parseJsonPath(expression: String): CatalogPath = parseJsonPathQuery(expression)

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
        /**
         * Field, then element, then descendant — an order for a stable report and nothing more.
         *
         * It says nothing about what a step *selects*: `$..a` and `$.a` overlap and neither contains
         * the other in this ordering. Sorting is what makes two runs of `InferredSchema.render`
         * comparable, and a descendant never reaches a sketch, so the third rank is reached only by
         * a caller sorting paths of their own.
         */
        private fun compareSteps(left: CatalogStep, right: CatalogStep): Int = when {
            left is CatalogStep.Field && right is CatalogStep.Field -> left.name.compareTo(right.name)
            left is CatalogStep.Field -> -1
            right is CatalogStep.Field -> 1
            left === right -> 0
            left === CatalogStep.AnyElement -> -1
            else -> 1
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
