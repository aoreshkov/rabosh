package app.oreshkov.rabosh.catalog

import app.oreshkov.rabosh.core.DocumentStore
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Regression: a compaction landing during a backfill must not cost a live segment its sidecar.
 *
 * `DocumentStore.backfill` used to report, through `SegmentObserver.retain`, the segments that were
 * live when its scan **started** — it read them from the version it had pinned before the loop. With
 * background maintenance running, a compaction finishing during a long scan produces a segment that
 * set cannot mention, and `attach`'s reclamation then treats that brand-new segment as departed and
 * deletes the sidecar it had just written.
 *
 * Found while building phase 7's index sidecars, where the same bug costs a full index rebuild rather
 * than a rescan. Two things now prevent it and both are kept: `backfill` reads the live set after its
 * loop, and no sidecar numbered above the retained live maximum is ever deleted — a number above it
 * is not a departed segment, it is one the set in hand is too old to know about.
 *
 * The whole rest of `CatalogLifecycleTest` runs with `backgroundMaintenance = false`, which is why
 * this went unnoticed: it is the one place the race is reachable.
 */
class CatalogBackfillRetainTest {

    private fun options(catalog: SchemaCatalog?) = catalogStoreOptions(catalog).let {
        app.oreshkov.rabosh.core.StoreOptions(
            durability = app.oreshkov.rabosh.core.Durability.BUFFERED,
            segmentMaxBytes = 8 * 1024,
            blockSize = 512,
            backgroundMaintenance = true,
            segmentObserver = catalog,
        )
    }

    @Test
    fun `a live segment keeps its sidecar when a compaction lands during attach`(@TempDir root: Path) {
        val directory = scratch(root, "retain")
        DocumentStore.open(directory, options(null)).use { store ->
            repeat(10) { round ->
                (0 until 120).forEach {
                    store.put(keyFor(round * 120 + it), jsonDocument("""{"team":"t${it % 9}","n":$it}"""))
                }
                store.flush()
            }
        }

        val catalog = SchemaCatalog(directory)
        DocumentStore.open(directory, options(catalog)).use { store ->
            catalog.attach(store)
            store.compact()
            catalog.attach(store)

            assertEquals(segmentNumbers(directory), sidecarNumbers(directory), "a live segment lost its sidecar")
            assertTrue(catalog.problems.isEmpty(), "problems: ${catalog.problems}")
            assertTrue(catalog.inferSchema().coverage.isComplete, "coverage: ${catalog.inferSchema().coverage}")
        }
    }
}
