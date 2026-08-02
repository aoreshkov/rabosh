package app.oreshkov.rabosh.testkit.property

import kotlin.random.Random

/** Random iterations per property, unless overridden. See [PropertyConfig]. */
public const val DEFAULT_ITERATIONS: Int = 200

/** Ceiling on shrink attempts, so a pathological generator cannot hang the suite. */
public const val DEFAULT_MAX_SHRINKS: Int = 1_000

/**
 * Reads harness-wide defaults from system properties, so CI can turn the dial without editing
 * tests:
 *
 * ```
 * ./gradlew test -Drabosh.property.iterations=5000
 * ./gradlew test -Drabosh.property.seed=8402219922357261000
 * ```
 *
 * Pinning the seed globally is how you re-run an entire suite exactly as CI ran it.
 */
public object PropertyConfig {
    public const val ITERATIONS_PROPERTY: String = "rabosh.property.iterations"
    public const val SEED_PROPERTY: String = "rabosh.property.seed"

    public fun iterations(): Int =
        System.getProperty(ITERATIONS_PROPERTY)?.toIntOrNull() ?: DEFAULT_ITERATIONS

    public fun seed(): Long = System.getProperty(SEED_PROPERTY)?.toLongOrNull() ?: Random.nextLong()
}

/** Thrown when a property does not hold. The message carries the seed needed to reproduce it. */
public class PropertyFailure internal constructor(
    message: String,
    cause: Throwable,
) : AssertionError(message, cause)

/**
 * Checks that [property] holds for every value [gen] produces.
 *
 * Each run tries the generator's [Gen.edgeCases] first — boundaries are where encoders and
 * comparators actually break — then [iterations] random values.
 *
 * On failure the value is shrunk to a minimal counterexample and reported together with [seed].
 * Passing that seed back reproduces the run exactly:
 *
 * ```kotlin
 * // Regression: pinned from a failure first seen in CI.
 * forAll(Gen.int(), seed = 8402219922357261000L) { value -> ... }
 * ```
 *
 * @param seed root seed; defaults to a fresh random seed, or to `-Drabosh.property.seed`.
 * @throws PropertyFailure if any value falsifies the property.
 */
public fun <T> forAll(
    gen: Gen<T>,
    iterations: Int = PropertyConfig.iterations(),
    seed: Long = PropertyConfig.seed(),
    maxShrinks: Int = DEFAULT_MAX_SHRINKS,
    property: (T) -> Unit,
) {
    val root = Random(seed)
    var caseNumber = 0

    for (edgeCase in gen.edgeCases) {
        caseNumber++
        val failure = runCatching { property(edgeCase) }.exceptionOrNull() ?: continue
        throw report(gen, edgeCase, failure, seed, caseNumber, isEdgeCase = true, maxShrinks, property)
    }

    repeat(iterations) {
        caseNumber++
        // Derived per iteration, so a value depends only on the root seed and the iteration index.
        val source = RandomSource(root.nextLong())
        val value = gen.generate(source)
        val failure = runCatching { property(value) }.exceptionOrNull() ?: return@repeat
        throw report(gen, value, failure, seed, caseNumber, isEdgeCase = false, maxShrinks, property)
    }
}

/** Two-generator form. See the single-generator [forAll]. */
public fun <A, B> forAll(
    first: Gen<A>,
    second: Gen<B>,
    iterations: Int = PropertyConfig.iterations(),
    seed: Long = PropertyConfig.seed(),
    maxShrinks: Int = DEFAULT_MAX_SHRINKS,
    property: (A, B) -> Unit,
) {
    forAll(Gen.pair(first, second), iterations, seed, maxShrinks) { (a, b) -> property(a, b) }
}

private class Shrunk<T>(val value: T, val failure: Throwable, val steps: Int)

/**
 * Walks towards a minimal failing value: repeatedly take the first candidate that still fails and
 * restart from it. Stops when no candidate fails, or when the budget runs out.
 *
 * Termination rests on [Gen.shrink] being well-founded — each candidate must be strictly simpler
 * than its input. [maxShrinks] is the backstop for generators that get that wrong.
 */
private fun <T> shrink(
    gen: Gen<T>,
    value: T,
    failure: Throwable,
    maxShrinks: Int,
    property: (T) -> Unit,
): Shrunk<T> {
    var best = value
    var bestFailure = failure
    var steps = 0

    while (steps < maxShrinks) {
        var improved = false
        for (candidate in gen.shrink(best)) {
            if (steps >= maxShrinks) break
            steps++
            // Any failure counts, not only an identical one: a smaller value that breaks the
            // property in a different way is still a better bug report than a large one.
            val candidateFailure = runCatching { property(candidate) }.exceptionOrNull()
            if (candidateFailure != null) {
                best = candidate
                bestFailure = candidateFailure
                improved = true
                break
            }
        }
        if (!improved) break
    }

    return Shrunk(best, bestFailure, steps)
}

private fun <T> report(
    gen: Gen<T>,
    value: T,
    failure: Throwable,
    seed: Long,
    caseNumber: Int,
    isEdgeCase: Boolean,
    maxShrinks: Int,
    property: (T) -> Unit,
): PropertyFailure {
    val shrunk = shrink(gen, value, failure, maxShrinks, property)
    val origin = if (isEdgeCase) "edge case #$caseNumber" else "random case #$caseNumber"
    val minimised = gen.render(shrunk.value) != gen.render(value)

    val message = buildString {
        appendLine("Property failed on $origin.")
        appendLine()
        appendLine("Counterexample${if (minimised) " (shrunk in ${shrunk.steps} steps)" else ""}:")
        appendLine("  ${gen.render(shrunk.value)}")
        if (minimised) {
            appendLine()
            appendLine("Original failing value:")
            appendLine("  ${gen.render(value)}")
        }
        appendLine()
        appendLine("Reproduce this exact run by pinning the seed:")
        appendLine("  forAll(gen, seed = ${seed}L) { ... }")
        appendLine("or for the whole suite:")
        appendLine("  ./gradlew test -D${PropertyConfig.SEED_PROPERTY}=$seed")
        appendLine()
        append("Failure: ${shrunk.failure}")
    }

    return PropertyFailure(message, shrunk.failure)
}
