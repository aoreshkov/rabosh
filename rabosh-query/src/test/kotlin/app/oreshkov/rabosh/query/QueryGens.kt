package app.oreshkov.rabosh.query

import app.oreshkov.rabosh.testkit.json.JsonValue
import app.oreshkov.rabosh.testkit.property.Gen
import app.oreshkov.rabosh.testkit.property.RandomSource

/**
 * Generated predicates, with shrinking that actually works.
 *
 * Written as a [Gen] rather than built with `Gen.map`, because `map` cannot map a `B` back to its `A`
 * and therefore drops shrinking — and a counterexample here is a *tree*, which is exactly the shape
 * that is unreadable unminimised.
 *
 * **Every candidate [shrink] offers is strictly simpler than its input**, which is what makes the
 * shrink loop terminate by finding a fixed point rather than by exhausting its budget: a junction
 * shrinks to a single operand or to a shorter junction, a negation to its operand, and a leaf to a
 * constant. Nothing ever produces something at least as large as what it came from.
 */
internal object QueryGens {

    private val paths = listOf("$.team", "$.score", "$.note", "$.tags[*]", "$.missing")

    private val texts = listOf("team-0", "team-2", "team-9", "t1", "n5", "")

    private val numbers = listOf(0L, 1L, 5L, 17L, 30L, 59L, 1_000L)

    /** A leaf over one of the fixture's paths. */
    val leaf: Gen<Predicate> = object : Gen<Predicate> {
        override fun generate(source: RandomSource): Predicate {
            val reference = path(source.pick(paths))
            return when (source.nextInt(8)) {
                0 -> reference eq source.pick(texts)
                1 -> reference eq source.pick(numbers)
                2 -> reference lt source.pick(numbers)
                3 -> reference ge source.pick(numbers)
                4 -> reference.oneOf(source.pick(texts), source.pick(numbers))
                5 -> reference.exists()
                6 -> reference.isNull()
                else -> reference eq source.nextBoolean()
            }
        }

        override fun shrink(value: Predicate): Sequence<Predicate> = sequenceOf(Predicate.True)

        override val edgeCases: List<Predicate> = listOf(
            path("$.team") eq "team-2",
            path("$.score") ge 30L,
            path("$.note").exists(),
            path("$.missing").isNull(),
        )
    }

    /** A predicate up to [maxDepth] deep. */
    fun predicate(maxDepth: Int = 3): Gen<Predicate> = object : Gen<Predicate> {
        override fun generate(source: RandomSource): Predicate = build(source, maxDepth)

        private fun build(source: RandomSource, depth: Int): Predicate {
            if (depth <= 0 || source.chance(0.45)) return leaf.generate(source)
            return when (source.nextInt(3)) {
                0 -> Predicate.And(List(source.nextInt(2..3)) { build(source, depth - 1) })
                1 -> Predicate.Or(List(source.nextInt(2..3)) { build(source, depth - 1) })
                else -> Predicate.Not(build(source, depth - 1))
            }
        }

        override fun shrink(value: Predicate): Sequence<Predicate> = shrinkPredicate(value)

        override val edgeCases: List<Predicate> = leaf.edgeCases + listOf(
            Predicate.True,
            Predicate.False,
            and(path("$.team") eq "team-2", path("$.score") ge 10L),
            or(path("$.team") eq "team-1", not(path("$.note").exists())),
            not(and(path("$.team") eq "team-2", path("$.score") lt 5L)),
        )

        override fun render(value: Predicate): String = value.toString()
    }

    /** Strictly simpler candidates: fewer operands, a shallower tree, or a constant. */
    private fun shrinkPredicate(value: Predicate): Sequence<Predicate> = when (value) {
        is Predicate.And -> sequence {
            yieldAll(value.operands)
            if (value.operands.size > 2) {
                for (index in value.operands.indices) {
                    yield(Predicate.And(value.operands.filterIndexed { at, _ -> at != index }))
                }
            }
        }

        is Predicate.Or -> sequence {
            yieldAll(value.operands)
            if (value.operands.size > 2) {
                for (index in value.operands.indices) {
                    yield(Predicate.Or(value.operands.filterIndexed { at, _ -> at != index }))
                }
            }
        }

        is Predicate.Not -> sequenceOf(value.operand)
        is Predicate.AnyOf ->
            if (value.values.size > 1) {
                sequenceOf(Predicate.AnyOf(value.path, value.values.dropLast(1)))
            } else {
                sequenceOf(Predicate.True)
            }

        Predicate.True, Predicate.False -> emptySequence()
        else -> sequenceOf(Predicate.True)
    }
}

/** A document the generated predicates have something to say about. */
internal fun scriptDocument(index: Int) = jsonDocument(
    buildString {
        append("""{"team":"team-${index % 5}","score":${index % 60},""")
        append(""""tags":["t${index % 4}","t${index % 3}"]""")
        if (index % 3 != 1) append(""","note":${if (index % 6 == 2) "null" else "\"n$index\""}""")
        append("}")
    },
)

/**
 * The same document as the testkit's JSON model, for the reference oracle.
 *
 * Written out twice on purpose: the oracle walks this, the engine walks the encoded form, and the
 * two agreeing is evidence rather than a tautology.
 */
internal fun scriptJson(index: Int): JsonValue = JsonValue.Obj(
    buildList {
        add("team" to JsonValue.Str("team-${index % 5}"))
        add("score" to JsonValue.Num("${index % 60}"))
        add("tags" to JsonValue.Arr(listOf(JsonValue.Str("t${index % 4}"), JsonValue.Str("t${index % 3}"))))
        if (index % 3 != 1) {
            add("note" to if (index % 6 == 2) JsonValue.Null else JsonValue.Str("n$index"))
        }
    },
)

