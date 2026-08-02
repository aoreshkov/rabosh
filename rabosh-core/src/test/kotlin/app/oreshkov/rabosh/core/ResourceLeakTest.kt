package app.oreshkov.rabosh.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * "No file descriptor or arena leaks" — the second half of phase 4's acceptance criterion.
 *
 * **The assertion is that obsolete files are gone from the directory, and that is not a proxy.** A
 * compacted-away segment can only be deleted once its mapping is released, and on Windows — the
 * platform this is developed on, and one of the two CI runs — deleting a mapped file *fails*. So a
 * leaked mapping does not show up here as a slow drift in memory that a test would have to guess a
 * threshold for; it shows up as a file that is still there, immediately and deterministically. On
 * POSIX the same test passes for a weaker reason, which is why CI runs both.
 *
 * Descriptors are not leaked by construction rather than by discipline: a segment's channel is
 * closed the moment the mapping exists, because a mapping outlives the channel that made it. The
 * test below still opens and drops hundreds of segments, which would exhaust a process that kept
 * one descriptor each.
 */
class ResourceLeakTest {

    @TempDir
    lateinit var root: Path

    private val options = StoreOptions(
        durability = Durability.BUFFERED,
        segmentMaxBytes = 2 * 1024,
        blockSize = 256,
        l0CompactionTrigger = 2,
        baseLevelBytes = 4 * 1024,
        backgroundMaintenance = false,
    )

    @Test
    fun `compacted segments are unmapped and deleted`() {
        val directory = scratch(root, "leak")
        DocumentStore.open(directory, options).use { store ->
            repeat(15) { round ->
                for (index in 0 until 80) store.put(keyFor(round * 80 + index), documentFor(index))
                store.flush()
                store.compact()

                assertEquals(
                    store.stats.segmentCount,
                    segmentFiles(directory),
                    "round $round left files behind that no version names",
                )
            }
            assertTrue(store.stats.segmentCount > 0)
        }
    }

    /**
     * A snapshot keeps a replaced segment on disk, and closing it lets go.
     *
     * This is the deterministic-unmap contract stated as an observation: while the snapshot is open
     * the file *must* still be there, because a reader may be inside it; the moment it closes the
     * file *must* go, because nothing else refers to it. A design that unmapped when the collector
     * noticed could only manage the first half.
     */
    @Test
    fun `a snapshot holds a replaced segment on disk until it closes`() {
        val directory = scratch(root, "pinned")
        DocumentStore.open(directory, options).use { store ->
            for (index in 0 until 200) store.put(keyFor(index), documentFor(index))
            store.flush()

            val snapshot = store.snapshot()
            try {
                repeat(6) { round ->
                    for (index in 0 until 200) store.put(keyFor(index), documentFor(index + round * 1000 + 1))
                    store.flush()
                    store.compact()
                }
                assertTrue(
                    segmentFiles(directory) > store.stats.segmentCount,
                    "the snapshot should be holding files the live version no longer names",
                )
                // And the pinned view still reads out of them.
                for (index in 0 until 200) {
                    assertEquals(
                        documentFor(index).toString(),
                        store.get(keyFor(index), snapshot).toString(),
                    )
                }
            } finally {
                snapshot.close()
            }

            assertEquals(
                store.stats.segmentCount,
                segmentFiles(directory),
                "closing the snapshot should have released and deleted the files it was holding",
            )
        }
    }

    /**
     * A cursor holds its segments for as long as it is open, for the same reason: it is reading
     * documents straight out of them.
     */
    @Test
    fun `an open cursor holds its segments`() {
        val directory = scratch(root, "cursor")
        DocumentStore.open(directory, options).use { store ->
            for (index in 0 until 200) store.put(keyFor(index), documentFor(index))
            store.flush()

            val cursor = store.scan()
            try {
                assertTrue(cursor.next())
                repeat(6) { round ->
                    for (index in 0 until 200) store.put(keyFor(index), documentFor(index + round * 1000 + 1))
                    store.flush()
                    store.compact()
                }
                assertTrue(segmentFiles(directory) > store.stats.segmentCount)
                // The cursor keeps walking the files it started on.
                var walked = 1
                while (cursor.next()) walked++
                assertEquals(200, walked)
            } finally {
                cursor.close()
            }
            assertEquals(store.stats.segmentCount, segmentFiles(directory))
        }
    }

    /**
     * Closing a store releases every mapping it holds, so the directory can be removed.
     *
     * Not a rehearsal of the previous tests: those are about files that *left* the tree, and this is
     * about the live ones, which must be unmapped on close and must *not* be deleted by it.
     */
    @Test
    fun `closing a store releases its live segments without deleting them`() {
        val directory = scratch(root, "close")
        DocumentStore.open(directory, options).use { store ->
            for (index in 0 until 300) store.put(keyFor(index), documentFor(index))
            store.flush()
            store.compact()
            assertTrue(store.stats.segmentCount > 0)
        }
        val remaining = segmentFiles(directory)
        assertTrue(remaining > 0, "closing a store must not delete its data")

        // If any mapping had survived the close, Windows would refuse this outright.
        for (file in listStoreFiles(directory)) {
            Files.delete(directory.resolve(file.name))
        }
        assertEquals(0, segmentFiles(directory))
    }

    /** Opening and closing many stores must not accumulate descriptors or mappings. */
    @Test
    fun `many open and close cycles leave nothing behind`() {
        val directory = scratch(root, "cycles")
        repeat(40) { round ->
            DocumentStore.open(directory, options).use { store ->
                for (index in 0 until 30) store.put(keyFor(round * 30 + index), documentFor(index))
                store.flush()
                store.compact()
                assertEquals(store.stats.segmentCount, segmentFiles(directory), "at round $round")
            }
        }
        DocumentStore.open(directory, options).use { store ->
            for (index in 0 until 40 * 30) {
                assertEquals(documentFor(index % 30).toString(), store.get(keyFor(index)).toString())
            }
        }
    }

    private fun segmentFiles(directory: Path): Int =
        listStoreFiles(directory).count { it.kind == StoreFileKind.SEGMENT }
}
