package app.oreshkov.rabosh.testkit.property

/**
 * A source of test values of type [T].
 *
 * A generator does three jobs, and the second and third are what make failures useful:
 *
 * - [generate] produces a random value.
 * - [shrink] proposes simpler values, so a failure is reported as a minimal counterexample
 *   rather than as whatever 400-byte structure happened to trip it.
 * - [edgeCases] lists values always worth trying. Boundaries are where storage formats break,
 *   so these run before any random value does.
 */
public interface Gen<T> {
    public fun generate(source: RandomSource): T

    /**
     * Returns candidate values simpler than [value], best-first.
     *
     * The sequence must be finite, must never contain [value] itself, and must be
     * *well-founded*: repeatedly shrinking has to terminate. Returning a value that can shrink
     * back to [value] would make the shrink loop spin until it hits its budget.
     *
     * The default gives up immediately, which is correct but reports unminimised failures.
     */
    public fun shrink(value: T): Sequence<T> = emptySequence()

    /** Values tried before random generation begins. Boundaries, empties, and known traps. */
    public val edgeCases: List<T> get() = emptyList()

    /** How a counterexample is displayed in a failure report. */
    public fun render(value: T): String = value.toString()

    public companion object
}

/**
 * Maps generated values through [transform].
 *
 * Note that shrinking is **not** preserved: a `B` cannot be mapped back to the `A` it came from,
 * so failures of a mapped generator are reported unminimised. Where minimal counterexamples
 * matter, implement [Gen] directly and give it a real [Gen.shrink].
 */
public fun <A, B> Gen<A>.map(transform: (A) -> B): Gen<B> {
    val upstream = this
    return object : Gen<B> {
        override fun generate(source: RandomSource): B = transform(upstream.generate(source))
        override val edgeCases: List<B> get() = upstream.edgeCases.map(transform)
    }
}

/**
 * Filters generated and shrunk values by [predicate].
 *
 * Unlike [map] this preserves shrinking, since the values keep their type. Generation retries up
 * to [maxAttempts] times before failing loudly — a predicate that rejects almost everything is a
 * bug in the test, not something to paper over with a silent skip.
 */
public fun <T> Gen<T>.filter(maxAttempts: Int = 100, predicate: (T) -> Boolean): Gen<T> {
    val upstream = this
    return object : Gen<T> {
        override fun generate(source: RandomSource): T {
            repeat(maxAttempts) {
                val candidate = upstream.generate(source)
                if (predicate(candidate)) return candidate
            }
            error("filter rejected $maxAttempts consecutive values; loosen the predicate")
        }

        override fun shrink(value: T): Sequence<T> = upstream.shrink(value).filter(predicate)
        override val edgeCases: List<T> get() = upstream.edgeCases.filter(predicate)
        override fun render(value: T): String = upstream.render(value)
    }
}

/** Prepends extra [values] to this generator's [Gen.edgeCases]. */
public fun <T> Gen<T>.withEdgeCases(vararg values: T): Gen<T> {
    val upstream = this
    val extra = values.toList()
    return object : Gen<T> {
        override fun generate(source: RandomSource): T = upstream.generate(source)
        override fun shrink(value: T): Sequence<T> = upstream.shrink(value)
        override val edgeCases: List<T> get() = extra + upstream.edgeCases
        override fun render(value: T): String = upstream.render(value)
    }
}

/** A generator of pairs, shrinking each side in turn. */
public fun <A, B> Gen.Companion.pair(first: Gen<A>, second: Gen<B>): Gen<Pair<A, B>> =
    object : Gen<Pair<A, B>> {
        override fun generate(source: RandomSource): Pair<A, B> =
            first.generate(source) to second.generate(source)

        override fun shrink(value: Pair<A, B>): Sequence<Pair<A, B>> =
            first.shrink(value.first).map { it to value.second } +
                second.shrink(value.second).map { value.first to it }

        override val edgeCases: List<Pair<A, B>>
            get() = first.edgeCases.flatMap { a -> second.edgeCases.map { b -> a to b } }

        override fun render(value: Pair<A, B>): String =
            "(${first.render(value.first)}, ${second.render(value.second)})"
    }
