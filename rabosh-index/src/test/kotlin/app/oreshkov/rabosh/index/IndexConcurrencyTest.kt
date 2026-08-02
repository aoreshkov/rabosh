package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.variant.Variant
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * The two tests that are specifically *about* racing maintenance, and therefore the two that turn it
 * back on.
 *
 * Every other store-backed test here sets `backgroundMaintenance = false`, for the reason
 * `.claude/rules/testing.md` gives: a test that reasons about which segments exist cannot have a
 * background thread rewriting them underneath it. These two have nothing else to reason about.
 */
class IndexConcurrencyTest {

    private fun document(index: Int): Variant = jsonDocument(
        """{"team":"team-${index % 11}","score":${index % 31}}""",
    )

    @Test
    fun `a build that races a compaction converges`(@TempDir root: Path) {
        val directory = scratch(root)
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, indexStoreOptions(catalog, backgroundMaintenance = true)).use { store ->
                catalog.attach(store)
                repeat(8) { round ->
                    (0 until 200).forEach { store.put(keyFor(round * 200 + it), document(round * 200 + it)) }
                }
                store.flush()

                // The build walks segments a compaction may be replacing underneath it. Splitting the
                // sidecars by lifetime is what makes this safe: nothing rewrites a file another writer
                // is producing, so the worst outcome is a segment left uncovered — never a lost index.
                val handle = catalog.createIndex(store, IndexDefinition.inverted("$.team"))

                store.compact()
                catalog.attach(store)

                assertTrue(catalog.problems.isEmpty(), "problems: ${catalog.problems}")
                val segments = segmentNumbers(directory)
                assertEquals(segments, baseSidecarNumbers(directory))
                assertEquals(segments.map { it to handle.id }.toSet(), postingFiles(directory))

                store.snapshot().use { snapshot ->
                    catalog.read(store, handle, snapshot).use { reader ->
                        assertTrue(reader.coverage.isComplete, "coverage after settling: ${reader.coverage}")
                        val term = IndexTerm.ofString("team-4")
                        assertEquals(
                            IndexQuery.scanKeys(store, reader, matches = { term in it }),
                            IndexQuery.keysEqualTo(store, reader, term),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `a compaction landing during a backfill does not cost the new segment its sidecar`(@TempDir root: Path) {
        val directory = scratch(root)
        // The regression this phase fixed. `DocumentStore.backfill` used to report the segments that
        // were live when its scan *started*; a compaction finishing during a long scan would then see
        // its own brand-new segment reported as departed, and reclamation would delete a live
        // sidecar. Two things now prevent it: `backfill` reads the live set after its loop, and no
        // sidecar numbered above the retained live maximum is ever deleted.
        DocumentStore.open(directory, indexStoreOptions(null)).use { store ->
            repeat(10) { round ->
                (0 until 200).forEach { store.put(keyFor(round * 200 + it), document(round * 200 + it)) }
                store.flush()
            }
        }

        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, indexStoreOptions(catalog, backgroundMaintenance = true)).use { store ->
                catalog.attach(store)
                store.compact()
                catalog.attach(store)

                val segments = segmentNumbers(directory)
                assertEquals(segments, baseSidecarNumbers(directory), "a live segment lost its sidecar")
                assertTrue(catalog.problems.isEmpty(), "problems: ${catalog.problems}")
            }
        }
    }

    /**
     * An index dropped while a build is part way through writing its sidecars.
     *
     * The residue rule `IndexCrashTest` asserts after a kill, asserted here against a live race:
     * **no posting or column file may survive for an index that is not defined.** Such a file is one
     * nothing would ever open and nothing would ever delete — the registry's durability rule exists to
     * make that unreachable, and a background build is what makes the window ordinary. It was always
     * reachable, mind: a flush on this store's maintenance thread sits in exactly the same method.
     *
     * The drop is aimed at a known segment through the build seam, so this is a *scheduled* race
     * rather than a hoped-for one — the drop lands provably between the observation beginning and its
     * sidecars being written, which is the only window that leaves residue.
     */
    @Test
    fun `an index dropped while a build is running leaves no file behind`(@TempDir root: Path) {
        val directory = scratch(root)
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, indexStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                repeat(20) { round ->
                    (0 until 200).forEach { store.put(keyFor(round * 200 + it), document(round * 200 + it)) }
                    store.flush()
                }
                assertTrue(segmentNumbers(directory).size > 4, "the fixture has too few segments")

                val reached = CountDownLatch(1)
                val release = CountDownLatch(1)
                val seen = AtomicInteger()
                catalog.backgroundSegmentHook = {
                    if (seen.incrementAndGet() == 3) {
                        reached.countDown()
                        check(release.await(30, TimeUnit.SECONDS)) { "the test never released the build" }
                    }
                }

                val build = catalog.createIndexInBackground(store, IndexDefinition.column("$.score"))
                val handle = requireNotNull(build.handle)
                check(reached.await(30, TimeUnit.SECONDS)) { "the build never reached the third segment" }
                catalog.dropIndex(handle)
                release.countDown()
                build.await()
                catalog.backgroundSegmentHook = null

                assertTrue(catalog.indexes().isEmpty(), "the index came back: ${catalog.indexes()}")
                assertEquals(emptySet(), postingFiles(directory), "a posting file outlived its index")
                assertEquals(emptySet(), columnFiles(directory), "a column file outlived its index")
                // The base sidecars belong to the segments rather than to any index, so they stay.
                assertEquals(segmentNumbers(directory), baseSidecarNumbers(directory))
                assertTrue(catalog.problems.isEmpty(), "problems: ${catalog.problems}")
            }
        }
    }
}
