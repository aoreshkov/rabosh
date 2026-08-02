package app.oreshkov.rabosh.core

import app.oreshkov.rabosh.variant.Variant
import app.oreshkov.rabosh.variant.toJsonString
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * The seam the catalog and the index layers attach through.
 *
 * Three properties are asserted here rather than in the catalog's own tests, because they are the
 * core's promises and would otherwise be tested only through something that depends on them: one
 * observation per **distinct key**, not per version; an abandoned segment reported as abandoned; and
 * an observer that throws costing its own segment and nothing else.
 */
class SegmentObserverTest {

    @TempDir
    lateinit var root: Path

    private val options get() = StoreOptions(
        durability = Durability.BUFFERED,
        backgroundMaintenance = false,
    )

    @Test
    fun `a key written three times is observed once`() {
        val recorder = Recorder()
        val directory = scratch(root, "versions")
        DocumentStore.open(directory, withObserver(recorder)).use { store ->
            repeat(3) { round -> store.put(keyFor(1), Variant.fromJson("""{"round":$round}""")) }
            store.put(keyFor(2), documentFor(2))
            store.flush()
        }

        assertEquals(listOf(keyFor(1), keyFor(2)), recorder.keys, "one observation per distinct key")
        // Newest first within a key, so the version that survives is the one that is observed.
        assertEquals("""{"round":2}""", recorder.documents.first())
        assertEquals(1, recorder.completed.size)
        assertEquals(2, recorder.completed.single().distinctKeyCount)
        assertEquals(4, recorder.completed.single().entryCount, "all four versions are still stored")
    }

    @Test
    fun `a tombstone is observed with no document`() {
        val recorder = Recorder()
        val directory = scratch(root, "tombstone")
        DocumentStore.open(directory, withObserver(recorder)).use { store ->
            store.put(keyFor(1), documentFor(1))
            store.delete(keyFor(2))
            store.flush()
        }
        assertEquals(listOf(keyFor(1), keyFor(2)), recorder.keys)
        assertEquals(listOf(true, false), recorder.present, "the deleted key carries no document")
    }

    @Test
    fun `retain names exactly the live segments`() {
        val recorder = Recorder()
        val directory = scratch(root, "retain")
        DocumentStore.open(directory, withObserver(recorder)).use { store ->
            for (batch in 0 until 4) {
                for (index in 0 until 50) store.put(keyFor(batch * 50 + index), documentFor(index))
                store.flush()
            }
            val afterFlushes = recorder.live
            assertEquals(store.liveVersion.segments().mapTo(HashSet()) { it.number }, afterFlushes)

            store.compact()
            assertEquals(store.liveVersion.segments().mapTo(HashSet()) { it.number }, recorder.live)
            assertTrue(recorder.live != afterFlushes, "the compaction changed which files are live")
        }
    }

    @Test
    fun `an observer that throws costs its segment and nothing else`() {
        // Derived data must not be able to take down the engine: a document is not recoverable and a
        // sketch always is.
        val recorder = object : Recorder() {
            override fun observe(userKey: Key, sequence: Long, document: Variant?) {
                super.observe(userKey, sequence, document)
                if (keys.size == 3) throw IllegalStateException("deliberate")
            }
        }
        val directory = scratch(root, "throwing")
        DocumentStore.open(directory, withObserver(recorder)).use { store ->
            for (index in 0 until 20) store.put(keyFor(index), documentFor(index))
            store.flush()

            assertEquals(20, (0 until 20).count { store.get(keyFor(it)) != null }, "every write survived")
        }
        assertEquals(1, recorder.failures.size, "the failure was reported to the observer")
        assertEquals(0, recorder.completed.size, "and the segment's observation was abandoned")
        assertEquals(1, recorder.abandoned)

        // The store itself is intact and reopens.
        DocumentStore.open(directory, options).use { store ->
            assertNotNull(store.get(keyFor(19)))
        }
    }

    @Test
    fun `backfill replays segments through the same contract`() {
        val directory = scratch(root, "backfill")
        DocumentStore.open(directory, options).use { store ->
            for (index in 0 until 120) store.put(keyFor(index), documentFor(index))
            store.compact()
        }

        val recorder = Recorder()
        DocumentStore.open(directory, options).use { store ->
            store.backfill(recorder)
            assertEquals((0 until 120).map(::keyFor), recorder.keys, "every key, in order, exactly once")
            assertEquals(store.liveVersion.segments().size, recorder.completed.size)
            assertEquals(store.liveVersion.segments().mapTo(HashSet()) { it.number }, recorder.live)
        }
    }

    @Test
    fun `backfill skips a segment the observer already covers`() {
        val directory = scratch(root, "skip")
        DocumentStore.open(directory, options).use { store ->
            for (index in 0 until 60) store.put(keyFor(index), documentFor(index))
            store.compact()

            val recorder = object : Recorder() {
                override fun beginSegment(segmentNumber: Long): SegmentObservation? = null
            }
            store.backfill(recorder)
            assertTrue(recorder.keys.isEmpty(), "a null observation reads no bytes")
        }
    }

    private fun withObserver(observer: SegmentObserver): StoreOptions = StoreOptions(
        durability = Durability.BUFFERED,
        backgroundMaintenance = false,
        segmentObserver = observer,
    )

    private open class Recorder : SegmentObserver, SegmentObservation {
        val keys = CopyOnWriteArrayList<Key>()
        val present = CopyOnWriteArrayList<Boolean>()
        val documents = CopyOnWriteArrayList<String>()
        val completed = CopyOnWriteArrayList<SegmentSummary>()
        val failures = CopyOnWriteArrayList<Throwable>()

        @Volatile
        var live: Set<Long> = emptySet()

        @Volatile
        var abandoned: Int = 0

        override fun beginSegment(segmentNumber: Long): SegmentObservation? = this

        override fun retain(liveSegments: Set<Long>) {
            live = liveSegments
        }

        override fun observerFailed(cause: Throwable) {
            failures += cause
        }

        override fun observe(userKey: Key, sequence: Long, document: Variant?) {
            keys += userKey
            present += (document != null)
            document?.let { documents += it.toJsonString() }
        }

        override fun complete(summary: SegmentSummary) {
            completed += summary
        }

        override fun abandon() {
            abandoned++
        }
    }
}
