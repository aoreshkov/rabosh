package app.oreshkov.rabosh.variant

import app.oreshkov.rabosh.testkit.json.JsonGens
import app.oreshkov.rabosh.testkit.json.toJsonString
import app.oreshkov.rabosh.testkit.property.forAll
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.fail
import kotlin.uuid.Uuid

/**
 * [VariantBuilder.append] — the operation that moves a stored document into a segment.
 *
 * A segment holds one dictionary for every document in it, so a flush cannot copy value bytes
 * across unchanged: the field ids in them mean something else against the target dictionary. This
 * is the test that the translation is faithful in both directions it can fail — the *meaning* of
 * the document, and the *physical encoding* of its scalars, which a re-derivation would silently
 * rewrite.
 */
class VariantAppendTest {

    /**
     * The property flush depends on: same document, different dictionary, same meaning.
     *
     * The target dictionary is pre-seeded so its ids are already taken. That is not decoration — a
     * copy that forwarded field ids unchanged passes every test where the two dictionaries happen
     * to agree, and this one is built so they cannot.
     */
    @Test
    fun `a document keeps its meaning when re-expressed against another dictionary`() {
        forAll(JsonGens.document()) { document ->
            val text = document.toJsonString()
            val source = Variant.fromJson(text)

            val target = VariantBuilder(seededDictionary())
            target.append(source)
            val copy = target.buildVariant()

            JsonOracle.assertEquivalent(text, copy.toJsonString())
            assertFieldIdsOrdered(copy)
        }
    }

    /**
     * The ids really do move.
     *
     * Without this the property above would still pass on a copy that never translated anything,
     * as long as the seeding happened to be a no-op. Any document with a field is enough: the
     * seeded names occupy ids 0..n before the document's first name is interned.
     */
    @Test
    fun `field ids are translated, not forwarded`() {
        val source = Variant.fromJson("""{"alpha":1,"beta":2}""")
        val target = VariantBuilder(seededDictionary())
        target.append(source)
        val copy = target.buildVariant()

        assertEquals(source.fieldCount, copy.fieldCount)
        assertNotEquals(
            (0 until source.fieldCount).map(source::fieldId),
            (0 until copy.fieldCount).map(copy::fieldId),
            "the seeded dictionary should have forced different ids",
        )
        assertEquals("alpha", copy.fieldName(0))
        assertEquals("beta", copy.fieldName(1))
    }

    /**
     * Scalars survive as the exact bytes they were written as.
     *
     * Reading a value out and appending it again would re-derive its physical type — a `float`
     * would come back a `double`, an `int32` an `int8`, a decimal would lose its scale. None of
     * those changes what the value *means*, which is why a roundtrip test would not catch them, and
     * all of them rewrite bytes a caller chose. Compaction runs this path over every document in
     * the store, repeatedly, so a drift here compounds.
     */
    @Test
    fun `scalars are copied byte for byte, keeping their physical type`() {
        val cases = listOf<Pair<String, VariantBuilder.() -> Unit>>(
            "null" to { appendNull() },
            "true" to { appendBoolean(true) },
            "false" to { appendBoolean(false) },
            "int8" to { appendLong(7) },
            "int16" to { appendLong(300) },
            "int32" to { appendLong(70_000) },
            "int64" to { appendLong(5_000_000_000) },
            // A double and a float holding the same number: the widths must not be conflated.
            "double" to { appendDouble(1.5) },
            "float" to { appendFloat(1.5f) },
            "decimal4 with trailing zeros" to { appendDecimal(BigDecimal("1.50")) },
            "decimal8" to { appendDecimal(BigDecimal("12345678901234.5")) },
            "decimal16" to { appendDecimal(BigDecimal("1234567890123456789012345678901234.56")) },
            "short string" to { appendString("ada") },
            "long string" to { appendString("x".repeat(200)) },
            "empty string" to { appendString("") },
            "binary" to { appendBinary(byteArrayOf(0, 1, -1, 127)) },
            "date" to { appendDate(20_000) },
            "time" to { appendTimeNtz(43_200_000_000) },
            "timestamp tz" to { appendTimestampMicros(1_700_000_000_000_000, adjustedToUtc = true) },
            "timestamp ntz" to { appendTimestampMicros(1_700_000_000_000_000, adjustedToUtc = false) },
            "timestamp nanos" to { appendTimestampNanos(1_700_000_000_000_000_000, adjustedToUtc = true) },
            "uuid" to { appendUuid(Uuid.parse("f81d4fae-7dec-11d0-a765-00a0c91e6bf6")) },
        )

        for ((name, write) in cases) {
            val original = VariantBuilder().apply(write).buildVariant()
            val copy = VariantBuilder(seededDictionary()).apply { append(original) }.buildVariant()

            assertContentEquals(original.toByteArray(), copy.toByteArray(), "$name was re-encoded")
            assertEquals(original.basicType, copy.basicType, name)
            assertEquals(original.primitiveType, copy.primitiveType, name)
            assertEquals(original.kind, copy.kind, name)
        }
    }

    /** Empty containers and nesting: the cases where a recursive copy loses count of itself. */
    @Test
    fun `empty and nested containers survive`() {
        val documents = listOf(
            "{}",
            "[]",
            """{"a":{}}""",
            """{"a":[]}""",
            """{"a":[[],[[]]],"b":{"c":{"d":[]}}}""",
            """[{"z":1},{"a":2},[3,[4,[5]]]]""",
        )
        for (text in documents) {
            val copy = VariantBuilder(seededDictionary())
                .apply { append(Variant.fromJson(text)) }
                .buildVariant()
            JsonOracle.assertEquivalent(text, copy.toJsonString(), hint = text)
            assertFieldIdsOrdered(copy)
        }
    }

    /**
     * The flush loop itself: many documents, one builder, one dictionary, `reset` between them.
     *
     * Each value is then decoded against the *final* dictionary rather than the one that existed
     * when it was appended, because that is what a segment reader does — it maps the footer's
     * dictionary once and reads every document in the file against it.
     */
    @Test
    fun `a run of documents shares one grown dictionary`() {
        forAll(JsonGens.document(), JsonGens.document(), iterations = 40) { first, second ->
            val sources = listOf(first, second, first, second).map { Variant.fromJson(it.toJsonString()) }
            val dictionary = VariantDictionaryBuilder()
            val builder = VariantBuilder(dictionary)

            val encoded = sources.map { source ->
                builder.reset()
                builder.append(source)
                builder.build()
            }

            val metadata = dictionary.build()
            encoded.forEachIndexed { index, bytes ->
                JsonOracle.assertEquivalent(
                    sources[index].toJsonString(),
                    Variant(metadata, bytes).toJsonString(),
                    hint = "document $index of a shared-dictionary segment",
                )
            }
        }
    }

    /** Copying a copy must be a fixed point, or compaction would drift over successive merges. */
    @Test
    fun `appending is idempotent across repeated merges`() {
        forAll(JsonGens.document(), iterations = 60) { document ->
            var current = Variant.fromJson(document.toJsonString())
            val first = VariantBuilder(seededDictionary()).apply { append(current) }.buildVariant()
            current = first
            repeat(3) {
                current = VariantBuilder(seededDictionary()).apply { append(current) }.buildVariant()
            }
            assertContentEquals(first.toByteArray(), current.toByteArray())
            assertEquals(first.metadata.size, current.metadata.size)
        }
    }

    /**
     * A dictionary that already holds names, chosen to sort around ordinary field names so the
     * ids a document's names get are neither the ids it had nor a simple shift of them.
     */
    private fun seededDictionary(): VariantDictionaryBuilder = VariantDictionaryBuilder().apply {
        listOf("zzz", "aaa", " ", "mmm", "Ａ").forEach(::intern)
    }

    /** The one structural rule a translation can break: ids ascending by UTF-8 name, and unique. */
    private fun assertFieldIdsOrdered(value: Variant) {
        when (value.kind) {
            VariantKind.OBJECT -> {
                for (index in 1 until value.fieldCount) {
                    val previous = value.fieldName(index - 1)
                    val current = value.fieldName(index)
                    if (compareUtf8(previous.toUtf8("name"), current.toUtf8("name")) >= 0) {
                        fail("'$previous' must sort strictly before '$current'")
                    }
                }
                (0 until value.fieldCount).forEach { assertFieldIdsOrdered(value.fieldValue(it)) }
            }

            VariantKind.ARRAY -> (0 until value.elementCount).forEach { assertFieldIdsOrdered(value.element(it)) }
            else -> Unit
        }
    }
}
