package app.oreshkov.rabosh.core

import app.oreshkov.rabosh.testkit.property.Gen
import app.oreshkov.rabosh.testkit.property.forAll
import app.oreshkov.rabosh.testkit.property.list
import app.oreshkov.rabosh.testkit.property.pair
import app.oreshkov.rabosh.variant.Variant
import app.oreshkov.rabosh.variant.toJsonString
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * A written segment, read back.
 *
 * Two things are under test that the block tests could not reach. The first is the **shared
 * dictionary**: documents go in carrying dictionaries of their own and come out decoded against one
 * per file, so a segment that got the id translation wrong returns the right document with the
 * wrong field names — a failure that a byte-level roundtrip of the block would never see. The
 * second is the **whole read path**: bloom, index block, data block and Variant decode in sequence,
 * where each stage's output is the next stage's offset.
 */
class SegmentTest {

    @TempDir
    lateinit var root: Path

    /** Small blocks, so even a modest test writes a multi-block segment with a real index. */
    private val options = StoreOptions(blockSize = 256)

    @Test
    fun `every document written comes back, decoded against the segment dictionary`() {
        forAll(Gen.list(Gen.pair(CoreGens.key, CoreGens.document), sizes = 1..40), iterations = 40) { pairs ->
            val entries = versions(pairs)
            withSegment(entries) { table ->
                for (entry in entries) {
                    // Bounded by the entry's own sequence, so a key written several times is
                    // looked up at the point each version was current rather than only at its last.
                    val found = assertNotNull(
                        table.get(entry.key, entry.sequence),
                        "${entry.key}@${entry.sequence} was written and then not found",
                    )
                    assertEquals(entry.json, found.document?.toJsonString(), "for ${entry.key}@${entry.sequence}")
                }
            }
        }
    }

    /**
     * The whole segment in order, which is the walk compaction does.
     *
     * Compared against the same entries sorted by the segment comparator, so an index that skipped
     * a block or a cursor that failed to cross one shows up as a missing entry rather than as a
     * lookup that happened to still work.
     */
    @Test
    fun `a cursor walks every version in key order`() {
        forAll(Gen.list(Gen.pair(CoreGens.key, CoreGens.document), sizes = 1..40), iterations = 40) { pairs ->
            val entries = versions(pairs)
            withSegment(entries) { table ->
                val walked = ArrayList<Pair<String, String?>>()
                table.cursor().use { cursor ->
                    cursor.seekToFirst()
                    while (cursor.valid()) {
                        walked += cursor.key.copyOfRange(0, cursor.keyLength).toHex() to
                            cursor.document()?.toJsonString()
                        cursor.next()
                    }
                }
                assertContentEquals(
                    entries.map { it.encoded.toHex() to it.json },
                    walked,
                )
            }
        }
    }

    /**
     * A lookup bounded by a sequence sees the version that was current at that sequence.
     *
     * This is what a snapshot read reduces to inside a single segment, and it is the reason the
     * sequence half of a key sorts descending: the seek lands on the newest version at or below the
     * bound, so no scan over the newer ones is needed to skip them.
     */
    @Test
    fun `a bounded lookup finds the version current at that sequence`() {
        val key = Key.of("user:1")
        val entries = (1..5).map { sequence ->
            Entry(key, sequence.toLong(), OperationKind.PUT, """{"v":$sequence}""")
        }.sortedWith { left, right -> compareEncodedKeys(left.encoded, right.encoded) }

        withSegment(entries) { table ->
            for (bound in 1L..5L) {
                assertEquals(
                    """{"v":$bound}""",
                    table.get(key, bound)?.document?.toJsonString(),
                    "at sequence bound $bound",
                )
            }
            assertNull(table.get(key, 0), "nothing was written at or below sequence 0")
            assertEquals("""{"v":5}""", table.get(key, 99)?.document?.toJsonString())
        }
    }

    /** A tombstone is an answer the segment gives, not an entry it fails to find. */
    @Test
    fun `a tombstone reads back as a deletion, not as an absence`() {
        val key = Key.of("gone")
        val entries = listOf(
            Entry(key, 7, OperationKind.DELETE, null),
            Entry(key, 3, OperationKind.PUT, """{"still":"here"}"""),
        )
        withSegment(entries) { table ->
            val newest = assertNotNull(table.get(key, Long.MAX_VALUE))
            assertNull(newest.document, "the newest version is a tombstone")
            assertEquals("""{"still":"here"}""", table.get(key, 5)?.document?.toJsonString())
            assertNull(table.get(Key.of("never-written"), Long.MAX_VALUE))
        }
    }

    /**
     * Field names are paid for once per segment.
     *
     * The claim the README makes about the shared dictionary, asserted rather than assumed: a
     * hundred documents with the same three field names produce a dictionary of three.
     */
    @Test
    fun `one dictionary serves the whole segment`() {
        val entries = (0 until 100).map { index ->
            Entry(
                Key.of("key:%04d".format(index)),
                index.toLong() + 1,
                OperationKind.PUT,
                """{"alpha":$index,"beta":"value-$index","gamma":[1,2,3]}""",
            )
        }
        withSegment(entries) { table ->
            assertEquals(3, table.dictionary.size, "one dictionary entry per distinct field name")
            // And the documents still decode correctly against it.
            for (entry in entries) {
                assertEquals(entry.json, table.get(entry.key, Long.MAX_VALUE)?.document?.toJsonString())
            }
        }
    }

    /**
     * Damage to a data block is reported, never returned.
     *
     * Only the data region is damaged: the header and footer have their own tests, and the point
     * here is the per-block checksum that a read verifies before it decodes anything.
     */
    @Test
    fun `a flipped bit in a data block is reported`() {
        val entries = (0 until 200).map { index ->
            Entry(Key.of("key:%04d".format(index)), index.toLong() + 1, OperationKind.PUT, """{"n":$index}""")
        }
        val path = write(entries, "damaged")
        val clean = Files.readAllBytes(path)

        var reported = 0
        for (offset in SegmentFormat.HEADER_BYTES until minOf(clean.size - SegmentFormat.FOOTER_BYTES, 900) step 7) {
            val damaged = clean.copyOf()
            damaged[offset] = (damaged[offset].toInt() xor 0x5A).toByte()
            val target = root.resolve("damaged-$offset.seg")
            Files.write(target, damaged)

            val failure = runCatching {
                SegmentTable.open(target, metadataFor(entries, target)).use { table ->
                    table.cursor().use { cursor ->
                        cursor.seekToFirst()
                        while (cursor.valid()) {
                            cursor.document()?.toJsonString()
                            cursor.next()
                        }
                    }
                }
            }.exceptionOrNull()
            if (failure != null) {
                assertTrue(
                    failure is CorruptSegmentException || failure is UnsupportedFormatException,
                    "a flip at $offset produced $failure",
                )
                reported++
            }
        }
        assertTrue(reported > 100, "only $reported of the damaged copies were detected")
    }

    /**
     * A damaged **index** block is caught when the segment is mapped, and that is where it must be.
     *
     * `SegmentTable.open` verifies the index, bloom and dictionary blocks once, and a lookup then
     * trusts them — the trade its KDoc has always described. It was not what the code did: `get`
     * re-verified the index block on every call, so a point lookup paid a CRC32C over one entry per
     * data block in the segment. Measured at 3.0 µs of a 4.15 µs lookup at the default sizes, and
     * growing with segment size; see `ReadCostMain`.
     *
     * Removing that is only sound because *this* holds, so it is asserted directly rather than left
     * as a property of a method nobody calls in anger.
     */
    @Test
    fun `a damaged index block is refused when the segment is opened`() {
        val entries = (0 until 200).map { index ->
            Entry(Key.of("key:%04d".format(index)), index.toLong() + 1, OperationKind.PUT, """{"n":$index}""")
        }
        val path = write(entries, "badindex")
        val clean = Files.readAllBytes(path)
        val footer = SegmentFormat.readFooter(segmentBytesOf(clean, "badindex.seg"))
        assertTrue(footer.index.length > 0, "the fixture must have a real index block")

        // Every byte of the index block, so this cannot pass by hitting a lucky offset.
        for (offset in footer.index.offset until footer.index.offset + footer.index.length step 3) {
            val damaged = clean.copyOf()
            damaged[offset.toInt()] = (damaged[offset.toInt()].toInt() xor 0x5A).toByte()
            val target = root.resolve("badindex-$offset.seg")
            Files.write(target, damaged)

            assertFailsWith<CorruptSegmentException>("a flip at $offset in the index block was not caught") {
                SegmentTable.open(target, metadataFor(entries, target)).close()
            }
        }
    }

    /**
     * The two readers differ in exactly one thing, and it is the checksum.
     *
     * `readBlockCheckedAtOpen` is a promise the caller makes, not a check it performs, so the promise
     * has to be visible at this level too — otherwise the only thing standing between "verified once"
     * and "never verified" is a method name.
     */
    @Test
    fun `a block reader that trusts open does not re-check the checksum`() {
        val entries = (0 until 40).map { index ->
            Entry(Key.of("key:%04d".format(index)), index.toLong() + 1, OperationKind.PUT, """{"n":$index}""")
        }
        val path = write(entries, "trusting")
        val clean = Files.readAllBytes(path)
        val footer = SegmentFormat.readFooter(segmentBytesOf(clean, "trusting.seg"))

        val damaged = clean.copyOf()
        val at = footer.index.offset.toInt()
        damaged[at] = (damaged[at].toInt() xor 0x5A).toByte()
        val bytes = segmentBytesOf(damaged, "trusting.seg")

        assertFailsWith<CorruptSegmentException> { bytes.readBlock(footer.index, "index block") }
        // The same bytes, the same handle, no exception: this reader is told the block was already
        // checked and takes the caller's word for it.
        bytes.readBlockCheckedAtOpen(footer.index)
    }

    @Test
    fun `a segment with no entries is refused rather than written`() {
        val path = root.resolve("empty.seg")
        SegmentWriter(path, 1, options).use { writer ->
            assertFailsWith<IllegalStateException> { writer.finish() }
        }
        assertTrue(Files.notExists(path), "an abandoned segment must not be left behind")
    }

    /**
     * Closing a segment unmaps it, which on Windows is the difference between a file that can be
     * deleted and one that cannot. See `ResourceLeakTest` for the same check after a compaction.
     */
    @Test
    fun `a closed segment releases its mapping`() {
        val entries = listOf(Entry(Key.of("k"), 1, OperationKind.PUT, """{"a":1}"""))
        val path = write(entries, "mapped")
        val table = SegmentTable.open(path, metadataFor(entries, path))
        assertTrue(table.isOpen)
        table.close()
        assertTrue(!table.isOpen)
        Files.delete(path)
    }

    /** A second reference keeps the mapping alive until both are released. */
    @Test
    fun `references keep a segment mapped`() {
        val entries = listOf(Entry(Key.of("k"), 1, OperationKind.PUT, """{"a":1}"""))
        val path = write(entries, "shared")
        val table = SegmentTable.open(path, metadataFor(entries, path))
        assertTrue(table.acquire())
        table.release()
        assertTrue(table.isOpen, "one reference remains")
        table.release()
        assertTrue(!table.isOpen)
        assertTrue(!table.acquire(), "a released segment must not be resurrected")
    }

    // --- helpers ------------------------------------------------------------------------------

    private class Entry(
        val key: Key,
        val sequence: Long,
        val kind: OperationKind,
        val json: String?,
    ) {
        val encoded: ByteArray = SegmentFormat.encodeKey(key, sequence, kind)
        val document: Variant? = json?.let(Variant::fromJson)
    }

    /** Distinct `(key, sequence)` versions in segment order, one document each. */
    private fun versions(pairs: List<Pair<Key, Variant>>): List<Entry> = pairs
        .mapIndexed { index, pair -> Entry(pair.first, index.toLong() + 1, OperationKind.PUT, pair.second.toJsonString()) }
        .distinctBy { it.encoded.toHex() }
        .sortedWith { left, right -> compareEncodedKeys(left.encoded, right.encoded) }

    private fun write(entries: List<Entry>, name: String): Path {
        val path = root.resolve("$name.seg")
        Files.deleteIfExists(path)
        SegmentWriter(path, 1, options).use { writer ->
            entries.forEach { writer.add(it.encoded, it.encoded.size, it.document) }
            writer.finish()
        }
        return path
    }

    private fun metadataFor(entries: List<Entry>, path: Path): SegmentMetadata = SegmentMetadata(
        number = 1,
        fileBytes = Files.size(path),
        smallestKey = entries.minOf { it.key },
        largestKey = entries.maxOf { it.key },
        smallestSequence = entries.minOf { it.sequence },
        largestSequence = entries.maxOf { it.sequence },
        entryCount = entries.size.toLong(),
    )

    private fun withSegment(entries: List<Entry>, body: (SegmentTable) -> Unit) {
        val path = write(entries, "segment-${counter++}")
        SegmentTable.open(path, metadataFor(entries, path)).use(body)
    }

    private var counter = 0
}
