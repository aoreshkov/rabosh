package app.oreshkov.rabosh.core

import app.oreshkov.rabosh.variant.Variant
import app.oreshkov.rabosh.variant.VariantBuilder
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class DocumentStoreTest {

    @TempDir
    lateinit var root: Path

    @Test
    fun `stores and reads a document without copying it`() {
        withStore { store ->
            store.put(Key.of("user:1"), Variant.fromJson("""{"user":{"name":"ada","tags":["x","y"]}}"""))

            val found = assertNotNull(store.get(Key.of("user:1")))
            assertEquals("y", found.select("$.user.tags[1]")?.stringValue())
            assertEquals(1, store.sequence)
        }
    }

    @Test
    fun `a later put replaces an earlier one`() {
        withStore { store ->
            store.put(Key.of("k"), Variant.fromJson("""{"v":1}"""))
            store.put(Key.of("k"), Variant.fromJson("""{"v":2}"""))
            assertEquals("""{"v":2}""", store.jsonAt(Key.of("k")))
        }
    }

    @Test
    fun `a deleted key reads as absent and stays absent across a reopen`() {
        val directory = scratch(root)
        DocumentStore.open(directory).use { store ->
            store.put(Key.of("k"), Variant.fromJson("""{"v":1}"""))
            store.delete(Key.of("k"))
            assertNull(store.get(Key.of("k")))
        }
        DocumentStore.open(directory).use { store ->
            assertNull(store.get(Key.of("k")), "the tombstone has to be replayed, not just the put")
        }
    }

    @Test
    fun `deleting an absent key is legal`() {
        withStore { store ->
            store.delete(Key.of("never-written"))
            assertNull(store.get(Key.of("never-written")))
            assertEquals(1, store.sequence, "a tombstone is still a commit")
        }
    }

    @Test
    fun `a batch commits every operation under consecutive sequence numbers`() {
        withStore { store ->
            val batch = WriteBatch()
                .put(Key.of("a"), Variant.fromJson("""{"v":1}"""))
                .put(Key.of("b"), Variant.fromJson("""{"v":2}"""))
                .delete(Key.of("c"))
            assertEquals(3, batch.size)

            store.write(batch)
            assertEquals(3, store.sequence)
            assertEquals("""{"v":1}""", store.jsonAt(Key.of("a")))
            assertEquals("""{"v":2}""", store.jsonAt(Key.of("b")))
        }
    }

    @Test
    fun `an empty batch does nothing`() {
        withStore { store ->
            val before = store.stats
            store.write(WriteBatch())
            assertEquals(before.lastSequence, store.stats.lastSequence)
            assertEquals(before.logBytes, store.stats.logBytes, "not even a record header is written")
        }
    }

    @Test
    fun `a batch copies the document, so a reused builder cannot change it`() {
        // A Variant is a view over a buffer, and VariantBuilder reuses its buffer for the next
        // document. A batch that kept the view would commit whatever was in the buffer at commit
        // time — which is not what the caller wrote, and not detectable afterwards.
        withStore { store ->
            val builder = VariantBuilder()
            builder.startObject()
            builder.field("v")
            builder.appendLong(1)
            builder.endObject()
            val first = builder.buildVariant()

            val batch = WriteBatch().put(Key.of("k"), first)

            builder.reset()
            builder.startObject()
            builder.field("v")
            builder.appendLong(999)
            builder.endObject()
            builder.buildVariant()

            store.write(batch)
            assertEquals("""{"v":1}""", store.jsonAt(Key.of("k")))
        }
    }

    /**
     * Maintenance is off here so the sealed memtables stay sealed.
     *
     * With it on, this store would flush each one to a segment and delete the log behind it, which
     * is what `SegmentLifecycleTest` covers. What is under test in this file is the in-memory half
     * of the read path, and it only exists while nothing has drained it.
     */
    @Test
    fun `reopening replays every log the directory holds`() {
        val directory = scratch(root)
        val options = StoreOptions(backgroundMaintenance = false)
        DocumentStore.open(directory, options).use { store ->
            for (index in 0 until 5) {
                store.put(keyFor(index), documentFor(index))
                store.rotate()
            }
            // Five rotations, so the sixth log is the one now open and the first five are sealed.
            assertEquals(6, store.stats.logNumber, "each rotation starts a new log")
            assertEquals(5, store.stats.sealedMemtables)
        }

        DocumentStore.open(directory, options).use { store ->
            for (index in 0 until 5) {
                assertEquals(documentFor(index).toString(), store.get(keyFor(index)).toString())
            }
            assertEquals(5, store.sequence)
        }
    }

    @Test
    fun `reads walk sealed memtables newest first`() {
        withStore(StoreOptions(backgroundMaintenance = false)) { store ->
            store.put(Key.of("k"), Variant.fromJson("""{"v":1}"""))
            store.rotate()
            store.put(Key.of("k"), Variant.fromJson("""{"v":2}"""))
            store.rotate()
            store.put(Key.of("other"), Variant.fromJson("""{"v":3}"""))

            assertEquals(2, store.stats.sealedMemtables)
            assertEquals("""{"v":2}""", store.jsonAt(Key.of("k")), "the newer sealed version must win")
        }
    }

    @Test
    fun `a tombstone in a newer memtable hides a value in an older one`() {
        withStore { store ->
            store.put(Key.of("k"), Variant.fromJson("""{"v":1}"""))
            store.rotate()
            store.delete(Key.of("k"))

            assertNull(store.get(Key.of("k")))
        }
    }

    @Test
    fun `rotation is a no-op while the memtable is empty`() {
        withStore { store ->
            store.rotate()
            store.rotate()
            assertEquals(1, store.stats.logNumber)
            assertEquals(0, store.stats.sealedMemtables)
        }
    }

    @Test
    fun `reaching the memtable ceiling seals it automatically`() {
        withStore(StoreOptions(durability = Durability.BUFFERED, memtableMaxBytes = 4 * 1024)) { store ->
            for (index in 0 until 200) store.put(keyFor(index), documentFor(index))

            assertTrue(store.stats.sealedMemtables > 0, "the ceiling should have been reached")
            assertTrue(store.stats.logNumber > 1, "and a new log started with it")
            assertTrue(
                store.stats.memtableBytes < 4 * 1024,
                "the active memtable is the fresh one: ${store.stats.memtableBytes}",
            )
            for (index in 0 until 200) {
                assertEquals(documentFor(index).toString(), store.get(keyFor(index)).toString())
            }
        }
    }

    @Test
    fun `a second store cannot open the same directory`() {
        val directory = scratch(root)
        DocumentStore.open(directory).use {
            val failure = assertFailsWith<StoreLockedException> { DocumentStore.open(directory) }
            assertTrue(failure.message!!.contains("already open"), failure.message)
        }
        // Released on close, so the next open succeeds.
        DocumentStore.open(directory).use { store -> assertEquals(0, store.sequence) }
    }

    @Test
    fun `the lock file survives a close`() {
        val directory = scratch(root)
        DocumentStore.open(directory).use { }
        assertTrue(
            Files.exists(directory.resolve(LOCK_FILE_NAME)),
            "deleting the lock file would let a second process lock a fresh one",
        )
    }

    @Test
    fun `a closed store refuses every operation`() {
        val store = DocumentStore.open(scratch(root))
        store.close()
        store.close() // idempotent

        assertFailsWith<StoreClosedException> { store.get(Key.of("k")) }
        assertFailsWith<StoreClosedException> { store.put(Key.of("k"), documentFor(1)) }
        assertFailsWith<StoreClosedException> { store.sync() }
        assertFailsWith<StoreClosedException> { store.rotate() }
    }

    @Test
    fun `a store that failed to write refuses more writes but still serves reads`() {
        // The policy, exercised through the internal seam that a real IO fault will trip. Carrying on
        // after a failed append would put every later commit behind a partial record, so writes stop
        // — while reads keep working, because the memtable is untouched by a filesystem fault and a
        // caller who has just lost their writer usually needs to get the data out.
        val directory = scratch(root)
        DocumentStore.open(directory).use { store ->
            store.put(Key.of("k"), Variant.fromJson("""{"v":1}"""))
            store.markFailed(java.io.IOException("simulated disk failure"))

            assertEquals("""{"v":1}""", store.jsonAt(Key.of("k")), "reads must keep working")

            val failure = assertFailsWith<StoreFailedException> {
                store.put(Key.of("k2"), Variant.fromJson("""{"v":2}"""))
            }
            assertEquals("simulated disk failure", failure.cause?.message)
            assertFailsWith<StoreFailedException> { store.sync() }
            assertFailsWith<StoreFailedException> { store.rotate() }
        }

        // Closing a failed store must still release the directory, and what was committed before the
        // fault must still be there.
        DocumentStore.open(directory).use { store ->
            assertEquals("""{"v":1}""", store.jsonAt(Key.of("k")))
        }
    }

    @Test
    fun `createIfMissing false refuses to create the directory`() {
        val directory = scratch(root)
        assertFailsWith<NoSuchFileException> {
            DocumentStore.open(directory, StoreOptions(createIfMissing = false))
        }
        assertTrue(Files.notExists(directory))
    }

    @Test
    fun `buffered writes survive a reopen after sync`() {
        val directory = scratch(root)
        DocumentStore.open(directory, StoreOptions(durability = Durability.BUFFERED)).use { store ->
            store.put(Key.of("k"), Variant.fromJson("""{"v":1}"""))
            store.sync()
        }
        DocumentStore.open(directory).use { store ->
            assertEquals("""{"v":1}""", store.jsonAt(Key.of("k")))
        }
    }

    @Test
    fun `an empty key is a legal key`() {
        withStore { store ->
            store.put(Key.of(ByteArray(0)), Variant.fromJson("""{"v":1}"""))
            assertEquals("""{"v":1}""", store.jsonAt(Key.of(ByteArray(0))))
        }
    }

    @Test
    fun `documents larger than the staging buffer are written whole`() {
        // Forces the log writer to grow its off-heap staging buffer, and the reader to grow its own.
        withStore(StoreOptions(durability = Durability.BUFFERED)) { store ->
            val large = Variant.fromJson("""{"blob":"${"x".repeat(200_000)}"}""")
            store.put(Key.of("large"), large)
            store.sync()

            assertEquals(large.toString(), assertNotNull(store.get(Key.of("large"))).toString())
        }
    }

    @Test
    fun `stats describe what the store is holding`() {
        withStore { store ->
            store.put(Key.of("k"), Variant.fromJson("""{"v":1}"""))
            val stats = store.stats

            assertEquals(1, stats.lastSequence)
            assertEquals(1, stats.memtableEntries)
            assertEquals(0, stats.sealedMemtables)
            assertEquals(1, stats.logNumber)
            assertTrue(stats.logBytes > LogFormat.HEADER_BYTES, "header plus one record")
            assertTrue(stats.memtableBytes > 0)
            assertTrue(store.toString().contains("lastSequence=1"))
        }
    }

    private fun withStore(
        options: StoreOptions = StoreOptions.DEFAULT,
        body: (DocumentStore) -> Unit,
    ) {
        DocumentStore.open(scratch(root), options).use(body)
    }
}
