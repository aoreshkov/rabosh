package app.oreshkov.rabosh.variant

import app.oreshkov.rabosh.testkit.json.JsonGens
import app.oreshkov.rabosh.testkit.json.JsonValue
import app.oreshkov.rabosh.testkit.json.toJsonString
import app.oreshkov.rabosh.testkit.property.forAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The phase-2 acceptance property: anything that goes in as JSON comes back as the same JSON.
 *
 * The comparison is against `kotlinx-serialization` rather than against string equality, because
 * the codec normalises deliberately — `1.500` and `1.5` are one number, and a duplicate key
 * resolves to one field. See [JsonOracle] for exactly what "the same" is taken to mean.
 *
 * The structural invariants are checked on the same generated values, since an encoder can produce
 * bytes that decode correctly *in this implementation* while violating the ordering the format
 * promises everyone else.
 */
class JsonRoundtripTest {

    @Test
    fun `documents round-trip through the codec`() {
        forAll(JsonGens.document()) { document ->
            val text = document.toJsonString()
            val variant = Variant.fromJson(text)
            JsonOracle.assertEquivalent(text, variant.toJsonString())
        }
    }

    @Test
    fun `any JSON value round-trips, not only documents`() {
        forAll(JsonGens.value(maxDepth = 5, maxBreadth = 5)) { value ->
            val text = value.toJsonString()
            JsonOracle.assertEquivalent(text, Variant.fromJson(text).toJsonString())
        }
    }

    @Test
    fun `encoded values obey the format's structural rules`() {
        forAll(JsonGens.document()) { document ->
            val variant = Variant.fromJson(document.toJsonString())
            checkStructure(variant)
        }
    }

    @Test
    fun `the encoded length matches what the reader walks`() {
        forAll(JsonGens.document()) { document ->
            val builder = VariantBuilder()
            JsonParser().parseInto(builder, document.toJsonString())
            val bytes = builder.build()
            // The writer's length and the reader's independent walk of the same bytes must agree,
            // or a value copied out of a segment would be truncated or would swallow its neighbour.
            assertEquals(bytes.size.toLong(), Variant(builder.dictionary.build(), bytes).byteSize)
        }
    }

    @Test
    fun `parsing bytes and parsing text give identical encodings`() {
        forAll(JsonGens.document()) { document ->
            val text = document.toJsonString()
            assertEquals(
                Variant.fromJson(text).toByteArray().toHex(),
                Variant.fromJson(text.toByteArray(Charsets.UTF_8)).toByteArray().toHex(),
            )
        }
    }

    /**
     * The ingest shape: one dictionary for a whole segment, one builder reset per document.
     *
     * Ids handed out for an earlier document must stay valid against the larger dictionary the
     * segment ends up with — that is what makes a shared dictionary possible at all.
     */
    @Test
    fun `many documents share one dictionary`() {
        forAll(JsonGens.document(), JsonGens.document(), iterations = 40) { first, second ->
            // Interleaved so the dictionary grows between writes: the second document's names are
            // interned after the first document's values have already been encoded.
            val documents = listOf(first, second, first, second)
            val dictionary = VariantDictionaryBuilder()
            val builder = VariantBuilder(dictionary)
            val parser = JsonParser()

            val encoded = documents.map { document ->
                builder.reset()
                parser.parseInto(builder, document.toJsonString())
                builder.build()
            }

            // Every value is decoded against the *final* dictionary, not the one that existed when
            // it was written.
            val metadata = dictionary.build()
            encoded.forEachIndexed { index, bytes ->
                JsonOracle.assertEquivalent(
                    documents[index].toJsonString(),
                    Variant(metadata, bytes).toJsonString(),
                    hint = "document $index of a shared-dictionary segment",
                )
            }
        }
    }

    @Test
    fun `duplicate field names resolve the way JSON readers resolve them`() {
        val variant = Variant.fromJson("""{"a":1,"b":2,"a":3,"a":4}""")
        assertEquals(2, variant.fieldCount)
        assertEquals(4, variant.field("a")?.longValue())
        assertEquals(2, variant.field("b")?.longValue())
        JsonOracle.assertEquivalent("""{"a":1,"b":2,"a":3,"a":4}""", variant.toJsonString())

        // The strict policy is available for callers who would rather hear about it.
        val strict = VariantBuilder(duplicateFields = DuplicateFieldPolicy.REJECT)
        val failure = runCatching {
            JsonParser().parseInto(strict, """{"a":1,"a":2}""")
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException, "expected a rejection, got $failure")
        assertTrue("duplicate field 'a'" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    /**
     * The three things that break an object encoder at once: keys arriving in reverse order, a
     * duplicate among them, and an empty name — which is a legal field name and sorts before
     * everything.
     */
    @Test
    fun `reverse-sorted keys, a duplicate and an empty name`() {
        val document = JsonValue.Obj(
            listOf(
                "z" to JsonValue.Num("1"),
                "a" to JsonValue.Arr(listOf(JsonValue.Null)),
                "z" to JsonValue.Obj(listOf("" to JsonValue.Str("é"))),
            ),
        )
        val text = document.toJsonString()
        val variant = Variant.fromJson(text)
        JsonOracle.assertEquivalent(text, variant.toJsonString())
        checkStructure(variant)
    }

    /** Every rule the format states about an encoded value, checked over a whole tree. */
    private fun checkStructure(variant: Variant) {
        when (variant.kind) {
            VariantKind.OBJECT -> {
                val names = (0 until variant.fieldCount).map(variant::fieldName)
                for (index in 1 until names.size) {
                    val previous = names[index - 1].toUtf8("name")
                    val current = names[index].toUtf8("name")
                    val order = compareUtf8(previous, current)
                    if (order >= 0) {
                        fail(
                            "field ids must be in ascending UTF-8 order and unique, but " +
                                "'${names[index - 1]}' is not before '${names[index]}'",
                        )
                    }
                }
                // The binary search must find everything the sequential walk sees.
                names.forEachIndexed { index, name ->
                    assertEquals(
                        variant.fieldValue(index).toJsonString(),
                        variant.field(name)?.toJsonString(),
                        "field '$name' was not found by name",
                    )
                }
                (0 until variant.fieldCount).forEach { checkStructure(variant.fieldValue(it)) }
            }

            VariantKind.ARRAY -> (0 until variant.elementCount).forEach { checkStructure(variant.element(it)) }
            else -> Unit
        }
    }
}
