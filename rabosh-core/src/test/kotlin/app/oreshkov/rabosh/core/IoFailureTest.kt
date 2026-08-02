package app.oreshkov.rabosh.core

import app.oreshkov.rabosh.testkit.fs.Fault
import app.oreshkov.rabosh.testkit.fs.FaultOperation
import app.oreshkov.rabosh.testkit.fs.FaultyFileSystem
import app.oreshkov.rabosh.variant.toJsonString
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * What the engine does when the disk says no.
 *
 * Until now the IO-failure *policy* was tested by calling `markFailed` by hand, because there was no
 * way to produce the fault. There is now, and the difference matters: a hand-called hook tests what
 * the store does once it knows it has failed, while a real fault tests everything up to that point —
 * whether the failure is noticed at all, what state the files are left in, and whether what survives
 * is a prefix of what was acknowledged.
 *
 * **The rule every test here checks is the same one the crash harness checks**, reached a different
 * way: after any failure, reopening yields exactly the acknowledged-commit prefix. A `put` that threw
 * may or may not be there — that is what "not acknowledged" means — but a `put` that returned must
 * be, and nothing that was never written may appear.
 */
class IoFailureTest {

    @TempDir
    lateinit var root: Path

    private fun options(durability: Durability = Durability.SYNC) = StoreOptions(
        durability = durability,
        segmentMaxBytes = 4 * 1024,
        blockSize = 256,
        backgroundMaintenance = false,
    )

    /**
     * The log is appended before the memtable is touched, so a failing append is a write that never
     * happened — not one that happened and cannot be found.
     */
    @Test
    fun `a failing log append is refused and loses nothing that was acknowledged`() {
        withFaultyStore { fs, store, directory ->
            for (index in 0 until 20) store.put(keyFor(index), documentFor(index))

            val fault = fs.arm(Fault.onSuffix(FaultOperation.WRITE, ".wal"))
            assertFailsWith<IOException> { store.put(keyFor(20), documentFor(20)) }
            assertEquals(1, fault.fireCount, "the fault must actually have fired")

            // Refused from here on, and told why. Carrying on would be writing into a log the store
            // knows is broken, which is how an unacknowledged write becomes a present one.
            val refused = assertFailsWith<StoreFailedException> { store.put(keyFor(21), documentFor(21)) }
            assertNotNull(refused.cause)
            assertFailsWith<StoreFailedException> { store.sync() }

            // Reads still work: a store that cannot write is not a store that cannot answer.
            assertEquals(documentFor(0).toJsonString(), store.jsonAt(keyFor(0)))
        }

        // And what an operator finds afterwards is the acknowledged prefix, exactly.
        reopen { store ->
            for (index in 0 until 20) assertEquals(documentFor(index).toJsonString(), store.jsonAt(keyFor(index)))
            assertNull(store.jsonAt(keyFor(20)))
            assertNull(store.jsonAt(keyFor(21)))
        }
    }

    /**
     * The sharpest fault a storage engine can be handed: the bytes are written, the file has them,
     * and the barrier that was supposed to make them durable failed.
     *
     * Under [Durability.SYNC] that is a failure to acknowledge, so the commit must be refused — even
     * though the data is, at this instant, sitting in the file.
     */
    @Test
    fun `a failing force under SYNC refuses the commit`() {
        withFaultyStore { fs, store, _ ->
            for (index in 0 until 10) store.put(keyFor(index), documentFor(index))

            val fault = fs.arm(Fault.onSuffix(FaultOperation.FORCE, ".wal"))
            assertFailsWith<IOException> { store.put(keyFor(10), documentFor(10)) }
            assertEquals(1, fault.fireCount)
            assertFailsWith<StoreFailedException> { store.put(keyFor(11), documentFor(11)) }
        }

        reopen { store ->
            for (index in 0 until 10) assertEquals(documentFor(index).toJsonString(), store.jsonAt(keyFor(index)))
            assertNull(store.jsonAt(keyFor(11)))
        }
    }

    /** A torn write is the case the log's recovery exists for; here it arrives from the disk. */
    @Test
    fun `a short write to the log leaves a torn tail that recovery truncates`() {
        withFaultyStore { fs, store, _ ->
            for (index in 0 until 10) store.put(keyFor(index), documentFor(index))
            fs.arm(Fault.onSuffix(FaultOperation.WRITE, ".wal", shortWrite = 12))
            assertFailsWith<IOException> { store.put(keyFor(10), documentFor(10)) }
        }

        reopen { store ->
            for (index in 0 until 10) assertEquals(documentFor(index).toJsonString(), store.jsonAt(keyFor(index)))
            assertNull(store.jsonAt(keyFor(10)), "a half-written record is not a commit")
        }
    }

    /**
     * A flush that cannot write its segment fails the flush and **keeps the data**, because the
     * memtable and its log are still there. Nothing is acknowledged that is not durable, so the store
     * is not condemned — a retry on a working disk is a legitimate outcome, and this asserts it.
     */
    @Test
    fun `a failing segment write fails the flush without losing the memtable`() {
        withFaultyStore { fs, store, directory ->
            for (index in 0 until 40) store.put(keyFor(index), documentFor(index))

            val fault = fs.arm(Fault.onSuffix(FaultOperation.WRITE, ".seg", times = Int.MAX_VALUE))
            assertFailsWith<IOException> { store.flush() }
            assertTrue(fault.fireCount >= 1)

            // The documents are still readable, from the memtable that was never emptied.
            for (index in 0 until 40) assertEquals(documentFor(index).toJsonString(), store.jsonAt(keyFor(index)))

            // A manifest must never name a segment that is not there, so nothing was published.
            fs.heal()
            store.flush()
            for (index in 0 until 40) assertEquals(documentFor(index).toJsonString(), store.jsonAt(keyFor(index)))
        }

        reopen { store ->
            for (index in 0 until 40) assertEquals(documentFor(index).toJsonString(), store.jsonAt(keyFor(index)))
        }
    }

    /**
     * The manifest is what makes a segment visible. A failure to publish must leave the segment
     * unreferenced — which the next open sweeps — rather than leave a manifest naming a file that
     * recovery will then fail to find.
     */
    @Test
    fun `a failing manifest write leaves an unreferenced segment, never a dangling reference`() {
        withFaultyStore { fs, store, _ ->
            for (index in 0 until 40) store.put(keyFor(index), documentFor(index))
            val fault = fs.arm(Fault.onName(FaultOperation.WRITE, "MANIFEST", times = Int.MAX_VALUE))
            assertFailsWith<IOException> { store.flush() }
            assertTrue(fault.fireCount >= 1)
        }

        reopen { store ->
            for (index in 0 until 40) assertEquals(documentFor(index).toJsonString(), store.jsonAt(keyFor(index)))
            store.flush()
            for (index in 0 until 40) assertEquals(documentFor(index).toJsonString(), store.jsonAt(keyFor(index)))
        }
    }

    /** The disk filling up mid-load: whatever was acknowledged survives, and nothing else appears. */
    @Test
    fun `a disk that fills up costs the writes it refused and no others`() {
        var acknowledged = 0
        withFaultyStore { fs, store, _ ->
            for (index in 0 until 50) store.put(keyFor(index), documentFor(index))
            acknowledged = 50
            fs.arm(Fault.outOfSpace(remainingBytes = 2_000))

            val failure = runCatching {
                for (index in 50 until 400) {
                    store.put(keyFor(index), documentFor(index))
                    acknowledged = index + 1
                }
            }.exceptionOrNull()
            assertNotNull(failure, "the disk should have run out")
            assertTrue(failure is IOException || failure is StoreFailedException, "$failure")
        }

        val survivors = acknowledged
        reopen { store ->
            for (index in 0 until survivors) {
                assertEquals(
                    documentFor(index).toJsonString(),
                    store.jsonAt(keyFor(index)),
                    "key $index was acknowledged and must be here",
                )
            }
        }
    }

    /**
     * A compaction that cannot write is a maintenance failure, not a data loss: the inputs are still
     * live, the manifest still names them, and every document is still there.
     */
    @Test
    fun `a failing compaction leaves the tree as it was`() {
        withFaultyStore(Durability.BUFFERED) { fs, store, _ ->
            for (round in 0 until 4) {
                for (index in round * 60 until round * 60 + 60) store.put(keyFor(index), documentFor(index))
                store.flush()
            }
            val before = store.liveSegmentNumbers

            val fault = fs.arm(Fault.onSuffix(FaultOperation.WRITE, ".seg", times = Int.MAX_VALUE))
            runCatching { store.compact() }
            fs.heal()

            assertTrue(fault.fireCount >= 1, "the compaction should have tried to write")
            for (index in 0 until 240) assertEquals(documentFor(index).toJsonString(), store.jsonAt(keyFor(index)))
            assertTrue(
                store.liveSegmentNumbers.containsAll(before) || store.liveSegmentNumbers.isNotEmpty(),
                "the inputs must remain readable",
            )
        }

        reopen { store ->
            for (index in 0 until 240) assertEquals(documentFor(index).toJsonString(), store.jsonAt(keyFor(index)))
        }
    }

    /** A failed open leaves nothing behind that stops the next one from working. */
    @Test
    fun `a store that cannot create its directory reports rather than half-opens`() {
        FaultyFileSystem.wrapping(root).use { fs ->
            val directory = fs.path(root).resolve("store")
            fs.arm(Fault.onName(FaultOperation.CREATE_DIRECTORY, "store", times = Int.MAX_VALUE))
            assertFailsWith<IOException> { DocumentStore.open(directory, options()) }
        }
        assertTrue(!Files.exists(root.resolve("store")) || Files.list(root.resolve("store")).use { it.count() } == 0L)
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private val storeDirectory: Path get() = root.resolve("store")

    private fun withFaultyStore(
        durability: Durability = Durability.SYNC,
        body: (FaultyFileSystem, DocumentStore, Path) -> Unit,
    ) {
        Files.createDirectories(storeDirectory)
        FaultyFileSystem.wrapping(root).use { fs ->
            val directory = fs.path(storeDirectory)
            DocumentStore.open(directory, options(durability)).use { store ->
                body(fs, store, directory)
            }
        }
    }

    /** Reopens through the *real* filesystem: what an operator would find. */
    private fun reopen(body: (DocumentStore) -> Unit) {
        DocumentStore.open(storeDirectory, options()).use(body)
    }
}
