package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.catalog.CatalogPath
import app.oreshkov.rabosh.catalog.IndexKind
import app.oreshkov.rabosh.catalog.ValueSignature
import app.oreshkov.rabosh.core.Key
import java.lang.foreign.MemorySegment
import java.util.TreeMap
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The on-disk ids, pinned.
 *
 * Every number here is written into files that outlive this build, and `CLAUDE.md`'s rule is *add,
 * never renumber*. An exhaustive `when` makes the compiler force a decision when a kind is added; it
 * cannot stop somebody changing an existing number, and that is what these assertions are for. A
 * renumbering should fail the build here rather than be discovered by a reader six months later.
 */
class IndexFormatTest {

    @Test
    fun `magics are the ones on disk`() {
        assertEquals("JKDB-IXR", IndexFormat.REGISTRY_MAGIC.decodeToString())
        assertEquals("JKDB-IDX", IndexFormat.BASE_MAGIC.decodeToString())
        assertEquals("JKDB-PST", IndexFormat.POSTING_MAGIC.decodeToString())
        // Distinct from every other magic in the engine, and from each other.
        val all = listOf("JKDB-WAL", "JKDB-SEG", "JKDB-MAN", "JKDB-CAT", "JKDB-IXR", "JKDB-IDX", "JKDB-PST")
        assertEquals(all.size, all.toSet().size)
        assertTrue(all.all { it.length == IndexFormat.MAGIC_BYTES })
    }

    /**
     * A version bump is not a renumbering, and phase 17 is the first one taken here.
     *
     * The permanence rule is about *ids* — a section kind, an encoding byte, a type tag — because an
     * id is read by a build that never heard of the value that replaced it and has nothing in the file
     * to warn it. A version is the one number a reader checks *before* it believes anything else, so a
     * new one means "you may not understand this" rather than "reinterpret what you already read".
     * Both numbers are pinned here for that reason: the version because raising it is a decision, and
     * version 1 because this build still reads it and must go on doing so.
     */
    @Test
    fun `format versions are pinned, and version 1 is still read`() {
        assertEquals(1, IndexFormat.REGISTRY_VERSION)
        assertEquals(2, IndexFormat.BASE_VERSION)
        assertEquals(1, IndexFormat.BASE_VERSION_FLAT)
        assertEquals(2, IndexFormat.POSTING_VERSION)
        assertEquals(1, IndexFormat.POSTING_VERSION_FLAT)
        // Both, and in that order, because `SegmentIndex.open` throws on a sidecar it cannot decode:
        // dropping version 1 from this array would fail `attach` on every older store rather than
        // rebuilding it, which is the thing that forces the old reader to stay.
        assertContentEquals(intArrayOf(2, 1), IndexFormat.BASE_VERSIONS)
    }

    @Test
    fun `section kinds are permanent`() {
        assertEquals(1, IndexFormat.SECTION_KIND_META)
        assertEquals(2, IndexFormat.SECTION_KIND_KEYS)
        assertEquals(3, IndexFormat.SECTION_KIND_PRESENT)
        // Reserved for a shredded column before columns existed, and permanently unused now that they
        // do: phase 7b gave `.col` its own section-kind namespace. Pinned anyway — an id published as
        // meaning one thing must not be repurposed to mean another, whether or not it was ever written.
        assertEquals(4, IndexFormat.SECTION_KIND_COLUMN)
        assertNull(IndexFormat.sectionKindName(5), "an unknown kind must not be given a name")
    }

    @Test
    fun `index kinds carry their own ids, never the enum ordinal`() {
        assertEquals(1, IndexFormat.indexKindId(IndexKind.INVERTED))
        assertEquals(2, IndexFormat.indexKindId(IndexKind.SHREDDED_COLUMN))
        // The trap this avoids: inserting a kind at the front would shift every ordinal and silently
        // change what every registry ever written means. Ids start at one, ordinals at zero, so the
        // two can never coincide by accident either.
        for (kind in IndexKind.entries) {
            assertEquals(kind, IndexFormat.indexKindOfId(IndexFormat.indexKindId(kind)))
            assertTrue(IndexFormat.indexKindId(kind) != kind.ordinal)
        }
        assertNull(IndexFormat.indexKindOfId(7))
    }

    @Test
    fun `posting encodings are permanent`() {
        assertEquals(1, IndexFormat.POSTING_ENCODING_BITMAP)
        assertEquals("BITMAP", IndexFormat.postingEncodingName(1))
        // Added in phase 11 as a new id on a format that already existed, which is why
        // POSTING_VERSION is still 1 above. Renumbering either would change what every posting file
        // ever written means.
        assertEquals(2, IndexFormat.POSTING_ENCODING_SINGLE)
        assertEquals("SINGLE", IndexFormat.postingEncodingName(2))
        assertNull(IndexFormat.postingEncodingName(3), "an unknown encoding must not be given a name")
    }

    @Test
    fun `record widths are what the layout says`() {
        assertEquals(20, IndexFormat.REGISTRY_HEADER_BYTES)
        assertEquals(20, IndexFormat.BASE_HEADER_BYTES)
        assertEquals(20, IndexFormat.BASE_ENTRY_BYTES)
        assertEquals(32, IndexFormat.META_BYTES)
        assertEquals(64, IndexFormat.POSTING_HEADER_BYTES)
        assertEquals(24, IndexFormat.POSTING_V1_TERM_ENTRY_BYTES)
        assertEquals(16, IndexFormat.POSTING_V2_TERM_ENTRY_BYTES)
        assertEquals(48, IndexFormat.POSTING_PRESENCE_OFFSET)
        assertEquals(60, IndexFormat.POSTING_CHECKSUM_OFFSET)
        assertEquals(16, IndexFormat.KEY_RESTART_INTERVAL)
        // The version-1 key entry header. Version 2 has no constant to pin, which is the whole of
        // what phase 18 did: two varints are two bytes at every key length this engine sees.
        assertEquals(8, IndexFormat.KEY_V1_ENTRY_HEADER_BYTES)
        // The same number as the key block's interval and a separate constant on purpose: different
        // files, different version fields, and different questions — a key block is entered by
        // ordinal, a term dictionary by value.
        assertEquals(16, IndexFormat.POSTING_TERM_RESTART_INTERVAL)
    }

    @Test
    fun `the restart count is derived from the term count and nothing else`() {
        // Stored nowhere, so it cannot disagree with itself — the rule `KeyBlockReader` already
        // applies to the KEYS section. Checked *at* the interval, because 16 and 17 are the two
        // values an off-by-one tells apart.
        assertEquals(0, IndexFormat.postingRestartCount(0))
        assertEquals(1, IndexFormat.postingRestartCount(1))
        assertEquals(1, IndexFormat.postingRestartCount(16))
        assertEquals(2, IndexFormat.postingRestartCount(17))
        assertEquals(2, IndexFormat.postingRestartCount(32))
        assertEquals(3, IndexFormat.postingRestartCount(33))
    }

    @Test
    fun `signature tags are the ones the catalog already wrote`() {
        // Moved from SketchFormat to ValueSignature in this phase. Every HyperLogLog register in
        // every existing sidecar is a function of them, so the move had to preserve the numbers.
        assertEquals(0, ValueSignature.BOOLEAN)
        assertEquals(1, ValueSignature.NUMERIC)
        assertEquals(2, ValueSignature.TEXT)
        assertEquals(3, ValueSignature.BINARY)
        assertEquals(4, ValueSignature.TEMPORAL)
        assertEquals(5, ValueSignature.UUID)
    }

    @Test
    fun `filenames round-trip through their parsers`() {
        assertEquals("0000000042.idx", baseFileName(42))
        assertEquals("0000000042.0007.pst", postingFileName(42, 7))
        assertEquals(42L, baseSegmentNumber(baseFileName(42)))
        assertEquals(42L to 7, postingNumbers(postingFileName(42, 7)))
        // Ten digits so name order is number order, as everything else in the directory does it.
        assertTrue(baseFileName(1) < baseFileName(2))
        assertTrue(baseFileName(9) < baseFileName(10))
        // Names that are not ours are not claimed.
        assertNull(baseSegmentNumber("0000000042.seg"))
        assertNull(baseSegmentNumber("CURRENT"))
        assertNull(postingNumbers("0000000042.idx"))
        assertNull(postingNumbers("notanumber.0001.pst"))
        assertNull(postingNumbers("0000000042.notanumber.pst"))
    }
}

/** The registry, the base sidecar and the posting file, round-tripped without a store. */
class SidecarRoundTripTest {

    @Test
    fun `the registry round-trips`() {
        val contents = RegistryContents(
            nextIndexId = 9,
            indexes = listOf(
                IndexHandle(1, IndexDefinition.inverted("$.team"), 100),
                IndexHandle(4, IndexDefinition.inverted("$.items[*].sku"), 250),
                IndexHandle(8, IndexDefinition(CatalogPath.parse("$.legacy"), IndexKind.SHREDDED_COLUMN), 300),
            ),
        )
        val decoded = IndexRegistry.decode(IndexRegistry.encode(contents), "INDEXES")
        assertEquals(contents.nextIndexId, decoded.nextIndexId)
        assertEquals(contents.indexes.map { it.id }, decoded.indexes.map { it.id })
        assertEquals(contents.indexes.map { it.definition }, decoded.indexes.map { it.definition })
        assertEquals(contents.indexes.map { it.createdAtSequence }, decoded.indexes.map { it.createdAtSequence })
    }

    @Test
    fun `an empty registry round-trips`() {
        val decoded = IndexRegistry.decode(IndexRegistry.encode(RegistryContents.EMPTY), "INDEXES")
        assertEquals(1, decoded.nextIndexId)
        assertTrue(decoded.indexes.isEmpty())
    }

    @Test
    fun `a registry naming an id at or above the next one is rejected`() {
        // Otherwise an id could be handed out twice, and a stale posting file from a dropped index
        // would be readable as a live one's postings.
        val bad = RegistryContents(nextIndexId = 2, indexes = listOf(IndexHandle(2, IndexDefinition.inverted("$.a"), 0)))
        assertFailsWithMessage("could be reused") { IndexRegistry.decode(IndexRegistry.encode(bad), "INDEXES") }
    }

    @Test
    fun `the base sidecar round-trips`() {
        val builder = BaseSidecarBuilder()
        val keys = (0 until 70).map { keyFor(it) }
        keys.forEachIndexed { ordinal, key -> builder.observe(key, 1000L + ordinal, isPut = ordinal % 7 != 0) }
        val encoded = builder.build(segmentNumber = 12)

        val sidecar = BaseSidecar.open(MemorySegment.ofArray(encoded), encoded.size, "0000000012.idx", 12)
        assertEquals(12L, sidecar.segmentNumber)
        assertEquals(70, sidecar.documentCount)
        assertEquals(10, sidecar.tombstoneCount)
        assertEquals(1000L, sidecar.smallestSequence)
        assertEquals(1069L, sidecar.largestSequence)
        for (ordinal in keys.indices) assertEquals(keys[ordinal], sidecar.keyAt(ordinal))
        assertEquals((0 until 70).filter { it % 7 != 0 }, sidecar.present().toIntArray().toList())
        sidecar.verify()
    }

    @Test
    fun `a base sidecar filed under the wrong segment is refused`() {
        val builder = BaseSidecarBuilder()
        builder.observe(keyFor(0), 1, isPut = true)
        val encoded = builder.build(segmentNumber = 12)
        assertFailsWithMessage("describes segment 12") {
            BaseSidecar.open(MemorySegment.ofArray(encoded), encoded.size, "0000000013.idx", 13)
        }
    }

    @Test
    fun `the posting file round-trips and bisects to the same answers as a TreeMap`() {
        val model = TreeMap<IndexTerm, MutableSet<Int>>()
        val builder = PostingBuilder(maxTerms = 1 shl 16)
        for (ordinal in 0 until 500) {
            val term = IndexTerm.ofString("value-${ordinal % 37}")
            builder.add(term.bytes, ordinal)
            model.getOrPut(term) { sortedSetOf() }.add(ordinal)
        }
        val encoded = builder.build(
            segmentNumber = 5,
            indexId = 3,
            path = "$.team",
            documentCount = 500,
            largestSequence = 900,
        )

        val file = PostingFile.open(
            MemorySegment.ofArray(encoded),
            encoded.size,
            "0000000005.0003.pst",
            5,
            3,
            "$.team",
            900,
        )
        assertEquals(model.size, file.termCount)
        assertEquals("$.team", file.path)
        file.verify()

        for ((term, ordinals) in model) {
            val postings = assertNotNull(file.postings(term.bytes), "postings for $term")
            assertEquals(ordinals.toList(), postings.toIntArray().toList())
        }
        assertNull(file.postings(IndexTerm.ofString("absent").bytes))
        // Presence is the union of every posting list, stored once.
        assertEquals((0 until 500).toList(), file.presence().toIntArray().toList())
        // Terms come out in ascending unsigned order, which is what the bisect depends on.
        val read = (0 until file.termCount).map { file.termAt(it) }
        assertEquals(read.sortedWith { a, b -> java.util.Arrays.compareUnsigned(a, b) }, read)
    }

    @Test
    fun `a posting file whose identity disagrees with the caller is refused`() {
        val builder = PostingBuilder(maxTerms = 16)
        builder.add(IndexTerm.ofString("a").bytes, 0)
        val encoded = builder.build(5, 3, "$.team", 10, 900)
        fun open(segment: Long, id: Int, path: String, sequence: Long) =
            PostingFile.open(MemorySegment.ofArray(encoded), encoded.size, "x.pst", segment, id, path, sequence)

        assertFailsWithMessage("describes segment 5") { open(6, 3, "$.team", 900) }
        assertFailsWithMessage("describes index #3") { open(5, 4, "$.team", 900) }
        assertFailsWithMessage("the registry says") { open(5, 3, "$.other", 900) }
        assertFailsWithMessage("its base sidecar says") { open(5, 3, "$.team", 901) }
    }

    @Test
    fun `the term budget drops the index rather than truncating it`() {
        val builder = PostingBuilder(maxTerms = 4)
        for (ordinal in 0 until 10) builder.add(IndexTerm.ofString("v$ordinal").bytes, ordinal)
        assertTrue(builder.overflowed)
        // Nothing is kept. A dictionary holding four of ten values, with no record of which four, is
        // an index that returns wrong answers and nothing downstream could tell it apart.
        assertEquals(0, builder.termCount)
    }

    @Test
    fun `a value with no signature is present but not a term`() {
        val builder = PostingBuilder(maxTerms = 16)
        builder.addPresenceOnly(3)
        builder.add(IndexTerm.ofString("x").bytes, 5)
        val encoded = builder.build(1, 1, "$.note", 10, 10)
        val file = PostingFile.open(MemorySegment.ofArray(encoded), encoded.size, "p.pst", 1, 1, "$.note", 10)
        // A `{"note": null}` document has a note. `EXISTS` must say so; `= "x"` must not.
        assertEquals(listOf(3, 5), file.presence().toIntArray().toList())
        assertContentEquals(intArrayOf(5), file.postings(IndexTerm.ofString("x").bytes)!!.toIntArray())
        file.verify()
    }

    private fun keyFor(index: Int): Key = Key.of("key:%06d".format(index))
}
