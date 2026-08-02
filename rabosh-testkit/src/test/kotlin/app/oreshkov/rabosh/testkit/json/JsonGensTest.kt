package app.oreshkov.rabosh.testkit.json

import app.oreshkov.rabosh.testkit.property.forAll
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Validates the JSON generators and writer against `kotlinx-serialization` as an oracle.
 *
 * This has to hold before Phase 2 starts. If the generators can emit text that is not valid JSON,
 * every Variant roundtrip property built on them would fail for reasons that have nothing to do
 * with the Variant codec — and the resulting debugging would be miserable.
 */
class JsonGensTest {

    private val json = Json

    @Test
    fun `every generated value serialises to parseable JSON`() {
        forAll(JsonGens.value(maxDepth = 5, maxBreadth = 5), iterations = 500, seed = 1L) { value ->
            val text = value.toJsonString()
            // Throws on malformed input, which is exactly the failure we want surfaced.
            json.parseToJsonElement(text)
        }
    }

    @Test
    fun `every generated document serialises to a parseable JSON object`() {
        forAll(JsonGens.document(), iterations = 300, seed = 2L) { document ->
            val parsed = json.parseToJsonElement(document.toJsonString())
            assertTrue(parsed is JsonObject, "top level must be an object, got ${parsed::class.simpleName}")
        }
    }

    @Test
    fun `string values survive the round trip through JSON text`() {
        forAll(JsonGens.string, iterations = 500, seed = 3L) { value ->
            val parsed = json.parseToJsonElement(value.toJsonString())
            assertTrue(parsed is JsonPrimitive && parsed.isString)
            assertEquals(value.value, parsed.content)
        }
    }

    @Test
    fun `field names survive the round trip through JSON text`() {
        forAll(JsonGens.fieldName, iterations = 300, seed = 4L) { name ->
            val document = JsonValue.Obj(listOf(name to JsonValue.Null))
            val parsed = json.parseToJsonElement(document.toJsonString())
            assertTrue(parsed is JsonObject)
            assertEquals(setOf(name), parsed.keys)
        }
    }

    @Test
    fun `number literals are preserved exactly, not rounded through Double`() {
        forAll(JsonGens.number, iterations = 500, seed = 5L) { value ->
            val parsed = json.parseToJsonElement(value.toJsonString())
            assertTrue(parsed is JsonPrimitive)
            // Exact text comparison: a value beyond Double's precision, such as
            // 9223372036854775808, must not come back as 9.223372036854776E18.
            assertEquals(value.literal, parsed.content)
        }
    }

    @Test
    fun `arrays preserve element order and count`() {
        forAll(JsonGens.value(maxDepth = 3, maxBreadth = 6), iterations = 300, seed = 6L) { value ->
            if (value !is JsonValue.Arr) return@forAll
            val parsed = json.parseToJsonElement(value.toJsonString())
            assertTrue(parsed is JsonArray)
            assertEquals(value.elements.size, parsed.size)
        }
    }

    @Test
    fun `generated strings never contain a lone surrogate`() {
        // Documented exclusion in JsonGens: unpaired surrogates are a malformed-input concern,
        // and one leaking in here would break every downstream roundtrip property.
        forAll(JsonGens.string, iterations = 1000, seed = 7L) { value ->
            val text = value.value
            var index = 0
            while (index < text.length) {
                val character = text[index]
                when {
                    character.isHighSurrogate() -> {
                        assertTrue(
                            index + 1 < text.length && text[index + 1].isLowSurrogate(),
                            "high surrogate at $index without a following low surrogate",
                        )
                        index += 2
                    }

                    character.isLowSurrogate() -> throw AssertionError("lone low surrogate at $index")
                    else -> index++
                }
            }
        }
    }

    @Test
    fun `structural helpers agree with the generated shape`() {
        assertEquals(1, JsonValue.Null.nodeCount())
        assertEquals(1, JsonValue.Null.depth())

        val nested = JsonValue.Obj(
            listOf("a" to JsonValue.Arr(listOf(JsonValue.Null, JsonValue.Num("1")))),
        )
        // object + array + two scalars
        assertEquals(4, nested.nodeCount())
        assertEquals(3, nested.depth())
    }

    @Test
    fun `shrinking a document always reduces its node count`() {
        forAll(JsonGens.document(maxDepth = 4, maxBreadth = 4), iterations = 200, seed = 8L) { document ->
            // Well-foundedness is what guarantees the shrink loop terminates; assert it directly
            // rather than trusting the budget to hide a violation.
            for (candidate in shrinkJson(document).take(50)) {
                assertTrue(
                    candidate.nodeCount() <= document.nodeCount() && candidate != document,
                    "shrink produced ${candidate.toJsonString()} from ${document.toJsonString()}",
                )
            }
        }
    }

    @Test
    fun `duplicate keys and reverse ordered keys are among the edge cases`() {
        val edgeCases = JsonGens.document().edgeCases
        assertTrue(
            edgeCases.any { document -> document.fields.map { it.first }.let { it.size != it.toSet().size } },
            "expected a duplicate-key document among the edge cases",
        )
        assertTrue(
            edgeCases.any { document ->
                val names = document.fields.map { it.first }
                names.size > 1 && names != names.sorted()
            },
            "expected a reverse-ordered-key document among the edge cases",
        )
    }
}
