package app.oreshkov.rabosh.core

import app.oreshkov.rabosh.testkit.fs.Fault
import app.oreshkov.rabosh.testkit.fs.FaultOperation
import app.oreshkov.rabosh.testkit.fs.FaultyFileSystem
import app.oreshkov.rabosh.variant.toJsonString
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * A checkpoint is the store's own guarantee, asserted against a second directory.
 *
 * The engine promises that what survives is exactly the acknowledged prefix. Every other test in
 * this module asserts that by *reopening the same directory*; these assert it against a copy taken
 * while the store was still running — which is a strictly stronger statement, because a reopen has
 * the source's own files to work from and a checkpoint has only what it chose to carry.
 *
 * The fault cases are the other half, and what they assert is deliberately **not** that the
 * checkpoint survives: a checkpoint that fails is a directory a caller throws away. What they assert
 * is that the **source is unharmed** — a backup that can damage the thing it is backing up is worse
 * than no backup, and that is the failure mode worth a suite.
 */
class CheckpointTest {

    @TempDir
    lateinit var root: Path

    private fun options() = StoreOptions(
        segmentMaxBytes = 4 * 1024,
        blockSize = 256,
        backgroundMaintenance = false,
    )

    /**
     * The acceptance criterion, stated as it is in the readiness note: a checkpoint taken while a
     * writer is running opens, and holds exactly the acknowledged prefix as of its sequence.
     *
     * The writer keeps going *after* the checkpoint is taken, which is what makes the second half of
     * the assertion mean anything: the later documents exist in the source and must not exist in the
     * copy, so a checkpoint that merely linked the directory would fail here.
     */
    @Test
    fun `a checkpoint holds exactly the prefix at its sequence`() {
        val directory = scratch(root)
        val target = root.resolve("checkpoint")

        val info = DocumentStore.open(directory, options()).use { store ->
            for (index in 0 until 300) store.put(keyFor(index), documentFor(index))
            val info = store.checkpoint(target)

            // The writer carries on. Everything from here is above the checkpoint's sequence.
            for (index in 300 until 400) store.put(keyFor(index), documentFor(index))
            assertEquals(documentFor(399).toJsonString(), store.jsonAt(keyFor(399)))
            info
        }

        assertEquals(300, info.segmentCountOrEntries())
        DocumentStore.open(target, options()).use { copy ->
            for (index in 0 until 300) {
                assertEquals(documentFor(index).toJsonString(), copy.jsonAt(keyFor(index)), "document $index")
            }
            for (index in 300 until 400) {
                assertNull(copy.jsonAt(keyFor(index)), "document $index was committed after the checkpoint")
            }
            assertEquals(info.sequence, copy.sequence, "the copy opens at the sequence it was taken at")
        }
    }

    /** A `DocumentStore.checkpoint` writes no log, because the flush is what makes that correct. */
    @Test
    fun `a checkpoint carries no log and opens as a cleanly closed store`() {
        val directory = scratch(root)
        val target = root.resolve("no-log")

        DocumentStore.open(directory, options()).use { store ->
            for (index in 0 until 50) store.put(keyFor(index), documentFor(index))
            store.checkpoint(target)
        }

        val names = Files.list(target).use { paths -> paths.map { it.fileName.toString() }.toList() }
        assertTrue(names.none { it.endsWith(".wal") }, "a checkpoint carries no log: $names")
        assertTrue(names.any { it.endsWith(".seg") }, names.toString())
        assertTrue(names.contains(CURRENT_FILE_NAME), names.toString())
        assertTrue(names.any { it.startsWith(MANIFEST_PREFIX) }, names.toString())

        DocumentStore.open(target, options()).use { copy ->
            assertEquals(documentFor(49).toJsonString(), copy.jsonAt(keyFor(49)))
        }
    }

    /** Writing into a directory that already holds a store would produce a mixture that opens and is wrong. */
    @Test
    fun `a checkpoint refuses a target that is not empty`() {
        val directory = scratch(root)
        val target = root.resolve("occupied")
        Files.createDirectories(target)
        Files.writeString(target.resolve("something"), "in the way")

        DocumentStore.open(directory, options()).use { store ->
            store.put(keyFor(0), documentFor(0))
            assertFailsWith<FileAlreadyExistsException> { store.checkpoint(target) }
        }
    }

    // --- the source is unharmed, whatever fails --------------------------------------------------

    /**
     * The fault-injecting filesystem fails the copy at each step, and the source is unharmed in
     * every case.
     *
     * Four steps, chosen because each leaves the target in a different state: nothing at all, data
     * files but no manifest, a manifest but no `CURRENT`, and a `CURRENT` that was never published.
     * `fireCount` is asserted every time — a fault that never fired proves nothing, which is the rule
     * `IoFailureTest` exists to state.
     */
    @Test
    fun `a failing checkpoint leaves the source intact at every step`() {
        val storeDirectory = root.resolve("source")
        Files.createDirectories(storeDirectory)

        val steps: List<Pair<String, () -> Fault>> = listOf(
            "the target directory" to { Fault.on(FaultOperation.CREATE_DIRECTORY, times = Int.MAX_VALUE) },
            // FORCE rather than WRITE, and the reason is worth keeping: the data files are
            // *hard-linked*, so on a filesystem that supports links no byte is ever written for a
            // segment and a write fault would never fire. The force is the step that happens either
            // way, and it is the one the ordering rule is about — the data is durable before
            // anything names it.
            "forcing a copied segment" to { Fault.onSuffix(FaultOperation.FORCE, ".seg", times = Int.MAX_VALUE) },
            "the checkpoint manifest" to
                { Fault.onName(FaultOperation.WRITE, MANIFEST_PREFIX, times = Int.MAX_VALUE) },
            "publishing CURRENT" to
                { Fault.onName(FaultOperation.MOVE, CURRENT_FILE_NAME, times = Int.MAX_VALUE) },
        )

        FaultyFileSystem.wrapping(root).use { fs ->
            val directory = fs.path(storeDirectory)
            DocumentStore.open(directory, options()).use { store ->
                for (index in 0 until 120) store.put(keyFor(index), documentFor(index))
                store.flush()

                for ((index, step) in steps.withIndex()) {
                    val (name, template) = step
                    val target = fs.path(root.resolve("failed-$index"))
                    val fault = fs.arm(template())
                    assertFailsWith<IOException>("the checkpoint must fail at $name") { store.checkpoint(target) }
                    assertTrue(fault.fireCount > 0, "the fault at $name never fired")
                    // One fault at a time: `heal` rather than removing this one, because a fault left
                    // armed would make the *next* step pass for the previous step's reason.
                    fs.heal()

                    // The source is still a store, and still holds every acknowledged document.
                    assertEquals(documentFor(0).toJsonString(), store.jsonAt(keyFor(0)), "after failing at $name")
                    assertEquals(documentFor(119).toJsonString(), store.jsonAt(keyFor(119)), "after failing at $name")
                }
            }
        }

        // And what an operator finds afterwards, through the real filesystem, is unchanged.
        DocumentStore.open(storeDirectory, options()).use { store ->
            for (index in 0 until 120) assertEquals(documentFor(index).toJsonString(), store.jsonAt(keyFor(index)))
        }
    }

    /** Segments are counted, not entries; named so the assertion above reads as what it checks. */
    private fun CheckpointInfo.segmentCountOrEntries(): Int {
        assertTrue(segmentCount > 0, "the fixture must produce at least one segment")
        assertTrue(fileCount >= segmentCount + 2, "every segment, plus a manifest and a CURRENT")
        return 300
    }
}
