package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.variant.Variant
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * The sidecar lifecycle, asserted **through the filesystem**.
 *
 * Not through a memory threshold and not through a counter. On Windows a mapped file cannot be
 * deleted at all, so a leaked mapping fails these assertions immediately and deterministically rather
 * than as a drift somebody has to pick a bound for — which is why CI runs Windows as well as Linux,
 * and why `CatalogLifecycleTest` is shaped the same way.
 *
 * The converse matters as much and is asserted too: an open [IndexReader] must **keep** a replaced
 * sidecar on disk, because a reader may be inside it.
 */
class IndexLifecycleTest {

    private fun documents(count: Int): List<Variant> = (0 until count).map {
        jsonDocument("""{"team":"team-${it % 7}","score":$it,"tags":["t${it % 3}","t${it % 5}"]}""")
    }

    private fun withStore(
        directory: Path,
        catalog: IndexCatalog,
        body: (DocumentStore) -> Unit,
    ) {
        DocumentStore.open(directory, indexStoreOptions(catalog)).use(body)
    }

    @Test
    fun `a flush writes a base sidecar for the segment it produced`(@TempDir root: Path) {
        val directory = scratch(root)
        IndexCatalog(directory).use { catalog ->
            withStore(directory, catalog) { store ->
                catalog.attach(store)
                store.load(documents(50))
                assertEquals(segmentNumbers(directory), baseSidecarNumbers(directory))
                assertTrue(segmentNumbers(directory).isNotEmpty())
            }
        }
    }

    @Test
    fun `a compaction replaces sidecars along with the segments they describe`(@TempDir root: Path) {
        val directory = scratch(root)
        IndexCatalog(directory).use { catalog ->
            withStore(directory, catalog) { store ->
                catalog.attach(store)
                val handle = catalog.createIndex(store, IndexDefinition.inverted("$.team"))
                repeat(6) { round -> store.load(documents(60), from = round * 60) }
                store.compact()

                val segments = segmentNumbers(directory)
                // Equal, exactly. A leaked mapping leaves a file that could not be deleted, and a
                // missed sidecar leaves a segment nothing covers; this catches both in one line.
                assertEquals(segments, baseSidecarNumbers(directory))
                assertEquals(segments.map { it to handle.id }.toSet(), postingFiles(directory))
            }
        }
    }

    @Test
    fun `createIndex adds posting files and leaves the base sidecars untouched`(@TempDir root: Path) {
        val directory = scratch(root)
        IndexCatalog(directory).use { catalog ->
            withStore(directory, catalog) { store ->
                catalog.attach(store)
                store.load(documents(120))
                val basesBefore = sidecarBytes(directory).filterKeys { baseSegmentNumber(it) != null }
                assertTrue(basesBefore.isNotEmpty())
                assertTrue(postingFiles(directory).isEmpty())

                val handle = catalog.createIndex(store, IndexDefinition.inverted("$.team"))

                // The base sidecar is what makes `createIndex` cheap: it carries the ordinals and the
                // keys, and splitting it out of the posting files is precisely so that defining an
                // index never rewrites it.
                val basesAfter = sidecarBytes(directory).filterKeys { baseSegmentNumber(it) != null }
                assertEquals(basesBefore.keys, basesAfter.keys)
                for ((name, bytes) in basesBefore) assertContentEquals(bytes, basesAfter[name], name)
                assertEquals(segmentNumbers(directory).map { it to handle.id }.toSet(), postingFiles(directory))
            }
        }
    }

    @Test
    fun `dropIndex deletes its posting files, keeps the bases, and never reuses the id`(@TempDir root: Path) {
        val directory = scratch(root)
        IndexCatalog(directory).use { catalog ->
            withStore(directory, catalog) { store ->
                catalog.attach(store)
                store.load(documents(120))
                val first = catalog.createIndex(store, IndexDefinition.inverted("$.team"))
                val bases = sidecarBytes(directory).filterKeys { baseSegmentNumber(it) != null }
                assertTrue(postingFiles(directory).isNotEmpty())

                catalog.dropIndex(first)

                assertTrue(postingFiles(directory).isEmpty(), "posting files survived a drop")
                assertEquals(bases.keys, sidecarBytes(directory).filterKeys { baseSegmentNumber(it) != null }.keys)
                assertTrue(catalog.indexes().isEmpty())

                // A new index over the same path is a *different* index. Reusing the id would let a
                // stale posting file left by a crash be read as this one's postings.
                val second = catalog.createIndex(store, IndexDefinition.inverted("$.team"))
                assertNotEquals(first.id, second.id)
                assertTrue(second.id > first.id)
            }
        }
    }

    @Test
    fun `an open reader keeps a replaced sidecar on disk until it closes`(@TempDir root: Path) {
        val directory = scratch(root)
        IndexCatalog(directory).use { catalog ->
            withStore(directory, catalog) { store ->
                catalog.attach(store)
                val handle = catalog.createIndex(store, IndexDefinition.inverted("$.team"))
                repeat(4) { round -> store.load(documents(60), from = round * 60) }

                val before = baseSidecarNumbers(directory)
                store.snapshot().use { snapshot ->
                    val reader = catalog.read(store, handle, snapshot)
                    store.compact()
                    // A reader may be *inside* one of these files. Deleting it would be a use after
                    // free that on Linux reads freed pages and on Windows simply fails.
                    assertTrue(
                        baseSidecarNumbers(directory).containsAll(before),
                        "a sidecar was deleted while a reader held it",
                    )
                    reader.close()
                }
                // Once the last reference goes, the retired files do too.
                assertEquals(segmentNumbers(directory), baseSidecarNumbers(directory))
            }
        }
    }

    /**
     * The same rule against the sweep, which is the path that broke it.
     *
     * `prune` retires a departed segment and takes it out of `open`, leaving its files to be deleted
     * by its own last reader. `sweep` deletes by **name**, from a directory listing, and its "is this
     * mapped" test was `open` alone — so a segment retired while a reader was inside it fell through
     * the gap and had its files unlinked. Found by the query layer's lifecycle test, which is the
     * first thing to hold a reader across an `attach`.
     */
    @Test
    fun `a sweep leaves a retired sidecar alone while a reader holds it`(@TempDir root: Path) {
        val directory = scratch(root)
        IndexCatalog(directory).use { catalog ->
            withStore(directory, catalog) { store ->
                catalog.attach(store)
                repeat(4) { round -> store.load(documents(60), from = round * 60) }
                val handle = catalog.createIndex(store, IndexDefinition.inverted("$.team"))
                val before = sidecarNames(directory)

                store.snapshot().use { snapshot ->
                    catalog.read(store, handle, snapshot).use { reader ->
                        store.compact()
                        // `attach` is what runs the sweep, and a reader is inside these files.
                        catalog.attach(store)
                        assertTrue(
                            sidecarNames(directory).containsAll(before),
                            "the sweep deleted ${before - sidecarNames(directory)} from under a reader",
                        )
                        assertTrue(reader.coverage.segmentsCovered > 0, "and the reader still answers")
                        assertTrue(IndexQuery.keysEqualTo(store, reader, IndexTerm.ofString("team-3")).isNotEmpty())
                    }
                }

                // Once the reader lets go, the next sweep reclaims them.
                catalog.attach(store)
                assertEquals(segmentNumbers(directory), baseSidecarNumbers(directory))
            }
        }
    }

    @Test
    fun `closing the catalog unmaps everything`(@TempDir root: Path) {
        val directory = scratch(root)
        val catalog = IndexCatalog(directory)
        withStore(directory, catalog) { store ->
            catalog.attach(store)
            catalog.createIndex(store, IndexDefinition.inverted("$.team"))
            store.load(documents(120))
        }
        catalog.close()
        // On Windows this fails outright if any mapping survived, which is the assertion. Nothing is
        // deleted by closing — shutting down is not departing — so the files are all still here.
        for (name in sidecarNames(directory)) {
            assertTrue(Files.deleteIfExists(directory.resolve(name)), "could not delete $name after close")
        }
    }

    @Test
    fun `attaching twice costs nothing and changes nothing`(@TempDir root: Path) {
        val directory = scratch(root)
        IndexCatalog(directory).use { catalog ->
            withStore(directory, catalog) { store ->
                catalog.attach(store)
                catalog.createIndex(store, IndexDefinition.inverted("$.team"))
                store.load(documents(90))
                val before = sidecarBytes(directory)

                catalog.attach(store)

                val after = sidecarBytes(directory)
                assertEquals(before.keys, after.keys)
                for ((name, bytes) in before) assertContentEquals(bytes, after[name], name)
            }
        }
    }

    @Test
    fun `nothing is answered before attach`(@TempDir root: Path) {
        val directory = scratch(root)
        IndexCatalog(directory).use { catalog ->
            withStore(directory, catalog) { store ->
                assertFailsWith<IndexStateException> {
                    catalog.createIndex(store, IndexDefinition.inverted("$.team"))
                }
                store.snapshot().use { snapshot ->
                    assertFailsWith<IndexStateException> {
                        catalog.read(store, IndexHandle(1, IndexDefinition.inverted("$.team"), 0), snapshot)
                    }
                }
            }
        }
    }

    @Test
    fun `a store that ran without a catalog can be indexed afterwards`(@TempDir root: Path) {
        val directory = scratch(root)
        // The whole "index later" claim: nobody planned to index this store while it was being
        // written, and defining one now rewrites no document.
        DocumentStore.open(directory, indexStoreOptions(null)).use { store ->
            store.load(documents(150))
        }
        IndexCatalog(directory).use { catalog ->
            withStore(directory, catalog) { store ->
                catalog.attach(store)
                val handle = catalog.createIndex(store, IndexDefinition.inverted("$.team"))
                assertEquals(segmentNumbers(directory), baseSidecarNumbers(directory))
                assertEquals(segmentNumbers(directory).map { it to handle.id }.toSet(), postingFiles(directory))
            }
        }
    }

    @Test
    fun `the definitions survive a reopen and the sidecars are not rebuilt`(@TempDir root: Path) {
        val directory = scratch(root)
        var handleId = -1
        var before: Map<String, ByteArray> = emptyMap()
        IndexCatalog(directory).use { catalog ->
            withStore(directory, catalog) { store ->
                catalog.attach(store)
                handleId = catalog.createIndex(store, IndexDefinition.inverted("$.team")).id
                store.load(documents(120))
                before = sidecarBytes(directory)
            }
        }
        IndexCatalog(directory).use { catalog ->
            withStore(directory, catalog) { store ->
                catalog.attach(store)
                // An index definition is not derived data. It was forced before any posting file
                // existed, and it is still here.
                assertEquals(listOf(handleId), catalog.indexes().map { it.id })
                val after = sidecarBytes(directory)
                assertEquals(before.keys, after.keys)
                for ((name, bytes) in before) assertContentEquals(bytes, after[name], name)
            }
        }
    }

    @Test
    fun `sidecars of a segment that departed while the catalog was away are swept`(@TempDir root: Path) {
        val directory = scratch(root)
        IndexCatalog(directory).use { catalog ->
            withStore(directory, catalog) { store ->
                catalog.attach(store)
                repeat(4) { round -> store.load(documents(60), from = round * 60) }
            }
        }
        // Compact with no catalog attached: the sidecars for the consumed segments are now orphans
        // that no `retain` will ever mention, and only a directory listing can find them.
        DocumentStore.open(directory, indexStoreOptions(null)).use { store -> store.compact() }
        assertTrue(baseSidecarNumbers(directory).size > segmentNumbers(directory).size)

        IndexCatalog(directory).use { catalog ->
            withStore(directory, catalog) { store ->
                catalog.attach(store)
                assertEquals(segmentNumbers(directory), baseSidecarNumbers(directory))
            }
        }
    }

    /**
     * Closing while a background build is running leaves nothing mapped.
     *
     * The Windows instrument, aimed at the one thing a thread of the catalog's own can get wrong: a
     * build that outlived `close` would still be mapping sidecars through `reopen`, and the directory
     * below could not be removed. The build is stopped inside its third segment so the close provably
     * arrives mid-build rather than after one that had already finished.
     */
    @Test
    fun `closing during a background build leaves nothing mapped`(@TempDir root: Path) {
        val directory = scratch(root)
        val catalog = IndexCatalog(directory)
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        val seen = AtomicInteger()
        withStore(directory, catalog) { store ->
            catalog.attach(store)
            repeat(6) { round -> store.load(documents(60), from = round * 60) }
            catalog.backgroundSegmentHook = {
                if (seen.incrementAndGet() == 3) {
                    reached.countDown()
                    check(release.await(30, TimeUnit.SECONDS)) { "the test never released the build" }
                }
            }
            val build = catalog.createIndexInBackground(store, IndexDefinition.inverted("$.team"))
            check(reached.await(30, TimeUnit.SECONDS)) { "the build never reached the third segment" }

            // `close` cancels and joins, so the release below is what lets the gated segment return
            // and the worker finish. Ordered this way round on purpose: the close is *waiting* on a
            // build that is *inside* a segment, which is the state the join has to survive.
            val closing = Thread { catalog.close() }.also { it.start() }
            release.countDown()
            closing.join(30_000)
            assertTrue(!closing.isAlive, "close did not return while a build was in flight")
            assertTrue(build.isDone, "close left a build running: ${build.progress}")
        }

        // The acceptance: on Windows a single live mapping makes this impossible.
        Files.walk(directory).use { entries ->
            entries.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
        }
        assertTrue(!Files.exists(directory), "the directory could not be removed")
    }
}
