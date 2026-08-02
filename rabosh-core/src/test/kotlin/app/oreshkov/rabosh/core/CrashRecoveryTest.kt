package app.oreshkov.rabosh.core

import app.oreshkov.rabosh.testkit.crash.ChildJvm
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * The acceptance test for the write path: a JVM killed mid-write, uninterruptibly, from outside.
 *
 * `Process.destroyForcibly` is `SIGKILL` on POSIX and `TerminateProcess` on Windows. Neither can be
 * caught, neither runs a shutdown hook, and neither gives the store a chance to flush anything. What
 * survives is exactly what the process had already handed to the operating system — which makes this
 * the right instrument for the question that matters most:
 *
 * > **Was anything acknowledged that is not there afterwards?**
 *
 * It is deliberately *not* a power-loss test. The page cache survives a killed process, so a
 * buffered write survives one too; losing the cache is simulated instead by damaging the files
 * directly, which is what [LogRecoveryTest] does.
 */
class CrashRecoveryTest {

    @TempDir
    lateinit var root: Path

    @Test
    fun `every acknowledged commit survives a kill, under both durability settings`() {
        for (durability in Durability.entries) {
            for (acknowledgements in listOf(1, 6, 23)) {
                val directory = scratch(root, "crash")
                val lastAcknowledged = killAfter(directory, durability, acknowledgements)

                assertEquals(
                    acknowledgements - 1,
                    lastAcknowledged,
                    "the parent should have observed $acknowledgements acknowledgements",
                )
                verifyPrefix(directory, lastAcknowledged, "$durability after $acknowledgements acks")
            }
        }
    }

    /**
     * The same guarantee with flushes and compactions in flight.
     *
     * Phase 4 gave a crash more states to be caught in: a segment written but not yet named by the
     * manifest, a manifest record half appended, logs already deleted for a flush that did complete.
     * None of them may cost an acknowledged commit — an unrecorded segment is unreachable and swept,
     * a torn manifest tail describes a version that was never installed, and a deleted log is only
     * ever deleted after the segment holding its commits is durable.
     */
    @Test
    fun `every acknowledged commit survives a kill during flush and compaction`() {
        for (durability in Durability.entries) {
            for (acknowledgements in listOf(40, 150)) {
                val directory = scratch(root, "crash-maintenance")
                val lastAcknowledged = killAfter(directory, durability, acknowledgements, rotateEvery = 8)

                assertEquals(acknowledgements - 1, lastAcknowledged)
                verifyPrefix(directory, lastAcknowledged, "$durability with maintenance running")
            }
        }
    }

    @Test
    fun `a store recovered from a kill can be written to and reopened again`() {
        val directory = scratch(root, "crash-continue")
        val lastAcknowledged = killAfter(directory, Durability.SYNC, acknowledgements = 8)

        val resumed = lastAcknowledged + 100
        DocumentStore.open(directory).use { store ->
            store.put(keyFor(resumed), documentFor(resumed))
        }
        DocumentStore.open(directory).use { store ->
            assertEquals(
                documentFor(resumed).toString(),
                assertNotNull(store.get(keyFor(resumed))).toString(),
            )
            for (index in 0..lastAcknowledged) {
                assertEquals(documentFor(index).toString(), store.get(keyFor(index)).toString())
            }
        }
    }

    @Test
    fun `a killed writer leaves the lock behind but not held`() {
        val directory = scratch(root, "crash-lock")
        killAfter(directory, Durability.SYNC, acknowledgements = 3)

        assertTrue(Files.exists(directory.resolve(LOCK_FILE_NAME)))
        // The operating system releases a file lock when the process holding it dies, so a crash
        // must not leave a store that cannot be reopened. A lock file deleted on close would.
        DocumentStore.open(directory).use { store -> assertTrue(store.sequence >= 3) }
    }

    /**
     * Runs a child that commits documents until it is killed, and returns the index of the last
     * commit the child acknowledged.
     */
    private fun killAfter(
        directory: Path,
        durability: Durability,
        acknowledgements: Int,
        rotateEvery: Int = 0,
    ): Int {
        // More commits than will ever be reached: the child must be killed mid-run, not finish.
        val child = ChildJvm.launch(
            mainClass = "app.oreshkov.rabosh.core.CrashWriterMain",
            arguments = listOf(directory.toString(), durability.name, "1000000", rotateEvery.toString()),
        )
        return child.use {
            assertEquals("READY", child.nextLine(), "child failed to start: ${child.standardError}")

            var last = -1
            repeat(acknowledgements) {
                val line = child.nextLine()
                    ?: throw AssertionError("child stopped acknowledging: ${child.standardError}")
                assertTrue(line.startsWith("ACK "), "unexpected line '$line': ${child.standardError}")
                last = line.removePrefix("ACK ").toInt()
            }
            child.killForcibly()
            last
        }
    }

    /**
     * Checks the recovered store against the acknowledged prefix.
     *
     * Two distinct claims, and both matter. Nothing acknowledged may be missing — that is the
     * guarantee. And what survived must be a *contiguous* prefix: a store that had lost commit 4 and
     * kept commit 5 would satisfy "no gaps in the acknowledged range" only by accident of where the
     * kill landed, and would mean the log's ordering cannot be trusted.
     */
    private fun verifyPrefix(directory: Path, lastAcknowledged: Int, context: String) {
        DocumentStore.open(directory).use { store ->
            for (index in 0..lastAcknowledged) {
                assertEquals(
                    documentFor(index).toString(),
                    store.get(keyFor(index))?.toString(),
                    "$context: acknowledged document $index is missing or wrong",
                )
            }

            var present = 0
            while (store.get(keyFor(present)) != null) present++
            assertTrue(
                present > lastAcknowledged,
                "$context: expected at least ${lastAcknowledged + 1} documents, found $present",
            )
            // Nothing beyond the first gap. The writer commits in order, so a document that is
            // present while an earlier one is missing would mean the log had been applied out of
            // order — the failure this whole design exists to make impossible.
            for (index in present + 1..present + 20) {
                assertNull(
                    store.get(keyFor(index)),
                    "$context: document $index is present although $present is not",
                )
            }
            assertEquals(
                present.toLong(),
                store.sequence,
                "$context: the sequence must match the number of recovered commits",
            )
        }
    }
}
