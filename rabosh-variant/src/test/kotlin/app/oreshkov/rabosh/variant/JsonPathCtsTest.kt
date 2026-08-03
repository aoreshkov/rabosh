package app.oreshkov.rabosh.variant

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * RFC 9535 §2.7, checked against Normalized Paths somebody else wrote.
 *
 * The fixtures are the JSONPath Compliance Test Suite's, vendored verbatim under
 * `src/test/resources/jsonpath/` with their upstream commit and their (different) licence recorded
 * beside them — the same discipline as the Roaring conformance fixtures, and for the same reason.
 * Read that README before changing anything here; in particular, it records the one thing these
 * files cannot cover, which is the `\u00xx` escape table.
 *
 * `kotlinx-serialization-json` reads the fixtures and [JsonOracle] compares the values, both in the
 * role they already have in this module: an oracle sharing no implementation with the code under
 * test.
 */
class JsonPathCtsTest {

    @Test
    fun `every normalized path in the suite round-trips character for character`() {
        var checked = 0
        for (case in cases()) {
            for (path in case.paths) {
                assertEquals(
                    path,
                    VariantPath.parseNormalized(path).toNormalizedPath(),
                    "$case: a normalized path did not survive being parsed and rendered",
                )
                checked++
            }
        }
        assertTrue(checked > 0, "no normalized path was checked; the fixtures did not load")
    }

    @Test
    fun `every normalized path in the suite selects the value the suite pairs with it`() {
        var checked = 0
        for (case in cases()) {
            val documentText = case.document ?: continue
            val document = Variant.fromJson(documentText)
            val values = case.values
            assertEquals(
                case.paths.size,
                values.size,
                "$case: the suite pairs each result with a path, so these must agree",
            )
            for ((index, path) in case.paths.withIndex()) {
                val selected = assertNotNull(
                    document.select(VariantPath.parseNormalized(path)),
                    "$case: $path selected nothing in $documentText",
                )
                JsonOracle.assertEquivalent(values[index], selected.toJsonString(), "$case: at $path")
                checked++
            }
        }
        assertTrue(checked > 0, "no node was checked; the fixtures did not load")
    }

    /**
     * The fixtures still hold the spellings this test exists to pin.
     *
     * Without it the two above are satisfied by a corpus that lost its interesting cases — the same
     * reason an assertion about absence needs the presence case somewhere. Each entry is a case the
     * escaping table can get wrong in a way self-consistency would never reveal: the seven named
     * escapes, and the two characters a writer built out of a JSON string escaper would over-escape.
     */
    @Test
    fun `the vendored suite still holds the spellings this pins`() {
        val distinct = cases().flatMap { it.paths }.toSet()
        for (required in REQUIRED_SPELLINGS) {
            assertTrue(required in distinct, "the vendored suite no longer holds $required")
        }
        assertTrue(
            distinct.any { path -> path.codePoints().anyMatch { it > 0xFFFF } },
            "the vendored suite no longer holds an astral code point, so surrogate pairs are uncovered",
        )
        assertTrue(
            distinct.any { path -> path.any { it.code == SURROGATE_BLOCK_BELOW } },
            "the vendored suite no longer holds U+D7FF, the lower edge of normal-unescaped's gap",
        )
        assertTrue(
            distinct.any { path -> path.any { it.code == SURROGATE_BLOCK_ABOVE } },
            "the vendored suite no longer holds U+E000, the upper edge of normal-unescaped's gap",
        )
    }

    private fun cases(): List<Case> = FIXTURES.flatMap { fixture ->
        val text = checkNotNull(javaClass.getResourceAsStream("/jsonpath/$fixture")) {
            "missing vendored fixture $fixture; see the README beside it"
        }.use { it.readBytes().decodeToString() }

        Json.parseToJsonElement(text).jsonObject.getValue("tests").jsonArray
            .map { it.jsonObject }
            // An invalid selector has no document and no nodes; §2.7 is not what it is about.
            .filter { "invalid_selector" !in it }
            .map { Case(fixture, it) }
    }

    private class Case(private val fixture: String, private val test: JsonObject) {
        val paths: List<String>
            get() = test["result_paths"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

        /** The values, as the JSON text the suite wrote them as. */
        val values: List<String>
            get() = test["result"]?.jsonArray?.map { it.toString() }.orEmpty()

        val document: String?
            get() = test["document"]?.toString()

        override fun toString(): String = "$fixture '${test.getValue("name").jsonPrimitive.content}'"
    }

    private companion object {
        val FIXTURES = listOf("name_selector.json", "index_selector.json")

        /** U+D7FF and U+E000: `normal-unescaped` is defined as the two ranges either side of these. */
        const val SURROGATE_BLOCK_BELOW = 0xD7FF
        const val SURROGATE_BLOCK_ABOVE = 0xE000

        val REQUIRED_SPELLINGS = listOf(
            // The seven of `normal-escapable` that are not `\uXXXX`.
            "$['\\b']",
            "$['\\f']",
            "$['\\n']",
            "$['\\r']",
            "$['\\t']",
            "$['\\'']",
            "$['\\\\']",
            // Raw, and the two an implementation reusing a JSON string writer would escape.
            "$['\"']",
            "$['/']",
            // The empty member name, which a length-driven writer forgets.
            "$['']",
        )
    }
}
