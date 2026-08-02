package app.oreshkov.rabosh.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * The boundary phase 3 could not cross: a memtable becoming a file, and the log behind it going
 * away.
 *
 * Phase 3's `rotate` was a rotation and not an eviction, so a long-running store grew without
 * bound — every log it had ever written stayed on disk and every sealed memtable stayed on the
 * heap. These tests are about that specifically: the segment appears, the log disappears, and what
 * reopens is the same store.
 */
class SegmentLifecycleTest {

    @TempDir
    lateinit var root: Path

    private val options = StoreOptions(
        durability = Durability.BUFFERED,
        segmentMaxBytes = 4 * 1024,
        blockSize = 256,
        backgroundMaintenance = false,
    )

    @Test
    fun `a flush turns a memtable into a segment and frees its log`() {
        val directory = scratch(root, "lifecycle")
        DocumentStore.open(directory, options).use { store ->
            for (index in 0 until 200) store.put(keyFor(index), documentFor(index))
            assertEquals(0, store.stats.segmentCount, "nothing is on disk as a segment before a flush")

            store.flush()

            assertEquals(0, store.stats.sealedMemtables, "the sealed memtable is gone")
            assertTrue(store.stats.segmentCount > 0, "a segment took its place")
            assertEquals(1, files(directory, StoreFileKind.LOG).size, "only the open log remains")
            assertEquals(store.stats.segmentCount, files(directory, StoreFileKind.SEGMENT).size)

            // And the data is still all there, now read out of a mapped file.
            for (index in 0 until 200) {
                assertEquals(documentFor(index).toString(), store.get(keyFor(index)).toString())
            }
        }
    }

    @Test
    fun `a store reopens onto its segments`() {
        val directory = scratch(root, "reopen")
        DocumentStore.open(directory, options).use { store ->
            for (index in 0 until 300) store.put(keyFor(index), documentFor(index))
            store.flush()
        }

        DocumentStore.open(directory, options).use { store ->
            assertTrue(store.stats.segmentCount > 0, "the manifest should have named the segments")
            assertEquals(300, store.sequence)
            for (index in 0 until 300) {
                assertEquals(documentFor(index).toString(), store.get(keyFor(index)).toString())
            }
        }
    }

    /**
     * Repeated flushes do not grow the directory without bound.
     *
     * The failure this rules out is the one phase 3 documented and left open: a store that keeps
     * every log it has ever written. Here twenty flushes leave one log, whatever else they leave.
     */
    @Test
    fun `many flushes leave one log behind`() {
        val directory = scratch(root, "many")
        DocumentStore.open(directory, options).use { store ->
            repeat(20) { round ->
                for (index in 0 until 40) store.put(keyFor(round * 40 + index), documentFor(index))
                store.flush()
            }
            assertEquals(1, files(directory, StoreFileKind.LOG).size)
            assertEquals(1, files(directory, StoreFileKind.MANIFEST).size, "old manifests are swept too")
        }
    }

    /** A tombstone that reached a segment still hides the document a deeper source holds. */
    @Test
    fun `a deletion survives a flush`() {
        val directory = scratch(root, "tombstone")
        DocumentStore.open(directory, options).use { store ->
            store.put(Key.of("k"), documentFor(1))
            store.flush()
            store.delete(Key.of("k"))
            store.flush()
            assertNull(store.get(Key.of("k")))
        }
        DocumentStore.open(directory, options).use { store ->
            assertNull(store.get(Key.of("k")), "the deletion must survive a reopen as well")
        }
    }

    /**
     * A file no manifest names is the residue of a process that died before recording it, and is
     * swept at open. A file the manifest *does* name is not touched.
     */
    @Test
    fun `orphan files are swept and live ones are not`() {
        val directory = scratch(root, "orphans")
        DocumentStore.open(directory, options).use { store ->
            for (index in 0 until 100) store.put(keyFor(index), documentFor(index))
            store.flush()
        }
        val live = files(directory, StoreFileKind.SEGMENT).map { it.name }.toSet()
        assertTrue(live.isNotEmpty())

        // A segment nobody recorded, and a manifest nobody points at.
        Files.write(directory.resolve(segmentFileName(9999)), ByteArray(64))
        Files.write(directory.resolve(manifestFileName(9998)), ByteArray(64))

        DocumentStore.open(directory, options).use { store ->
            assertEquals(live, files(directory, StoreFileKind.SEGMENT).map { it.name }.toSet())
            assertTrue(Files.notExists(directory.resolve(manifestFileName(9998))))
            for (index in 0 until 100) {
                assertEquals(documentFor(index).toString(), store.get(keyFor(index)).toString())
            }
        }
    }

    /**
     * A directory with store files and no `CURRENT` is an unrecognised state, and the engine's rule
     * is that unknown data is a signalled failure rather than a default.
     *
     * Guessing here would mean inventing a version out of whatever files happen to be present,
     * which is exactly how a partially-deleted directory becomes a store that silently lost half
     * its data.
     */
    @Test
    fun `a directory with files and no CURRENT is reported`() {
        val directory = scratch(root, "no-current")
        DocumentStore.open(directory, options).use { store ->
            store.put(keyFor(1), documentFor(1))
        }
        Files.delete(directory.resolve(CURRENT_FILE_NAME))

        assertFailsWith<CorruptManifestException> { DocumentStore.open(directory, options) }
    }

    @Test
    fun `CURRENT naming a manifest that is not there is reported`() {
        val directory = scratch(root, "missing-manifest")
        DocumentStore.open(directory, options).use { store ->
            store.put(keyFor(1), documentFor(1))
        }
        CurrentFile.write(directory, 4242)

        assertFailsWith<CorruptManifestException> { DocumentStore.open(directory, options) }
    }

    /** An empty store still has a manifest, so it reopens through the same path as a full one. */
    @Test
    fun `an empty store round-trips`() {
        val directory = scratch(root, "empty")
        DocumentStore.open(directory, options).use { store ->
            store.flush()
            assertEquals(0, store.stats.segmentCount)
        }
        DocumentStore.open(directory, options).use { store ->
            assertEquals(0, store.sequence)
            assertEquals(0, store.stats.segmentCount)
            assertNull(store.get(Key.of("anything")))
        }
    }

    private fun files(directory: Path, kind: StoreFileKind): List<StoreFile> =
        listStoreFiles(directory).filter { it.kind == kind }
}
