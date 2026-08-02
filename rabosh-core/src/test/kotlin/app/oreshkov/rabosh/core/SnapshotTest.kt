package app.oreshkov.rabosh.core

import app.oreshkov.rabosh.variant.Variant
import app.oreshkov.rabosh.variant.toJsonString
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * A snapshot is a view that does not move.
 *
 * What it has to survive is everything the engine does to its own data behind the reader's back: new
 * writes, a memtable being sealed, a flush that turns that memtable into a file, and a compaction
 * that merges the file away and deletes it. Each of those is a different mechanism keeping the view
 * alive — the sequence bound, the pinned memtables, the reference on the version — and this is
 * where all three are exercised together.
 */
class SnapshotTest {

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
    fun `a snapshot is unchanged by writes, flushes and compactions`() {
        withStore { store ->
            for (index in 0 until 200) store.put(keyFor(index), documentFor(index))

            store.snapshot().use { snapshot ->
                // Everything the store can do to itself, while the snapshot is open.
                for (index in 0 until 200) store.put(keyFor(index), documentFor(index + 1000))
                store.delete(keyFor(7))
                store.flush()
                store.compact()
                for (index in 200 until 400) store.put(keyFor(index), documentFor(index))
                store.flush()
                store.compact()

                for (index in 0 until 200) {
                    assertEquals(
                        documentFor(index).toString(),
                        store.get(keyFor(index), snapshot).toString(),
                        "the snapshot moved at ${keyFor(index)}",
                    )
                }
                assertNull(store.get(keyFor(250), snapshot), "a key written later must not be visible")
                // The store itself has moved on: the key the snapshot still sees is deleted now.
                assertNull(store.get(keyFor(7)))
            }
        }
    }

    /** A scan through a snapshot sees the same fixed view a point lookup does. */
    @Test
    fun `a scan through a snapshot sees the snapshot`() {
        withStore { store ->
            for (index in 0 until 50) store.put(keyFor(index), documentFor(index))
            store.snapshot().use { snapshot ->
                val before = scanKeys(store, snapshot)
                for (index in 50 until 100) store.put(keyFor(index), documentFor(index))
                store.flush()
                store.compact()
                assertEquals(before, scanKeys(store, snapshot))
                assertEquals(50, before.size)
                assertEquals(100, scanKeys(store, null).size, "the store itself has a hundred")
            }
        }
    }

    /**
     * A snapshot holds back the versions compaction would drop.
     *
     * Without it, "the view does not move" would be true only until the next merge — which is
     * exactly when it stops being observable and starts being a bug.
     */
    @Test
    fun `an open snapshot keeps superseded versions alive through a compaction`() {
        withStore { store ->
            for (index in 0 until 100) store.put(keyFor(index), documentFor(index))
            store.flush()

            store.snapshot().use { snapshot ->
                repeat(6) { round ->
                    for (index in 0 until 100) store.put(keyFor(index), documentFor(index + round * 1000 + 1))
                    store.flush()
                    store.compact()
                }
                for (index in 0 until 100) {
                    assertEquals(
                        documentFor(index).toString(),
                        store.get(keyFor(index), snapshot).toString(),
                    )
                }
            }
        }
    }

    @Test
    fun `stats count live snapshots`() {
        withStore { store ->
            store.put(keyFor(1), documentFor(1))
            assertEquals(0, store.stats.liveSnapshots)
            val first = store.snapshot()
            val second = store.snapshot()
            assertEquals(2, store.stats.liveSnapshots, "two snapshots at the same sequence")
            first.close()
            assertEquals(1, store.stats.liveSnapshots, "closing one must not free the other")
            second.close()
            assertEquals(0, store.stats.liveSnapshots)
            // Closing twice is a no-op, not a double release.
            second.close()
            assertEquals(0, store.stats.liveSnapshots)
        }
    }

    @Test
    fun `a closed snapshot refuses to be read`() {
        withStore { store ->
            store.put(keyFor(1), documentFor(1))
            val snapshot = store.snapshot()
            snapshot.close()
            assertFailsWith<StoreClosedException> { store.get(keyFor(1), snapshot) }
            assertFailsWith<StoreClosedException> { store.scan(snapshot = snapshot) }
        }
    }

    /**
     * A batch is never half-visible through one view of the store.
     *
     * The gap between the sequence a commit reaches and the sequence a read is bounded by is what
     * buys this, and it costs one volatile write per commit: the log and the memtable are updated
     * first, and only then is the bound published. Phase 3 documented the absence of it as a known
     * limitation — a batch was atomic for durability but not for visibility — and a snapshot would
     * have made that limitation permanent, so it is closed here.
     *
     * **The unit of atomicity is one bound, not one call.** Eight separate [DocumentStore.get] calls
     * are eight reads at eight sequence numbers, and a batch landing between two of them is not a
     * torn read, it is two reads. The guarantee is about a view, so the reader takes one.
     *
     * The reader spins rather than sleeping: the window under test is the time it takes to insert
     * eight entries into a skip list, and a sleep would step straight over it.
     */
    @Test
    fun `a batch is never half-visible through one snapshot`() {
        withStore { store ->
            val keys = (0 until 8).map { Key.of("batch:$it") }
            keys.forEach { store.put(it, Variant.fromJson("""{"round":0}""")) }

            val stop = AtomicBoolean(false)
            val torn = AtomicReference<String?>(null)
            val started = CountDownLatch(1)
            val reader = Thread.ofPlatform().name("snapshot-reader").start {
                started.countDown()
                while (!stop.get()) {
                    val rounds = store.snapshot().use { snapshot ->
                        keys.map { store.get(it, snapshot)?.select("$.round")?.longValue() }
                    }
                    if (rounds.distinct().size != 1) {
                        torn.compareAndSet(null, "saw a mix of rounds: $rounds")
                        return@start
                    }
                }
            }
            started.await(5, TimeUnit.SECONDS)

            for (round in 1..200) {
                val batch = WriteBatch()
                keys.forEach { batch.put(it, Variant.fromJson("""{"round":$round}""")) }
                store.write(batch)
            }
            stop.set(true)
            reader.join(10_000)

            assertNull(torn.get(), torn.get())
        }
    }

    private fun scanKeys(store: DocumentStore, snapshot: Snapshot?): List<String> =
        store.scan(snapshot = snapshot).use { cursor ->
            val keys = ArrayList<String>()
            while (cursor.next()) {
                // Touch the document too: a cursor that hands back a key without a readable
                // document would pass a key-only comparison.
                assertTrue(cursor.document.toJsonString().isNotEmpty())
                keys += cursor.key.toString()
            }
            keys
        }

    private fun withStore(body: (DocumentStore) -> Unit) {
        DocumentStore.open(scratch(root, "snapshot"), options).use(body)
    }
}
