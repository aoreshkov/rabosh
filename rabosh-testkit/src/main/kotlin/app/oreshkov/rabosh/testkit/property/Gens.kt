package app.oreshkov.rabosh.testkit.property

/**
 * Candidate values simpler than [value], ordered best-first: zero, the sign flip, then a binary
 * search back towards [value].
 *
 * This is the classic QuickCheck integral shrink. It is well-founded because every candidate is
 * strictly closer to zero than [value].
 */
internal fun shrinkTowardsZero(value: Long): Sequence<Long> = sequence {
    if (value == 0L) return@sequence
    yield(0L)
    // Negative values are usually incidental; try the positive twin early.
    if (value < 0 && value != Long.MIN_VALUE) yield(-value)

    var delta = value / 2
    while (delta != 0L) {
        val candidate = value - delta
        if (candidate != value && candidate != 0L) yield(candidate)
        delta /= 2
    }
}

/** Always generates [value]. Useful as a fixed leg of [oneOf] or [frequency]. */
public fun <T> Gen.Companion.constant(value: T): Gen<T> = object : Gen<T> {
    override fun generate(source: RandomSource): T = value
}

public fun Gen.Companion.boolean(): Gen<Boolean> = object : Gen<Boolean> {
    // `false` is the simpler value, so a failing `true` shrinks to it.
    override fun shrink(value: Boolean): Sequence<Boolean> =
        if (value) sequenceOf(false) else emptySequence()

    override fun generate(source: RandomSource): Boolean = source.nextBoolean()
    override val edgeCases: List<Boolean> get() = listOf(false, true)
}

public fun Gen.Companion.int(range: IntRange = Int.MIN_VALUE..Int.MAX_VALUE): Gen<Int> =
    object : Gen<Int> {
        override fun generate(source: RandomSource): Int = source.nextInt(range)

        override fun shrink(value: Int): Sequence<Int> =
            shrinkTowardsZero(value.toLong()).map { it.toInt() }.filter { it in range }

        override val edgeCases: List<Int>
            get() = listOf(0, 1, -1, range.first, range.last).filter { it in range }.distinct()
    }

public fun Gen.Companion.long(range: LongRange = Long.MIN_VALUE..Long.MAX_VALUE): Gen<Long> =
    object : Gen<Long> {
        override fun generate(source: RandomSource): Long = source.nextLong(range)

        override fun shrink(value: Long): Sequence<Long> =
            shrinkTowardsZero(value).filter { it in range }

        override val edgeCases: List<Long>
            get() = listOf(0L, 1L, -1L, range.first, range.last).filter { it in range }.distinct()
    }

/** Characters that stay readable in a failure report, so shrunk counterexamples are legible. */
private const val SIMPLE_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-."

public fun Gen.Companion.string(
    lengths: IntRange = 0..24,
    alphabet: String = SIMPLE_ALPHABET,
): Gen<String> = object : Gen<String> {
    override fun generate(source: RandomSource): String {
        val length = source.nextInt(lengths)
        return buildString(length) {
            repeat(length) { append(alphabet[source.nextInt(alphabet.length)]) }
        }
    }

    override fun shrink(value: String): Sequence<String> = shrinkString(value, lengths)

    override val edgeCases: List<String>
        get() = listOf("", "a", alphabet.take(lengths.last.coerceAtMost(alphabet.length)))
            .filter { it.length in lengths }
            .distinct()
}

/** Shorter strings first, then character-by-character simplification. */
internal fun shrinkString(value: String, lengths: IntRange): Sequence<String> = sequence {
    if (value.isEmpty()) return@sequence

    if (lengths.first == 0) yield("")

    // Halving prefixes: the fastest way down from a long string.
    var length = value.length / 2
    while (length > 0) {
        yield(value.take(length))
        length /= 2
    }

    // Then remove a single character at a time, so the minimum is genuinely minimal.
    for (index in value.indices) {
        yield(value.removeRange(index, index + 1))
    }

    // Finally simplify characters in place, which does not change the length.
    for (index in value.indices) {
        val character = value[index]
        if (character != 'a') {
            yield(value.replaceRange(index, index + 1, "a"))
        }
    }
}.filter { it.length in lengths && it != value }

public fun <T> Gen.Companion.list(
    element: Gen<T>,
    sizes: IntRange = 0..16,
): Gen<List<T>> = object : Gen<List<T>> {
    override fun generate(source: RandomSource): List<T> {
        val size = source.nextInt(sizes)
        return List(size) { element.generate(source) }
    }

    override fun shrink(value: List<T>): Sequence<List<T>> = sequence {
        if (value.isEmpty()) return@sequence

        if (sizes.first == 0) yield(emptyList())

        var size = value.size / 2
        while (size > 0) {
            yield(value.take(size))
            size /= 2
        }

        for (index in value.indices) {
            yield(value.subList(0, index) + value.subList(index + 1, value.size))
        }

        // Shrink elements in place. Capped: without a bound this is quadratic on large lists
        // and the shrink budget is better spent removing elements than perfecting them.
        for (index in value.indices.take(ELEMENT_SHRINK_LIMIT)) {
            for (shrunk in element.shrink(value[index]).take(ELEMENT_SHRINK_LIMIT)) {
                yield(value.toMutableList().also { it[index] = shrunk })
            }
        }
    }.filter { it.size in sizes && it != value }

    override val edgeCases: List<List<T>>
        get() = buildList {
            if (sizes.first == 0) add(emptyList())
            element.edgeCases.firstOrNull()?.let { if (1 in sizes) add(listOf(it)) }
            if (element.edgeCases.size in sizes) add(element.edgeCases)
        }.distinct()

    override fun render(value: List<T>): String =
        value.joinToString(prefix = "[", postfix = "]") { element.render(it) }
}

private const val ELEMENT_SHRINK_LIMIT = 8

/** Picks uniformly between generators, shrinking with whichever one accepts the value. */
public fun <T> Gen.Companion.oneOf(vararg options: Gen<T>): Gen<T> {
    require(options.isNotEmpty()) { "oneOf requires at least one generator" }
    val choices = options.toList()
    return object : Gen<T> {
        override fun generate(source: RandomSource): T = source.pick(choices).generate(source)
        override fun shrink(value: T): Sequence<T> = choices.asSequence().flatMap { it.shrink(value) }
        override val edgeCases: List<T> get() = choices.flatMap { it.edgeCases }
        override fun render(value: T): String = choices.first().render(value)
    }
}

/** Picks between generators with the given weights. See [RandomSource.frequency]. */
public fun <T> Gen.Companion.frequency(vararg options: Pair<Int, Gen<T>>): Gen<T> {
    require(options.isNotEmpty()) { "frequency requires at least one generator" }
    val choices = options.toList()
    return object : Gen<T> {
        override fun generate(source: RandomSource): T = source.frequency(choices).generate(source)
        override fun shrink(value: T): Sequence<T> =
            choices.asSequence().flatMap { (_, gen) -> gen.shrink(value) }

        override val edgeCases: List<T> get() = choices.flatMap { (_, gen) -> gen.edgeCases }
        override fun render(value: T): String = choices.first().second.render(value)
    }
}
