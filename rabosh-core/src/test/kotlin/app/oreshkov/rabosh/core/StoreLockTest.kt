package app.oreshkov.rabosh.core

import app.oreshkov.rabosh.testkit.crash.ChildJvm
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
 * What a caller can learn when the directory is already open.
 *
 * `DocumentStoreTest` asserts that a second open *fails*. These assert that it fails **legibly**:
 * two instances of a desktop application on one data directory is not an error, it is Tuesday, and
 * an application cannot tell that case from a genuine IO failure without something better than a
 * message to match on.
 *
 * The cross-process test is the one that matters and it is why [LockHolderMain] exists. A second
 * `DocumentStore.open` in *this* JVM takes a different branch — `OverlappingFileLockException`, which
 * the JVM raises before the operating system is consulted — so a single-process test would leave the
 * path that a real second launch takes completely uncovered.
 */
class StoreLockTest {

    @TempDir
    lateinit var root: Path

    @Test
    fun `a second open in this process names the process and the directory`() {
        val directory = scratch(root)
        DocumentStore.open(directory).use {
            val failure = assertFailsWith<StoreLockedException> { DocumentStore.open(directory) }

            assertEquals(directory, failure.directory, "the directory is a property, not a substring")
            val holder = assertNotNull(failure.holder, "this process wrote the record moments ago")
            assertEquals(ProcessHandle.current().pid(), holder.pid)
            assertTrue(holder.isRunning, "the holder is this very JVM")
            assertTrue(failure.message!!.contains("already open"), failure.message)
            assertTrue(failure.message!!.contains(holder.pid.toString()), failure.message)
        }
    }

    /**
     * **The case the item exists for: a second *instance*, not a second call.**
     *
     * A child JVM takes the lock and holds it. What this pins is that the pid in the report is the
     * pid of the process that actually holds the directory — not merely a well-formed number, which
     * is all a same-process test can establish, and not this JVM's.
     */
    @Test
    fun `a second open from another process names that process`() {
        val directory = scratch(root)
        // Created here so the child does not race the parent creating it.
        DocumentStore.open(directory).use { }

        ChildJvm.launch("app.oreshkov.rabosh.core.LockHolderMain", listOf(directory.toString())).use { child ->
            assertEquals("HELD", child.nextLine(), "child stderr:\n${child.standardError}")

            val failure = assertFailsWith<StoreLockedException> { DocumentStore.open(directory) }

            assertEquals(directory, failure.directory)
            val holder = assertNotNull(failure.holder, "the child wrote the record before printing HELD")
            assertEquals(child.pid, holder.pid, "the report must name the holder, not this JVM")
            assertTrue(holder.isRunning, "the child is alive; that is why this open failed")
            assertTrue(failure.message!!.contains("another process"), failure.message)
        }
    }

    /**
     * A lock file with no record reports no holder, rather than guessing at one.
     *
     * This is the shape a store written by an earlier release has — `LOCK` held nothing at all until
     * phase 24 — and the conservative answer is the required one: an error message that asserted
     * something false about a process id would be worse than one that says nothing. Arranged by
     * emptying the file while nobody holds it, which is exactly the older release's state.
     */
    @Test
    fun `a lock file written by an older release reports no holder`() {
        val directory = scratch(root)
        DocumentStore.open(directory).use { }

        val lock = directory.resolve(LOCK_FILE_NAME)
        assertTrue(Files.size(lock) > 0, "this release writes a record, or the test below proves nothing")
        Files.write(lock, ByteArray(0))

        // Nothing holds it now, so it reopens — and the record it then writes is this release's.
        DocumentStore.open(directory).use {
            val failure = assertFailsWith<StoreLockedException> { DocumentStore.open(directory) }
            assertNotNull(failure.holder)
        }

        // The absent-record case itself: empty the file again and read it back through a failed open.
        Files.write(lock, ByteArray(0))
        DocumentStore.open(directory).use { store ->
            Files.write(lock, ByteArray(0))
            val failure = assertFailsWith<StoreLockedException> { DocumentStore.open(directory) }
            assertNull(failure.holder, "an empty record is no holder, never a guessed one")
            assertEquals(directory, failure.directory, "the directory is known even when the holder is not")
            assertTrue(store.stats.lastSequence >= 0)
        }
    }

    /**
     * The lock file survives a close, and so does the record.
     *
     * `DocumentStoreTest` already pins the first half — deleting the file would let a second process
     * lock a fresh one while a third still holds this one. The record is on the same footing: it is
     * a diagnostic, not state, and nothing reads it except a process that has just failed to open.
     */
    @Test
    fun `the record is left behind for the next process to read`() {
        val directory = scratch(root)
        DocumentStore.open(directory).use { }

        val record = Files.readString(directory.resolve(LOCK_FILE_NAME))
        assertTrue(record.contains("pid=${ProcessHandle.current().pid()}"), record)
        assertTrue(record.contains("startedAt="), record)
    }
}
