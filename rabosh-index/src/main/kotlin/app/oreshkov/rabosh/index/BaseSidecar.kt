package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.core.Key
import java.lang.foreign.MemorySegment
import java.nio.file.Path
import java.util.zip.CRC32C

/**
 * Accumulates one segment's base sidecar as its documents go past.
 *
 * Fed from a `SegmentObservation`, one call per distinct user key, in ascending key order — which is
 * where ordinals come from. The *k*-th call is ordinal *k*, and because `SegmentWriter` and
 * `DocumentStore.backfill` share the same distinct-key filter, a segment indexed as it was written
 * and the same segment indexed by a backfill assign identical ordinals. That is what lets the two
 * sidecars be compared byte for byte rather than merely believed to agree.
 *
 * **Tombstones consume an ordinal.** They have to: dropping them would make the numbering depend on
 * which versions a segment happens to hold, and the two paths would stop agreeing. What they do not
 * do is join [present], so a complement is over the documents rather than over the ordinals.
 */
internal class BaseSidecarBuilder(expectedKeys: Int = 1024) {
    private val keys = KeyBlockWriter(expectedKeys * 24)
    private val present = Bitmap()
    private var tombstones = 0
    private var minSequence = Long.MAX_VALUE
    private var maxSequence = Long.MIN_VALUE

    /** Ordinals assigned so far. The next document is this ordinal. */
    var count: Int = 0
        private set

    fun observe(userKey: Key, sequence: Long, isPut: Boolean) {
        if (count == BitmapFormat.MAX_ORDINAL) {
            throw CorruptIndexException(
                "a segment holding more than ${BitmapFormat.MAX_ORDINAL} documents cannot be indexed",
                "<building>",
            )
        }
        keys.add(userKey.toByteArray())
        if (isPut) present.add(count) else tombstones++
        minSequence = minOf(minSequence, sequence)
        maxSequence = maxOf(maxSequence, sequence)
        count++
    }

    /**
     * The largest sequence seen.
     *
     * This is the whole of the soundness guard. Each key's newest version carries that key's largest
     * sequence, so the maximum over the versions reported here is the maximum over every entry in the
     * segment — and an index built from newest-versions-only is sound exactly for the snapshots that
     * can see all of them. See `IndexCoverage`.
     */
    val largestSequence: Long get() = if (count == 0) 0 else maxSequence

    fun build(segmentNumber: Long): ByteArray {
        val meta = IndexWriter(IndexFormat.META_BYTES)
        meta.writeLong(segmentNumber)
        meta.writeU32(count)
        meta.writeU32(tombstones)
        meta.writeLong(if (count == 0) 0 else minSequence)
        meta.writeLong(if (count == 0) 0 else maxSequence)

        return SectionDirectory.encode(
            IndexFormat.BASE_MAGIC,
            IndexFormat.BASE_VERSION,
            listOf(
                IndexFormat.SECTION_KIND_META to meta.toByteArray(),
                IndexFormat.SECTION_KIND_KEYS to keys.build(),
                IndexFormat.SECTION_KIND_PRESENT to present.encode(),
            ),
        )
    }
}

/**
 * One segment's base sidecar, read in place off a mapping.
 *
 * Holds nothing but the mapping, the numbers from `META` and the offsets of the other two sections.
 * Resolving an ordinal to a key walks the key block where it lies; the present bitmap is a
 * `BitmapView` over the mapped bytes. No section is copied to the heap and none is decoded on open —
 * which is the claim §8 made when RoaringBitmap was declined, collected a second time.
 *
 * **A section's checksum is verified when that section is first used, not when the file is opened.**
 * The header's checksum, which covers the directory, is what open checks: it protects everything that
 * decides *where* a byte is, and it is four bytes to compute over a few dozen. Verifying a key block
 * of ten million keys on every open would read the whole file and defeat mapping entirely — the same
 * division `BitmapView.open` and `BitmapView.verify` already make.
 */
internal class BaseSidecar private constructor(
    val segmentNumber: Long,
    /** Ordinals in this segment: one per distinct user key, tombstones included. */
    val documentCount: Int,
    /** How many of those ordinals are deletions. */
    val tombstoneCount: Int,
    val smallestSequence: Long,
    /** The soundness guard: an index over this segment is usable at a snapshot at or above this. */
    val largestSequence: Long,
    private val keysSection: SectionDirectory.Section,
    private val presentSection: SectionDirectory.Section,
    val file: String,
    /** Which key-block layout this file carries. The one place the version is acted on. */
    val version: Int,
) {
    private val keys: KeyBlockReader by lazy {
        val section = keysSection.verified()
        // The single version check. Everything below reads one key block, not two — see the KDoc on
        // `KeyBlockReader`, and `PostingFile.open` for the same rule a level down.
        if (version >= IndexFormat.BASE_VERSION) {
            VarintKeyBlockReader(section, documentCount)
        } else {
            FixedWidthKeyBlockReader(section, documentCount)
        }
    }

    /** The user key at [ordinal]. */
    fun keyAt(ordinal: Int): Key = Key.of(keys.keyAt(ordinal))

    /** The ordinal of [key], or `-(insertionPoint + 1)`. */
    fun ordinalOf(key: Key): Int = keys.ordinalOf(key.toByteArray())

    /**
     * The ordinals whose newest version in this segment is a document rather than a deletion.
     *
     * The universe a complement is taken over. `NOT p` is `present.andNot(p)` rather than
     * `ofRange(0 until documentCount).andNot(p)`, because every tombstone ordinal in the second form
     * is a candidate whose recheck resolves to nothing — pure waste in a segment that is mostly
     * deletions, which is exactly what a segment full of expired keys is.
     */
    fun present(): BitmapView {
        val bytes = presentSection.verified()
        return BitmapView.open(bytes.source, bytes.sourceOffset, bytes.length, file)
    }

    /**
     * Checks every section and decodes every key.
     *
     * `O(documentCount)`, and deliberately not what [open] does. This is the diagnostic tool and the
     * thing the corruption suite runs; the read path pays for the bytes it actually touches.
     */
    fun verify() {
        keys.verifyRestarts()
        for (ordinal in 0 until keys.count) keys.keyAt(ordinal)
        present().verify()
        val bits = present().cardinality
        if (bits != documentCount - tombstoneCount) {
            presentSection.bytes.corrupt(
                "PRESENT holds $bits ordinal(s) but META says $documentCount less $tombstoneCount tombstone(s)",
            )
        }
    }

    override fun toString(): String =
        "BaseSidecar(#$segmentNumber v$version, $documentCount ordinal(s), $tombstoneCount tombstone(s), " +
            "sequences $smallestSequence..$largestSequence)"

    companion object {
        /**
         * Reads the directory of a mapped base sidecar.
         *
         * @param expectedSegmentNumber the segment the *filename* says this describes. A sidecar
         *   copied or renamed into place must not be folded in as if it belonged where it now sits.
         */
        fun open(
            segment: MemorySegment,
            length: Int,
            file: String,
            expectedSegmentNumber: Long,
        ): BaseSidecar {
            val sections = SectionDirectory.open(
                segment,
                length,
                file,
                IndexFormat.BASE_MAGIC,
                IndexFormat.BASE_VERSIONS,
                "base sidecar",
                IndexFormat::sectionKindName,
            )
            val keysSection = sections.require(IndexFormat.SECTION_KIND_KEYS, "KEYS")
            val presentSection = sections.require(IndexFormat.SECTION_KIND_PRESENT, "PRESENT")

            // META is thirty-two bytes and every other field is read through it, so it is the one
            // section checked eagerly.
            val metaBytes = sections.require(IndexFormat.SECTION_KIND_META, "META").verified()
            if (metaBytes.length != IndexFormat.META_BYTES) {
                metaBytes.corrupt("a META section is ${IndexFormat.META_BYTES} bytes, not ${metaBytes.length}")
            }

            val segmentNumber = metaBytes.i64(0, "segment number")
            if (segmentNumber != expectedSegmentNumber) {
                metaBytes.corrupt(
                    "the base sidecar filed under segment $expectedSegmentNumber describes segment $segmentNumber",
                )
            }
            val documentCount = metaBytes.u32(8, "document count", BitmapFormat.MAX_ORDINAL)
            val tombstoneCount = metaBytes.u32(12, "tombstone count", documentCount)
            val smallest = metaBytes.i64(16, "smallest sequence")
            val largest = metaBytes.i64(24, "largest sequence")
            if (documentCount > 0 && smallest > largest) {
                metaBytes.corrupt("the smallest sequence $smallest is above the largest $largest", 16)
            }

            return BaseSidecar(
                segmentNumber = segmentNumber,
                documentCount = documentCount,
                tombstoneCount = tombstoneCount,
                smallestSequence = smallest,
                largestSequence = largest,
                keysSection = keysSection,
                presentSection = presentSection,
                file = file,
                version = sections.version,
            )
        }
    }
}

/** Writing and deleting base sidecars, which is all the filesystem knows about them. */
internal object BaseSidecarFile {
    fun write(directory: Path, segmentNumber: Long, bytes: ByteArray) {
        writeSidecarAtomically(
            directory.resolve(temporaryBaseFileName(segmentNumber)),
            directory.resolve(baseFileName(segmentNumber)),
            bytes,
        )
    }

    fun delete(directory: Path, segmentNumber: Long) {
        java.nio.file.Files.deleteIfExists(directory.resolve(baseFileName(segmentNumber)))
        java.nio.file.Files.deleteIfExists(directory.resolve(temporaryBaseFileName(segmentNumber)))
    }
}
