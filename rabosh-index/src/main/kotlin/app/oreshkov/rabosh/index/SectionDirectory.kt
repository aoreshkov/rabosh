package app.oreshkov.rabosh.index

import java.lang.foreign.MemorySegment
import java.util.zip.CRC32C

/**
 * The framing both sidecar files share: a fixed-width section directory over a mapped file.
 *
 * ```
 * file      := header entry[sectionCount] section*
 * header    := magic[8] version:u32 sectionCount:u32 crc32c:u32       (20 bytes)
 * entry     := kind:u8 reserved:u8[3] offset:u64 length:u32 crc32c:u32 (20 bytes)
 * ```
 *
 * Extracted so the base sidecar and the column file share one implementation. That is the same rule
 * phase 6 applied to the container read algorithms: two copies of "where does this section begin" that
 * disagreed would make a file readable by one reader and damaged to the other, and the divergence
 * would show up as corruption rather than as a bug.
 *
 * Three properties carry weight and all three are checked here rather than by each caller:
 *
 * **The sections must tile the file exactly**, from the end of the directory to the last byte. One
 * check catches truncation, a gap, an overlap and trailing bytes.
 *
 * **The checksums are two levels.** The header's covers everything that decides *where a byte is* —
 * version, section count and the whole directory — and is checked once, here, on open. Each entry's
 * covers one section's bytes together with its own kind byte and is checked when that section is
 * first read, by the caller, because verifying a ten-million-key section on every open would read the
 * whole file and defeat mapping entirely.
 *
 * **An unknown section kind is skipped, not reported.** That is safe *because* the directory is
 * fixed-width and carries each section's extent, so a reader that does not understand one can still
 * find its way past it. The manifest cannot do that — an unknown edit tag there has no width, which
 * is why `Manifest` calls it corruption — and the two must not be made to behave alike. It is what
 * lets a new section kind be an addition rather than a format version.
 *
 * **What each file means by a kind is its own business.** `.idx` and `.col` keep *separate* kind
 * namespaces, both starting at 1: a section of `.idx` is a fact about a segment and a section of
 * `.col` is a fact about one path within one index over one segment, and the file is what
 * disambiguates — exactly as `BitmapFormat`'s container kinds and `IndexFormat`'s section kinds both
 * start at 1 and are never confused. Sharing one namespace would make every future column encoding
 * burn a globally scarce id to save nothing.
 */
internal object SectionDirectory {

    const val HEADER_BYTES: Int = 20
    const val ENTRY_BYTES: Int = 20

    /**
     * Lays out [sections] behind a header and a directory.
     *
     * @param sections `(kind, body)` in the order they are written. Kinds are not checked here: the
     *   file's own format object owns what they mean.
     */
    fun encode(magic: ByteArray, version: Int, sections: List<Pair<Int, ByteArray>>): ByteArray {
        val directoryEnd = HEADER_BYTES + sections.size * ENTRY_BYTES
        val out = IndexWriter(directoryEnd + sections.sumOf { it.second.size })
        out.write(magic)
        out.writeU32(version)
        out.writeU32(sections.size)
        val checksumAt = out.size
        out.writeU32(0)

        var offset = directoryEnd.toLong()
        for ((kind, body) in sections) {
            out.writeByte(kind)
            out.pad(3)
            out.writeLong(offset)
            out.writeU32(body.size)
            out.writeU32(sectionChecksum(kind, body))
            offset += body.size
        }
        out.patchU32(checksumAt, out.checksum(IndexFormat.MAGIC_BYTES, checksumAt, HEADER_BYTES, out.size))
        for ((_, body) in sections) out.write(body)
        return out.toByteArray()
    }

    /**
     * Reads and validates the header and directory of a mapped file.
     *
     * @param versions every version of this file the caller can read, and nothing else. A set rather
     *   than a number because a format that replaces a layout has to keep reading the layout it
     *   replaced — `.idx` reads two since phase 18, `.col` reads one and must keep reading one. The
     *   accepted version is reported back as [Sections.version] so exactly one caller, the file's own
     *   `open`, decides what to do about it.
     * @param kindName names a kind for failure messages, or returns `null` for one this build does
     *   not know — which is skipped rather than reported, per the class KDoc.
     */
    fun open(
        segment: MemorySegment,
        length: Int,
        file: String,
        magic: ByteArray,
        versions: IntArray,
        what: String,
        kindName: (Int) -> String?,
    ): Sections {
        val bytes = IndexBytes(segment, 0, length, file, ::CorruptIndexException)
        if (length < HEADER_BYTES) bytes.corrupt("a $what needs at least a $HEADER_BYTES-byte header")
        for (index in 0 until IndexFormat.MAGIC_BYTES) {
            if (bytes.u8(index, "magic") != (magic[index].toInt() and 0xFF)) {
                bytes.corrupt("a $what does not begin with ${magic.decodeToString()}", index)
            }
        }
        val actualVersion = bytes.u32(8, "$what version", Int.MAX_VALUE)
        if (actualVersion !in versions) {
            throw UnsupportedIndexFormatException(
                "the $what $file is version $actualVersion; this build reads ${versions.joinToString()}",
            )
        }

        val maxSections = (length - HEADER_BYTES) / ENTRY_BYTES
        val sectionCount = bytes.u32(12, "$what section count", maxSections)
        val directoryEnd = HEADER_BYTES + sectionCount * ENTRY_BYTES
        val expected = bytes.i32(16, "$what header checksum")
        val actual = run {
            val crc = CRC32C()
            val head = bytes.bytes(IndexFormat.MAGIC_BYTES, 8, "version and section count")
            crc.update(head, 0, head.size)
            val directory = bytes.bytes(HEADER_BYTES, directoryEnd - HEADER_BYTES, "directory")
            crc.update(directory, 0, directory.size)
            crc.value.toInt()
        }
        if (expected != actual) bytes.corrupt("the $what's header checksum does not match its directory", 16)

        val found = HashMap<Int, Section>(sectionCount)
        var tiled = directoryEnd.toLong()
        for (index in 0 until sectionCount) {
            val at = HEADER_BYTES + index * ENTRY_BYTES
            val kind = bytes.u8(at, "section kind")
            val offset = bytes.i64(at + 4, "section offset")
            val sectionLength = bytes.u32(at + 12, "section length", length)
            val checksum = bytes.i32(at + 16, "section checksum")
            if (offset != tiled) {
                bytes.corrupt("section $index begins at $offset, not at $tiled where the previous one ended", at + 4)
            }
            if (offset + sectionLength > length) {
                bytes.corrupt("section $index runs past the end of the $what", at + 4)
            }
            tiled = offset + sectionLength
            val slice = bytes.slice(offset.toInt(), sectionLength, kindName(kind) ?: "section $index")
            // A duplicate known kind is damage: which one would a reader believe?
            if (kindName(kind) != null && found.put(kind, Section(kind, slice, checksum)) != null) {
                bytes.corrupt("the $what names the ${kindName(kind)} section twice", at)
            }
        }
        if (tiled != length.toLong()) {
            bytes.corrupt("the $what's sections end at $tiled, leaving ${length - tiled} byte(s) unaccounted for")
        }
        return Sections(found, bytes, what, actualVersion)
    }

    /** One section: its bytes, and the checksum its directory entry claims for them. */
    class Section(val kind: Int, val bytes: IndexBytes, val checksum: Int) {
        private var verified = false

        /** Checks this section's bytes against its entry. Idempotent; the first call pays. */
        fun verify() {
            if (verified) return
            val actual = sectionChecksum(kind, bytes.bytes(0, bytes.length, "section body"))
            if (actual != checksum) {
                bytes.corrupt("a section's checksum does not match its bytes")
            }
            verified = true
        }

        /** The verified bytes. */
        fun verified(): IndexBytes {
            verify()
            return bytes
        }
    }

    /** The sections a file declared, by kind. */
    class Sections(
        private val byKind: Map<Int, Section>,
        private val bytes: IndexBytes,
        private val what: String,
        /** Which of the versions the caller offered this file actually carries. */
        val version: Int,
    ) {
        /** The section of [kind], or `null` if the file does not carry one. */
        operator fun get(kind: Int): Section? = byKind[kind]

        /** The section of [kind]. A missing one this build needs is damage, not an absence. */
        fun require(kind: Int, name: String): Section =
            byKind[kind] ?: bytes.corrupt("the $what has no $name section")

        /** Every known section, for [verify]-style walks. */
        fun all(): Collection<Section> = byKind.values
    }
}

/** CRC32C over a section's kind byte and its bytes. The kind is covered so a retag is damage. */
internal fun sectionChecksum(kind: Int, body: ByteArray): Int {
    val crc = CRC32C()
    crc.update(kind)
    crc.update(body, 0, body.size)
    return crc.value.toInt()
}
