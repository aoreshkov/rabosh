package app.oreshkov.rabosh.testkit.json

import app.oreshkov.rabosh.testkit.property.Gen
import app.oreshkov.rabosh.testkit.property.RandomSource

/**
 * Generators for [JsonValue].
 *
 * Two deliberate exclusions, both *malformed-input* concerns rather than roundtrip concerns.
 * They belong in targeted negative tests, not here:
 *
 * - **Lone surrogates.** Generated strings always pair surrogates correctly. An unpaired
 *   surrogate cannot be encoded as UTF-8, so including it here would make every roundtrip
 *   property fail for a reason unrelated to the code under test.
 * - **Malformed number literals.** [JsonValue.Num] holds text, but these generators only emit
 *   literals satisfying the JSON grammar.
 */
public object JsonGens {

    /** Field names: ordinary identifiers, plus the ones that break naive encoders. */
    public val fieldName: Gen<String> = object : Gen<String> {
        override fun generate(source: RandomSource): String =
            when (source.frequency(listOf(8 to ORDINARY, 2 to CAPITALISED, 1 to AWKWARD))) {
                ORDINARY -> source.pick(ORDINARY_NAMES)
                // Case-only differences matter: field ids are ordered lexicographically and
                // 'Z' (0x5A) sorts before 'a' (0x61) in UTF-8 byte order.
                CAPITALISED -> source.pick(ORDINARY_NAMES).replaceFirstChar { it.uppercase() }
                else -> source.pick(AWKWARD_NAMES)
            }

        override fun shrink(value: String): Sequence<String> =
            if (value == "a") emptySequence() else sequenceOf("a")

        override val edgeCases: List<String> get() = AWKWARD_NAMES
    }

    /** Booleans and null. */
    public val scalarKeyword: Gen<JsonValue> = object : Gen<JsonValue> {
        private val keywords =
            listOf<JsonValue>(JsonValue.Null, JsonValue.Bool(true), JsonValue.Bool(false))

        override fun generate(source: RandomSource): JsonValue = source.pick(keywords)
        override val edgeCases: List<JsonValue> get() = keywords
        override fun render(value: JsonValue): String = value.toJsonString()
    }

    /**
     * Number literals spread across the boundaries the Variant encoder must switch on:
     * int8/int16/int32/int64 widths, values beyond `Long`, decimals, and floats.
     */
    public val number: Gen<JsonValue.Num> = object : Gen<JsonValue.Num> {
        override fun generate(source: RandomSource): JsonValue.Num {
            val kind = source.frequency(
                listOf(4 to INT8, 3 to INT16, 3 to INT32, 3 to INT64, 2 to BIG, 3 to DECIMAL, 2 to FLOAT),
            )
            val literal = when (kind) {
                INT8 -> source.nextLong(-128L..127L).toString()
                INT16 -> source.nextLong(-32_768L..32_767L).toString()
                INT32 -> source.nextLong(Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()).toString()
                INT64 -> source.nextLong().toString()
                BIG -> bigInteger(source)
                DECIMAL -> decimal(source)
                else -> float(source)
            }
            return JsonValue.Num(literal)
        }

        /** Beyond `Long`: forces promotion to a decimal representation. */
        private fun bigInteger(source: RandomSource): String = buildString {
            if (source.nextBoolean()) append('-')
            append(source.nextInt(1..9))
            repeat(source.nextInt(19..30)) { append(source.nextInt(10)) }
        }

        private fun decimal(source: RandomSource): String {
            val whole = source.nextLong(-1_000_000L..1_000_000L)
            val fraction = buildString { repeat(source.nextInt(1..18)) { append(source.nextInt(10)) } }
            return "$whole.$fraction"
        }

        private fun float(source: RandomSource): String =
            "${source.nextInt(1..9999)}.0e${source.nextInt(-30..30)}"

        override fun shrink(value: JsonValue.Num): Sequence<JsonValue.Num> =
            if (value.literal == "0") emptySequence() else sequenceOf(JsonValue.Num("0"))

        override val edgeCases: List<JsonValue.Num> get() = BOUNDARY_NUMBERS
        override fun render(value: JsonValue.Num): String = value.toJsonString()
    }

    /** Strings including escapes, control characters, and non-BMP code points as valid pairs. */
    public val string: Gen<JsonValue.Str> = object : Gen<JsonValue.Str> {
        override fun generate(source: RandomSource): JsonValue.Str {
            val text = buildString {
                repeat(source.nextInt(0..16)) {
                    when (source.frequency(listOf(10 to PLAIN, 2 to ESCAPED, 2 to NON_ASCII, 1 to ASTRAL))) {
                        PLAIN -> append('a' + source.nextInt(26))
                        ESCAPED -> append(source.pick(ESCAPE_WORTHY))
                        NON_ASCII -> append(source.pick(NON_ASCII_BMP))
                        // Astral plane, appended as a correctly formed surrogate pair.
                        else -> appendCodePoint(source.nextInt(0x10000..0x10FFFF))
                    }
                }
            }
            return JsonValue.Str(text)
        }

        override fun shrink(value: JsonValue.Str): Sequence<JsonValue.Str> = sequence {
            if (value.value.isEmpty()) return@sequence
            yield(JsonValue.Str(""))
            var length = value.value.length / 2
            while (length > 0) {
                yield(JsonValue.Str(value.value.safeTake(length)))
                length /= 2
            }
        }.filter { it != value }

        override val edgeCases: List<JsonValue.Str> get() = AWKWARD_STRINGS
        override fun render(value: JsonValue.Str): String = value.toJsonString()
    }

    /** Any scalar: keyword, number, or string. */
    public val scalar: Gen<JsonValue> = object : Gen<JsonValue> {
        override fun generate(source: RandomSource): JsonValue =
            when (source.frequency(listOf(2 to KEYWORD, 4 to NUMBER, 4 to STRING))) {
                KEYWORD -> scalarKeyword.generate(source)
                NUMBER -> number.generate(source)
                else -> string.generate(source)
            }

        override fun shrink(value: JsonValue): Sequence<JsonValue> = shrinkJson(value)

        override val edgeCases: List<JsonValue>
            get() = scalarKeyword.edgeCases + number.edgeCases + string.edgeCases

        override fun render(value: JsonValue): String = value.toJsonString()
    }

    /**
     * Arbitrary JSON, bounded by [maxDepth] and [maxBreadth].
     *
     * Depth is decremented on recursion so generation always terminates. Unbounded, a recursive
     * generator will eventually build a tree that overflows the stack before it ever reaches the
     * code under test.
     */
    public fun value(maxDepth: Int = 4, maxBreadth: Int = 6): Gen<JsonValue> =
        JsonValueGen(maxDepth, maxBreadth)

    /** JSON documents: an object at the top level, as a stored document always is. */
    public fun document(maxDepth: Int = 4, maxBreadth: Int = 6): Gen<JsonValue.Obj> =
        JsonDocumentGen(maxDepth, maxBreadth)

    private class JsonValueGen(private val maxDepth: Int, private val maxBreadth: Int) :
        Gen<JsonValue> {

        override fun generate(source: RandomSource): JsonValue = generate(source, maxDepth)

        private fun generate(source: RandomSource, depth: Int): JsonValue {
            if (depth <= 1) return scalar.generate(source)
            return when (source.frequency(listOf(6 to SCALAR, 2 to ARRAY, 3 to OBJECT))) {
                SCALAR -> scalar.generate(source)
                ARRAY -> JsonValue.Arr(
                    List(source.nextInt(0..maxBreadth)) { generate(source, depth - 1) },
                )

                else -> JsonValue.Obj(
                    List(source.nextInt(0..maxBreadth)) {
                        fieldName.generate(source) to generate(source, depth - 1)
                    },
                )
            }
        }

        override fun shrink(value: JsonValue): Sequence<JsonValue> = shrinkJson(value)
        override val edgeCases: List<JsonValue> get() = STRUCTURAL_EDGE_CASES
        override fun render(value: JsonValue): String = value.toJsonString()
    }

    private class JsonDocumentGen(maxDepth: Int, private val maxBreadth: Int) : Gen<JsonValue.Obj> {
        private val inner = JsonValueGen(maxDepth - 1, maxBreadth)

        override fun generate(source: RandomSource): JsonValue.Obj = JsonValue.Obj(
            List(source.nextInt(0..maxBreadth)) {
                fieldName.generate(source) to inner.generate(source)
            },
        )

        override fun shrink(value: JsonValue.Obj): Sequence<JsonValue.Obj> =
            shrinkJson(value).filterIsInstance<JsonValue.Obj>()

        override val edgeCases: List<JsonValue.Obj>
            get() = STRUCTURAL_EDGE_CASES.filterIsInstance<JsonValue.Obj>()

        override fun render(value: JsonValue.Obj): String = value.toJsonString()
    }
}

/**
 * Structural shrinking: collapse to a scalar first, then remove children, then simplify one child.
 *
 * Every candidate has a strictly smaller [nodeCount] than its input, which makes shrinking
 * well-founded and guarantees the shrink loop terminates.
 */
internal fun shrinkJson(value: JsonValue): Sequence<JsonValue> = sequence {
    when (value) {
        is JsonValue.Null -> return@sequence
        is JsonValue.Bool -> if (value.value) yield(JsonValue.Bool(false))
        is JsonValue.Num -> if (value.literal != "0") yield(JsonValue.Num("0"))
        is JsonValue.Str -> if (value.value.isNotEmpty()) yield(JsonValue.Str(""))

        is JsonValue.Arr -> {
            yield(JsonValue.Null)
            if (value.elements.isNotEmpty()) {
                yield(JsonValue.Arr(emptyList()))
                // Lift a child up: often one element alone reproduces the failure.
                yieldAll(value.elements)
                for (index in value.elements.indices) {
                    yield(JsonValue.Arr(value.elements.withoutIndex(index)))
                }
                for (index in value.elements.indices) {
                    for (shrunk in shrinkJson(value.elements[index]).take(SHRINK_FANOUT)) {
                        yield(JsonValue.Arr(value.elements.replacingIndex(index, shrunk)))
                    }
                }
            }
        }

        is JsonValue.Obj -> {
            yield(JsonValue.Null)
            if (value.fields.isNotEmpty()) {
                yield(JsonValue.Obj(emptyList()))
                for (index in value.fields.indices) {
                    yield(JsonValue.Obj(value.fields.withoutIndex(index)))
                }
                for (index in value.fields.indices) {
                    val (name, fieldValue) = value.fields[index]
                    for (shrunk in shrinkJson(fieldValue).take(SHRINK_FANOUT)) {
                        yield(JsonValue.Obj(value.fields.replacingIndex(index, name to shrunk)))
                    }
                }
            }
        }
    }
}.filter { it != value && it.nodeCount() <= value.nodeCount() }

/**
 * How many shrink candidates are taken from each child.
 *
 * This bounds the branching factor, not the depth: a nested value is only reachable through its
 * parent's candidate list, so too small a value leaves deep structures unminimised. Set to 4 it
 * left a 131-character counterexample against a 40-character property; the overall shrink budget
 * ([app.oreshkov.rabosh.testkit.property.DEFAULT_MAX_SHRINKS]) is the real safety net.
 */
private const val SHRINK_FANOUT = 24

// Tags for weighted choice. Plain constants keep `frequency` monomorphic and the `when`
// branches readable, which lambdas of differing return types would not.
private const val ORDINARY = 0
private const val CAPITALISED = 1
private const val AWKWARD = 2

private const val INT8 = 0
private const val INT16 = 1
private const val INT32 = 2
private const val INT64 = 3
private const val BIG = 4
private const val DECIMAL = 5
private const val FLOAT = 6

private const val PLAIN = 0
private const val ESCAPED = 1
private const val NON_ASCII = 2
private const val ASTRAL = 3

private const val KEYWORD = 0
private const val NUMBER = 1
private const val STRING = 2

private const val SCALAR = 0
private const val ARRAY = 1
private const val OBJECT = 2

private fun <T> List<T>.withoutIndex(index: Int): List<T> =
    subList(0, index) + subList(index + 1, size)

private fun <T> List<T>.replacingIndex(index: Int, value: T): List<T> =
    toMutableList().also { it[index] = value }

/** Truncates without splitting a surrogate pair, so the result is always well-formed. */
private fun String.safeTake(count: Int): String {
    if (count >= length) return this
    val end = if (count > 0 && this[count - 1].isHighSurrogate()) count - 1 else count
    return substring(0, end)
}

private fun StringBuilder.appendCodePoint(codePoint: Int) {
    if (codePoint <= 0xFFFF) {
        append(codePoint.toChar())
    } else {
        val offset = codePoint - 0x10000
        append((0xD800 + (offset shr 10)).toChar())
        append((0xDC00 + (offset and 0x3FF)).toChar())
    }
}

/** Form feed, `U+000C`. Kotlin has no `\f` char escape, so it is named by code point. */
private val FORM_FEED: Char = 0x0C.toChar()

private val ORDINARY_NAMES = listOf(
    "id", "name", "type", "value", "userId", "created_at", "updatedAt",
    "count", "items", "data", "meta", "tags", "url", "status", "payload",
)

private val AWKWARD_NAMES = listOf(
    "",
    "a",
    "A",
    "z",
    "Z",
    // Case-only pairs catch a comparator that is not byte-ordered.
    "field",
    "Field",
    "with space",
    "with\"quote",
    "with\\backslash",
    "with\nnewline",
    "unicode-üïø",
    "日本語",
    "a".repeat(300),
)

private val AWKWARD_STRINGS = listOf(
    JsonValue.Str(""),
    JsonValue.Str("a"),
    JsonValue.Str("\""),
    JsonValue.Str("\\"),
    JsonValue.Str("\n\r\t"),
    JsonValue.Str(FORM_FEED.toString()),
    JsonValue.Str(" "),
    JsonValue.Str(" "),
    JsonValue.Str("unicode-üïø"),
    JsonValue.Str("日本語"),
    // Non-BMP, as a valid surrogate pair (U+1F600).
    JsonValue.Str("😀"),
    // Either side of the 63-byte short-string boundary in the Variant encoding.
    JsonValue.Str("x".repeat(63)),
    JsonValue.Str("x".repeat(64)),
)

private val BOUNDARY_NUMBERS = listOf(
    "0", "-0", "1", "-1",
    "127", "-128", // int8
    "128", "-129",
    "32767", "-32768", // int16
    "32768", "-32769",
    "2147483647", "-2147483648", // int32
    "2147483648", "-2147483649",
    "9223372036854775807", "-9223372036854775808", // int64
    // Beyond int64: must promote to a decimal representation.
    "9223372036854775808", "-9223372036854775809",
    "0.1", "-0.1", "1.5", "0.000000000000000001",
    "1.0e10", "1.0e-10", "1.0e308",
).map { JsonValue.Num(it) }

private val STRUCTURAL_EDGE_CASES: List<JsonValue> = listOf(
    JsonValue.Null,
    JsonValue.Obj(emptyList()),
    JsonValue.Arr(emptyList()),
    JsonValue.Obj(listOf("a" to JsonValue.Null)),
    JsonValue.Arr(listOf(JsonValue.Null)),
    // Duplicate keys: legal JSON, and something the encoder must make a decision about.
    JsonValue.Obj(listOf("a" to JsonValue.Num("1"), "a" to JsonValue.Num("2"))),
    // Reverse-sorted keys: field ids must still be written in lexicographic order.
    JsonValue.Obj(
        listOf(
            "z" to JsonValue.Num("1"),
            "m" to JsonValue.Num("2"),
            "a" to JsonValue.Num("3"),
        ),
    ),
    // Nesting, both kinds.
    JsonValue.Obj(
        listOf("a" to JsonValue.Obj(listOf("b" to JsonValue.Arr(listOf(JsonValue.Null))))),
    ),
    JsonValue.Arr(listOf(JsonValue.Arr(listOf(JsonValue.Arr(emptyList()))))),
)

private val ESCAPE_WORTHY = listOf('"', '\\', '\n', '\r', '\t', ' ', FORM_FEED)

private val NON_ASCII_BMP =
    listOf('ü', 'é', 'ß', 'ñ', '日', '本', 'Ω', '€')
