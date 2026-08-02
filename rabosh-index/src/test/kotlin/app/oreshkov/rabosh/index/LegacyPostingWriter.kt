package app.oreshkov.rabosh.index

import java.util.Arrays
import java.util.TreeMap

/**
 * A version-1 posting file, written by the tests so the version-1 *reader* has something to face.
 *
 * **This lives in the test source set on purpose, and it must stay there.** The engine has exactly one
 * writer, and a second one in `main` would be dead code that the next reader of `PostingBuilder` has
 * to reason about — worse, it would be a standing invitation to keep writing the old format "just for
 * a while". What the reader needs is bytes, not a production path.
 *
 * The two instruments over version 1 are not interchangeable and neither replaces the other:
 *
 * - `FormatCompatibilityTest` reads the **committed golden stores**, which is the only evidence that
 *   bytes written by a build that no longer exists still mean what they meant. It cannot damage them,
 *   and there are only a handful of files.
 * - This writes version-1 bytes **on demand**, which is what lets `IndexCorruptionTest` truncate at
 *   every offset and flip every bit of a version-1 file, and lets `TermDictionaryTest` compare the two
 *   dictionaries over generated terms. It proves nothing about history, because it is this build's
 *   idea of version 1.
 *
 * So this is deliberately a transcription of what phase 11 wrote, kept beside
 * [PostingBuilder] rather than derived from it. If the two ever have to agree about something, they
 * are agreeing by transcription — and the golden stores are what catches a transcription that drifted.
 */
internal class LegacyPostingBuilder(private val maxTerms: Int = 1 shl 16) {
    private val terms = TreeMap<ByteArray, Bitmap>(Arrays::compareUnsigned)
    private val presence = Bitmap()

    fun add(term: ByteArray, ordinal: Int) {
        presence.add(ordinal)
        val existing = terms[term]
        if (existing != null) {
            existing.add(ordinal)
            return
        }
        check(terms.size < maxTerms) { "the legacy fixture does not model the term budget" }
        terms[term] = Bitmap.of(ordinal)
    }

    fun addPresenceOnly(ordinal: Int) {
        presence.add(ordinal)
    }

    /** The bytes phase 11's `PostingBuilder.build` would have produced for these postings. */
    fun build(
        segmentNumber: Long,
        indexId: Int,
        path: String,
        documentCount: Int,
        largestSequence: Long,
    ): ByteArray {
        val pathBytes = path.encodeToByteArray()
        val directoryOffset = IndexFormat.POSTING_HEADER_BYTES + 4 + pathBytes.size
        val termsOffset = directoryOffset + terms.size * IndexFormat.POSTING_V1_TERM_ENTRY_BYTES

        val out = IndexWriter(termsOffset + 64 * terms.size)
        out.write(IndexFormat.POSTING_MAGIC)
        out.writeU32(IndexFormat.POSTING_VERSION_FLAT)
        out.writeByte(IndexFormat.INDEX_KIND_INVERTED)
        out.pad(3)
        out.writeLong(segmentNumber)
        out.writeLong(largestSequence)
        out.writeU32(indexId)
        out.writeU32(documentCount)
        out.writeU32(terms.size)
        out.writeU32(directoryOffset)
        check(out.size == IndexFormat.POSTING_PRESENCE_OFFSET)
        out.writeU32(0)
        out.writeU32(0)
        out.writeU32(0)
        check(out.size == IndexFormat.POSTING_CHECKSUM_OFFSET)
        out.writeU32(0)
        out.writeBytes(pathBytes)
        check(out.size == directoryOffset)

        val entryAt = IntArray(terms.size)
        for (index in 0 until terms.size) {
            entryAt[index] = out.size
            repeat(6) { out.writeU32(0) }
        }
        check(out.size == termsOffset)

        val encoded = arrayOfNulls<ByteArray>(terms.size)
        var index = 0
        for ((term, bitmap) in terms) {
            out.patchU32(entryAt[index], out.size)
            out.patchU32(entryAt[index] + 4, term.size)
            out.write(term)
            if (bitmap.cardinality == 1) {
                val ordinal = bitmap.first()
                out.patchU32(entryAt[index] + 8, ordinal)
                out.patchU32(entryAt[index] + 12, 0)
                out.patchU32(entryAt[index] + 16, IndexFormat.POSTING_ENCODING_SINGLE)
                out.patchU32(
                    entryAt[index] + 20,
                    postingChecksum(IndexFormat.POSTING_ENCODING_SINGLE, ordinalBytes(ordinal)),
                )
            } else {
                out.patchU32(entryAt[index] + 16, IndexFormat.POSTING_ENCODING_BITMAP)
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
            out.patchU32(entryAt[position] + 8, out.size)
            out.patchU32(entryAt[position] + 12, posting.size)
            out.patchU32(entryAt[position] + 20, postingChecksum(IndexFormat.POSTING_ENCODING_BITMAP, posting))
            out.write(posting)
        }

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
