package app.oreshkov.rabosh.testkit.json

/**
 * An independent JSON model used to describe test data.
 *
 * This deliberately does not reuse `kotlinx.serialization`'s `JsonElement`. That library is the
 * *oracle* the Variant codec is checked against, and a generator built on the oracle's own types
 * would make the comparison partly circular. Keeping a separate model means a disagreement
 * between the two is real evidence.
 *
 * Two representation choices matter for what is being tested:
 *
 * - [Num] keeps the literal text rather than a parsed number, so values that no `Double` can hold
 *   (large integers, high-scale decimals) survive intact and exercise the encoder's
 *   int8 -> int64 -> decimal promotion.
 * - [Obj] keeps fields as an ordered list rather than a map, so field order and duplicate keys are
 *   both expressible. The Variant specification requires field ids be written in lexicographic
 *   order regardless of input order, and that is only testable if input order can vary.
 */
public sealed interface JsonValue {
    public data object Null : JsonValue

    public data class Bool(val value: Boolean) : JsonValue

    /**
     * A JSON number held as its literal text.
     *
     * [literal] must match the JSON grammar: `-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?`.
     */
    public data class Num(val literal: String) : JsonValue

    public data class Str(val value: String) : JsonValue

    public data class Arr(val elements: List<JsonValue>) : JsonValue

    public data class Obj(val fields: List<Pair<String, JsonValue>>) : JsonValue
}

/** Serialises to JSON text. Output is always valid JSON; see [escapeJsonString]. */
public fun JsonValue.toJsonString(): String = buildString { appendJson(this@toJsonString) }

private fun StringBuilder.appendJson(value: JsonValue) {
    when (value) {
        is JsonValue.Null -> append("null")
        is JsonValue.Bool -> append(if (value.value) "true" else "false")
        is JsonValue.Num -> append(value.literal)
        is JsonValue.Str -> appendEscaped(value.value)

        is JsonValue.Arr -> {
            append('[')
            value.elements.forEachIndexed { index, element ->
                if (index > 0) append(',')
                appendJson(element)
            }
            append(']')
        }

        is JsonValue.Obj -> {
            append('{')
            value.fields.forEachIndexed { index, (name, fieldValue) ->
                if (index > 0) append(',')
                appendEscaped(name)
                append(':')
                appendJson(fieldValue)
            }
            append('}')
        }
    }
}

/** Form feed, `U+000C`. Kotlin has no `\f` char escape, so the code point is named here. */
private const val FORM_FEED_CODE = 0x0C

/** Escapes [value] as a quoted JSON string. */
public fun escapeJsonString(value: String): String = buildString { appendEscaped(value) }

private fun StringBuilder.appendEscaped(value: String) {
    append('"')
    for (character in value) {
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")

            else -> when {
                character.code == FORM_FEED_CODE -> append("\\f")
                // JSON has no literal control characters; everything below U+0020 is escaped.
                character < ' ' ->
                    append("\\u").append(character.code.toString(16).padStart(4, '0'))

                else -> append(character)
            }
        }
    }
    append('"')
}

/** Number of values in the tree, counting containers themselves. Used to bound generated size. */
public fun JsonValue.nodeCount(): Int = when (this) {
    is JsonValue.Arr -> 1 + elements.sumOf { it.nodeCount() }
    is JsonValue.Obj -> 1 + fields.sumOf { (_, value) -> value.nodeCount() }
    else -> 1
}

/** Nesting depth, where a scalar is depth 1. */
public fun JsonValue.depth(): Int = when (this) {
    is JsonValue.Arr -> 1 + (elements.maxOfOrNull { it.depth() } ?: 0)
    is JsonValue.Obj -> 1 + (fields.maxOfOrNull { (_, value) -> value.depth() } ?: 0)
    else -> 1
}
