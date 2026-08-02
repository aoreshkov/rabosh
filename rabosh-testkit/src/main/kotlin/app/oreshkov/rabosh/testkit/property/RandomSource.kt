package app.oreshkov.rabosh.testkit.property

import kotlin.random.Random
import kotlin.random.nextLong

/**
 * The randomness available to a generator during a single property iteration.
 *
 * Every source is created from an explicit [seed], and [Random] is fully specified by its seed,
 * so a run is reproducible: re-running a property with the same root seed replays exactly the
 * same values. That is the whole point of the harness — a failure prints its seed and can be
 * pinned as a regression test.
 *
 * Instances are not thread-safe and are not meant to be shared between iterations.
 */
public class RandomSource(public val seed: Long) {
    private val random = Random(seed)

    public fun nextBoolean(): Boolean = random.nextBoolean()

    /** Returns `true` with probability [probability], clamped to `0.0..1.0`. */
    public fun chance(probability: Double): Boolean = random.nextDouble() < probability

    public fun nextInt(): Int = random.nextInt()

    /** Returns a value in `0 until until`. [until] must be positive. */
    public fun nextInt(until: Int): Int = random.nextInt(until)

    public fun nextInt(range: IntRange): Int = random.nextInt(range.first, range.last + 1)

    public fun nextLong(): Long = random.nextLong()

    public fun nextLong(range: LongRange): Long = random.nextLong(range)

    public fun nextDouble(): Double = random.nextDouble()

    /** Picks one element uniformly. [items] must not be empty. */
    public fun <T> pick(items: List<T>): T {
        require(items.isNotEmpty()) { "cannot pick from an empty list" }
        return items[random.nextInt(items.size)]
    }

    /**
     * Picks one element with probability proportional to its weight.
     *
     * Weights must be positive; an entry with weight `3` is drawn three times as often as one
     * with weight `1`.
     */
    public fun <T> frequency(weighted: List<Pair<Int, T>>): T {
        require(weighted.isNotEmpty()) { "cannot pick from an empty distribution" }
        val total = weighted.sumOf { (weight, _) ->
            require(weight > 0) { "weights must be positive, got $weight" }
            weight
        }
        var remaining = random.nextInt(total)
        for ((weight, value) in weighted) {
            remaining -= weight
            if (remaining < 0) return value
        }
        // Unreachable: the loop subtracts `total` in aggregate and `remaining < total` on entry.
        return weighted.last().second
    }

    /** Derives an independent source, so a composite generator can seed its parts reproducibly. */
    public fun split(): RandomSource = RandomSource(random.nextLong())
}
