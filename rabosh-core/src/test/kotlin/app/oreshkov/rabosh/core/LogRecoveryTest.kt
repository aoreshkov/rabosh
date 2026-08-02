package app.oreshkov.rabosh.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.CRC32C
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Recovery against damaged logs.
 *
 * The invariant under test throughout: **reopening yields exactly the acknowledged prefix.** A torn
 * tail — bytes an interrupted writer left behind, which nobody was ever told about — may be dropped.
 * Anything that would drop an *acknowledged* commit must be reported instead, and the two checks a
 * checksum cannot make are what catch the difference: whether a readable record follows a broken
 * one, and whether the sequence numbers are continuous.
 */
class LogRecoveryTest {

    @TempDir
    lateinit var root: Path

    /** Commits in the prepared store below. Small, because one sweep visits every byte of it. */
    private val commits = 10

    @Test
    fun `truncation at any offset recovers a prefix, and never less as more survives`() {
        val original = prepare()
        val length = Files.size(logPath(original, 1)).toInt()

        var previous = 0
        for (limit in 0..length) {
            val damaged = copyStore(original, scratch(root, "truncated"))
            truncateTo(logPath(damaged, 1), limit.toLong())

            val recovered = recoverAndAssertPrefix(damaged, "truncated to $limit of $length bytes")
            // Monotone in the number of surviving bytes. A recovery that went *backwards* as more
            // data became available would mean the reader's stopping condition depends on something
            // other than the data — the kind of fault that surfaces as a rare lost commit.
            assertTrue(
                recovered >= previous,
                "truncating to $limit recovered $recovered, but $previous survived at ${limit - 1}",
            )
            previous = recovered
        }
        assertEquals(commits, previous, "the untruncated log must recover everything")
    }

    @Test
    fun `a torn tail is truncated away, and the log continues from there`() {
        val store = prepare()
        val path = logPath(store, 1)
        val length = Files.size(path)
        // Half a record: the state an interrupted append leaves behind.
        truncateTo(path, length - 4)

        DocumentStore.open(store, tolerant()).use { reopened ->
            assertEquals(commits - 1, countPresent(reopened), "only the interrupted commit is lost")
            // Cut back to where the incomplete record *started*, not merely to the bytes that were
            // readable: a partial record is not a shorter record, and leaving any of it behind would
            // put every future append behind bytes recovery has already rejected.
            val truncatedTo = reopened.stats.logBytes
            assertTrue(
                truncatedTo < length - 4,
                "the partial record should be gone entirely, file is $truncatedTo of $length bytes",
            )
            assertEquals(truncatedTo, Files.size(path))
            reopened.put(keyFor(99), documentFor(99))
        }

        DocumentStore.open(store, tolerant()).use { reopened ->
            assertEquals(commits - 1, countPresent(reopened))
            assertEquals(
                documentFor(99).toString(),
                assertNotNull(reopened.get(keyFor(99))).toString(),
                "a commit appended after recovery must itself survive a reopen",
            )
        }
    }

    @Test
    fun `strict mode refuses to open a log with a torn tail`() {
        val store = prepare()
        truncateTo(logPath(store, 1), Files.size(logPath(store, 1)) - 4)

        val failure = assertFailsWith<CorruptLogException> {
            DocumentStore.open(store, StoreOptions(recoveryMode = LogRecoveryMode.STRICT, backgroundMaintenance = false))
        }
        assertTrue(failure.message!!.contains("incomplete tail"), failure.message)
        assertEquals("0000000001.wal", failure.file)
    }

    @Test
    fun `trailing rubbish is a torn tail, not a record`() {
        val store = prepare()
        Files.write(logPath(store, 1), ByteArray(37) { 0x5A }, StandardOpenOption.APPEND)

        DocumentStore.open(store, tolerant()).use { reopened ->
            assertEquals(commits, countPresent(reopened))
        }
    }

    @Test
    fun `a damaged record with readable data behind it is corruption, not a torn tail`() {
        // The case that separates a real design from a hopeful one. Dropping everything from a bad
        // checksum onwards is only safe at the tail; here the records behind it were acknowledged.
        val store = prepare()
        flipBit(logPath(store, 1), LogFormat.HEADER_BYTES + LogFormat.FRAME_HEADER_BYTES + 20)

        for (mode in LogRecoveryMode.entries) {
            val failure = assertFailsWith<CorruptLogException> {
                DocumentStore.open(store, StoreOptions(recoveryMode = mode, backgroundMaintenance = false))
            }
            assertTrue(failure.message!!.contains("checksum"), "under $mode: ${failure.message}")
        }
    }

    @Test
    fun `a damaged last record is a torn tail`() {
        val original = prepare()
        val damagedOffset = (Files.size(logPath(original, 1)) - 1).toInt()

        val tolerated = copyStore(original, scratch(root, "flip-tolerated"))
        flipBit(logPath(tolerated, 1), damagedOffset)
        DocumentStore.open(tolerated, tolerant()).use { reopened ->
            assertEquals(commits - 1, countPresent(reopened))
        }

        // A separate copy, because the open above *repaired* its own directory by truncating the
        // damaged record away. Reusing it would have tested a clean log and passed for that reason.
        val strict = copyStore(original, scratch(root, "flip-strict"))
        flipBit(logPath(strict, 1), damagedOffset)
        assertFailsWith<CorruptLogException> {
            DocumentStore.open(strict, StoreOptions(recoveryMode = LogRecoveryMode.STRICT, backgroundMaintenance = false))
        }
    }

    @Test
    fun `a sealed log may not have a torn tail`() {
        val store = prepare(rotateAfter = 6)
        // Log 1 was forced before log 2 was created, so an incomplete tail in it cannot be an
        // interrupted write. It is damage, and it hides commits that log 2's records follow on from.
        truncateTo(logPath(store, 1), Files.size(logPath(store, 1)) - 4)

        val failure = assertFailsWith<CorruptLogException> { DocumentStore.open(store, tolerant()) }
        assertTrue(failure.message!!.contains("sealed log"), failure.message)
        assertEquals("0000000001.wal", failure.file)
    }

    @Test
    fun `a missing log is caught by the sequence numbers, not by the file names`() {
        val store = prepare(rotateAfter = 4)
        appendMore(store, from = commits, count = 3, rotateFirst = true)
        Files.delete(logPath(store, 2))

        val failure = assertFailsWith<CorruptLogException> { DocumentStore.open(store, tolerant()) }
        assertTrue(
            failure.message!!.contains("starts at sequence"),
            "expected a continuity report, got: ${failure.message}",
        )
    }

    @Test
    fun `a half-created log is re-initialised, because only the newest log can be one`() {
        // A process that died between creating a log and writing its header. Nothing was ever
        // acknowledged into it, so re-initialising loses nothing — and it is the only log that can
        // be in that state, which is why the same damage anywhere else is reported.
        val store = prepare(rotateAfter = commits)
        truncateTo(logPath(store, 2), 12)

        DocumentStore.open(store, tolerant()).use { reopened ->
            assertEquals(commits, countPresent(reopened))
            reopened.put(keyFor(500), documentFor(500))
        }
        DocumentStore.open(store, tolerant()).use { reopened ->
            assertEquals(commits, countPresent(reopened))
            assertEquals(documentFor(500).toString(), assertNotNull(reopened.get(keyFor(500))).toString())
        }
    }

    @Test
    fun `a sealed log with an unreadable header is corruption`() {
        val store = prepare(rotateAfter = 6)
        flipBit(logPath(store, 1), 3)

        val failure = assertFailsWith<CorruptLogException> { DocumentStore.open(store, tolerant()) }
        assertTrue(failure.message!!.contains("header"), failure.message)
    }

    @Test
    fun `a log renamed to another number is rejected`() {
        val store = prepare()
        Files.move(logPath(store, 1), logPath(store, 5))

        val failure = assertFailsWith<CorruptLogException> { DocumentStore.open(store, tolerant()) }
        assertTrue(failure.message!!.contains("claims to be number 1"), failure.message)
    }

    @Test
    fun `a file that looks like a log but is not is rejected`() {
        val store = prepare()
        Files.writeString(store.resolve("backup.wal"), "not a log")

        val failure = assertFailsWith<CorruptLogException> { DocumentStore.open(store, tolerant()) }
        assertTrue(failure.message!!.contains("not a log number"), failure.message)
    }

    @Test
    fun `logs from a newer format version are unsupported rather than corrupt`() {
        val store = prepare()
        val path = logPath(store, 1)
        val bytes = readAllBytes(path)
        // Version 2, with a header checksum that agrees: what a future release would write.
        bytes[8] = 2
        val checksum = CRC32C().apply { update(bytes, 0, 28) }.value.toInt()
        for (index in 0 until 4) bytes[28 + index] = (checksum ushr (8 * index)).toByte()
        writeAllBytes(path, bytes)

        assertFailsWith<UnsupportedFormatException> { DocumentStore.open(store, tolerant()) }
    }

    // --- helpers -------------------------------------------------------------------------------

    /**
     * Maintenance is off in every store this file opens, and that is the point of the file.
     *
     * A flush would write the sealed memtable out as a segment and delete the log that fed it —
     * which is correct behaviour and exactly what would remove the fixtures these tests damage.
     * What is under test here is the log's own recovery, so the logs have to stay where they are put.
     */
    private fun tolerant() =
        StoreOptions(recoveryMode = LogRecoveryMode.TOLERATE_TORN_TAIL, backgroundMaintenance = false)

    /**
     * A store holding [commits] single-document commits, closed cleanly.
     *
     * [Durability.BUFFERED] with an explicit close, which forces: the bytes on disk are exactly what
     * a durable run would have left, without paying an `fsync` per commit to produce them.
     */
    private fun prepare(rotateAfter: Int = -1): Path {
        val directory = scratch(root, "prepared")
        DocumentStore.open(directory, StoreOptions(durability = Durability.BUFFERED, backgroundMaintenance = false)).use { store ->
            for (index in 0 until commits) {
                store.put(keyFor(index), documentFor(index))
                if (index + 1 == rotateAfter) store.rotate()
            }
        }
        return directory
    }

    private fun appendMore(directory: Path, from: Int, count: Int, rotateFirst: Boolean) {
        DocumentStore.open(directory, StoreOptions(durability = Durability.BUFFERED, backgroundMaintenance = false)).use { store ->
            if (rotateFirst) store.rotate()
            for (index in from until from + count) store.put(keyFor(index), documentFor(index))
        }
    }

    /** How many of the numbered documents are present, counting from the first. */
    private fun countPresent(store: DocumentStore): Int {
        var present = 0
        while (present < commits && store.get(keyFor(present)) != null) present++
        return present
    }

    /**
     * Recovers [directory] and checks that what survived is a genuine prefix.
     *
     * "Nine of ten survived" is not the invariant and would be satisfied by having lost the wrong
     * one; every document below the boundary must be present *and correct*, and every document above
     * it absent.
     */
    private fun recoverAndAssertPrefix(directory: Path, context: String): Int =
        DocumentStore.open(directory, tolerant()).use { store ->
            val present = countPresent(store)
            for (index in 0 until present) {
                assertEquals(
                    documentFor(index).toString(),
                    store.get(keyFor(index)).toString(),
                    "$context: document $index survived but is not itself",
                )
            }
            for (index in present until commits) {
                assertNull(store.get(keyFor(index)), "$context: document $index must not be present")
            }
            present
        }
}
