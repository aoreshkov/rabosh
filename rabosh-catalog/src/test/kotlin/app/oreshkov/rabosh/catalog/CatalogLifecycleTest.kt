package app.oreshkov.rabosh.catalog

import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.core.Durability
import app.oreshkov.rabosh.core.StoreOptions
import app.oreshkov.rabosh.variant.Variant
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * The catalog against a real store: sidecars appearing on a flush, following a compaction, surviving
 * a reopen, and going away when the segment they describe does.
 *
 * `backgroundMaintenance = false` throughout, for the reason `.claude/rules/testing.md` gives — a
 * test that reasons about which segments exist cannot have a background thread rewriting them
 * underneath it.
 */
class CatalogLifecycleTest {

    @TempDir
    lateinit var root: Path

    @Test
    fun `a flush writes a sidecar for the segment it produced`() {
        val directory = scratch(root, "flush")
        val catalog = SchemaCatalog(directory)
        DocumentStore.open(directory, catalogStoreOptions(catalog)).use { store ->
            store.load(corpus(200))
            catalog.attach(store)

            assertEquals(segmentNumbers(directory), sidecarNumbers(directory), "one sidecar per segment")
            val schema = catalog.inferSchema()
            assertTrue(schema.coverage.isComplete, "every live segment contributed")
            assertEquals(200, schema.documentCount)
            assertEquals(200, schema["$.name"]!!.observations)
        }
    }

    @Test
    fun `a compaction replaces sidecars along with the segments`() {
        val directory = scratch(root, "compact")
        val catalog = SchemaCatalog(directory)
        DocumentStore.open(directory, catalogStoreOptions(catalog)).use { store ->
            for (batch in 0 until 6) store.load(corpus(150), from = batch * 150)
            catalog.attach(store)
            val beforeSegments = segmentNumbers(directory)

            store.compact()

            val after = segmentNumbers(directory)
            assertTrue(after != beforeSegments, "the compaction actually rewrote something")
            // The filesystem is the assertion, not a memory count: on Windows a mapped file cannot
            // be deleted at all, so a leaked sidecar or segment fails here immediately rather than
            // as a drift somebody has to pick a bound for.
            assertEquals(after, sidecarNumbers(directory), "sidecars follow their segments exactly")

            val schema = catalog.inferSchema()
            assertTrue(schema.coverage.isComplete)
            assertEquals(900, schema.documentCount, "compaction merged the duplicates away")
        }
    }

    @Test
    fun `the model survives a reopen without rescanning`() {
        val directory = scratch(root, "reopen")
        val rendered: String
        DocumentStore.open(directory, catalogStoreOptions(SchemaCatalog(directory))).use { store ->
            store.load(corpus(300))
            store.compact()
        }

        val second = SchemaCatalog(directory)
        DocumentStore.open(directory, catalogStoreOptions(second)).use { store ->
            // Nothing is written, so nothing is observed; everything here came off the sidecars.
            second.attach(store)
            rendered = second.inferSchema().render()
            assertTrue(second.inferSchema().coverage.isComplete)
            assertEquals(300, second.inferSchema().documentCount)
        }
        assertTrue(rendered.contains("$.name"), rendered)
    }

    @Test
    fun `deleting the sidecars and backfilling reproduces the model exactly`() {
        val directory = scratch(root, "backfill")
        val expected: String
        DocumentStore.open(directory, catalogStoreOptions(SchemaCatalog(directory))).use { store ->
            store.load(corpus(250))
            store.compact()
        }
        SchemaCatalog(directory).let { catalog ->
            DocumentStore.open(directory, catalogStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                expected = catalog.inferSchema().render()
            }
        }

        for (number in sidecarNumbers(directory)) {
            Files.delete(directory.resolve(sketchFileName(number)))
        }

        val rebuilt = SchemaCatalog(directory)
        DocumentStore.open(directory, catalogStoreOptions(rebuilt)).use { store ->
            rebuilt.attach(store)
            assertEquals(expected, rebuilt.inferSchema().render(), "a backfill reproduces the model")
            assertEquals(segmentNumbers(directory), sidecarNumbers(directory), "and rewrites the sidecars")
        }
    }

    @Test
    fun `a store that ran without a catalog can be modelled afterwards`() {
        // This is the "model later" claim, tested: nothing was collected while the data was written,
        // and the model is still exactly what it would have been.
        val directory = scratch(root, "later")
        DocumentStore.open(directory, catalogStoreOptions(null)).use { store ->
            store.load(corpus(250))
            store.compact()
        }
        assertTrue(sidecarNumbers(directory).isEmpty(), "nothing was collected on the way in")

        val catalog = SchemaCatalog(directory)
        DocumentStore.open(directory, StoreOptions(backgroundMaintenance = false)).use { store ->
            catalog.attach(store)
            val schema = catalog.inferSchema()
            assertTrue(schema.coverage.isComplete)
            assertEquals(250, schema.documentCount)
            assertEquals(250, schema["$.name"]!!.observations)
        }
        assertEquals(segmentNumbers(directory), sidecarNumbers(directory))
    }

    @Test
    fun `attaching twice is cheap and changes nothing`() {
        val directory = scratch(root, "twice")
        val catalog = SchemaCatalog(directory)
        DocumentStore.open(directory, catalogStoreOptions(catalog)).use { store ->
            store.load(corpus(100))
            catalog.attach(store)
            val first = catalog.inferSchema().render()
            catalog.attach(store)
            assertEquals(first, catalog.inferSchema().render())
        }
    }

    @Test
    fun `nothing is answered before the catalog is attached`() {
        val directory = scratch(root, "detached")
        val catalog = SchemaCatalog(directory)
        DocumentStore.open(directory, catalogStoreOptions(catalog)).use { store ->
            store.load(corpus(20))
            // A model that quietly reported on half a store would be worse than one that refuses.
            assertFailsWith<CatalogStateException> { catalog.inferSchema() }
        }
    }

    @Test
    fun `a damaged sidecar is reported by default and rebuilt on request`() {
        val directory = scratch(root, "damaged")
        DocumentStore.open(directory, catalogStoreOptions(SchemaCatalog(directory))).use { store ->
            store.load(corpus(120))
            store.compact()
        }
        val victim = sidecarNumbers(directory).first()
        val path = directory.resolve(sketchFileName(victim))
        Files.readAllBytes(path).also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0xFF).toByte() }
            .let { Files.write(path, it) }

        val strict = SchemaCatalog(directory)
        DocumentStore.open(directory, catalogStoreOptions(strict)).use { store ->
            assertFailsWith<CorruptSketchException> { strict.attach(store) }
        }

        val forgiving = SchemaCatalog(directory, CatalogOptions(damagedSketches = DamagedSketchPolicy.REBUILD))
        DocumentStore.open(directory, catalogStoreOptions(forgiving)).use { store ->
            forgiving.attach(store)
            assertTrue(forgiving.inferSchema().coverage.isComplete, "the damaged segment was rescanned")
            assertEquals(1, forgiving.problems.size, "and the damage was still reported")
        }
    }

    @Test
    fun `a deleted document leaves the model when compaction drops its version`() {
        // The approximation [InferredSchema] documents, demonstrated rather than assumed: a
        // tombstone in one segment does not undo the observation in another, and it is compaction
        // that resolves the two. `l0CompactionTrigger = 2` so that two flushes are enough to
        // provoke the merge.
        val directory = scratch(root, "tombstone")
        val catalog = SchemaCatalog(directory)
        val options = StoreOptions(
            durability = Durability.BUFFERED,
            backgroundMaintenance = false,
            l0CompactionTrigger = 2,
            segmentObserver = catalog,
        )
        DocumentStore.open(directory, options).use { store ->
            store.load(corpus(50))
            catalog.attach(store)
            assertEquals(50, catalog.inferSchema().documentCount)

            for (index in 0 until 20) store.delete(keyFor(index))
            store.flush()
            assertEquals(50, catalog.inferSchema().documentCount, "the tombstones are in a segment of their own")

            store.compact()
            assertEquals(30, catalog.inferSchema().documentCount, "and the merge resolves them")
        }
    }

    private fun corpus(count: Int): List<Variant> = List(count) { index ->
        Variant.fromJson(
            """
            {"name":"person-${index % 97}","team":"team-${index % 7}","active":${index % 2 == 0},
             "score":${index % 13}.5,"tags":["a","b"],"profile":{"city":"city-${index % 23}"}}
            """.trimIndent(),
        )
    }
}
