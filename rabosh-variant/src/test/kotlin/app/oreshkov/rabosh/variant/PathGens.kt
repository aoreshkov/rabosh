package app.oreshkov.rabosh.variant

import app.oreshkov.rabosh.testkit.json.JsonGens
import app.oreshkov.rabosh.testkit.property.Gen
import app.oreshkov.rabosh.testkit.property.RandomSource

/**
 * Generators for [VariantPath].
 *
 * Implemented directly rather than through `Gen.map`, which drops shrinking: a normalized-path
 * failure is nearly always about one character of one field name, and a counterexample that is a
 * six-step path over three-hundred-character names says nothing about which.
 *
 * One deliberate exclusion, and it is the same one [JsonGens] makes for the same reason: **no
 * unpaired surrogates**. A name holding one cannot be written as a Normalized Path at all, so
 * including it here would make the round-trip property fail for a reason that is the *documented*
 * behaviour. It is a targeted negative test in `NormalizedPathTest` instead.
 */
internal object PathGens {

    /** Field names, drawn from the testkit's awkward set plus the ones §2.7 has to escape. */
    val fieldName: Gen<String> = object : Gen<String> {
        override fun generate(source: RandomSource): String =
            if (source.nextInt(ESCAPE_WORTHY_ODDS) == 0) {
                source.pick(ESCAPE_WORTHY_NAMES)
            } else {
                JsonGens.fieldName.generate(source)
            }

        override fun shrink(value: String): Sequence<String> = sequence {
            if (value.isEmpty()) return@sequence
            yield("")
            if (value.length > 1) yield("a")
        }.filter { it != value }

        override val edgeCases: List<String> get() = ESCAPE_WORTHY_NAMES + JsonGens.fieldName.edgeCases
    }

    /** Locations of up to [maxSteps] steps, mixing field and index steps. */
    fun path(maxSteps: Int = DEFAULT_MAX_STEPS): Gen<VariantPath> = object : Gen<VariantPath> {
        override fun generate(source: RandomSource): VariantPath = VariantPath(
            List(source.nextInt(0..maxSteps)) {
                if (source.nextBoolean()) {
                    VariantPathStep.Field(fieldName.generate(source))
                } else {
                    VariantPathStep.Index(source.nextInt(0..MAX_GENERATED_INDEX))
                }
            },
        )

        /**
         * Fewer steps, then a simpler step. Well-founded on `(step count, total name length, index
         * sum)`, every candidate strictly below its input on one of the three and no higher on any.
         */
        override fun shrink(value: VariantPath): Sequence<VariantPath> = sequence {
            if (value.steps.isEmpty()) return@sequence
            yield(VariantPath.ROOT)
            for (index in value.steps.indices) {
                yield(VariantPath(value.steps.filterIndexed { position, _ -> position != index }))
            }
            for (index in value.steps.indices) {
                for (simpler in simplify(value.steps[index])) {
                    yield(VariantPath(value.steps.toMutableList().also { it[index] = simpler }))
                }
            }
        }.filter { it != value }

        override val edgeCases: List<VariantPath> get() = PATH_EDGE_CASES
        override fun render(value: VariantPath): String = value.toString()
    }

    private fun simplify(step: VariantPathStep): Sequence<VariantPathStep> = when (step) {
        is VariantPathStep.Field -> fieldName.shrink(step.name).map { VariantPathStep.Field(it) }
        is VariantPathStep.Index ->
            if (step.index == 0) emptySequence() else sequenceOf(VariantPathStep.Index(0))
    }

    private const val DEFAULT_MAX_STEPS = 5
    private const val MAX_GENERATED_INDEX = 64

    /** One name in this many comes from the set §2.7 has to escape rather than from [JsonGens]. */
    private const val ESCAPE_WORTHY_ODDS = 4

    /** Every C0 control in one name, so a single case exercises the whole escape table. */
    private val ALL_C0_CONTROLS: String = (0..0x1F).map { it.toChar() }.joinToString("")

    private val ESCAPE_WORTHY_NAMES: List<String> = listOf(
        "'",
        "\\",
        "it's",
        "a\\b",
        "'\\'",
        // Written raw by §2.7, and the pair a JSON-derived writer would over-escape.
        "\"",
        "/",
        "\"/\\'",
        ALL_C0_CONTROLS,
        // Astral, as a correctly formed surrogate pair (U+1D11E), and the edges of the gap
        // `normal-unescaped` is defined around.
        String(Character.toChars(0x1D11E)),
        0xD7FF.toChar().toString() + 0xE000.toChar(),
    )

    private val PATH_EDGE_CASES: List<VariantPath> = listOf(
        VariantPath.ROOT,
        VariantPath(listOf(VariantPathStep.Field(""))),
        VariantPath(listOf(VariantPathStep.Index(0))),
        VariantPath(listOf(VariantPathStep.Index(Int.MAX_VALUE))),
        VariantPath(listOf(VariantPathStep.Field(ALL_C0_CONTROLS))),
        VariantPath(listOf(VariantPathStep.Field("a"), VariantPathStep.Index(0), VariantPathStep.Field("b"))),
    ) + ESCAPE_WORTHY_NAMES.map { VariantPath(listOf(VariantPathStep.Field(it))) }
}
