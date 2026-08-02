package app.oreshkov.rabosh.index

import java.lang.foreign.MemorySegment
import java.nio.file.Files
import java.nio.file.Path
import java.util.Arrays
import java.util.TreeMap
import java.util.zip.CRC32C

/**
 * Accumulates one index's posting lists over one segment.
 *
 * A `TreeMap` keyed by the term's bytes, so the terms come out in the order the directory needs and
 * the writer never sorts. The comparator is **unsigned** byte order: signatures are arbitrary bytes
 * and a signed comparison would sort a term whose first payload byte is above 0x7F before one whose
 * first byte is 0x01, making every binary search over that dictionary quietly wrong. The same trap
 * `Char`-rather-than-`Short` avoids inside a bitmap block, in a different guise.
 *
 * **The term budget drops the index for the segment rather than truncating it.** An index that held
 * the first million terms of a path and silently stopped would be an index that returns wrong
 * answers, and the one thing derived data is not allowed to do is claim coverage it does not have —
 * the same rule that makes a missing sidecar read as "not collected" rather than "collected and
 * empty". So overflow is signalled, the segment goes uncovered, and a query scans it.
 */
internal class PostingBuilder(private val maxTerms: Int) {
    private val terms = TreeMap<ByteArray, Bitmap>(Arrays::compareUnsigned)
    private val presence = Bitmap()

    /** Set when the budget was exceeded. The segment is then not covered by this index. */
    var overflowed: Boolean = false
        private set

    val termCount: Int get() = terms.size

    fun add(term: ByteArray, ordinal: Int) {
        if (overflowed) return
        // Presence is recorded even for a value too long to be a term, and even once the budget is
        // full — but the builder is discarded in that case anyway, so only the first matters.
        presence.add(ordinal)
        val existing = terms[term]
        if (existing != null) {
            existing.add(ordinal)
            return
        }
        if (terms.size >= maxTerms) {
            // Everything accumulated is dropped, not kept: a partial dictionary is the one outcome
            // worse than none, because nothing downstream could tell it apart from a complete one.
            terms.clear()
            presence.clear()
            overflowed = true
            return
        }
        terms[term] = Bitmap.of(ordinal)
    }

    /** Records that [ordinal] has a value at the path that is not a term. Keeps existence exact. */
    fun addPresenceOnly(ordinal: Int) {
        if (!overflowed) presence.add(ordinal)
    }

    /**
     * Encodes the file.
     *
     * @param path the canonical text of the indexed path, repeated into the file so a posting file
     *   can be checked against the registry that names it.
     */
    fun build(segmentNumber: Long, indexId: Int, path: String, documentCount: Int, largestSequence: Long): ByteArray {
        check(!overflowed) { "a posting file must not be built after the term budget was exceeded" }

        val pathBytes = path.encodeToByteArray()
        val restartCount = IndexFormat.postingRestartCount(terms.size)
        val directoryOffset = IndexFormat.POSTING_HEADER_BYTES + 4 + pathBytes.size
        val restartsOffset = directoryOffset + terms.size * IndexFormat.POSTING_V2_TERM_ENTRY_BYTES
        val termsOffset = restartsOffset + restartCount * 4

        val out = IndexWriter(termsOffset + 16 * terms.size)
        out.write(IndexFormat.POSTING_MAGIC)
        out.writeU32(IndexFormat.POSTING_VERSION)
        out.writeByte(IndexFormat.INDEX_KIND_INVERTED)
        out.pad(3)
        out.writeLong(segmentNumber)
        out.writeLong(largestSequence)
        out.writeU32(indexId)
        out.writeU32(documentCount)
        out.writeU32(terms.size)
        out.writeU32(directoryOffset)
        check(out.size == IndexFormat.POSTING_PRESENCE_OFFSET) { "the posting header moved: ${out.size}" }
        out.writeU32(0)
        out.writeU32(0)
        out.writeU32(0)
        check(out.size == IndexFormat.POSTING_CHECKSUM_OFFSET) { "the posting header moved: ${out.size}" }
        out.writeU32(0)
        out.writeBytes(pathBytes)
        check(out.size == directoryOffset) { "the posting directory moved: ${out.size}" }

        // The directory is written first with placeholder offsets and patched as each region is laid
        // down, which is the same shape `ReadableBitmap.encode` uses and the reason `IndexWriter` has
        // `patchU32` at all.
        val entryAt = IntArray(terms.size)
        for (index in 0 until terms.size) {
            entryAt[index] = out.size
            out.writeU32(0)
            out.writeU32(0)
            // The encoding is not known until this entry's own posting list is in hand, so it is
            // patched below with the reserved bytes — one little-endian `u32` covers both.
            out.writeU32(0)
            out.writeU32(0)
        }
        check(out.size == restartsOffset) { "the posting restart array moved: ${out.size}" }

        val restartAt = IntArray(restartCount)
        for (group in 0 until restartCount) {
            restartAt[group] = out.size
            out.writeU32(0)
        }
        check(out.size == termsOffset) { "the posting term region moved: ${out.size}" }

        // `null` where the term is a singleton: its ordinal goes into the entry itself, so there is
        // no bitmap to encode and nothing to lay down in the posting region afterwards. The choice is
        // `cardinality == 1` and nothing else — a pure function of the posting list, which is what
        // makes a flush-written sidecar and a backfill-rebuilt one byte-identical rather than merely
        // equivalent. A threshold that looked at anything but the list would break that quietly.
        //
        // Front-coding is a pure function of the *sorted sequence* for the same reason, and the
        // `TreeMap` is what makes the sequence independent of arrival order. A restart entry shares
        // nothing, so the term it carries is complete and can be compared without reconstructing
        // anything — which is exactly what lets the reader bisect the restarts.
        val encoded = arrayOfNulls<ByteArray>(terms.size)
        var index = 0
        var previous = ByteArray(0)
        for ((term, bitmap) in terms) {
            val restart = index % IndexFormat.POSTING_TERM_RESTART_INTERVAL == 0
            if (restart) out.patchU32(restartAt[index / IndexFormat.POSTING_TERM_RESTART_INTERVAL], out.size - termsOffset)
            val shared = if (restart) 0 else sharedPrefix(previous, term)
            out.writeVarint(shared)
            out.writeVarint(term.size - shared)
            out.write(if (shared == 0) term else term.copyOfRange(shared, term.size))
            previous = term

            if (bitmap.cardinality == 1) {
                val ordinal = bitmap.first()
                out.patchU32(entryAt[index], ordinal)
                out.patchU32(entryAt[index] + 4, 0)
                out.patchU32(entryAt[index] + 8, IndexFormat.POSTING_ENCODING_SINGLE)
                out.patchU32(
                    entryAt[index] + 12,
                    postingChecksum(IndexFormat.POSTING_ENCODING_SINGLE, ordinalBytes(ordinal)),
                )
            } else {
                out.patchU32(entryAt[index] + 8, IndexFormat.POSTING_ENCODING_BITMAP)
                encoded[index] = bitmap.encode()
            }
            index++
        }

        val postingsOffset = out.size

        val presenceBytes = presence.encode()
        out.patchU32(IndexFormat.POSTING_PRESENCE_OFFSET, out.size)
        out.patchU32(IndexFormat.POSTING_PRESENCE_OFFSET + 4, presenceBytes.size)
        out.patchU32(
            IndexFormat.POSTING_PRESENCE_OFFSET + 8,
            postingChecksum(IndexFormat.POSTING_ENCODING_BITMAP, presenceBytes),
        )
        out.write(presenceBytes)

        for ((position, posting) in encoded.withIndex()) {
            if (posting == null) continue
            out.patchU32(entryAt[position], out.size)
            out.patchU32(entryAt[position] + 4, posting.size)
            out.patchU32(entryAt[position] + 12, postingChecksum(IndexFormat.POSTING_ENCODING_BITMAP, posting))
            out.write(posting)
        }

        // Last, because the directory it covers is patched as each posting is laid down. Everything
        // that decides *where* a posting is — the header fields, the path, the directory and the term
        // bytes — under one checksum, checked once on open. Each posting's own bytes are covered by
        // its own directory entry and checked when that posting is first read.
        out.patchU32(
            IndexFormat.POSTING_CHECKSUM_OFFSET,
            out.checksum(
                IndexFormat.MAGIC_BYTES,
                IndexFormat.POSTING_CHECKSUM_OFFSET,
                IndexFormat.POSTING_CHECKSUM_OFFSET + 4,
                postingsOffset,
            ),
        )
        return out.toByteArray()
    }
}

/** How many leading bytes two terms agree on. The whole of what front-coding needs. */
internal fun sharedPrefix(left: ByteArray, right: ByteArray): Int {
    val limit = minOf(left.size, right.size)
    var index = 0
    while (index < limit && left[index] == right[index]) index++
    return index
}

/**
 * CRC32C over a posting's encoding byte and its bytes. The encoding is covered so a retag is damage.
 *
 * For [IndexFormat.POSTING_ENCODING_SINGLE] the "bytes" are the ordinal's four little-endian bytes —
 * the same four the entry holds, produced by [ordinalBytes] on both sides so that the writer and the
 * reader cannot disagree about what is covered.
 */
internal fun postingChecksum(encoding: Int, body: ByteArray): Int {
    val crc = CRC32C()
    crc.update(encoding)
    crc.update(body, 0, body.size)
    return crc.value.toInt()
}

/** An ordinal as the four little-endian bytes the term entry holds it in. */
internal fun ordinalBytes(ordinal: Int): ByteArray =
    ByteArray(4) { index -> (ordinal ushr (8 * index)).toByte() }

/**
 * One index's posting lists over one segment, read in place off a mapping.
 *
 * A lookup is a [TermDictionary.search] — a bisect over restart points in a version-2 file, or over
 * every term in a version-1 one — and then a [ReadableBitmap] over the posting: a `BitmapView` over
 * its bytes, or, for a term matching exactly one document, a heap [Bitmap] over the ordinal the entry
 * itself carries. Nothing is copied and nothing is decoded until a term matches, so a planner that
 * asks about a term this segment does not hold pays for the bisect and nothing else.
 *
 * **Two dictionary layouts, and this class knows about it in exactly one place.** [open] chooses the
 * [TermDictionary] from the version field; [postings], [termAt], [postingAt] and [verify] are one code
 * path over both. That is the same rule `IndexFormat.POSTING_ENCODING_SINGLE` states one level down —
 * a reader with two notions of what a posting list *is* would be a second definition of one — and it
 * is why the posting fields' offset within an entry is a property of the dictionary rather than a
 * `when` on the hot path.
 */
internal class PostingFile private constructor(
    val segmentNumber: Long,
    val indexId: Int,
    val path: String,
    val documentCount: Int,
    val largestSequence: Long,
    private val dictionary: TermDictionary,
    private val bytes: IndexBytes,
    private val presenceOffset: Int,
    private val presenceLength: Int,
    private val presenceChecksum: Int,
    val file: String,
) {
    /** How many terms the dictionary holds. */
    val termCount: Int get() = dictionary.termCount

    /**
     * The ordinals carrying any value at the indexed path.
     *
     * What `EXISTS` is, directly. Its complement within a segment's live documents —
     * `base.present().andNot(presence())` — is `NOT EXISTS`, and neither costs a walk of the
     * dictionary.
     */
    fun presence(): BitmapView {
        val actual = postingChecksum(
            IndexFormat.POSTING_ENCODING_BITMAP,
            bytes.bytes(presenceOffset, presenceLength, "presence bitmap"),
        )
        if (actual != presenceChecksum) {
            bytes.corrupt("the presence bitmap's checksum does not match its bytes", IndexFormat.POSTING_PRESENCE_OFFSET + 8)
        }
        return BitmapView.open(bytes.source, bytes.sourceOffset + presenceOffset, presenceLength, file)
    }

    /** The posting list for [term], or `null` if this segment holds no document carrying it. */
    fun postings(term: ByteArray): ReadableBitmap? {
        val index = dictionary.search(term)
        if (index < 0) return null
        return postingAt(index)
    }

    /** The [index]-th term in ascending order. For verification and for diagnostics. */
    fun termAt(index: Int): ByteArray = dictionary.termAt(index)

    /**
     * The [index]-th posting list, its checksum and encoding checked.
     *
     * A singleton comes back as a heap [Bitmap] over its one ordinal rather than as a second kind of
     * posting list. Nothing above this branches on the encoding, which is the point: a reader with two
     * notions of what a posting list is would be a second definition of one, and the two would only
     * have to disagree once.
     */
    fun postingAt(index: Int): ReadableBitmap {
        // One code path over both dictionary layouts: the version decided where these four fields sit
        // inside an entry when the file was opened, and nothing here asks again.
        val at = dictionary.postingFieldsAt(index)
        val encoding = bytes.u8(at + 8, "posting encoding")
        val expected = bytes.i32(at + 12, "posting checksum")
        if (encoding == IndexFormat.POSTING_ENCODING_SINGLE) {
            // `documentCount - 1` rather than the file length: for this encoding the field is an
            // ordinal, and a bound applied at the read is what stops a corrupt one becoming a
            // candidate nobody can resolve. A zero-document segment has no terms to reach this.
            val ordinal = bytes.u32(at, "posting ordinal", documentCount - 1)
            // Must be zero: the ordinal is the entry, so there is nothing in the posting region.
            bytes.u32(at + 4, "posting length", 0)
            val actual = postingChecksum(encoding, ordinalBytes(ordinal))
            if (actual != expected) bytes.corrupt("posting $index's checksum does not match its ordinal", at + 12)
            return Bitmap.of(ordinal)
        }
        if (encoding != IndexFormat.POSTING_ENCODING_BITMAP) {
            throw UnsupportedIndexFormatException(
                "posting $index in $file uses encoding $encoding, which this build does not know",
            )
        }
        val offset = bytes.u32(at, "posting offset", bytes.length)
        val length = bytes.u32(at + 4, "posting length", bytes.length - offset)
        val actual = postingChecksum(encoding, bytes.bytes(offset, length, "posting $index"))
        if (actual != expected) bytes.corrupt("posting $index's checksum does not match its bytes", at + 12)
        return BitmapView.open(bytes.source, bytes.sourceOffset + offset, length, file)
    }

    /** Checks every term and every posting list. `O(size)`, and not what [open] does. */
    fun verify() {
        val presence = presence()
        presence.verify()
        if (!presence.isEmpty && presence.last() >= documentCount) {
            bytes.corrupt("the presence bitmap names ordinal ${presence.last()} beyond the $documentCount in this segment")
        }
        var previous: ByteArray? = null
        for (index in 0 until termCount) {
            val term = termAt(index)
            if (term.isEmpty()) bytes.corrupt("term $index is empty; every signature carries a tag byte")
            val last = previous
            if (last != null && Arrays.compareUnsigned(last, term) >= 0) {
                bytes.corrupt("term $index does not follow term ${index - 1} in unsigned order")
            }
            previous = term
            val posting = postingAt(index)
            // A singleton is a heap bitmap built from one bounds-checked ordinal, so there is no
            // encoding to walk; `postingAt` has already checked everything `verify` would.
            if (posting is BitmapView) posting.verify()
            if (posting.isEmpty) bytes.corrupt("term $index has an empty posting list, so the term should not be here")
            if (posting.last() >= documentCount) {
                bytes.corrupt("term $index names ordinal ${posting.last()} beyond the $documentCount in this segment")
            }
            // Every ordinal under a term carries a value at the path, so presence has to contain it.
            // A presence bitmap that did not would make `EXISTS` disagree with `= x`, which is the
            // shape a wrong answer takes here.
            if (posting.andCardinality(presence) != posting.cardinality) {
                bytes.corrupt("term $index names ordinals the presence bitmap does not")
            }
        }
    }

    override fun toString(): String = "PostingFile(#$segmentNumber/$indexId, $path, $termCount term(s))"

    companion object {
        /**
         * Reads the header and directory of a mapped posting file.
         *
         * Every identity the file carries is checked against what the caller already knows. A `.pst`
         * copied from another store, or left behind by a dropped index whose id was reused, would
         * otherwise decode perfectly and answer with somebody else's documents.
         */
        fun open(
            segment: MemorySegment,
            length: Int,
            file: String,
            expectedSegmentNumber: Long,
            expectedIndexId: Int,
            expectedPath: String,
            expectedLargestSequence: Long,
        ): PostingFile {
            val bytes = IndexBytes(segment, 0, length, file, ::CorruptIndexException)
            if (length < IndexFormat.POSTING_HEADER_BYTES) {
                bytes.corrupt("a posting file needs at least a ${IndexFormat.POSTING_HEADER_BYTES}-byte header")
            }
            for (index in 0 until IndexFormat.MAGIC_BYTES) {
                if (bytes.u8(index, "magic") != (IndexFormat.POSTING_MAGIC[index].toInt() and 0xFF)) {
                    bytes.corrupt("a posting file does not begin with JKDB-PST", index)
                }
            }
            // The one place in this file that knows there is more than one dictionary layout. Version
            // 1 is read and never written: a `.pst` that exists and will not decode *throws* in
            // `SegmentIndex.open`, so refusing it would fail `attach` on every pre-phase-17 store
            // rather than rebuilding it.
            val version = bytes.u32(8, "posting file version", Int.MAX_VALUE)
            if (version != IndexFormat.POSTING_VERSION && version != IndexFormat.POSTING_VERSION_FLAT) {
                throw UnsupportedIndexFormatException(
                    "the posting file $file is version $version; this build reads " +
                        "${IndexFormat.POSTING_VERSION_FLAT} and ${IndexFormat.POSTING_VERSION}",
                )
            }
            val entryBytes =
                if (version == IndexFormat.POSTING_VERSION) IndexFormat.POSTING_V2_TERM_ENTRY_BYTES
                else IndexFormat.POSTING_V1_TERM_ENTRY_BYTES
            val kind = bytes.u8(12, "posting file index kind")
            if (kind != IndexFormat.INDEX_KIND_INVERTED) {
                throw UnsupportedIndexFormatException(
                    "the posting file $file holds index kind $kind, which this build does not read",
                )
            }
            val segmentNumber = bytes.i64(16, "segment number")
            val largestSequence = bytes.i64(24, "largest sequence")
            val indexId = bytes.u32(32, "index id", Int.MAX_VALUE)
            val documentCount = bytes.u32(36, "document count", BitmapFormat.MAX_ORDINAL)
            val maxTerms = (length - IndexFormat.POSTING_HEADER_BYTES) / entryBytes
            val termCount = bytes.u32(40, "term count", maxTerms)
            val directoryOffset = bytes.u32(44, "directory offset", length)
            val presenceOffset = bytes.u32(IndexFormat.POSTING_PRESENCE_OFFSET, "presence offset", length)
            val presenceLength = bytes.u32(
                IndexFormat.POSTING_PRESENCE_OFFSET + 4,
                "presence length",
                length - presenceOffset,
            )
            val presenceChecksum = bytes.i32(IndexFormat.POSTING_PRESENCE_OFFSET + 8, "presence checksum")
            val expectedChecksum = bytes.i32(IndexFormat.POSTING_CHECKSUM_OFFSET, "header checksum")

            val pathLength = bytes.u32(
                IndexFormat.POSTING_HEADER_BYTES,
                "path length",
                length - IndexFormat.POSTING_HEADER_BYTES - 4,
            )
            if (directoryOffset != IndexFormat.POSTING_HEADER_BYTES + 4 + pathLength) {
                bytes.corrupt("the directory offset $directoryOffset does not follow a $pathLength-byte path", 44)
            }
            val restartsOffset = directoryOffset + termCount * entryBytes
            if (restartsOffset > length) bytes.corrupt("the posting directory runs past the end of the file", 40)
            val restartCount =
                if (version == IndexFormat.POSTING_VERSION) IndexFormat.postingRestartCount(termCount) else 0
            val termsOffset = restartsOffset + restartCount * 4
            if (termsOffset > length) bytes.corrupt("the posting restart array runs past the end of the file", 40)

            val path = try {
                bytes.bytes(IndexFormat.POSTING_HEADER_BYTES + 4, pathLength, "path")
                    .decodeToString(throwOnInvalidSequence = true)
            } catch (failure: java.nio.charset.CharacterCodingException) {
                bytes.corrupt("the posting file's path is not valid UTF-8", IndexFormat.POSTING_HEADER_BYTES + 4, failure)
            }

            // Where the terms end, which is where the first posting begins and where the header
            // checksum stops. The two versions establish it differently, and the asymmetry is the
            // point rather than an accident of migration.
            val dictionary: TermDictionary
            val postingsOffset: Int
            if (version == IndexFormat.POSTING_VERSION) {
                // Version 2 *declares* it: `presenceOffset` is inside the region the header checksum
                // covers, so once that checksum verifies the extent is a fact and there is nothing to
                // derive. What is left is whether the restart array is structurally sound, which is
                // O(restartCount) — a sixteenth of the work version 1 does below, and the reason
                // opening a large sidecar got cheaper rather than merely no dearer. Per-term
                // validation is deferred to `termAt`, exactly as a posting's checksum is deferred to
                // the read: pay on open for what decides *where* a byte is, and for nothing else.
                if (presenceOffset < termsOffset) {
                    bytes.corrupt(
                        "the presence bitmap begins at $presenceOffset, before the term region at $termsOffset",
                        IndexFormat.POSTING_PRESENCE_OFFSET,
                    )
                }
                postingsOffset = presenceOffset
                val front = FrontCodedTermDictionary(
                    bytes = bytes,
                    directoryOffset = directoryOffset,
                    termCount = termCount,
                    termsOffset = termsOffset,
                    restartsOffset = restartsOffset,
                    regionLength = presenceOffset - termsOffset,
                )
                front.verifyRestarts()
                dictionary = front
            } else {
                // Version 1 has to *derive* it, because its terms are reached through per-entry
                // offsets that a reader cannot bound any other way. Deriving it from the directory
                // rather than storing it is what means there is no second field to disagree with the
                // first — the property version 2 gets for free by having no such offsets at all.
                var end = termsOffset
                for (index in 0 until termCount) {
                    val at = directoryOffset + index * entryBytes
                    val termOffset = bytes.u32(at, "term offset", length)
                    val termLength = bytes.u32(at + 4, "term length", IndexFormat.MAX_TERM_BYTES)
                    if (termOffset < termsOffset || termOffset + termLength > length) {
                        bytes.corrupt("term $index lies outside the term region", at)
                    }
                    end = maxOf(end, termOffset + termLength)
                }
                if (presenceOffset != end) {
                    bytes.corrupt(
                        "the presence bitmap begins at $presenceOffset, not at $end where the terms end",
                        IndexFormat.POSTING_PRESENCE_OFFSET,
                    )
                }
                postingsOffset = end
                dictionary = FlatTermDictionary(bytes, directoryOffset, termCount)
            }
            val actualChecksum = run {
                val crc = CRC32C()
                val head = bytes.bytes(
                    IndexFormat.MAGIC_BYTES,
                    IndexFormat.POSTING_CHECKSUM_OFFSET - IndexFormat.MAGIC_BYTES,
                    "header fields",
                )
                crc.update(head, 0, head.size)
                val body = bytes.bytes(
                    IndexFormat.POSTING_CHECKSUM_OFFSET + 4,
                    postingsOffset - IndexFormat.POSTING_CHECKSUM_OFFSET - 4,
                    "path, directory and terms",
                )
                crc.update(body, 0, body.size)
                crc.value.toInt()
            }
            if (expectedChecksum != actualChecksum) {
                bytes.corrupt("the posting file's header checksum does not match its directory", IndexFormat.POSTING_CHECKSUM_OFFSET)
            }

            if (segmentNumber != expectedSegmentNumber) {
                bytes.corrupt("the posting file filed under segment $expectedSegmentNumber describes segment $segmentNumber", 16)
            }
            if (indexId != expectedIndexId) {
                bytes.corrupt("the posting file filed under index #$expectedIndexId describes index #$indexId", 32)
            }
            if (path != expectedPath) {
                bytes.corrupt("the posting file for index #$indexId is over '$path', but the registry says '$expectedPath'")
            }
            if (largestSequence != expectedLargestSequence) {
                bytes.corrupt(
                    "the posting file reports largest sequence $largestSequence but its base sidecar says " +
                        "$expectedLargestSequence",
                    24,
                )
            }

            return PostingFile(
                segmentNumber = segmentNumber,
                indexId = indexId,
                path = path,
                documentCount = documentCount,
                largestSequence = largestSequence,
                dictionary = dictionary,
                bytes = bytes,
                presenceOffset = presenceOffset,
                presenceLength = presenceLength,
                presenceChecksum = presenceChecksum,
                file = file,
            )
        }
    }
}

/** Writing and deleting posting files. */
internal object PostingFileIo {
    fun write(directory: Path, segmentNumber: Long, indexId: Int, bytes: ByteArray) {
        writeSidecarAtomically(
            directory.resolve(temporaryPostingFileName(segmentNumber, indexId)),
            directory.resolve(postingFileName(segmentNumber, indexId)),
            bytes,
        )
    }

    fun delete(directory: Path, segmentNumber: Long, indexId: Int) {
        Files.deleteIfExists(directory.resolve(postingFileName(segmentNumber, indexId)))
        Files.deleteIfExists(directory.resolve(temporaryPostingFileName(segmentNumber, indexId)))
    }
}
