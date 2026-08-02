package app.oreshkov.rabosh.variant

import java.lang.foreign.MemorySegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The codec against documents shaped like the ones it will actually be given.
 *
 * Generated data covers the grammar; it does not cover the *shape* of real payloads — wide objects
 * of short scalars, arrays of near-identical records, the same handful of keys repeated in every
 * document. That last property is the one the segment-shared dictionary is designed around, so it
 * is measured here rather than assumed.
 */
class JsonCorpusTest {

    private val corpus: Map<String, String> = listOf(
        "api-event.json",
        "order.json",
        "telemetry.json",
        "geo.json",
        "config.json",
    ).associateWith { name ->
        checkNotNull(javaClass.getResourceAsStream("/corpus/$name")) { "missing test resource /corpus/$name" }
            .use { it.readBytes().decodeToString() }
    }

    @Test
    fun `every corpus document round-trips`() {
        for ((name, text) in corpus) {
            val variant = Variant.fromJson(text)
            JsonOracle.assertEquivalent(text, variant.toJsonString(), hint = name)
        }
    }

    @Test
    fun `re-encoding a decoded document is a fixed point`() {
        for ((name, text) in corpus) {
            val once = Variant.fromJson(text)
            val twice = Variant.fromJson(once.toJsonString())
            val thrice = Variant.fromJson(twice.toJsonString())

            // At the JSON level the first pass already normalises — field order, number form,
            // duplicate keys — so nothing changes after it.
            assertEquals(once.toJsonString(), twice.toJsonString(), name)

            // At the byte level the first pass does *not* settle, and that is by design: dictionary
            // ids are handed out in the order names are first seen, which is input order, because a
            // segment dictionary is append-only and cannot be renumbered later. Once the input is
            // itself canonical the encoding is deterministic.
            assertEquals(twice.toByteArray().toHex(), thrice.toByteArray().toHex(), name)
            assertEquals(twice.metadata.toByteArray().toHex(), thrice.metadata.toByteArray().toHex(), name)
        }
    }

    @Test
    fun `known paths resolve to known values`() {
        val event = Variant.fromJson(corpus.getValue("api-event.json"))
        assertEquals("deployment.status", event.select("$.type")?.stringValue())
        assertEquals(false, event.select("$.actor.site_admin")?.booleanValue())
        assertEquals("success", event.select("$.payload.deployment.statuses[2].state")?.stringValue())
        assertEquals(326112, event.select("$.payload.deployment.statuses[2].duration_ms")?.longValue())
        assertTrue(event.select("$.repository.license")!!.isNull, "an explicit null is present, not absent")
        assertEquals(0, event.select("$.labels")?.elementCount)
        assertEquals(0, event.select("$.payload.deployment.payload")?.fieldCount)

        val order = Variant.fromJson(corpus.getValue("order.json"))
        assertEquals("Ана Петрова", order.select("$.customer.name")?.stringValue())
        assertEquals("София", order.select("$.customer.addresses[0].city")?.stringValue())
        assertEquals("Ул. \"Драган Цанков\" 36", order.select("$.customer.addresses[0].line1")?.stringValue())
        assertEquals(
            "Mechanical keyboard, 87-key\t(tenkeyless)",
            order.select("$.lines[0].description")?.stringValue(),
        )

        val geo = Variant.fromJson(corpus.getValue("geo.json"))
        assertEquals("13.3777", geo.select("$.features[0].geometry.coordinates[0][0][0]")?.decimalValue()?.toString())
        assertEquals("十字路", geo.select("$.features[1].properties.aliases[1]")?.stringValue())

        val config = Variant.fromJson(corpus.getValue("config.json"))
        assertEquals("значение", config.select("$[\"unicode_keys\"][\"ключ\"]")?.stringValue())
        assertEquals("C:\\data\\rabosh\\primary", config.select("$.engine.storage.path")?.stringValue())
        assertTrue(config.select("$.escapes")!!.stringValue().contains("😀"))
        assertTrue(config.select("$.escapes")!!.stringValue().contains('\u0000'))
    }

    /**
     * The saving the whole design rests on: field names are paid for once per segment rather than
     * once per document.
     */
    @Test
    fun `a shared dictionary stops charging for repeated field names`() {
        val text = corpus.getValue("api-event.json")
        val copies = 100

        val standalone = Variant.fromJson(text)
        val standaloneBytes = (standalone.metadata.byteSize + standalone.byteSize) * copies

        val dictionary = VariantDictionaryBuilder()
        val builder = VariantBuilder(dictionary)
        val parser = JsonParser()
        var shared = 0L
        repeat(copies) {
            builder.reset()
            parser.parseInto(builder, text)
            shared += builder.build().size
        }
        shared += dictionary.toByteArray().size

        assertTrue(
            shared < standaloneBytes * 4 / 5,
            "expected the shared dictionary to save at least a fifth: $shared vs $standaloneBytes",
        )
        // And the values must still be readable against the shared dictionary.
        val metadata = dictionary.build()
        builder.reset()
        parser.parseInto(builder, text)
        JsonOracle.assertEquivalent(text, Variant(metadata, builder.build()).toJsonString())
    }

    @Test
    fun `documents are readable through a single mapped byte range`() {
        // Approximates a segment: the dictionary once, then every value packed behind it.
        val dictionary = VariantDictionaryBuilder()
        val builder = VariantBuilder(dictionary)
        val parser = JsonParser()
        val values = corpus.values.map { text ->
            builder.reset()
            parser.parseInto(builder, text)
            builder.build()
        }

        val metadataBytes = dictionary.toByteArray()
        val file = GrowableBytes().apply {
            write(metadataBytes)
            values.forEach { write(it) }
        }.toByteArray()

        val segment = MemorySegment.ofArray(file)
        val metadata = VariantMetadata.of(file)
        var offset = metadata.byteSize
        for ((index, text) in corpus.values.withIndex()) {
            val variant = Variant(metadata, segment, offset)
            JsonOracle.assertEquivalent(text, variant.toJsonString(), hint = "document $index")
            assertEquals(values[index].size.toLong(), variant.byteSize)
            offset += variant.byteSize
        }
        assertEquals(file.size.toLong(), offset)
    }
}
