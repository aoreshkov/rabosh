package app.oreshkov.rabosh.api

import app.oreshkov.rabosh.index.IndexDefinition
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * `close` with maintenance running: nothing derived is lost, and everything mapped is released.
 *
 * The order inside `close` — store first, index catalog second — is argued in that method's KDoc:
 * stopping the store's maintenance *joins* the worker rather than cancelling it, so a flush already
 * inside its body completes and reports to an observer that is still open, where the other order
 * would silently drop that segment's sidecars.
 *
 * What this suite pins is the **invariant**, not the schedule. Forcing the worker to be mid-flush at
 * the instant `close` is called needs either a sleep or an observer that blocks the flush until the
 * test lets it go, and both would make the assertion a statement about timing; `Maintenance.close`
 * also abandons work that is merely *pending*, so a scheduled-but-unstarted flush is dropped by
 * design and would make the count vacuous. So the assertions here are the ones that hold however the
 * race lands: every segment on disk carries both its sidecars, and reopening finds the model whole.
 *
 * One of the few suites that runs with `backgroundMaintenance = true`, for the reason `CLAUDE.md`
 * gives about the ones that are specifically about racing maintenance.
 */
class RaboshCloseTest {

    @Test
    fun `every segment carries its sidecars after a close with maintenance running`(@TempDir root: Path) {
        val directory = scratch(root)
        Rabosh.open(
            directory,
            RaboshOptions(store = apiStoreOptions(backgroundMaintenance = true)),
        ).use { db ->
            db.createIndex(IndexDefinition.inverted("$.team"))
            for (index in 0 until 400) {
                db.put(keyFor(index), documentOf(index))
                // A real barrier on the calling thread, so there is something on disk to assert about
                // whatever the background worker does with what follows.
                if (index % 100 == 99) db.flush()
            }
            for (index in 400 until 600) db.put(keyFor(index), documentOf(index))
            // Seals the memtable and schedules the worker; the close below races it.
            db.rotate()
        }

        val segments = numbersOf(directory, ".seg")
        assertTrue(segments.size > 1, "the fixture should have flushed several segments")
        assertEquals(segments, numbersOf(directory, ".cat"), "every segment should have a sketch")
        assertEquals(segments, numbersOf(directory, ".idx"), "every segment should have a base sidecar")
        assertEquals(segments, postingSegments(directory), "every segment should have its postings")
    }

    @Test
    fun `reopening after such a close finds a complete model and the same documents`(@TempDir root: Path) {
        val directory = scratch(root)
        val options = RaboshOptions(store = apiStoreOptions(backgroundMaintenance = true))

        Rabosh.open(directory, options).use { db ->
            for (index in 0 until 500) {
                db.put(keyFor(index), documentOf(index))
                if (index % 100 == 99) db.flush()
            }
            db.rotate()
        }

        Rabosh.open(directory, options).use { db ->
            assertTrue(db.schema().coverage.isComplete, "reopening should find every segment covered")
            assertEquals(500, db.schema().documentCount, "no document should have been lost or double-counted")
        }

        deleteRecursively(directory)
    }

    /** Fails loudly rather than quietly: a file that will not delete is a mapping that was not released. */
    private fun deleteRecursively(directory: Path) {
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }

    private fun numbersOf(directory: Path, suffix: String): Set<Long> =
        namesEndingIn(directory, suffix).mapNotNull { it.removeSuffix(suffix).toLongOrNull() }.toSet()

    /** Posting files are named `%010d.%04d.pst` — segment number, then index id. */
    private fun postingSegments(directory: Path): Set<Long> =
        namesEndingIn(directory, ".pst").mapNotNull { it.substringBefore('.').toLongOrNull() }.toSet()
}
