package app.oreshkov.rabosh.core

import app.oreshkov.rabosh.variant.Variant
import app.oreshkov.rabosh.variant.toJsonString
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * The partial scan, and the contract that makes it usable rather than merely available.
 *
 * A query plan reads the segments an index does not cover and answers the rest from sidecars, so the
 * two halves have to partition something. They partition [Snapshot.segmentNumbers] — the version this
 * view pinned — and not the store's live set, which moves underneath a long-lived snapshot.
 *
 * The sharp test here is the last one. `scanSegments` collapses versions *within the sources it was
 * given*, so over a partition the parts' **keys** account for everything a full scan sees while the
 * parts' **documents** individually do not. That is what makes its output a candidate, and a caller
 * that treated it as an answer would report a stale document without anything failing.
 */
class ScanSegmentsTest {

    @TempDir
    lateinit var root: Path

    private val options = StoreOptions(
        durability = Durability.BUFFERED,
        segmentMaxBytes = 2 * 1024,
        blockSize = 256,
        backgroundMaintenance = false,
    )

    @Test
    fun `a partition of the snapshot's segments accounts for every key a full scan sees`() {
        withSpreadStore { store ->
            store.snapshot().use { snapshot ->
                val segments = snapshot.segmentNumbers.sorted()
                assertTrue(segments.size >= 3, "the fixture should span several segments")
                val left = segments.filterIndexed { index, _ -> index % 2 == 0 }.toSet()
                val right = segments.toSet() - left

                val whole = scan(store, snapshot).map { it.first }
                val parts = (
                    scanSegments(store, snapshot, left, includeUnflushed = true) +
                        scanSegments(store, snapshot, right, includeUnflushed = false)
                    ).map { it.first }

                assertContentEquals(whole, parts.distinct().sorted(), "keys must be accounted for")
            }
        }
    }

    /**
     * The other half of the same fixture, and the reason the KDoc says *candidate*: a part that holds
     * an older version of a key reports that older version, because nothing in its sources says
     * otherwise. Asserted rather than trusted — a caller reading this as an answer gets a stale
     * document and no failure anywhere.
     */
    @Test
    fun `a part reports the newest version it holds, which need not be the visible one`() {
        DocumentStore.open(scratch(root, "partial"), options).use { store ->
            store.put(keyFor(1), Variant.fromJson("""{"v":1}"""))
            store.flush()
            store.put(keyFor(1), Variant.fromJson("""{"v":2}"""))
            store.flush()

            store.snapshot().use { snapshot ->
                val segments = snapshot.segmentNumbers.sorted()
                assertEquals(2, segments.size)
                val older = scanSegments(store, snapshot, setOf(segments.first()), includeUnflushed = false)
                val newer = scanSegments(store, snapshot, setOf(segments.last()), includeUnflushed = false)

                assertEquals("""{"v":1}""", older.single().second)
                assertEquals("""{"v":2}""", newer.single().second)
                assertNotEquals(older.single().second, scan(store, snapshot).single().second)
                assertEquals(newer.single().second, scan(store, snapshot).single().second)
            }
        }
    }

    @Test
    fun `segment numbers the snapshot's version does not hold are ignored`() {
        withSpreadStore { store ->
            store.snapshot().use { snapshot ->
                val present = snapshot.segmentNumbers
                val absent = setOf(present.max() + 1_000, present.max() + 1_001)
                assertContentEquals(emptyList(), scanSegments(store, snapshot, absent, includeUnflushed = false))
                assertContentEquals(
                    scanSegments(store, snapshot, present, includeUnflushed = false),
                    scanSegments(store, snapshot, present + absent, includeUnflushed = false),
                )
            }
        }
    }

    @Test
    fun `includeUnflushed decides whether the memtables are read at all`() {
        DocumentStore.open(scratch(root, "partial"), options).use { store ->
            store.put(keyFor(1), documentFor(1))
            store.flush()
            store.put(keyFor(2), documentFor(2))

            store.snapshot().use { snapshot ->
                assertTrue(snapshot.hasUnflushedDocuments)
                val segments = snapshot.segmentNumbers
                assertContentEquals(
                    listOf(keyFor(1), keyFor(2)),
                    scanSegments(store, snapshot, segments, includeUnflushed = true).map { it.first },
                )
                assertContentEquals(
                    listOf(keyFor(1)),
                    scanSegments(store, snapshot, segments, includeUnflushed = false).map { it.first },
                )
                assertContentEquals(
                    listOf(keyFor(2)),
                    scanSegments(store, snapshot, emptySet(), includeUnflushed = true).map { it.first },
                )
            }
        }
    }

    @Test
    fun `key bounds are honoured, both inclusive`() {
        withSpreadStore { store ->
            store.snapshot().use { snapshot ->
                val segments = snapshot.segmentNumbers
                val bounded = scanSegments(
                    store,
                    snapshot,
                    segments,
                    includeUnflushed = true,
                    from = keyFor(40),
                    to = keyFor(60),
                ).map { it.first }
                val whole = scan(store, snapshot).map { it.first }
                assertContentEquals(whole.filter { it >= keyFor(40) && it <= keyFor(60) }, bounded)
            }
        }
    }

    /**
     * A snapshot keeps the segments it pinned, so its universe is stable while the store's is not.
     * This is the arrangement a query plan has to survive: a partition taken over the live set here
     * would name a segment the view has never seen and miss every one it holds.
     */
    @Test
    fun `the snapshot's universe survives a compaction that replaces every segment`() {
        val compacting = StoreOptions(
            durability = Durability.BUFFERED,
            segmentMaxBytes = 2 * 1024,
            blockSize = 256,
            l0CompactionTrigger = 1,
            baseLevelBytes = 1024 * 1024,
            backgroundMaintenance = false,
        )
        DocumentStore.open(scratch(root, "partial"), compacting).use { store ->
            for (index in 0 until 120) store.put(keyFor(index), documentFor(index))
            store.flush()

            store.snapshot().use { snapshot ->
                val pinned = snapshot.segmentNumbers
                store.compact()
                assertFalse(store.liveSegmentNumbers.any { it in pinned }, "the fixture should replace every segment")

                assertEquals(pinned, snapshot.segmentNumbers, "a snapshot's universe does not move")
                assertContentEquals(
                    scan(store, snapshot).map { it.first },
                    scanSegments(store, snapshot, pinned, includeUnflushed = true).map { it.first },
                )
                assertContentEquals(
                    emptyList(),
                    scanSegments(store, snapshot, store.liveSegmentNumbers, includeUnflushed = false),
                )
            }
        }
    }

    @Test
    fun `a closed snapshot is refused rather than read`() {
        DocumentStore.open(scratch(root, "partial"), options).use { store ->
            store.put(keyFor(1), documentFor(1))
            store.flush()
            val snapshot = store.snapshot()
            val segments = snapshot.segmentNumbers
            snapshot.close()
            kotlin.test.assertFailsWith<StoreClosedException> { store.scanSegments(segments, snapshot) }
            kotlin.test.assertFailsWith<StoreClosedException> { snapshot.segmentNumbers }
            kotlin.test.assertFailsWith<StoreClosedException> { snapshot.hasUnflushedDocuments }
        }
    }

    private fun scan(store: DocumentStore, snapshot: Snapshot): List<Pair<Key, String>> =
        store.scan(snapshot = snapshot).use(::drain)

    private fun scanSegments(
        store: DocumentStore,
        snapshot: Snapshot,
        segments: Set<Long>,
        includeUnflushed: Boolean,
        from: Key? = null,
        to: Key? = null,
    ): List<Pair<Key, String>> =
        store.scanSegments(segments, snapshot, includeUnflushed, from, to).use(::drain)

    private fun drain(cursor: DocumentCursor): List<Pair<Key, String>> {
        val entries = ArrayList<Pair<Key, String>>()
        while (cursor.next()) entries += cursor.key to cursor.document.toJsonString()
        return entries
    }

    /** Data across several segments, with overwrites and a deletion, plus an unflushed tail. */
    private fun withSpreadStore(body: (DocumentStore) -> Unit) {
        DocumentStore.open(scratch(root, "partial"), options).use { store ->
            for (index in 0 until 100) {
                store.put(keyFor(index), documentFor(index))
                if (index % 25 == 24) store.flush()
            }
            for (index in 0 until 100 step 10) store.put(keyFor(index), Variant.fromJson("""{"index":$index}"""))
            store.delete(keyFor(7))
            store.flush()
            for (index in 100 until 110) store.put(keyFor(index), documentFor(index))
            body(store)
        }
    }
}
