package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.variant.Variant
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * A second sidecar kind per (segment, index), asserted through the filesystem.
 *
 * Phase 7 established that after a compaction the set of sidecars must equal the set of segments,
 * *exactly*, because on Windows a mapped file cannot be deleted and a leaked mapping fails that
 * immediately. Every one of those reclamation paths had to learn about `.col`, and each of them fails
 * differently when it does not — so each gets its own assertion here rather than a single sweep.
 */
class ColumnLifecycleTest {

    private fun document(index: Int): Variant = jsonDocument(
        """{"price":${index % 300}.${"%02d".format(index % 100)},"team":"t${index % 9}"}""",
    )

    private fun load(store: DocumentStore, rounds: Int, perRound: Int = 60) {
        var written = 0
        repeat(rounds) {
            repeat(perRound) { store.put(keyFor(written++), document(written)) }
            store.flush()
        }
    }

    @Test
    fun `a compaction replaces columns along with the segments they describe`(@TempDir root: Path) {
        val directory = scratch(root, "collife")
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, indexStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                val column = catalog.createIndex(store, IndexDefinition.column("$.price"))
                val inverted = catalog.createIndex(store, IndexDefinition.inverted("$.team"))
                load(store, rounds = 6)
                store.compact()

                val segments = segmentNumbers(directory)
                assertEquals(segments, baseSidecarNumbers(directory))
                assertEquals(segments.map { it to column.id }.toSet(), columnFiles(directory))
                assertEquals(segments.map { it to inverted.id }.toSet(), postingFiles(directory))
            }
        }
    }

    @Test
    fun `createIndex adds columns and leaves the bases untouched`(@TempDir root: Path) {
        val directory = scratch(root, "collife")
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, indexStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                load(store, rounds = 3)
                val before = sidecarBytes(directory).filterKeys { baseSegmentNumber(it) != null }
                assertTrue(before.isNotEmpty())

                val handle = catalog.createIndex(store, IndexDefinition.column("$.price"))

                val after = sidecarBytes(directory).filterKeys { baseSegmentNumber(it) != null }
                assertEquals(before.keys, after.keys)
                for ((name, bytes) in before) assertContentEquals(bytes, after[name], name)
                assertEquals(segmentNumbers(directory).map { it to handle.id }.toSet(), columnFiles(directory))
            }
        }
    }

    @Test
    fun `dropIndex deletes columns, keeps the bases, and never reuses the id`(@TempDir root: Path) {
        val directory = scratch(root, "collife")
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, indexStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                load(store, rounds = 3)
                val first = catalog.createIndex(store, IndexDefinition.column("$.price"))
                val bases = sidecarBytes(directory).filterKeys { baseSegmentNumber(it) != null }
                assertTrue(columnFiles(directory).isNotEmpty())

                catalog.dropIndex(first)

                assertTrue(columnFiles(directory).isEmpty(), "columns survived a drop")
                assertEquals(bases.keys, sidecarBytes(directory).filterKeys { baseSegmentNumber(it) != null }.keys)

                val second = catalog.createIndex(store, IndexDefinition.column("$.price"))
                assertNotEquals(first.id, second.id)
            }
        }
    }

    @Test
    fun `an attach over a covered store rewrites nothing`(@TempDir root: Path) {
        val directory = scratch(root, "collife")
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, indexStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                catalog.createIndex(store, IndexDefinition.column("$.price"))
                load(store, rounds = 3)
                val before = sidecarBytes(directory)

                // The check `beginSegment` makes is over indexes of *both* kinds. If a column were
                // left out of that set, every attach would re-observe and rewrite every column.
                catalog.attach(store)

                val after = sidecarBytes(directory)
                assertEquals(before.keys, after.keys)
                for ((name, bytes) in before) assertContentEquals(bytes, after[name], name)
            }
        }
    }

    @Test
    fun `a damaged column is repaired rather than reopened forever`(@TempDir root: Path) {
        val directory = scratch(root, "collife")
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, indexStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                catalog.createIndex(store, IndexDefinition.column("$.price"))
                load(store, rounds = 3)
            }
        }

        val victim = sidecarNames(directory).first { columnNumbers(it) != null }
        val bytes = Files.readAllBytes(directory.resolve(victim))
        // Inside the section directory, which the header checksum covers, so `open` refuses it.
        val offset = SectionDirectory.HEADER_BYTES + 4
        bytes[offset] = (bytes[offset].toInt() xor 0xFF).toByte()
        Files.write(directory.resolve(victim), bytes)

        IndexCatalog(directory, IndexOptions(damagedSidecars = DamagedIndexPolicy.REBUILD)).use { catalog ->
            DocumentStore.open(directory, indexStoreOptions(catalog)).use { store ->
                // The repair path deletes sidecars of *both* kinds. Deleting only the posting files
                // would leave the damaged column in place, and every reopen would fail on it again —
                // a repair that never converges.
                catalog.attach(store)
                assertTrue(catalog.problems.isNotEmpty(), "the damage must be recorded")
                assertEquals(segmentNumbers(directory), baseSidecarNumbers(directory))
                assertEquals(
                    segmentNumbers(directory),
                    columnFiles(directory).map { it.first }.toSet(),
                    "every segment must be covered again after the repair",
                )
            }
        }
    }

    @Test
    fun `an open column reader keeps a replaced sidecar on disk`(@TempDir root: Path) {
        val directory = scratch(root, "collife")
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, indexStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                val handle = catalog.createIndex(store, IndexDefinition.column("$.price"))
                load(store, rounds = 4)

                val before = columnFiles(directory)
                store.snapshot().use { snapshot ->
                    val reader = catalog.readColumn(store, handle, snapshot)
                    store.compact()
                    assertTrue(
                        columnFiles(directory).containsAll(before),
                        "a column was deleted while a reader held it",
                    )
                    reader.close()
                }
                assertEquals(
                    segmentNumbers(directory).map { it to handle.id }.toSet(),
                    columnFiles(directory),
                )
            }
        }
    }

    @Test
    fun `a catalog recommendation can be built directly`(@TempDir root: Path) {
        val directory = scratch(root, "collife")
        val catalog = app.oreshkov.rabosh.catalog.SchemaCatalog(directory)
        IndexCatalog(directory).use { indexes ->
            DocumentStore.open(
                directory,
                indexStoreOptions(CompositeSegmentObserver(catalog, indexes)),
            ).use { store ->
                catalog.attach(store)
                indexes.attach(store)
                load(store, rounds = 4)
                catalog.attach(store)

                // The gap phase 7 left open: the catalog recommended shredded columns and
                // `createIndex` refused them. `IndexDefinition.of` is the bridge, and it now works
                // for both kinds.
                val recommended = catalog.indexCandidates()
                    .firstOrNull { it.kind == app.oreshkov.rabosh.catalog.IndexKind.SHREDDED_COLUMN }
                assertTrue(recommended != null, "the fixture should produce a column recommendation")
                val handle = indexes.createIndex(store, IndexDefinition.of(recommended))
                assertEquals(app.oreshkov.rabosh.catalog.IndexKind.SHREDDED_COLUMN, handle.kind)
                assertEquals(recommended.path, handle.path)
                assertEquals(segmentNumbers(directory).map { it to handle.id }.toSet(), columnFiles(directory))
            }
        }
    }
}
