package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.core.Key
import java.lang.foreign.MemorySegment
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Damaged sidecars are **reported**, never guessed at.
 *
 * The instrument is the one the log and the manifest already use: truncate at *every* offset rather
 * than at a sample. The interesting failures are the ones that leave a plausible header pointing at
 * bytes that are not what it says they are, and those live at specific offsets nobody would pick by
 * hand. Every one must produce an `IndexException` naming the file — never a wrong answer, and never
 * an `IndexOutOfBoundsException` from three frames down.
 *
 * The bit-flip half proves the two-level checksum design rather than merely exercising it: a flip in
 * the directory is caught when the file is *opened*, and a flip in a posting is caught only when that
 * posting is *read*. If either were caught by the other, one of the two checksums would be pointless.
 */
class IndexCorruptionTest {

    private fun baseSidecar(): ByteArray {
        val builder = BaseSidecarBuilder()
        (0 until 40).forEach { builder.observe(Key.of("key:%05d".format(it)), 100L + it, isPut = it % 5 != 0) }
        return builder.build(segmentNumber = 3)
    }

    /**
     * The same segment as a version-1 sidecar.
     *
     * Phase 18 gave the key block a second layout and kept the first **readable**, so the version-1
     * reader is reachable by every store written before this build — and a reader that is reachable
     * has to refuse damage. Sweeping only the current version would leave it covered by nothing but
     * three well-formed golden files, which is the same gap phase 17 closed for `.pst`.
     *
     * Forty keys, so both layouts span three restart groups and the sweeps see a restart array.
     */
    private fun legacyBaseSidecar(): ByteArray {
        val builder = LegacyBaseSidecarBuilder()
        (0 until 40).forEach { builder.observe(Key.of("key:%05d".format(it)), 100L + it, isPut = it % 5 != 0) }
        return builder.build(segmentNumber = 3)
    }

    /**
     * Nine terms with several ordinals each, and one with exactly one.
     *
     * Both posting encodings in one file on purpose: a bitmap posting lives in the posting region and
     * a [IndexFormat.POSTING_ENCODING_SINGLE] one lives inside the directory, so the truncation sweep,
     * the bit-flip sweep and the two-level checksum test all have to see both or they would each be
     * testing half the format.
     */
    private fun postingFile(): ByteArray {
        val builder = PostingBuilder(maxTerms = 1024)
        for (ordinal in 0 until 59) builder.add(IndexTerm.ofString("v${ordinal % 9}").bytes, ordinal)
        builder.add(IndexTerm.ofString("solo").bytes, 59)
        return builder.build(segmentNumber = 3, indexId = 2, path = "$.team", documentCount = 60, largestSequence = 500)
    }

    /**
     * The same postings as a version-1 file.
     *
     * Phase 17 gave `.pst` a second dictionary layout and kept the first one **readable**, and a
     * reader that is still reachable is a reader that still has to refuse damage. Sweeping only the
     * current version would leave the version-1 path — the one that runs against every store written
     * before this build, which is the only kind of store this can matter for — covered by nothing but
     * two well-formed golden files.
     *
     * Deliberately more than sixteen terms, so the version-2 file it is compared against has more than
     * one restart point and the sweeps see a restart array rather than a degenerate one.
     */
    private fun legacyPostingFile(): ByteArray {
        val builder = LegacyPostingBuilder()
        for (ordinal in 0 until 59) builder.add(IndexTerm.ofString("v${ordinal % 9}").bytes, ordinal)
        builder.add(IndexTerm.ofString("solo").bytes, 59)
        return builder.build(segmentNumber = 3, indexId = 2, path = "$.team", documentCount = 60, largestSequence = 500)
    }

    /** Twenty-one terms, so the dictionary spans two restart groups and the second is partial. */
    private fun multiRestartPostingFile(): ByteArray {
        val builder = PostingBuilder(maxTerms = 1024)
        for (ordinal in 0 until 21) builder.add(IndexTerm.ofString("term-%03d".format(ordinal)).bytes, ordinal)
        return builder.build(segmentNumber = 3, indexId = 2, path = "$.team", documentCount = 21, largestSequence = 500)
    }

    /** The index of the one term whose posting list is a single ordinal. Sorts first: `s` before `v`. */
    private val soloTerm = 0

    private fun registryFile(): ByteArray = IndexRegistry.encode(
        RegistryContents(3, listOf(IndexHandle(1, IndexDefinition.inverted("$.team"), 10))),
    )

    private fun assertReported(bytes: ByteArray, note: String, open: (ByteArray) -> Unit) {
        val failure = runCatching { open(bytes) }.exceptionOrNull()
        assertTrue(
            failure is IndexException,
            "$note gave ${failure?.let { it::class.qualifiedName }}: ${failure?.message}",
        )
    }

    private fun sweepTruncations(complete: ByteArray, name: String, open: (ByteArray) -> Unit) {
        for (limit in 0 until complete.size) {
            assertReported(complete.copyOf(limit), "$name truncated to $limit byte(s)", open)
        }
    }

    private fun openBase(bytes: ByteArray) {
        BaseSidecar.open(MemorySegment.ofArray(bytes), bytes.size, "0000000003.idx", 3).verify()
    }

    private fun openPosting(bytes: ByteArray) {
        PostingFile.open(MemorySegment.ofArray(bytes), bytes.size, "0000000003.0002.pst", 3, 2, "$.team", 500).verify()
    }

    @Test
    fun `a truncated base sidecar is reported at every offset`() {
        sweepTruncations(baseSidecar(), "base sidecar", ::openBase)
    }

    @Test
    fun `a truncated version-1 base sidecar is reported at every offset`() {
        sweepTruncations(legacyBaseSidecar(), "version-1 base sidecar", ::openBase)
    }

    @Test
    fun `a flipped bit anywhere in a version-1 base sidecar is reported`() {
        val complete = legacyBaseSidecar()
        for (offset in complete.indices) {
            val damaged = complete.copyOf()
            damaged[offset] = (damaged[offset].toInt() xor 0x40).toByte()
            assertReported(damaged, "a version-1 base sidecar with byte $offset flipped", ::openBase)
        }
    }

    @Test
    fun `a truncated posting file is reported at every offset`() {
        sweepTruncations(postingFile(), "posting file", ::openPosting)
    }

    @Test
    fun `a truncated version-1 posting file is reported at every offset`() {
        sweepTruncations(legacyPostingFile(), "version-1 posting file", ::openPosting)
    }

    @Test
    fun `a truncated multi-restart posting file is reported at every offset`() {
        sweepTruncations(multiRestartPostingFile(), "multi-restart posting file") {
            PostingFile.open(MemorySegment.ofArray(it), it.size, "0000000003.0002.pst", 3, 2, "$.team", 500).verify()
        }
    }

    @Test
    fun `a flipped bit anywhere in a version-1 posting directory is still refused`() {
        // The version-1 reader is not a museum piece: it runs against every store written before
        // phase 17. Its header checksum has to keep catching what it always caught.
        val complete = legacyPostingFile()
        val directoryOffset = readU32(complete, 44)
        for (offset in directoryOffset until directoryOffset + 24) {
            val damaged = complete.copyOf()
            damaged[offset] = (damaged[offset].toInt() xor 0x40).toByte()
            assertReported(damaged, "a version-1 posting file with byte $offset flipped") {
                PostingFile.open(MemorySegment.ofArray(it), it.size, "p.pst", 3, 2, "$.team", 500)
            }
        }
    }

    /**
     * A padded varint is corruption, because a format with two spellings of one value has no
     * canonical form.
     *
     * This is the rule byte identity between a flush-written sidecar and a backfill-rebuilt one rests
     * on, and it is the one new failure mode phase 17 introduces. Reaching it needs the header
     * checksum recomputed — a padded varint is otherwise caught as damage first — which is exactly
     * what a writer that had made this mistake would produce. Without that step the test would pass
     * against a reader that never checked minimality at all.
     */
    @Test
    fun `a non-minimal varint is reported rather than accepted`() {
        val builder = PostingBuilder(maxTerms = 1024)
        for (ordinal in 0 until 4) builder.add(IndexTerm.ofString("t$ordinal").bytes, ordinal)
        val original = builder.build(3, 2, "$.team", 4, 500)

        val directoryOffset = readU32(original, 44)
        val termsOffset = directoryOffset + 4 * IndexFormat.POSTING_V2_TERM_ENTRY_BYTES +
            IndexFormat.postingRestartCount(4) * 4
        // The first term's `shared`, which is a restart and therefore a single zero byte. `0x80 0x00`
        // is the same value spelled in two bytes: legal LEB128, and not legal here.
        val padded = original.copyOfRange(0, termsOffset) +
            byteArrayOf(0x80.toByte(), 0x00) +
            original.copyOfRange(termsOffset + 1, original.size)

        // Everything after the term region moved by one byte, so the presence triple has to follow or
        // the file fails for a reason that is not the one under test.
        val presenceOffset = readU32(original, IndexFormat.POSTING_PRESENCE_OFFSET)
        writeU32(padded, IndexFormat.POSTING_PRESENCE_OFFSET, presenceOffset + 1)
        writeU32(
            padded,
            IndexFormat.POSTING_CHECKSUM_OFFSET,
            IndexFormat.checksum(
                padded,
                IndexFormat.MAGIC_BYTES,
                IndexFormat.POSTING_CHECKSUM_OFFSET - IndexFormat.MAGIC_BYTES,
                IndexFormat.POSTING_CHECKSUM_OFFSET + 4,
                presenceOffset + 1 - IndexFormat.POSTING_CHECKSUM_OFFSET - 4,
            ),
        )

        val file = PostingFile.open(MemorySegment.ofArray(padded), padded.size, "p.pst", 3, 2, "$.team", 500)
        assertReported(padded, "a padded varint") { file.termAt(0) }
    }

    /**
     * The restart array is checked when the file is opened, because it decides *where* a term is.
     *
     * The open side of the two-level split, for the one structure phase 17 added to it. A restart that
     * does not ascend cannot be a restart array, and finding that out lazily would mean a query
     * getting a term from the wrong group rather than a report.
     */
    @Test
    fun `a restart array that does not ascend is caught on open`() {
        val original = multiRestartPostingFile()
        val termCount = readU32(original, 40)
        val directoryOffset = readU32(original, 44)
        val restartsOffset = directoryOffset + termCount * IndexFormat.POSTING_V2_TERM_ENTRY_BYTES
        assertTrue(IndexFormat.postingRestartCount(termCount) >= 2, "the fixture needs two restarts")

        val damaged = original.copyOf()
        // Point the second restart back at the first, which no ascending array can do.
        writeU32(damaged, restartsOffset + 4, 0)
        val presenceOffset = readU32(damaged, IndexFormat.POSTING_PRESENCE_OFFSET)
        writeU32(
            damaged,
            IndexFormat.POSTING_CHECKSUM_OFFSET,
            IndexFormat.checksum(
                damaged,
                IndexFormat.MAGIC_BYTES,
                IndexFormat.POSTING_CHECKSUM_OFFSET - IndexFormat.MAGIC_BYTES,
                IndexFormat.POSTING_CHECKSUM_OFFSET + 4,
                presenceOffset - IndexFormat.POSTING_CHECKSUM_OFFSET - 4,
            ),
        )

        assertReported(damaged, "a restart array that does not ascend") {
            // No `verify`: opening alone must find it.
            PostingFile.open(MemorySegment.ofArray(it), it.size, "p.pst", 3, 2, "$.team", 500)
        }
    }

    /**
     * A padded varint in a key entry is corruption, exactly as it is in a term record.
     *
     * The rule is `IndexBytes.varint`'s and phase 18 put a second region under it, so it needs a
     * second reproduction: one rule tested on one caller is a rule tested on half the format. As with
     * the `.pst` version, the damage has to be *framed correctly* or it is caught as damage before the
     * check under test runs — here that is free, because rebuilding the sidecar through
     * `SectionDirectory.encode` checksums the body it is given. Which is precisely what a writer that
     * had made this mistake would have produced.
     */
    @Test
    fun `a non-minimal varint in a key entry is reported rather than accepted`() {
        val keys = (0 until 40).map { Key.of("key:%05d".format(it)) }
        val body = keyBlockBody(keys)
        val restartsAt = body.size - 4 - 3 * 4
        assertEquals(3, readU32(body, body.size - 4), "the fixture needs three restart groups")

        // Entry 0 is a restart, so its `shared` is a single zero byte. `0x80 0x00` is the same value
        // spelled in two bytes: legal LEB128, and not legal here. Everything after it moves by one,
        // so restarts 1 and 2 follow — a damaged file that is otherwise self-consistent.
        val padded = byteArrayOf(0x80.toByte(), 0x00) + body.copyOfRange(1, restartsAt) +
            ByteArray(16).also { tail ->
                writeU32(tail, 0, 0)
                writeU32(tail, 4, readU32(body, restartsAt + 4) + 1)
                writeU32(tail, 8, readU32(body, restartsAt + 8) + 1)
                writeU32(tail, 12, 3)
            }

        assertReported(baseSidecarWith(padded, keys.size), "a padded varint in a key entry", ::openBase)
    }

    /**
     * A restart array that does not ascend is reported by `verify`, not by opening the file.
     *
     * Deliberately the other side of the line from the posting file's restart array, and the asymmetry
     * is the design rather than an oversight. A `.pst` checks its restarts on open because it has a
     * term count and is opened once; a key block's restart count grows with the *segment*, and
     * `keyAt` resolves one ordinal in constant time — so walking `documentCount / 16` offsets before
     * the first lookup would put an `O(n)` cost on the read path to catch damage that makes a key
     * wrong rather than a read wild. `BaseSidecar.verify` is where the `O(documentCount)` diagnostic
     * already lives, so it is where this belongs.
     */
    @Test
    fun `a restart array that does not ascend is reported by verify`() {
        val keys = (0 until 40).map { Key.of("key:%05d".format(it)) }
        val body = keyBlockBody(keys)
        val restartsAt = body.size - 4 - 3 * 4

        val damaged = body.copyOf()
        // Point the second restart back at the first, which no ascending array can do.
        writeU32(damaged, restartsAt + 4, 0)
        val sidecar = baseSidecarWith(damaged, keys.size)

        // Opening alone must *not* find it — that is the claim about where the cost is paid.
        BaseSidecar.open(MemorySegment.ofArray(sidecar), sidecar.size, "0000000003.idx", 3)
        assertReported(sidecar, "a restart array that does not ascend", ::openBase)
    }

    /** A well-formed version-2 key block over [keys]. */
    private fun keyBlockBody(keys: List<Key>): ByteArray {
        val writer = KeyBlockWriter()
        keys.forEach { writer.add(it.toByteArray()) }
        return writer.build()
    }

    /**
     * A base sidecar whose `KEYS` section is [keysBody], framed and checksummed correctly.
     *
     * Rebuilding rather than patching is what makes the two tests above reach the check they name:
     * `SectionDirectory.encode` computes the section checksum over whatever body it is handed, so the
     * only thing wrong with the file is the thing under test.
     */
    private fun baseSidecarWith(keysBody: ByteArray, count: Int): ByteArray {
        val meta = IndexWriter(IndexFormat.META_BYTES)
        meta.writeLong(3)
        meta.writeU32(count)
        meta.writeU32(0)
        meta.writeLong(100)
        meta.writeLong(100L + count)
        val present = Bitmap().also { bitmap -> (0 until count).forEach(bitmap::add) }
        return SectionDirectory.encode(
            IndexFormat.BASE_MAGIC,
            IndexFormat.BASE_VERSION,
            listOf(
                IndexFormat.SECTION_KIND_META to meta.toByteArray(),
                IndexFormat.SECTION_KIND_KEYS to keysBody,
                IndexFormat.SECTION_KIND_PRESENT to present.encode(),
            ),
        )
    }

    /**
     * A version above this build's is **unsupported**, not damaged — in every file this module writes.
     *
     * The individual checks existed; the coverage did not. `COMPATIBILITY.md` states this across the
     * whole format, so it is asserted across the whole module rather than wherever a corruption test
     * happened to need it: a claim about every file, held by a test over every file.
     *
     * No checksum arithmetic, and that is a property rather than a convenience. All three headers put
     * the version at offset 8 and check it *before* the checksum it sits under, which is the ordering
     * the engine uses everywhere — a reader must not report a file it cannot understand as a file that
     * is broken, and it cannot tell the difference after a checksum it computed with the wrong layout.
     */
    @Test
    fun `a newer format version is unsupported rather than damaged, in every index file`() {
        fun assertUnsupported(complete: ByteArray, newer: Int, note: String, open: (ByteArray) -> Unit) {
            val bytes = complete.copyOf()
            writeU32(bytes, 8, newer)
            val failure = runCatching { open(bytes) }.exceptionOrNull()
            assertTrue(
                failure is UnsupportedIndexFormatException,
                "$note must say this build is too old, not that the file is broken: $failure",
            )
        }

        assertUnsupported(baseSidecar(), IndexFormat.BASE_VERSION + 1, "a newer base sidecar", ::openBase)
        assertUnsupported(postingFile(), IndexFormat.POSTING_VERSION + 1, "a newer posting file", ::openPosting)
        assertUnsupported(registryFile(), IndexFormat.REGISTRY_VERSION + 1, "a newer registry") {
            IndexRegistry.decode(it, "INDEXES")
        }
    }

    /**
     * **A path this build cannot read is unsupported, not damaged**, and the distinction is the one
     * thing a downgrade turns on.
     *
     * The registry stores a path as text and reads it with `CatalogPath.parse`, so it is the one
     * field here whose *vocabulary* can grow: `..` became a step in a later release, and a registry
     * written by that build carries a path this reader cannot parse. Every byte is intact and the
     * checksum agrees, so reporting damage would send somebody looking for a failing disk — and
     * under `DamagedIndexPolicy.REBUILD` it would **delete an index definition**, which is the one
     * piece of derived data that cannot be rebuilt from a segment.
     *
     * Simulated by writing a path the *current* parser rejects, which is exactly the shape a future
     * step would take.
     */
    @Test
    fun `a registry naming a path this build cannot parse is unsupported rather than damaged`() {
        val encoded = IndexRegistry.encode(
            RegistryContents(3, listOf(IndexHandle(1, IndexDefinition.inverted("$.team"), 10))),
        )
        // The same width, so only the *vocabulary* changes — and then the checksum is recomputed,
        // because a file from a newer build is intact by definition and a mismatched one would be
        // testing the checksum instead of the parser.
        val text = String(encoded, Charsets.ISO_8859_1).replace("$.team", "$.tea~")
        val fromTheFuture = text.toByteArray(Charsets.ISO_8859_1)
        val body = fromTheFuture.copyOfRange(IndexFormat.REGISTRY_HEADER_BYTES, fromTheFuture.size)
        writeU32(
            fromTheFuture,
            16,
            IndexFormat.checksum(fromTheFuture, IndexFormat.MAGIC_BYTES, 8, body),
        )

        val failure = runCatching { IndexRegistry.decode(fromTheFuture, "INDEXES") }.exceptionOrNull()
        assertTrue(
            failure is UnsupportedIndexFormatException,
            "a path from a newer grammar must read as unsupported, not as corruption: $failure",
        )
        assertTrue(
            failure.message!!.contains("newer release"),
            "and must say so, since the fix is a newer build rather than a rebuild: ${failure.message}",
        )
    }

    @Test
    fun `a truncated registry is reported at every offset`() {
        sweepTruncations(registryFile(), "registry") { IndexRegistry.decode(it, "INDEXES") }
    }

    @Test
    fun `trailing bytes are reported rather than ignored`() {
        assertReported(baseSidecar() + byteArrayOf(0), "a base sidecar with a trailing byte", ::openBase)
        assertReported(registryFile() + byteArrayOf(0), "a registry with a trailing byte") {
            IndexRegistry.decode(it, "INDEXES")
        }
    }

    @Test
    fun `a flipped bit anywhere in a base sidecar is reported`() {
        val complete = baseSidecar()
        for (offset in complete.indices) {
            val damaged = complete.copyOf()
            damaged[offset] = (damaged[offset].toInt() xor 0x40).toByte()
            assertReported(damaged, "a base sidecar with byte $offset flipped", ::openBase)
        }
    }

    @Test
    fun `a directory flip is caught on open and a posting flip only when it is read`() {
        val complete = postingFile()
        val opened = PostingFile.open(MemorySegment.ofArray(complete), complete.size, "p.pst", 3, 2, "$.team", 500)

        // A flip inside the directory changes where a byte *is*, so the header checksum catches it
        // before anything is read.
        val inDirectory = complete.copyOf()
        inDirectory[IndexFormat.POSTING_HEADER_BYTES + 8] =
            (inDirectory[IndexFormat.POSTING_HEADER_BYTES + 8].toInt() xor 0x01).toByte()
        assertReported(inDirectory, "a flip in the posting directory") { openPosting(it) }

        // A flip inside a posting list is *not* caught by opening the file — which is the whole
        // reason opening a ten-million-key sidecar is cheap — and is caught by its own entry's
        // checksum the moment that posting is touched.
        val lastPosting = complete.size - 4
        val inPosting = complete.copyOf()
        inPosting[lastPosting] = (inPosting[lastPosting].toInt() xor 0x01).toByte()
        val stillOpens = runCatching {
            PostingFile.open(MemorySegment.ofArray(inPosting), inPosting.size, "p.pst", 3, 2, "$.team", 500)
        }
        assertTrue(stillOpens.isSuccess, "a posting flip must not stop the file opening")
        assertReported(inPosting, "a flip inside a posting list") {
            PostingFile.open(MemorySegment.ofArray(it), it.size, "p.pst", 3, 2, "$.team", 500).verify()
        }
        assertEquals(10, opened.termCount)
    }

    /**
     * A singleton's ordinal lives in the directory, so it is on the *open* side of the split.
     *
     * The clause the inline encoding adds to the rule above, asserted rather than left to be inferred:
     * the header checksum already covers every byte that decides where a posting is, and for
     * [IndexFormat.POSTING_ENCODING_SINGLE] the ordinal *is* one of those bytes. That is a stronger
     * guarantee than a bitmap posting gets, not a weaker one — and it costs nothing, because a file of
     * singletons has no posting region for the deferred half to skip.
     */
    @Test
    fun `a flip in an inline singleton ordinal is caught when the file is opened`() {
        val complete = postingFile()
        val directoryOffset = readU32(complete, 44)
        // Version 2 puts the posting fields first in the entry, so the inline ordinal is the entry.
        val at = directoryOffset + soloTerm * IndexFormat.POSTING_V2_TERM_ENTRY_BYTES

        val damaged = complete.copyOf()
        damaged[at] = (damaged[at].toInt() xor 0x08).toByte()
        assertReported(damaged, "a flip in an inline singleton ordinal") {
            // No `verify`: opening alone must find it.
            PostingFile.open(MemorySegment.ofArray(it), it.size, "p.pst", 3, 2, "$.team", 500)
        }
    }

    /**
     * An encoding this build does not know is **unsupported**, not damaged.
     *
     * The two are repaired differently — one by getting a newer build, one by rebuilding the sidecar —
     * so conflating them would send a reader after the wrong problem. Reaching the check needs the
     * header checksum recomputed, because a retag is otherwise caught as damage first; that is the
     * writer's own arithmetic, which is exactly what a file from a newer build would carry.
     */
    @Test
    fun `a posting encoding from a newer build is reported as unsupported`() {
        val bytes = postingFile()
        val directoryOffset = readU32(bytes, 44)
        bytes[directoryOffset + soloTerm * IndexFormat.POSTING_V2_TERM_ENTRY_BYTES + 8] = 3

        val postingsOffset = readU32(bytes, IndexFormat.POSTING_PRESENCE_OFFSET)
        writeU32(
            bytes,
            IndexFormat.POSTING_CHECKSUM_OFFSET,
            IndexFormat.checksum(
                bytes,
                IndexFormat.MAGIC_BYTES,
                IndexFormat.POSTING_CHECKSUM_OFFSET - IndexFormat.MAGIC_BYTES,
                IndexFormat.POSTING_CHECKSUM_OFFSET + 4,
                postingsOffset - IndexFormat.POSTING_CHECKSUM_OFFSET - 4,
            ),
        )

        val file = PostingFile.open(MemorySegment.ofArray(bytes), bytes.size, "p.pst", 3, 2, "$.team", 500)
        val failure = runCatching { file.postingAt(soloTerm) }.exceptionOrNull()
        assertTrue(
            failure is UnsupportedIndexFormatException,
            "an unknown encoding must say the build is too old, not that the file is broken: $failure",
        )
    }

    @Test
    fun `a damaged sidecar is reported on attach and rebuilt under the repair policy`(@TempDir root: Path) {
        val directory = scratch(root)
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, indexStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                catalog.createIndex(store, IndexDefinition.inverted("$.team"))
                (0 until 200).forEach { store.put(keyFor(it), jsonDocument("""{"team":"t${it % 7}"}""")) }
                store.flush()
            }
        }

        val victim = sidecarNames(directory).first { postingNumbers(it) != null }
        // Damage the *directory* region, which the header checksum covers and `open` therefore
        // checks. A flip inside a posting list would not be found here, and deliberately so — that
        // is what makes opening a sidecar cheap, and it is asserted directly in the bit-flip test
        // above. Attaching is an open, not a read.
        val bytes = Files.readAllBytes(directory.resolve(victim))
        val offset = IndexFormat.POSTING_HEADER_BYTES + 2
        bytes[offset] = (bytes[offset].toInt() xor 0xFF).toByte()
        Files.write(directory.resolve(victim), bytes)

        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, indexStoreOptions(catalog)).use { store ->
                // REPORT is the default, because silent repair hides a disk that is going. It fails
                // before writing anything, so the damaged file is still damaged afterwards — which is
                // what the second half of this test then runs against.
                val failure = runCatching { catalog.attach(store) }.exceptionOrNull()
                assertTrue(failure is CorruptIndexException, "expected a report, got $failure")
            }
        }

        IndexCatalog(directory, IndexOptions(damagedSidecars = DamagedIndexPolicy.REBUILD)).use { catalog ->
            DocumentStore.open(directory, indexStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                assertEquals(1, catalog.problems.size, "the damage is recorded rather than swallowed")
                // Derived data: the repair is a rescan of the segment, and it costs nothing else.
                assertEquals(segmentNumbers(directory), baseSidecarNumbers(directory))
                assertTrue(postingFiles(directory).isNotEmpty())
            }
        }
    }

    /**
     * A sidecar from a **newer** build is reported under `REPORT` and *rewritten downward* under
     * `REBUILD`, and the second half is why this test exists.
     *
     * `COMPATIBILITY.md` declares this rather than leaving it to be discovered: both repair policies
     * catch the base `IndexException`, which covers `UnsupportedIndexFormatException` as well as
     * damage, so an older build sharing a store with a newer one silently replaces the newer sidecars
     * with its own. That is defensible — a sidecar is derived data and a rebuild costs a rescan and
     * never a document — but it is a documented behaviour, and a documented behaviour with no test is
     * the defect this suite exists to remove. The sibling above covers the *damaged* half; nothing
     * covered the unsupported one.
     *
     * No checksum arithmetic here, unlike the encoding test above: `PostingFile.open` reads the version
     * before it verifies anything the checksum covers, which is the same ordering every format in the
     * engine uses for a version it cannot understand.
     */
    @Test
    fun `a sidecar from a newer build is reported, and rebuilt downward under the repair policy`(
        @TempDir root: Path,
    ) {
        val directory = scratch(root)
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, indexStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                catalog.createIndex(store, IndexDefinition.inverted("$.team"))
                (0 until 200).forEach { store.put(keyFor(it), jsonDocument("""{"team":"t${it % 7}"}""")) }
                store.flush()
            }
        }

        val victim = sidecarNames(directory).first { postingNumbers(it) != null }
        val bytes = Files.readAllBytes(directory.resolve(victim))
        writeU32(bytes, 8, IndexFormat.POSTING_VERSION + 1)
        Files.write(directory.resolve(victim), bytes)

        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, indexStoreOptions(catalog)).use { store ->
                val failure = runCatching { catalog.attach(store) }.exceptionOrNull()
                assertTrue(
                    failure is UnsupportedIndexFormatException,
                    "a newer sidecar must say this build is too old, not that the file is broken: $failure",
                )
            }
        }

        IndexCatalog(directory, IndexOptions(damagedSidecars = DamagedIndexPolicy.REBUILD)).use { catalog ->
            DocumentStore.open(directory, indexStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                assertEquals(1, catalog.problems.size, "the downgrade is recorded rather than swallowed")
                assertTrue(postingFiles(directory).isNotEmpty())
                // The point of the test: the file is not merely readable again, it is this build's
                // version. That is the sentence `COMPATIBILITY.md` has to be able to make.
                val rebuilt = sidecarNames(directory).first { postingNumbers(it) != null }
                assertEquals(
                    IndexFormat.POSTING_VERSION,
                    readU32(Files.readAllBytes(directory.resolve(rebuilt)), 8),
                    "REBUILD rewrites a newer sidecar in the format this build writes",
                )
            }
        }
    }
}
