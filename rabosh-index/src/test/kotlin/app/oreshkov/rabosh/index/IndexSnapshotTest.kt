package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.catalog.CatalogPath
import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.core.Key
import app.oreshkov.rabosh.core.Snapshot
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * The soundness guard, which is the sharpest thing phase 7 found.
 *
 * `SegmentObservation.observe` reports **the newest version of each key**, once — and it has to, or a
 * document written three times would look like three documents. But a segment can hold several
 * versions of one key: the compactor drops a superseded version only once the newer one is at or
 * below the oldest live snapshot, so an old snapshot is precisely what keeps the older version alive.
 *
 * A reader at that snapshot is entitled to see a version the index never recorded. So:
 *
 * > The index over segment `N` is sound at snapshot `S` **iff** `S.sequence >= largestSequence(N)`.
 *
 * A segment failing it is reported as *stale* and scanned instead. The two preconditions co-occur by
 * construction — the reason the old version is still there is the snapshot that makes the index wrong
 * about it — which is exactly why a differential test taken at the current sequence would never have
 * caught this.
 */
class IndexSnapshotTest {

    private val key = Key.of("key:pinned")

    private fun keysWithA(store: DocumentStore, snapshot: Snapshot, value: Long): List<Key> {
        val evaluator = Evaluator(CatalogPath.parse("$.a"), IndexOptions.DEFAULT)
        val term = IndexTerm.ofNumber(value)
        val keys = sortedSetOf<Key>()
        store.scan(snapshot = snapshot).use { cursor ->
            while (cursor.next()) {
                if (evaluator.terms(cursor.document)?.contains(term) == true) keys.add(cursor.key)
            }
        }
        return keys.toList()
    }

    @Test
    fun `a segment holding a version the index did not record declares itself stale`(@TempDir root: Path) {
        val directory = scratch(root)
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, indexStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                val handle = catalog.createIndex(store, IndexDefinition.inverted("$.a"))

                store.put(key, jsonDocument("""{"a":1}"""))
                store.flush()

                store.snapshot().use { pinned ->
                    // The overwrite the snapshot must not see.
                    store.put(key, jsonDocument("""{"a":2}"""))
                    store.flush()
                    // Enough L0 files that a compaction is actually picked, and the two versions of
                    // `key:pinned` are merged into one output segment.
                    repeat(4) { round ->
                        repeat(20) { store.put(keyFor(round * 20 + it), jsonDocument("""{"a":${900 + it}}""")) }
                        store.flush()
                    }
                    store.compact()

                    val truth = keysWithA(store, pinned, 1)
                    assertTrue(key in truth, "the pinned snapshot still sees a=1")

                    catalog.read(store, handle, pinned).use { reader ->
                        // The index declared itself unusable for the merged segment rather than
                        // answering from a record of newest versions only.
                        assertTrue(reader.coverage.segmentsStale > 0, "the merged segment must read as stale")
                        assertFalse(reader.isAuthoritative)
                        // And the answer is right anyway, because the scan covered it.
                        assertEquals(truth, IndexQuery.keysEqualTo(store, reader, IndexTerm.ofNumber(1L)))
                    }
                }

                // With the snapshot released and the superseded version compacted away, nothing is
                // stale any more and the index answers on its own.
                store.compact()
                store.snapshot().use { fresh ->
                    catalog.read(store, handle, fresh).use { reader ->
                        assertEquals(0, reader.coverage.segmentsStale)
                        assertTrue(reader.isAuthoritative)
                        assertEquals(
                            keysWithA(store, fresh, 2),
                            IndexQuery.keysEqualTo(store, reader, IndexTerm.ofNumber(2L)),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `unflushed documents make a reader non-authoritative`(@TempDir root: Path) {
        val directory = scratch(root)
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, indexStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                store.put(keyFor(0), jsonDocument("""{"a":1}"""))
                store.flush()
                val handle = catalog.createIndex(store, IndexDefinition.inverted("$.a"))

                store.snapshot().use { snapshot ->
                    catalog.read(store, handle, snapshot).use { reader ->
                        assertTrue(reader.isAuthoritative, "everything is in a segment")
                    }
                }

                // A memtable has no sidecar and never will: there is no per-segment unit to attach
                // one to. So a store with unflushed writes always needs the scan.
                store.put(keyFor(1), jsonDocument("""{"a":1}"""))
                store.snapshot().use { snapshot ->
                    catalog.read(store, handle, snapshot).use { reader ->
                        assertFalse(reader.isAuthoritative, "an unflushed document is not indexed")
                        assertEquals(
                            keysWithA(store, snapshot, 1),
                            IndexQuery.keysEqualTo(store, reader, IndexTerm.ofNumber(1L)),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `answers match a scan at every live snapshot of a write and compact script`(@TempDir root: Path) {
        val directory = scratch(root)
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, indexStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                val handle = catalog.createIndex(store, IndexDefinition.inverted("$.a"))

                val snapshots = ArrayList<Snapshot>()
                try {
                    var written = 0
                    repeat(10) { step ->
                        repeat(25) { store.put(keyFor(written++ % 60), jsonDocument("""{"a":${written % 9}}""")) }
                        store.flush()
                        if (step % 3 == 0) snapshots.add(store.snapshot())
                        if (step % 4 == 3) store.compact()

                        // Every snapshot ever taken is still answerable, and every one is checked —
                        // the older ones are exactly the ones the guard exists for.
                        for (snapshot in snapshots) {
                            catalog.read(store, handle, snapshot).use { reader ->
                                for (value in 0L..8L) {
                                    assertEquals(
                                        keysWithA(store, snapshot, value),
                                        IndexQuery.keysEqualTo(store, reader, IndexTerm.ofNumber(value)),
                                        "step $step, snapshot ${snapshot.sequence}, a=$value",
                                    )
                                }
                            }
                        }
                    }
                } finally {
                    snapshots.forEach(Snapshot::close)
                }
            }
        }
    }
}
