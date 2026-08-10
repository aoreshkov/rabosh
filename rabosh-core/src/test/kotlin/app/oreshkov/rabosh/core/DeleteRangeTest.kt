package app.oreshkov.rabosh.core

import app.oreshkov.rabosh.variant.toJsonString
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * `deleteRange` against a brute-force model of the same range.
 *
 * The differential is the whole test: after a range delete, a scan must return exactly what a scan
 * over *the same range minus the deletions* returns — before and after a compaction, and at a
 * snapshot taken before the delete. Anything less would pass for a loop that deleted a bit too much
 * or stopped a bit too early, which are the two ways a batched walk goes wrong and neither of which
 * shows up as an exception.
 */
class DeleteRangeTest {

    @TempDir
    lateinit var root: Path

    private fun options() = StoreOptions(
        segmentMaxBytes = 4 * 1024,
        blockSize = 256,
        backgroundMaintenance = false,
    )

    /**
     * The batch size is forced well below the corpus, because the interesting bugs are all at a
     * batch boundary: a loop that restarted at the last key handled rather than after it, or that
     * ended on a full batch, would pass with one batch and fail with six.
     */
    private val batch = 37

    @Test
    fun `a range delete removes exactly the range`() {
        withStore { store ->
            for (index in 0 until 500) store.put(keyFor(index), documentFor(index))
            store.flush()

            val deleted = store.deleteRange(keyFor(100), keyFor(299), batch)
            assertEquals(200, deleted, "every key in [100, 299] and no other")

            assertRange(store, deletedFrom = 100, deletedTo = 299, total = 500)

            // And again after a compaction, which is where a tombstone that was dropped too early
            // would let a deleted document come back.
            store.compact()
            assertRange(store, deletedFrom = 100, deletedTo = 299, total = 500)
        }
    }

    /** Both bounds are inclusive, which is the one off-by-one a caller cannot check for themselves. */
    @Test
    fun `both bounds are inclusive`() {
        withStore { store ->
            for (index in 0 until 20) store.put(keyFor(index), documentFor(index))
            assertEquals(3, store.deleteRange(keyFor(5), keyFor(7), batch))

            assertNull(store.jsonAt(keyFor(5)))
            assertNull(store.jsonAt(keyFor(7)))
            assertEquals(documentFor(4).toJsonString(), store.jsonAt(keyFor(4)))
            assertEquals(documentFor(8).toJsonString(), store.jsonAt(keyFor(8)))
        }
    }

    /** An open bound means unbounded, in each direction and in both at once. */
    @Test
    fun `an absent bound is unbounded`() {
        withStore { store ->
            for (index in 0 until 50) store.put(keyFor(index), documentFor(index))
            assertEquals(10, store.deleteRange(to = keyFor(9), batchSize = batch))
            assertEquals(10, store.deleteRange(from = keyFor(40), batchSize = batch))
            assertEquals(30, store.deleteRange(batchSize = batch), "everything that is left")
            assertEquals(0, store.deleteRange(batchSize = batch), "and it converges")
        }
    }

    /**
     * A snapshot taken before the delete still sees every document.
     *
     * The MVCC guarantee, asserted against the one operation whose whole job is to remove things.
     * This is also what stops the delete loop from being written against the live tree: a scan that
     * ignored its snapshot would be racing a compaction it cannot see.
     */
    @Test
    fun `a snapshot taken before the delete is unaffected`() {
        withStore { store ->
            for (index in 0 until 200) store.put(keyFor(index), documentFor(index))
            store.flush()

            store.snapshot().use { before ->
                assertEquals(100, store.deleteRange(keyFor(50), keyFor(149), batch))

                for (index in 0 until 200) {
                    assertEquals(
                        documentFor(index).toJsonString(),
                        store.get(keyFor(index), before)?.toJsonString(),
                        "document $index at the older snapshot",
                    )
                }
                // And the live view has lost them, at the same moment.
                assertNull(store.jsonAt(keyFor(50)))
            }
        }
    }

    /**
     * Keys written *during* the range's lifetime are not deleted by a call that preceded them.
     *
     * The snapshot is taken when `deleteRange` is called, so the range is emptied as of that moment.
     * Without it a long-running delete over a busy range would never finish — it would keep finding
     * keys a writer had added behind it.
     */
    @Test
    fun `the range is emptied as of the call, not as of its completion`() {
        withStore { store ->
            for (index in 0 until 100) store.put(keyFor(index), documentFor(index))
            assertEquals(100, store.deleteRange(batchSize = batch))

            store.put(keyFor(42), documentFor(42))
            assertEquals(documentFor(42).toJsonString(), store.jsonAt(keyFor(42)), "written after the delete")
        }
    }

    /** An empty range, an inverted range and a range over nothing are all zero rather than an error. */
    @Test
    fun `a range with nothing in it deletes nothing`() {
        withStore { store ->
            for (index in 0 until 10) store.put(keyFor(index), documentFor(index))

            assertEquals(0, store.deleteRange(keyFor(100), keyFor(200), batch), "beyond every key")
            assertEquals(0, store.deleteRange(keyFor(5), keyFor(4), batch), "an inverted range")
            assertEquals(10, store.deleteRange(batchSize = batch))
            assertEquals(0, store.deleteRange(batchSize = batch), "and now there is nothing left")
        }
    }

    /** The tombstones survive a reopen, which is the difference between a delete and a filter. */
    @Test
    fun `deletions survive a reopen`() {
        val directory = scratch(root)
        DocumentStore.open(directory, options()).use { store ->
            for (index in 0 until 200) store.put(keyFor(index), documentFor(index))
            assertEquals(100, store.deleteRange(keyFor(0), keyFor(99), batch))
        }
        DocumentStore.open(directory, options()).use { store ->
            assertRange(store, deletedFrom = 0, deletedTo = 99, total = 200)
        }
    }

    /**
     * Asserts the live store against the model: everything outside `[deletedFrom, deletedTo]` is
     * present and unchanged, everything inside is gone, and a full scan agrees with both.
     */
    private fun assertRange(store: DocumentStore, deletedFrom: Int, deletedTo: Int, total: Int) {
        val expected = (0 until total).filter { it < deletedFrom || it > deletedTo }

        for (index in 0 until total) {
            val json = store.jsonAt(keyFor(index))
            if (index in deletedFrom..deletedTo) {
                assertNull(json, "document $index is inside the deleted range")
            } else {
                assertEquals(documentFor(index).toJsonString(), json, "document $index is outside it")
            }
        }

        // The scan is the second oracle: a point lookup consults the memtable and then the levels,
        // while a scan merges every cursor — so a tombstone the merge collapses wrongly would show
        // up here and nowhere above.
        val scanned = ArrayList<Key>()
        store.scan().use { cursor -> while (cursor.next()) scanned += cursor.key }
        assertEquals(expected.map(::keyFor), scanned, "the scan and the point lookups must agree")
        assertTrue(expected.isNotEmpty(), "the fixture must leave something behind")
    }

    private fun withStore(body: (DocumentStore) -> Unit) {
        DocumentStore.open(scratch(root), options()).use(body)
    }
}
