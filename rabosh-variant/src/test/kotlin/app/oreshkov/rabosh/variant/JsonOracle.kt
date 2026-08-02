package app.oreshkov.rabosh.variant

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import kotlin.test.fail

/**
 * The reference the hand-written codec is checked against.
 *
 * `kotlinx-serialization-json` is used only here, and only as an oracle: it parses both the input
 * text and the codec's output, and the two trees are compared. Nothing in the codec or in the
 * generators shares an implementation with it, so a disagreement is evidence rather than a shared
 * bug — which is exactly why the test-data model in the testkit is not `JsonElement` either.
 */
internal object JsonOracle {
    private val json = Json

    fun parse(text: String): JsonElement = json.parseToJsonElement(text)

    /** Fails unless [actual] carries the same JSON data as [expected]. */
    fun assertEquivalent(expected: String, actual: String, hint: String = "") {
        val expectedTree = parse(expected)
        val actualTree = try {
            parse(actual)
        } catch (failure: Exception) {
            fail("codec produced text the oracle cannot parse: $actual\n$failure")
        }
        val difference = difference(expectedTree, actualTree, "$")
        if (difference != null) {
            fail(
                buildString {
                    if (hint.isNotEmpty()) appendLine(hint)
                    appendLine("JSON differs at $difference")
                    appendLine("expected: $expected")
                    append("actual:   $actual")
                },
            )
        }
    }

    /** The path of the first difference, or `null` when the two trees carry the same data. */
    private fun difference(expected: JsonElement, actual: JsonElement, path: String): String? = when {
        expected is JsonNull || actual is JsonNull ->
            if (expected is JsonNull && actual is JsonNull) null else path

        expected is JsonPrimitive && actual is JsonPrimitive -> when {
            expected.isString != actual.isString -> path
            expected.isString -> if (expected.content == actual.content) null else path
            else -> if (sameScalar(expected.content, actual.content)) null else path
        }

        expected is JsonArray && actual is JsonArray ->
            if (expected.size != actual.size) {
                path
            } else {
                expected.indices.firstNotNullOfOrNull { difference(expected[it], actual[it], "$path[$it]") }
            }

        expected is JsonObject && actual is JsonObject ->
            if (expected.keys != actual.keys) {
                path
            } else {
                expected.keys.firstNotNullOfOrNull {
                    difference(expected.getValue(it), actual.getValue(it), "$path.$it")
                }
            }

        else -> path
    }

    /**
     * Compares two unquoted JSON scalars — a keyword, or a number written in whatever form each
     * side chose.
     *
     * Numbers are compared by value, not by text: `1.500` and `1.5` are the same number, and the
     * encoder normalises. The `Double` fallback covers the codec's one documented lossy path — a
     * literal that fits neither `Long` nor 38 digits of decimal is stored as a `double`, and comes
     * back as the shortest text for that double rather than as the digits it went in with.
     */
    private fun sameScalar(expected: String, actual: String): Boolean {
        if (expected == actual) return true
        if (expected == "true" || expected == "false" || actual == "true" || actual == "false") return false
        val exact = runCatching { BigDecimal(expected).compareTo(BigDecimal(actual)) == 0 }.getOrDefault(false)
        return exact || expected.toDouble() == actual.toDouble()
    }
}
