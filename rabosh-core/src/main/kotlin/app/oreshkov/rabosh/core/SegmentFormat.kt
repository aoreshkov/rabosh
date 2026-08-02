package app.oreshkov.rabosh.core

import java.util.zip.CRC32C

/**
 * The on-disk layout of a sorted segment.
 *
 * ```
 * segment := header block* footer
 * header  := magic["JKDB-SEG"] version:u32 crc32c:u32                          (16 bytes)
 * block   := contents blockType:u8 crc32c:u32                                  (5-byte trailer)
 *
 * contents (data blocks and the index block alike):
 *            entry* restartOffset:u32[restartCount] restartCount:u32
 * entry   := sharedLength:u32 unsharedLength:u32 valueLength:u32
 *            unsharedKey[unsharedLength] value[valueLength]
 *
 * internalKey := userKey tag:u64          tag = (sequence shl 8) or kindId
 * data value  := Variant value bytes for a put, empty for a delete
 * index value := blockOffset:u64 blockLength:u32
 * bloom block := bitsPerKey:u32 hashCount:u32 bitCount:u32 keyCount:u32 bits[]
 * dict block  := the segment's Variant metadata, read in place
 *
 * footer  := dictionaryHandle indexHandle bloomHandle                          (3 x 12)
 *            entryCount:u64 smallestSequence:u64 largestSequence:u64           (24)
 *            version:u32 crc32c:u32 magic["JKDB-SEG"]                          (16)
 * handle  := offset:u64 length:u32                                            (76 bytes total)
 * ```
 *
 * Little-endian throughout, matching the log and the Variant encoding so the whole engine has one
 * byte order. **These constants are permanent**: they are written to files that later versions must
 * still read. Add, never renumber.
 *
 * Four decisions in that layout carry weight.
 *
 * **[BLOCK_TYPE_PLAIN] is the extension point, and it is one byte.** Block compression is deferred
 * until a benchmark says what it is worth, and variable-width entry fields are the same kind of
 * question. Both are *new type ids* when they arrive — additive, never a renumbering — and without
 * this byte the format could not grow into either without a new segment version.
 *
 * **The footer is at the tail, and repeats the magic.** A reader seeks to `length - 76`, checks the
 * magic, validates the footer's own checksum, and only then trusts a single handle in it. Every
 * other offset in the file is reached through a handle, so one unvalidated footer would put every
 * later read at an attacker-chosen address. The magic at the *front* is for humans and for tooling
 * that identifies files by their first bytes.
 *
 * **The tag packs the sequence into 56 bits and the operation into 8**, reusing [OperationKind]'s
 * already-permanent ids. Eight bits for two values is generous, and it is what keeps the tag a
 * single aligned-width field; the alternative, a separate byte, costs the same and reads worse. The
 * 2^56 ceiling on sequence numbers is checked where sequences are handed out rather than left to
 * wrap in silence.
 *
 * **Keys are prefix-compressed against restart points.** Real keys share prefixes and the tag never
 * does, which is harmless — it sits at the end. A restart every [RESTART_INTERVAL] entries is what
 * makes a block binary-searchable without an offset per entry: the search bisects the restart array
 * and then walks at most that many entries.
 */
internal object SegmentFormat {
    /** `JKDB-SEG` in ASCII. Legible in a hex dump, and distinct from the log's `JKDB-WAL`. */
    val MAGIC: ByteArray = "JKDB-SEG".encodeToByteArray()

    /** The only segment format version this build writes, and the only one it reads. */
    const val VERSION: Int = 1

    const val HEADER_BYTES: Int = 16
    const val FOOTER_BYTES: Int = 76

    /** `blockType:u8` + `crc32c:u32`, appended to every block. */
    const val BLOCK_TRAILER_BYTES: Int = 5

    /** Uncompressed contents with fixed-width entry fields — the only block type this build writes. */
    const val BLOCK_TYPE_PLAIN: Int = 0

    /** Entries between restart points. Sixteen is the ratio LevelDB settled on and it holds up here. */
    const val RESTART_INTERVAL: Int = 16

    /** `sharedLength:u32 unsharedLength:u32 valueLength:u32`. */
    const val ENTRY_HEADER_BYTES: Int = 12

    /** `offset:u64 length:u32`, the value an index-block entry carries. */
    const val HANDLE_BYTES: Int = 12

    /** Bits reserved in a tag for the operation id. */
    private const val TAG_KIND_BITS: Int = 8

    const val TAG_BYTES: Int = 8

    /** Largest sequence number a tag can carry. Beyond it a segment could not record the order. */
    const val MAX_SEQUENCE: Long = (1L shl (64 - TAG_KIND_BITS)) - 1

    private const val FOOTER_CRC_AT: Int = 64
    private const val FOOTER_VERSION_AT: Int = 60

    // --- internal keys ---------------------------------------------------------------------

    fun tag(sequence: Long, kind: OperationKind): Long {
        require(sequence in 0..MAX_SEQUENCE) { "sequence $sequence outside 0..$MAX_SEQUENCE" }
        return (sequence shl TAG_KIND_BITS) or kind.id.toLong()
    }

    fun sequenceOf(tag: Long): Long = tag ushr TAG_KIND_BITS

    /**
     * The operation a tag names.
     *
     * An id this build does not know is a signalled failure, never a default. Treating it as a put
     * would hand back bytes that are not a document; treating it as a delete would hide a document
     * that is there. Both are worse than saying the file is unreadable by this version.
     */
    fun kindOf(tag: Long, file: String, offset: Long): OperationKind =
        OperationKind.ofId((tag and 0xFF).toInt())
            ?: throw CorruptSegmentException("unknown operation id ${tag and 0xFF} in a key tag", file, offset)

    /** Newest first: equal user keys order by sequence descending, then by operation id. */
    fun compareTags(left: Long, right: Long): Int {
        val bySequence = sequenceOf(right).compareTo(sequenceOf(left))
        if (bySequence != 0) return bySequence
        return (left and 0xFF).compareTo(right and 0xFF)
    }

    fun readTag(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 0 until TAG_BYTES) {
            value = value or ((bytes[offset + index].toLong() and 0xFF) shl (8 * index))
        }
        return value
    }

    /** Encodes `userKey || tag`, the form every block entry stores. */
    fun encodeKey(userKey: Key, sequence: Long, kind: OperationKind): ByteArray {
        val raw = userKey.raw
        val encoded = ByteArray(raw.size + TAG_BYTES)
        raw.copyInto(encoded)
        val tag = tag(sequence, kind)
        for (index in 0 until TAG_BYTES) {
            encoded[raw.size + index] = (tag ushr (8 * index)).toByte()
        }
        return encoded
    }

    /** The user-key half of an encoded key. */
    fun userKeyOf(encoded: ByteArray, length: Int = encoded.size): Key =
        Key.wrap(encoded.copyOfRange(0, length - TAG_BYTES))

    /**
     * The largest key that can precede every version of [userKey].
     *
     * Sequences sort descending, so the *first* entry for a user key is the one with the largest
     * sequence — which makes this the seek target for "the newest version at or before this point".
     */
    fun seekKey(userKey: Key, sequence: Long): ByteArray =
        encodeKey(userKey, minOf(sequence, MAX_SEQUENCE), OperationKind.PUT)

    // --- headers, handles and the footer ------------------------------------------------------

    fun encodeHeader(): ByteArray {
        val out = ByteWriter(HEADER_BYTES)
        out.write(MAGIC)
        out.writeInt(VERSION)
        out.writeInt(checksum(out.backing, 0, 12))
        return out.toByteArray()
    }

    fun checkHeader(bytes: SegmentBytes) {
        if (!bytes.matches(0, MAGIC, "segment magic")) {
            bytes.corrupt("not a rabosh segment: the file does not begin with ${MAGIC.decodeToString()}", 0)
        }
        val version = bytes.i32(8, "segment version")
        if (version != VERSION) {
            throw UnsupportedFormatException(
                "${bytes.file} was written with segment format version $version; " +
                    "this build reads version $VERSION",
            )
        }
    }

    class Footer(
        val dictionary: BlockHandle,
        val index: BlockHandle,
        val bloom: BlockHandle,
        val entryCount: Long,
        val smallestSequence: Long,
        val largestSequence: Long,
    )

    fun encodeFooter(footer: Footer): ByteArray {
        val out = ByteWriter(FOOTER_BYTES)
        writeHandle(out, footer.dictionary)
        writeHandle(out, footer.index)
        writeHandle(out, footer.bloom)
        out.writeLong(footer.entryCount)
        out.writeLong(footer.smallestSequence)
        out.writeLong(footer.largestSequence)
        out.writeInt(VERSION)
        out.writeInt(checksum(out.backing, 0, FOOTER_CRC_AT))
        out.write(MAGIC)
        check(out.size == FOOTER_BYTES) { "footer is ${out.size} bytes, expected $FOOTER_BYTES" }
        return out.toByteArray()
    }

    /**
     * Reads and validates the footer of a mapped segment.
     *
     * Order matters here: magic, then checksum, then version, then handles. Checking the version
     * before the checksum would let a corrupt version field be reported as "written by a newer
     * build", sending an operator to look for an upgrade that does not exist.
     */
    fun readFooter(bytes: SegmentBytes): Footer {
        val size = bytes.byteSize
        if (size < HEADER_BYTES + FOOTER_BYTES) {
            bytes.corrupt("file is $size byte(s), too short to hold a segment header and footer", 0)
        }
        val base = size - FOOTER_BYTES
        if (!bytes.matches(base + FOOTER_CRC_AT + 4, MAGIC, "footer magic")) {
            bytes.corrupt("segment footer does not end with ${MAGIC.decodeToString()}", base)
        }
        val stored = bytes.i32(base + FOOTER_CRC_AT, "footer checksum")
        val computed = checksum(bytes.bytes(base, FOOTER_CRC_AT, "footer"), 0, FOOTER_CRC_AT)
        if (stored != computed) bytes.corrupt("segment footer checksum does not match", base)

        val version = bytes.i32(base + FOOTER_VERSION_AT, "footer version")
        if (version != VERSION) {
            throw UnsupportedFormatException(
                "${bytes.file} was written with segment format version $version; " +
                    "this build reads version $VERSION",
            )
        }

        return Footer(
            dictionary = readHandle(bytes, base, "dictionary handle"),
            index = readHandle(bytes, base + HANDLE_BYTES, "index handle"),
            bloom = readHandle(bytes, base + 2L * HANDLE_BYTES, "bloom handle"),
            entryCount = bytes.i64(base + 36, "entry count"),
            smallestSequence = bytes.i64(base + 44, "smallest sequence"),
            largestSequence = bytes.i64(base + 52, "largest sequence"),
        )
    }

    fun writeHandle(out: ByteWriter, handle: BlockHandle) {
        out.writeLong(handle.offset)
        out.writeInt(handle.length)
    }

    fun readHandle(bytes: SegmentBytes, offset: Long, what: String): BlockHandle {
        val at = bytes.i64(offset, what)
        val length = bytes.length(offset + 8, what)
        // A handle is the one thing that turns a byte in the file into an address, so it is
        // range-checked the moment it is read rather than at the point of use.
        bytes.requireRange(at, length.toLong(), what)
        return BlockHandle(at, length)
    }

    fun checksum(bytes: ByteArray, offset: Int, length: Int): Int {
        val crc = CRC32C()
        crc.update(bytes, offset, length)
        return crc.value.toInt()
    }
}

/** Where a block lives in a segment: the offset of its contents and their length, trailer excluded. */
internal class BlockHandle(val offset: Long, val length: Int) {
    override fun toString(): String = "BlockHandle($offset, $length)"
}
