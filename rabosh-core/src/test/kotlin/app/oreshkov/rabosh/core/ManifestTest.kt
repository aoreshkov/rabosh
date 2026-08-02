package app.oreshkov.rabosh.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * The manifest: the record of which segments are live, and the one file whose loss costs the shape
 * of the tree rather than the data in it.
 *
 * The truncation sweep is the same instrument the write-ahead log gets, for the same reason. Cutting
 * the file at *every* offset and asserting that what recovers is a prefix, is correct, and is
 * monotone in surviving bytes tests something a handful of hand-picked truncations cannot: that the
 * stopping condition depends on the data and on nothing else. A recovery that went backwards as
 * more bytes became available would mean it depends on something else.
 */
class ManifestTest {

    @TempDir
    lateinit var root: Path

    @Test
    fun `edits round-trip through a record`() {
        val edit = VersionEdit().also {
            it.logNumber = 12
            it.nextFileNumber = 40
            it.lastSequence = 990
            it.add(0, segment(7, Key.of("a"), Key.of("m")))
            it.add(2, segment(8, Key.of("n"), Key.of("z")))
            it.remove(0, 3)
            it.remove(1, 4)
        }
        val record = ManifestFormat.encodeRecord(edit)
        val payload = record.copyOfRange(Frames.HEADER_BYTES, record.size)
        val decoded = ManifestFormat.decodePayload(payload, "MANIFEST-test", 0)

        assertEquals(12L, decoded.logNumber)
        assertEquals(40L, decoded.nextFileNumber)
        assertEquals(990L, decoded.lastSequence)
        assertContentEquals(listOf(0 to 7L, 2 to 8L), decoded.added.map { it.first to it.second.number })
        assertContentEquals(listOf(0 to 3L, 1 to 4L), decoded.removed)

        val added = decoded.added.first().second
        assertEquals(Key.of("a"), added.smallestKey)
        assertEquals(Key.of("m"), added.largestKey)
        assertEquals(11L, added.smallestSequence)
        assertEquals(21L, added.largestSequence)
        assertEquals(64L, added.entryCount)
        assertEquals(4096L, added.fileBytes)
    }

    /** A field an edit does not set stays unset, rather than becoming zero on the way through. */
    @Test
    fun `an edit that touches nothing carries no numbers`() {
        val record = ManifestFormat.encodeRecord(VersionEdit().add(0, segment(1, Key.of("a"), Key.of("b"))))
        val decoded = ManifestFormat.decodePayload(
            record.copyOfRange(Frames.HEADER_BYTES, record.size),
            "MANIFEST-test",
            0,
        )
        assertNull(decoded.logNumber)
        assertNull(decoded.nextFileNumber)
        assertNull(decoded.lastSequence)
    }

    @Test
    fun `a manifest replays the version it recorded`() {
        val directory = scratch(root, "replay")
        Files.createDirectories(directory)
        ManifestWriter.create(directory, 1).use { writer ->
            writer.append(VersionEdit().also { it.logNumber = 1; it.nextFileNumber = 2; it.lastSequence = 0 })
            writer.append(VersionEdit().add(0, segment(2, Key.of("a"), Key.of("f"))).also { it.lastSequence = 10 })
            writer.append(VersionEdit().add(0, segment(3, Key.of("g"), Key.of("z"))).also { it.lastSequence = 20 })
            writer.append(VersionEdit().remove(0, 2).add(1, segment(4, Key.of("a"), Key.of("f"))))
        }

        val replay = ManifestReader.replay(
            directory.resolve(manifestFileName(1)),
            LogRecoveryMode.TOLERATE_TORN_TAIL,
        )
        assertEquals(4, replay.edits.size)
        assertEquals(Files.size(directory.resolve(manifestFileName(1))), replay.validLength)

        val live = liveSegments(replay.edits)
        assertContentEquals(listOf(0 to 3L, 1 to 4L), live)
    }

    /**
     * The sweep. Truncated at every byte, a manifest must recover a prefix of its records — never a
     * record it never had, and never fewer records than a shorter file gave.
     */
    @Test
    fun `truncation at every offset recovers a monotone prefix`() {
        val directory = scratch(root, "sweep")
        Files.createDirectories(directory)
        val cuts = Files.createDirectories(scratch(root, "cuts"))
        ManifestWriter.create(directory, 1).use { writer ->
            writer.append(VersionEdit().also { it.logNumber = 1; it.nextFileNumber = 2 })
            for (index in 2..12) {
                writer.append(VersionEdit().add(0, segment(index.toLong(), Key.of("k$index"), Key.of("k$index"))))
            }
        }
        val source = directory.resolve(manifestFileName(1))
        val whole = Files.readAllBytes(source)

        var previousCount = -1
        for (limit in 0..whole.size) {
            val damaged = cuts.resolve("cut-$limit")
            Files.write(damaged, whole.copyOf(limit))

            val edits = runCatching {
                ManifestReader.replay(damaged, LogRecoveryMode.TOLERATE_TORN_TAIL).edits
            }.getOrNull()

            if (limit < ManifestFormat.HEADER_BYTES) {
                assertNull(edits, "a manifest cut inside its header must not replay, at $limit bytes")
                continue
            }
            val recovered = requireNotNull(edits) { "a manifest cut at $limit bytes failed to replay" }
            // Every recovered prefix must also decode to a coherent set of live segments, not only
            // to a count of records.
            liveSegments(recovered)
            assertTrue(
                recovered.size >= previousCount,
                "recovery went backwards at $limit bytes: ${recovered.size} after $previousCount",
            )
            previousCount = recovered.size
        }
        assertEquals(12, previousCount, "the whole file should give every record")
    }

    /**
     * A checksum failure with a readable record behind it is corruption, not a torn tail.
     *
     * The writer could not have reached the later record without having completed this one, so
     * something changed these bytes after the fact — and the version they describe is one the store
     * has been running on.
     */
    @Test
    fun `damage with a valid record behind it is reported`() {
        val directory = scratch(root, "damage")
        Files.createDirectories(directory)
        ManifestWriter.create(directory, 1).use { writer ->
            repeat(6) { index ->
                writer.append(VersionEdit().add(0, segment(index.toLong() + 2, Key.of("a"), Key.of("b"))))
            }
        }
        val source = directory.resolve(manifestFileName(1))
        val whole = Files.readAllBytes(source)

        // Well inside the file, so several intact records follow whatever is damaged.
        val target = ManifestFormat.HEADER_BYTES + Frames.HEADER_BYTES + 4
        whole[target] = (whole[target].toInt() xor 0x20).toByte()
        Files.write(source, whole)

        assertFailsWith<CorruptManifestException> {
            ManifestReader.replay(source, LogRecoveryMode.TOLERATE_TORN_TAIL)
        }
    }

    @Test
    fun `a torn tail is refused under STRICT and dropped otherwise`() {
        val directory = scratch(root, "strict")
        Files.createDirectories(directory)
        ManifestWriter.create(directory, 1).use { writer ->
            writer.append(VersionEdit().also { it.logNumber = 1 })
            writer.append(VersionEdit().add(0, segment(2, Key.of("a"), Key.of("b"))))
        }
        val source = directory.resolve(manifestFileName(1))
        truncateTo(source, Files.size(source) - 3)

        assertEquals(1, ManifestReader.replay(source, LogRecoveryMode.TOLERATE_TORN_TAIL).edits.size)
        assertFailsWith<CorruptManifestException> { ManifestReader.replay(source, LogRecoveryMode.STRICT) }
    }

    @Test
    fun `a manifest that is not one is reported, and a newer version is reported as such`() {
        val directory = scratch(root, "header")
        Files.createDirectories(directory)
        val path = directory.resolve(manifestFileName(1))

        Files.write(path, "not a manifest at all".encodeToByteArray())
        assertFailsWith<CorruptManifestException> { ManifestReader.replay(path, LogRecoveryMode.TOLERATE_TORN_TAIL) }

        val header = ManifestFormat.encodeHeader()
        for (index in 0 until 4) header[8 + index] = (2 ushr (8 * index)).toByte()
        val crc = Frames.crc32c(header, 0, 12)
        for (index in 0 until 4) header[12 + index] = (crc ushr (8 * index)).toByte()
        Files.write(path, header)
        assertFailsWith<UnsupportedFormatException> {
            ManifestReader.replay(path, LogRecoveryMode.TOLERATE_TORN_TAIL)
        }
    }

    /** `CURRENT` is swapped whole, so a reader never sees a name that is half of two. */
    @Test
    fun `CURRENT names the manifest in force`() {
        val directory = scratch(root, "current")
        Files.createDirectories(directory)
        assertNull(CurrentFile.read(directory))

        CurrentFile.write(directory, 7)
        assertEquals(7L, CurrentFile.read(directory))
        CurrentFile.write(directory, 8)
        assertEquals(8L, CurrentFile.read(directory))
        assertTrue(Files.notExists(directory.resolve("CURRENT.tmp")), "the temporary file must not survive")

        Files.writeString(directory.resolve(CURRENT_FILE_NAME), "something else\n")
        assertFailsWith<CorruptManifestException> { CurrentFile.read(directory) }
    }

    /** An edit tag this build does not know means the file is unreadable, not that it can be skipped. */
    @Test
    fun `an unknown edit tag is reported`() {
        val record = ManifestFormat.encodeRecord(VersionEdit().also { it.logNumber = 5 })
        val payload = record.copyOfRange(Frames.HEADER_BYTES, record.size)
        payload[4] = 99
        assertFailsWith<CorruptManifestException> {
            ManifestFormat.decodePayload(payload, "MANIFEST-test", 0)
        }
    }

    private fun segment(number: Long, smallest: Key, largest: Key) = SegmentMetadata(
        number = number,
        fileBytes = 4096,
        smallestKey = smallest,
        largestKey = largest,
        smallestSequence = 11,
        largestSequence = 21,
        entryCount = 64,
    )

    /** Folds a list of edits into the `(level, number)` pairs they leave live. */
    private fun liveSegments(edits: List<VersionEdit>): List<Pair<Int, Long>> {
        val live = LinkedHashMap<Long, Int>()
        for (edit in edits) {
            edit.removed.forEach { live.remove(it.second) }
            edit.added.forEach { live[it.second.number] = it.first }
        }
        return live.entries.map { it.value to it.key }.sortedWith(compareBy({ it.first }, { it.second }))
    }
}
