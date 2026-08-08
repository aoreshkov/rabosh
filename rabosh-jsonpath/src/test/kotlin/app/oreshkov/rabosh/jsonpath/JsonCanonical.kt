package app.oreshkov.rabosh.jsonpath

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal

/**
 * One JSON value, written the same way whoever wrote it.
 *
 * The comparison this module's tests need is *by value*: the suite writes a document's number as
 * `1.0` and the encoder stores the number one, so a text comparison would fail on a difference that
 * is not one. `kotlinx-serialization-json` parses both sides — the oracle role it has everywhere
 * else in this repository, sharing no implementation with the code under test — and this reduces the
 * tree to a string, so the same function serves an ordered comparison and a multiset one.
 *
 * Member order is normalised because a JSON object is an unordered collection; array order is not,
 * because a nodelist's order is exactly what the suite pins.
 */
internal object JsonCanonical {

    /** The canonical form of the JSON text [text]. */
    fun of(text: String): String = render(Json.parseToJsonElement(text))

    private fun render(element: JsonElement): String = when (element) {
        is JsonNull -> "null"
        is JsonPrimitive -> renderPrimitive(element)
        is JsonArray -> element.joinToString(",", "[", "]", transform = ::render)
        is JsonObject -> element.entries
            .sortedBy { it.key }
            .joinToString(",", "{", "}") { (key, value) -> "${quote(key)}:${render(value)}" }
    }

    private fun renderPrimitive(primitive: JsonPrimitive): String = when {
        primitive.isString -> quote(primitive.content)
        primitive.content == "true" || primitive.content == "false" -> primitive.content
        // `stripTrailingZeros` is what makes `1.0`, `1` and `1e0` one value. A literal `BigDecimal`
        // cannot hold — an exponent beyond an `Int` — is left as it was written, which is still
        // comparable because both sides reach here through the same function.
        else -> runCatching { BigDecimal(primitive.content).stripTrailingZeros().toString() }
            .getOrDefault(primitive.content)
    }

    private fun quote(text: String): String = buildString {
        append('"')
        for (character in text) {
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }
}
