package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.variant.toJsonString
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * A column may hand back a value only when it can hand back **the** value.
 *
 * The trap phase 12 exists to avoid, and it is worth stating in full because nothing else in the
 * engine has this shape. A column stores the numeric family as unscaled integers at **one common
 * scale per segment**, so a segment holding `{"price":10}` beside `{"price":9.99}` picks scale 2 and
 * reads the first back as `10.00`. That is the same *number* and a different *value*: the document
 * says `10`, and handing a caller `10.00` would be an index changing an answer.
 *
 * Deciding a predicate never had this problem — `10.00 ≥ 10` is true either way — which is why it
 * went unnoticed until a projection needed to read a value out rather than test one.
 *
 * So the builder proves exactness where it can and records it in `FIDELITY`, and every accessor here
 * refuses rather than approximates. These tests arrange the mixed-scale segment **deliberately**;
 * hoping a generated corpus produces one would make the whole guard untested on most runs.
 */
class ColumnFidelityTest {

    private fun store(root: Path, documents: List<String>, path: String): Path {
        val directory = scratch(root, "fidelity")
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, indexStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                store.load(documents.map(::jsonDocument))
                catalog.createIndex(store, IndexDefinition.column(path))
            }
        }
        return directory
    }

    private inline fun withColumn(directory: Path, body: (DocumentStore, ColumnReader, Long) -> Unit) {
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, indexStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                val handle = catalog.indexes().single()
                store.snapshot().use { snapshot ->
                    catalog.readColumn(store, handle, snapshot).use { reader ->
                        body(store, reader, reader.usableSegments.single())
                    }
                }
            }
        }
    }

    /**
     * The assertion everything else here is a special case of: what the column gives back is what the
     * document says, compared as the caller would see it.
     *
     * Never against a literal the test author guessed — the first draft of this suite expected `0.50`
     * for a document written as `0.50`, and the engine says `0.5`, because `decideNumber` strips
     * trailing zeros before anything is stored. Comparing against the document is what makes the
     * suite about the *round trip* rather than about the author's model of the encoder.
     */
    private fun assertProjectionMatchesDocuments(
        store: DocumentStore,
        reader: ColumnReader,
        segment: Long,
        path: String,
        count: Int,
    ) {
        store.snapshot().use { snapshot ->
            for (ordinal in 0 until count) {
                if (!reader.canProject(segment, ordinal)) continue
                val key = reader.keyAt(segment, ordinal)
                val fromDocument = store.get(key, snapshot)?.select(path)?.toJsonString()
                assertEquals(fromDocument, reader.valueAt(segment, ordinal)?.toJsonString(), "ordinal $ordinal")
            }
        }
    }

    @Test
    fun `mixed scales round-trip, because a document holds the canonical form`(@TempDir root: Path) {
        // Scales 0, 1 and 2 in one column, which is what ordinary JSON looks like. The column's common
        // scale is 2 and every value is rescaled up to it — and back down again on the way out by the
        // parser's own rule, so `12.30` is `12.3` in the document and `12.3` here.
        val documents = listOf(
            """{"price":10}""", """{"price":9.99}""", """{"price":12.30}""", """{"price":0.50}""", """{"price":7}""",
        )
        withColumn(store(root, documents, "$.price")) { store, reader, segment ->
            assertTrue(reader.canProject(segment), "every value is already the form the parser produced")
            assertProjectionMatchesDocuments(store, reader, segment, "$.price", documents.size)
            // And the specific trap, named: the integer must not come back wearing the column's scale.
            assertEquals("10", reader.valueAt(segment, 0)?.toJsonString())
            assertEquals("12.3", reader.valueAt(segment, 2)?.toJsonString())
        }
    }

    @Test
    fun `a value that is not the parser's own form is refused`(@TempDir root: Path) {
        // No JSON document produces this: `decideNumber` would have stored `10`. Only a caller
        // building a Variant by hand can, and projecting it would hand back `10` for a decimal of
        // scale 2. The one case the flag exists to catch.
        val builder = app.oreshkov.rabosh.variant.VariantBuilder()
        builder.startObject()
        builder.field("price")
        builder.appendDecimal(java.math.BigDecimal("10.00"))
        builder.endObject()

        val directory = scratch(root, "handbuilt")
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, indexStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                store.put(keyFor(0), builder.buildVariant())
                store.put(keyFor(1), jsonDocument("""{"price":9.99}"""))
                store.flush()
                catalog.createIndex(store, IndexDefinition.column("$.price"))
            }
        }
        withColumn(directory) { _, reader, segment ->
            assertTrue(!reader.canProject(segment), "a non-canonical decimal must not claim exactness")
            assertFailsWith<IndexStateException> { reader.valueAt(segment, 0) }
        }
    }

    @Test
    fun `text and booleans are always exact`(@TempDir root: Path) {
        withColumn(store(root, (0 until 10).map { """{"t":"value-$it"}""" }, "$.t")) { _, reader, segment ->
            assertTrue(reader.canProject(segment))
            assertEquals("\"value-3\"", reader.valueAt(segment, 3)?.toJsonString())
        }
        withColumn(store(root, (0 until 10).map { """{"b":${it % 2 == 0}}""" }, "$.b")) { _, reader, segment ->
            assertTrue(reader.canProject(segment))
            assertEquals("true", reader.valueAt(segment, 0)?.toJsonString())
            assertEquals("false", reader.valueAt(segment, 1)?.toJsonString())
        }
    }

    @Test
    fun `a null projects as null and an absent path projects as nothing`(@TempDir root: Path) {
        val documents = listOf("""{"t":"a"}""", """{"t":null}""", """{"other":1}""")
        withColumn(store(root, documents, "$.t")) { _, reader, segment ->
            assertTrue(reader.canProject(segment))
            assertEquals("\"a\"", reader.valueAt(segment, 0)?.toJsonString())
            assertEquals("null", reader.valueAt(segment, 1)?.toJsonString(), "a JSON null is a value")
            assertNull(reader.valueAt(segment, 2), "an absent path is not")
        }
    }

    @Test
    fun `a residual ordinal is refused rather than approximated`(@TempDir root: Path) {
        // A value too wide for 64 unscaled bits goes to residual: the column never stored it, so only
        // the document knows what it is.
        val documents = listOf("""{"n":1}""", """{"n":2}""", """{"n":123456789012345678901234567890.5}""")
        withColumn(store(root, documents, "$.n")) { _, reader, segment ->
            assertTrue(!reader.canProject(segment, 2), "a residual ordinal cannot be projected")
            assertFailsWith<IndexStateException> { reader.valueAt(segment, 2) }
        }
    }

    @Test
    fun `a repeated path has no single value to project`(@TempDir root: Path) {
        val documents = listOf("""{"a":{"b":[1,2,3]}}""", """{"a":{"b":[4]}}""")
        withColumn(store(root, documents, "$.a.b[*]")) { _, reader, segment ->
            // Ordinal 0 carries three values, so there is no one value a projection could name. The
            // query layer never asks — a wildcard is not a legal projection — and the reader refuses
            // anyway rather than silently returning the first.
            assertTrue(!reader.canProject(segment, 0))
            assertTrue(reader.canProject(segment, 1), "one value is projectable")
        }
    }
}
