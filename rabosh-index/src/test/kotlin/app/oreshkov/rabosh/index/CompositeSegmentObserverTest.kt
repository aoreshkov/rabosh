package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.catalog.CatalogPath
import app.oreshkov.rabosh.catalog.SchemaCatalog
import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.core.Key
import app.oreshkov.rabosh.core.SegmentObservation
import app.oreshkov.rabosh.core.SegmentObserver
import app.oreshkov.rabosh.core.SegmentSummary
import app.oreshkov.rabosh.variant.Variant
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Two observers, one `StoreOptions` slot, and failures that stay where they happen.
 *
 * The core wraps whatever it is given in a guard that abandons an observation when a callback throws.
 * That guard is per *observer*, and if the thing it wraps is a naive composite then one child's throw
 * costs every other child its segment — which is exactly the outcome the core's own rule ("a broken
 * catalog must not cost a document") exists to prevent, one level up.
 */
class CompositeSegmentObserverTest {

    /** An observer that throws on the *n*-th document and counts what it saw. */
    private class Brittle(private val failAt: Int) : SegmentObserver {
        val observed = AtomicInteger()
        val completed = AtomicInteger()
        val abandoned = AtomicInteger()
        val failures = AtomicInteger()

        override fun beginSegment(segmentNumber: Long): SegmentObservation = object : SegmentObservation {
            override fun observe(userKey: Key, sequence: Long, document: Variant?) {
                if (observed.incrementAndGet() == failAt) error("brittle observer failed")
            }

            override fun complete(summary: SegmentSummary) {
                completed.incrementAndGet()
            }

            override fun abandon() {
                abandoned.incrementAndGet()
            }
        }

        override fun retain(liveSegments: Set<Long>) = Unit

        override fun observerFailed(cause: Throwable) {
            failures.incrementAndGet()
        }
    }

    @Test
    fun `a child that throws loses its own segment and no other`(@TempDir root: Path) {
        val directory = scratch(root)
        val brittle = Brittle(failAt = 5)
        val sturdy = Brittle(failAt = Int.MAX_VALUE)

        DocumentStore.open(
            directory,
            indexStoreOptions(CompositeSegmentObserver(brittle, sturdy)),
        ).use { store ->
            (0 until 40).forEach { store.put(keyFor(it), jsonDocument("""{"a":$it}""")) }
            store.flush()
        }

        assertEquals(1, brittle.failures.get(), "the failing child is told")
        assertEquals(1, brittle.abandoned.get(), "and its observation is abandoned")
        assertEquals(0, brittle.completed.get(), "so it never completes")

        assertEquals(40, sturdy.observed.get(), "the other child saw every document")
        assertEquals(1, sturdy.completed.get(), "and completed normally")
        assertEquals(0, sturdy.failures.get())
    }

    @Test
    fun `an observation opens when any child wants one`(@TempDir root: Path) {
        val directory = scratch(root)
        // A backfill where one layer already covers a segment and the other does not is the ordinary
        // case, not an edge one: `beginSegment` returning null is how "I have this one" is spelled.
        val declining = object : SegmentObserver {
            override fun beginSegment(segmentNumber: Long): SegmentObservation? = null
            override fun retain(liveSegments: Set<Long>) = Unit
        }
        val willing = Brittle(failAt = Int.MAX_VALUE)

        DocumentStore.open(
            directory,
            indexStoreOptions(CompositeSegmentObserver(declining, willing)),
        ).use { store ->
            (0 until 12).forEach { store.put(keyFor(it), jsonDocument("""{"a":$it}""")) }
            store.flush()
        }

        assertEquals(12, willing.observed.get())
        assertEquals(1, willing.completed.get())
    }

    @Test
    fun `a catalog and an index catalog share the slot and both stay complete`(@TempDir root: Path) {
        val directory = scratch(root)
        val catalog = SchemaCatalog(directory)
        IndexCatalog(directory).use { indexes ->
            DocumentStore.open(
                directory,
                indexStoreOptions(CompositeSegmentObserver(listOf(catalog, indexes))),
            ).use { store ->
                catalog.attach(store)
                indexes.attach(store)
                val handle = indexes.createIndex(store, IndexDefinition.inverted("$.team"))

                repeat(5) { round ->
                    (0 until 60).forEach {
                        store.put(keyFor(round * 60 + it), jsonDocument("""{"team":"t${it % 6}","n":$it}"""))
                    }
                    store.flush()
                }
                store.compact()

                // Both layers cover exactly the segments that exist. The catalog's `.cat` sidecars
                // and the index's `.idx`/`.pst` files are maintained by the same passes.
                val segments = segmentNumbers(directory)
                assertEquals(segments, baseSidecarNumbers(directory))
                assertEquals(segments.map { it to handle.id }.toSet(), postingFiles(directory))

                assertTrue(catalog.problems.isEmpty(), "the catalog: ${catalog.problems}")
                assertTrue(indexes.problems.isEmpty(), "the index catalog: ${indexes.problems}")
                assertTrue(catalog.inferSchema().coverage.isComplete)
                assertTrue(catalog.inferSchema()[CatalogPath.parse("$.team")] != null)

                store.snapshot().use { snapshot ->
                    indexes.read(store, handle, snapshot).use { reader ->
                        assertTrue(reader.coverage.isComplete)
                        assertEquals(
                            IndexQuery.scanKeys(store, reader, matches = { IndexTerm.ofString("t3") in it }),
                            IndexQuery.keysEqualTo(store, reader, IndexTerm.ofString("t3")),
                        )
                    }
                }
            }
        }
    }
}
