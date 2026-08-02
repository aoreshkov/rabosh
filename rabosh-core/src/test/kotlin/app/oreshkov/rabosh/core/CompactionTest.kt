package app.oreshkov.rabosh.core

import app.oreshkov.rabosh.variant.Variant
import app.oreshkov.rabosh.variant.toJsonString
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Levelled compaction: the invariants that make the read path legitimate, and the one that makes a
 * deletion permanent.
 *
 * **Levels below zero must not overlap.** Every lookup at level 1 and deeper is a binary search over
 * key ranges, which is only an answer if at most one segment per level can hold a key. An overlap
 * would not fail loudly; it would make a lookup miss a document that is on disk.
 *
 * **A tombstone may only be dropped at the bottom.** Dropping one while a deeper level still holds
 * the key it hides deletes the deletion, and the document comes back. It is the classic LSM bug and
 * it is invisible until exactly the wrong compaction happens, so it is tested by making that
 * compaction happen on purpose.
 */
class CompactionTest {

    @TempDir
    lateinit var root: Path

    /** Small everything, so a few hundred documents build a tree with real levels. */
    private val options = StoreOptions(
        durability = Durability.BUFFERED,
        segmentMaxBytes = 2 * 1024,
        blockSize = 256,
        l0CompactionTrigger = 2,
        baseLevelBytes = 4 * 1024,
        backgroundMaintenance = false,
    )

    @Test
    fun `levels below zero never overlap`() {
        val directory = scratch(root, "levels")
        DocumentStore.open(directory, options).use { store ->
            repeat(12) { round ->
                for (index in 0 until 60) {
                    store.put(keyFor(round * 60 + index), documentFor(index))
                }
                store.flush()
                store.compact()
                assertNonOverlapping(store)
            }
            assertTrue(
                store.stats.segmentsPerLevel.drop(1).sum() > 0,
                "nothing was ever compacted out of level 0: ${store.stats.segmentsPerLevel}",
            )
        }
    }

    /**
     * The answers do not move.
     *
     * Compaction rewrites every byte of the data it touches, so "an index may change query speed,
     * never query answers" applies to it as much as to a real index: the whole store is compared
     * before, during and after.
     */
    @Test
    fun `compaction does not change what the store returns`() {
        val directory = scratch(root, "answers")
        DocumentStore.open(directory, options).use { store ->
            for (index in 0 until 400) store.put(keyFor(index), documentFor(index % 7))
            for (index in 0 until 400 step 3) store.delete(keyFor(index))
            store.flush()

            val before = contents(store)
            assertTrue(before.isNotEmpty())

            repeat(6) {
                store.compact()
                assertEquals(before, contents(store), "a compaction changed the store's contents")
            }

            // And after a reopen, which reads it all back off the platter rather than out of any
            // memtable that happened to survive.
            store.close()
            DocumentStore.open(directory, options).use { reopened ->
                assertEquals(before, contents(reopened))
            }
        }
    }

    /**
     * A deleted document does not come back, however much merging happens on top of it.
     *
     * The shape here is the one that resurrects: the document is written and pushed *down* into the
     * tree first, and only then deleted, so the tombstone and the document it hides live at
     * different levels and every compaction is an opportunity to drop the wrong one.
     */
    @Test
    fun `a deletion is not undone by compaction`() {
        val directory = scratch(root, "resurrect")
        DocumentStore.open(directory, options).use { store ->
            val doomed = (0 until 40).map { keyFor(it) }
            for (index in 0 until 400) store.put(keyFor(index), documentFor(index))
            store.flush()
            repeat(4) { store.compact() }

            doomed.forEach(store::delete)
            store.flush()

            repeat(8) {
                store.compact()
                for (key in doomed) {
                    assertNull(store.get(key), "$key came back after a compaction")
                }
            }

            store.close()
            DocumentStore.open(directory, options).use { reopened ->
                for (key in doomed) assertNull(reopened.get(key), "$key came back after a reopen")
                // And everything that was not deleted is still there.
                for (index in 40 until 400) {
                    assertEquals(documentFor(index).toString(), reopened.get(keyFor(index)).toString())
                }
            }
        }
    }

    /** Compaction collapses superseded versions, which is what it is for. */
    @Test
    fun `repeated writes to one key collapse to one version`() {
        val directory = scratch(root, "collapse")
        DocumentStore.open(directory, options).use { store ->
            repeat(30) { round ->
                for (index in 0 until 20) {
                    store.put(keyFor(index), Variant.fromJson("""{"round":$round,"index":$index}"""))
                }
                store.flush()
            }
            val beforeBytes = store.stats.segmentBytes
            repeat(6) { store.compact() }

            assertTrue(
                store.stats.segmentBytes < beforeBytes,
                "compaction kept ${store.stats.segmentBytes} of $beforeBytes bytes, collapsing nothing",
            )
            for (index in 0 until 20) {
                // Fields come back in the specification's order — lexicographic by name — not in
                // the order they were written.
                assertEquals(
                    """{"index":$index,"round":29}""",
                    store.get(keyFor(index))?.toJsonString(),
                )
            }
        }
    }

    /** The predicate the tombstone rule rests on, tested where it can be seen directly. */
    @Test
    fun `a key present in a deeper level is reported as such`() {
        val directory = scratch(root, "deeper")
        DocumentStore.open(directory, options).use { store ->
            // Several flushes, because one segment at level 0 is below the compaction trigger and
            // would leave the tree exactly where it started.
            repeat(6) { round ->
                for (index in 0 until 70) store.put(keyFor(round * 70 + index), documentFor(index))
                store.flush()
            }
            repeat(6) { store.compact() }

            val version = store.liveVersion
            val deepest = version.levels.indexOfLast { it.isNotEmpty() }
            assertTrue(deepest >= 1, "the tree never got past level 0: ${store.stats.segmentsPerLevel}")

            // Below the deepest level nothing can hold anything, whatever the key.
            assertTrue(!version.mayContainBelow(keyFor(10), deepest))
            // And a key that is in the deepest level is found from above it.
            val held = version.levels[deepest].first().metadata.smallestKey
            assertTrue(version.mayContainBelow(held, deepest - 1))
        }
    }

    private fun assertNonOverlapping(store: DocumentStore) {
        val version = store.liveVersion
        for (level in 1 until version.levels.size) {
            val tables = version.levels[level]
            for (index in 1 until tables.size) {
                val previous = tables[index - 1].metadata
                val current = tables[index].metadata
                assertTrue(
                    previous.largestKey < current.smallestKey,
                    "level $level overlaps: ${previous.smallestKey}..${previous.largestKey} " +
                        "then ${current.smallestKey}..${current.largestKey}",
                )
            }
        }
    }

    private fun contents(store: DocumentStore): List<Pair<Key, String>> =
        store.scan().use { cursor ->
            val entries = ArrayList<Pair<Key, String>>()
            while (cursor.next()) entries += cursor.key to cursor.document.toJsonString()
            entries
        }
}
