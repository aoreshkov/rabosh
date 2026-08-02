package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.testkit.fs.Fault
import app.oreshkov.rabosh.testkit.fs.FaultOperation
import app.oreshkov.rabosh.testkit.fs.FaultyFileSystem
import app.oreshkov.rabosh.variant.toJsonString
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * A disk failure while writing derived data costs a rescan, never a document.
 *
 * This is the one relaxation the engine allows, and a real fault is the only way to check that it
 * actually holds rather than merely being intended. A sidecar that cannot be written must leave its
 * segment **uncovered** — not covered and empty, which would be a wrong answer — and the write that
 * was in progress must complete regardless, because an index has no business costing anybody a
 * document.
 */
class IndexIoFailureTest {

    @TempDir
    lateinit var root: Path

    private fun document(index: Int) = jsonDocument("""{"team":"team-${index % 5}","score":${index % 40}}""")

    @Test
    fun `a sidecar that cannot be written leaves its segment uncovered, and the write succeeds`() {
        val real = root.resolve("store")
        Files.createDirectories(real)

        FaultyFileSystem.wrapping(root).use { fs ->
            val directory = fs.path(real)
            IndexCatalog(directory).use { catalog ->
                DocumentStore.open(directory, indexStoreOptions(catalog)).use { store ->
                    catalog.attach(store)
                    store.load((0 until 100).map(::document))
                    val handle = catalog.createIndex(store, IndexDefinition.inverted("$.team"))

                    // The next flush writes a segment and then its sidecars. Only the sidecar fails.
                    val fault = fs.arm(Fault.onName(FaultOperation.WRITE, ".pst", times = Int.MAX_VALUE))
                    store.load((100 until 200).map(::document), from = 100)
                    fs.heal()

                    assertTrue(fault.fireCount >= 1, "the sidecar write should have failed")
                    assertTrue(catalog.problems.isNotEmpty(), "and the catalog should have recorded it")

                    // The documents are all there. That is the whole of the relaxation's promise.
                    for (index in 0 until 200) {
                        assertEquals(
                            document(index).toJsonString(),
                            store.get(keyFor(index))?.toJsonString(),
                            "document $index",
                        )
                    }

                    // And the index reports the segment as uncovered rather than answering for it.
                    store.snapshot().use { snapshot ->
                        catalog.read(store, handle, snapshot).use { reader ->
                            assertTrue(!reader.coverage.isComplete, "the failed segment must read as uncovered")
                            assertTrue(reader.coverage.segmentsCovered > 0, "and the others must still answer")
                            assertEquals(
                                IndexQuery.scanKeys(store, reader, { true }),
                                IndexQuery.keysExisting(store, reader),
                                "the answer must be identical either way",
                            )
                        }
                    }
                }
            }
        }
    }

    /** The repair is a rescan, and it works: rebuilding on a healthy disk covers everything again. */
    @Test
    fun `a rescan repairs what the failure cost`() {
        val real = root.resolve("store")
        Files.createDirectories(real)

        FaultyFileSystem.wrapping(root).use { fs ->
            val directory = fs.path(real)
            IndexCatalog(directory).use { catalog ->
                DocumentStore.open(directory, indexStoreOptions(catalog)).use { store ->
                    catalog.attach(store)
                    store.load((0 until 100).map(::document))
                    catalog.createIndex(store, IndexDefinition.inverted("$.team"))
                    fs.arm(Fault.onName(FaultOperation.WRITE, ".pst", times = Int.MAX_VALUE))
                    store.load((100 until 200).map(::document), from = 100)
                    fs.heal()
                }
            }
        }

        IndexCatalog(real).use { catalog ->
            DocumentStore.open(real, indexStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                val handle = catalog.indexes().single()
                store.snapshot().use { snapshot ->
                    catalog.read(store, handle, snapshot).use { reader ->
                        assertTrue(reader.coverage.isComplete, "attaching on a healthy disk rebuilds what is missing")
                        assertTrue(reader.isAuthoritative)
                    }
                }
            }
        }
    }

    /** A base sidecar that cannot be written costs the same: coverage, and nothing else. */
    @Test
    fun `a failing base sidecar does not stop the segment being written`() {
        val real = root.resolve("store")
        Files.createDirectories(real)

        FaultyFileSystem.wrapping(root).use { fs ->
            val directory = fs.path(real)
            IndexCatalog(directory).use { catalog ->
                DocumentStore.open(directory, indexStoreOptions(catalog)).use { store ->
                    catalog.attach(store)
                    catalog.createIndex(store, IndexDefinition.inverted("$.team"))
                    val fault = fs.arm(Fault.onName(FaultOperation.WRITE, ".idx", times = Int.MAX_VALUE))
                    store.load((0 until 100).map(::document))
                    fs.heal()

                    assertTrue(fault.fireCount >= 1)
                    assertEquals(1, store.liveSegmentNumbers.size, "the segment itself must exist")
                    for (index in 0 until 100) {
                        assertEquals(document(index).toJsonString(), store.get(keyFor(index))?.toJsonString())
                    }
                }
            }
        }
    }
}
