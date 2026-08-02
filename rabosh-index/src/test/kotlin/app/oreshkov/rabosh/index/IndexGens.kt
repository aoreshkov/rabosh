package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.testkit.property.Gen
import app.oreshkov.rabosh.testkit.property.RandomSource

/**
 * Generators for ordinal sets and for sequences of operations over them.
 *
 * The interesting property of a bitmap is that its behaviour depends on which *encoding* each of its
 * blocks happens to be in, and a uniformly random set of ordinals reaches only one of the three. So
 * these generators are shaped: sparse sets become arrays, dense ones become bitsets, ranges become run
 * lists, and one mode deliberately straddles block boundaries — which is where an implementation that
 * confuses an ordinal with a remainder gives itself away.
 *
 * Both generators implement [Gen] directly rather than going through `map`, because `map` drops
 * shrinking and a failing 9000-ordinal script is not a bug report.
 *
 * Ordinals stay below [MAX_ORDINAL] so the `java.util.BitSet` every property is checked against costs
 * tens of kilobytes rather than hundreds of megabytes. The genuinely extreme ordinals —
 * [BitmapFormat.MAX_ORDINAL] and the block boundaries around it — are asserted directly in
 * `BitmapFormatTest` instead, where one value is the point rather than a whole model run.
 */
internal object IndexGens {

    /** Ceiling on generated ordinals: five blocks and a bit. */
    const val MAX_ORDINAL: Int = 300_000

    /** Sorted, distinct ordinals, shaped to reach every container encoding. */
    val ordinals: Gen<List<Int>> = object : Gen<List<Int>> {
        override fun generate(source: RandomSource): List<Int> = when (source.nextInt(6)) {
            0 -> sparse(source)
            1 -> dense(source)
            2 -> runs(source)
            3 -> spanning(source)
            4 -> sparse(source) + dense(source)
            else -> sparse(source) + runs(source) + spanning(source)
        }.distinct().sorted()

        override fun shrink(value: List<Int>): Sequence<List<Int>> = shrinkOrdinals(value)

        override val edgeCases: List<List<Int>>
            get() = listOf(
                emptyList(),
                listOf(0),
                listOf(MAX_ORDINAL),
                listOf(0, MAX_ORDINAL),
                // The array/bitset boundary, from both sides.
                (0 until BitmapFormat.ARRAY_MAX_CARDINALITY - 1).toList(),
                (0 until BitmapFormat.ARRAY_MAX_CARDINALITY).toList(),
                (0 until BitmapFormat.ARRAY_MAX_CARDINALITY + 1).toList(),
                // Either side of a block boundary, which no arithmetic over remainders may blur.
                listOf(65534, 65535, 65536, 65537),
                // A whole block, which is one run and the only case a 16-bit length field cannot hold.
                (0 until BitmapFormat.CONTAINER_VALUES).toList(),
            )

        override fun render(value: List<Int>): String = summarise(value)
    }

    /** A sequence of mutations and set operations, for the differential model test. */
    val script: Gen<Script> = object : Gen<Script> {
        override fun generate(source: RandomSource): Script =
            Script(List(source.nextInt(0..14)) { operation(source) })

        override fun shrink(value: Script): Sequence<Script> = sequence {
            val operations = value.operations
            if (operations.isEmpty()) return@sequence
            yield(Script(emptyList()))

            var size = operations.size / 2
            while (size > 0) {
                yield(Script(operations.take(size)))
                size /= 2
            }
            for (index in operations.indices) {
                yield(Script(operations.subList(0, index) + operations.subList(index + 1, operations.size)))
            }
            // Then simplify one operation at a time, which is what turns "it breaks somewhere in these
            // four steps" into "it breaks on this one value".
            for (index in operations.indices) {
                for (simpler in simplify(operations[index])) {
                    yield(Script(operations.toMutableList().also { it[index] = simpler }))
                }
            }
        }.filter { it.operations != value.operations }

        override val edgeCases: List<Script>
            get() = listOf(
                Script(emptyList()),
                Script(listOf(Operation.Add(0))),
                Script(listOf(Operation.AddRange(0..BitmapFormat.CONTAINER_VALUES - 1))),
                Script(listOf(Operation.Add(0), Operation.Remove(0))),
                Script(
                    listOf(
                        Operation.AddRange(0..5000),
                        Operation.RemoveRange(1000..4000),
                        Operation.Xor(listOf(0, 65536)),
                    ),
                ),
            )

        override fun render(value: Script): String = value.toString()
    }

    // --- shapes ---------------------------------------------------------------------------------

    /** A handful of scattered ordinals: an array block, or several of them. */
    private fun sparse(source: RandomSource): List<Int> =
        List(source.nextInt(0..60)) { source.nextInt(MAX_ORDINAL + 1) }

    /** Enough ordinals in one block to force a bitset. */
    private fun dense(source: RandomSource): List<Int> {
        val key = source.nextInt((MAX_ORDINAL ushr 16) + 1)
        val base = key shl 16
        return List(source.nextInt(4000..9000)) { base + source.nextInt(BitmapFormat.CONTAINER_VALUES) }
            .filter { it <= MAX_ORDINAL }
    }

    /** A few long ranges: a run block, and the encoding a selective-but-common predicate produces. */
    private fun runs(source: RandomSource): List<Int> = buildList {
        repeat(source.nextInt(1..4)) {
            val start = source.nextInt(MAX_ORDINAL + 1)
            val length = source.nextInt(1..20_000)
            for (value in start until minOf(start + length, MAX_ORDINAL + 1)) add(value)
        }
    }

    /** Ordinals clustered on either side of a block boundary. */
    private fun spanning(source: RandomSource): List<Int> = buildList {
        val boundary = BitmapFormat.CONTAINER_VALUES * source.nextInt(1..4)
        repeat(source.nextInt(1..40)) {
            add((boundary + source.nextInt(-3..3)).coerceIn(0, MAX_ORDINAL))
        }
        add(boundary - 1)
        add(boundary)
    }

    private fun operation(source: RandomSource): Operation = when (source.nextInt(8)) {
        0, 1 -> Operation.Add(source.nextInt(MAX_ORDINAL + 1))
        2 -> Operation.Remove(source.nextInt(MAX_ORDINAL + 1))
        3 -> Operation.AddRange(range(source))
        4 -> Operation.RemoveRange(range(source))
        5 -> Operation.And(ordinals.generate(source))
        6 -> Operation.Or(ordinals.generate(source))
        else -> if (source.nextBoolean()) {
            Operation.AndNot(ordinals.generate(source))
        } else {
            Operation.Xor(ordinals.generate(source))
        }
    }

    private fun range(source: RandomSource): IntRange {
        val start = source.nextInt(MAX_ORDINAL + 1)
        val length = source.nextInt(1..70_000)
        return start..minOf(start + length - 1, MAX_ORDINAL)
    }

    // --- shrinking ------------------------------------------------------------------------------

    private fun shrinkOrdinals(value: List<Int>): Sequence<List<Int>> = sequence {
        if (value.isEmpty()) return@sequence
        yield(emptyList())

        var size = value.size / 2
        while (size > 0) {
            yield(value.take(size))
            size /= 2
        }
        // Removing one at a time is what produces a genuinely minimal counterexample, and it is capped
        // because a 9000-ordinal dense set would otherwise spend the whole shrink budget here.
        for (index in value.indices.take(ELEMENT_SHRINK_LIMIT)) {
            yield(value.subList(0, index) + value.subList(index + 1, value.size))
        }
        for (index in value.indices.take(ELEMENT_SHRINK_LIMIT)) {
            val ordinal = value[index]
            if (ordinal != 0) {
                yield((value.toMutableList().also { it[index] = 0 }).distinct().sorted())
            }
        }
    }.filter { it != value }

    private fun simplify(operation: Operation): Sequence<Operation> = when (operation) {
        is Operation.Add -> shrinkOrdinal(operation.ordinal).map { Operation.Add(it) }
        is Operation.Remove -> shrinkOrdinal(operation.ordinal).map { Operation.Remove(it) }
        is Operation.AddRange -> shrinkRange(operation.range).map { Operation.AddRange(it) }
        is Operation.RemoveRange -> shrinkRange(operation.range).map { Operation.RemoveRange(it) }
        is Operation.And -> shrinkOrdinals(operation.ordinals).map { Operation.And(it) }
        is Operation.Or -> shrinkOrdinals(operation.ordinals).map { Operation.Or(it) }
        is Operation.AndNot -> shrinkOrdinals(operation.ordinals).map { Operation.AndNot(it) }
        is Operation.Xor -> shrinkOrdinals(operation.ordinals).map { Operation.Xor(it) }
    }

    private fun shrinkOrdinal(ordinal: Int): Sequence<Int> = sequence {
        if (ordinal == 0) return@sequence
        yield(0)
        var delta = ordinal / 2
        while (delta != 0) {
            val candidate = ordinal - delta
            if (candidate != ordinal && candidate != 0) yield(candidate)
            delta /= 2
        }
    }

    private fun shrinkRange(range: IntRange): Sequence<IntRange> = sequence {
        if (range.first != 0) yield(0..range.last - range.first)
        if (range.last > range.first) {
            yield(range.first..range.first)
            yield(range.first..(range.first + (range.last - range.first) / 2))
        }
    }.filter { it != range && !it.isEmpty() }

    private const val ELEMENT_SHRINK_LIMIT = 24

    /** A legible rendering: a counterexample of 9000 ordinals has to fit in a failure report. */
    fun summarise(ordinals: List<Int>): String {
        if (ordinals.isEmpty()) return "[]"
        val head = ordinals.take(12).joinToString(", ")
        val tail = if (ordinals.size > 12) ", … (${ordinals.size} ordinals, ${ordinals.last()} highest)" else ""
        return "[$head$tail]"
    }
}

/** One step of a [Script]. */
internal sealed interface Operation {
    data class Add(val ordinal: Int) : Operation

    data class Remove(val ordinal: Int) : Operation

    data class AddRange(val range: IntRange) : Operation

    data class RemoveRange(val range: IntRange) : Operation

    data class And(val ordinals: List<Int>) : Operation {
        override fun toString(): String = "And(${IndexGens.summarise(ordinals)})"
    }

    data class Or(val ordinals: List<Int>) : Operation {
        override fun toString(): String = "Or(${IndexGens.summarise(ordinals)})"
    }

    data class AndNot(val ordinals: List<Int>) : Operation {
        override fun toString(): String = "AndNot(${IndexGens.summarise(ordinals)})"
    }

    data class Xor(val ordinals: List<Int>) : Operation {
        override fun toString(): String = "Xor(${IndexGens.summarise(ordinals)})"
    }
}

/** A sequence of operations applied to a bitmap and to the model in lockstep. */
internal data class Script(val operations: List<Operation>) {
    override fun toString(): String =
        if (operations.isEmpty()) "Script()" else operations.joinToString(prefix = "Script(\n  ", separator = "\n  ", postfix = "\n)")
}
