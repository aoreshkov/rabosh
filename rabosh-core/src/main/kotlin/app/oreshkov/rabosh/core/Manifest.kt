package app.oreshkov.rabosh.core

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * One atomic change to the set of live segments.
 *
 * A compaction removes several files and adds several others, and the two halves must land
 * together: a version in which the inputs are gone and the outputs are not yet there is a version
 * that has lost data. So an edit is the unit — one edit, one record, one `write` and one `force` —
 * and a crash leaves the previous version intact rather than a half-applied one.
 *
 * The optional fields are `null` when the edit does not touch them, which is not the same as
 * setting them to zero. An edit that only adds a segment must not silently reset the log number to
 * the beginning of the directory.
 */
internal class VersionEdit {
    /** Oldest log still needed; everything below it has been flushed into a segment. */
    var logNumber: Long? = null

    /** Next unused file number. Recorded so a crash cannot hand the same number out twice. */
    var nextFileNumber: Long? = null

    /** Last sequence number the segments account for. */
    var lastSequence: Long? = null

    val added: MutableList<Pair<Int, SegmentMetadata>> = ArrayList()
    val removed: MutableList<Pair<Int, Long>> = ArrayList()

    fun add(level: Int, segment: SegmentMetadata): VersionEdit = apply { added += level to segment }

    fun remove(level: Int, number: Long): VersionEdit = apply { removed += level to number }

    fun isEmpty(): Boolean =
        logNumber == null && nextFileNumber == null && lastSequence == null &&
            added.isEmpty() && removed.isEmpty()

    override fun toString(): String =
        "VersionEdit(log=$logNumber, nextFile=$nextFileNumber, lastSequence=$lastSequence, " +
            "added=${added.map { "L${it.first}#${it.second.number}" }}, " +
            "removed=${removed.map { "L${it.first}#${it.second}" }})"
}

/**
 * The on-disk layout of a manifest.
 *
 * ```
 * file    := magic["JKDB-MAN"] version:u32 crc32c:u32                  (16 bytes)
 *            record*
 * record  := payloadLength:u32 crc32c:u32 payload                      (see Frames)
 * payload := editCount:u32 edit*
 * edit    := 1 logNumber:u64
 *          | 2 nextFileNumber:u64
 *          | 3 lastSequence:u64
 *          | 4 level:u32 number:u64 fileBytes:u64
 *              smallestKeyLength:u32 smallestKey largestKeyLength:u32 largestKey
 *              smallestSequence:u64 largestSequence:u64 entryCount:u64
 *          | 5 level:u32 number:u64
 * ```
 *
 * Little-endian throughout. **These constants are permanent**, like the log's and the segment's:
 * add, never renumber.
 *
 * **The key ranges are stored here as well as in each segment's footer.** That is deliberate
 * duplication: the version set decides which file a lookup could be in, and if it had to map every
 * segment to find out, opening a store would map every segment. Read from the manifest, the whole
 * routing structure is in memory before a single file is touched.
 *
 * **`editCount` sits in the payload** the way the log's `operationCount` does, so one record can
 * carry a whole compaction's removes and adds. It is the same shape for the same reason.
 */
internal object ManifestFormat {
    /** `JKDB-MAN` in ASCII, distinct from `JKDB-WAL` and `JKDB-SEG`. */
    val MAGIC: ByteArray = "JKDB-MAN".encodeToByteArray()

    const val VERSION: Int = 1
    const val HEADER_BYTES: Int = 16

    const val TAG_LOG_NUMBER: Int = 1
    const val TAG_NEXT_FILE_NUMBER: Int = 2
    const val TAG_LAST_SEQUENCE: Int = 3
    const val TAG_ADD_SEGMENT: Int = 4
    const val TAG_REMOVE_SEGMENT: Int = 5

    /** Ceiling on one record, so a corrupt length is rejected rather than allocated. */
    const val MAX_RECORD_BYTES: Int = 1 shl 26

    fun encodeHeader(): ByteArray {
        val out = ByteWriter(HEADER_BYTES)
        out.write(MAGIC)
        out.writeInt(VERSION)
        out.writeInt(Frames.crc32c(out.backing, 0, 12))
        return out.toByteArray()
    }

    /**
     * Validates a manifest header.
     *
     * Unlike a log header there is no benign reading of a broken one. A manifest is created,
     * written and forced before `CURRENT` is swapped to name it, so a manifest that `CURRENT` names
     * and whose header is unreadable is damage.
     */
    fun checkHeader(bytes: ByteArray, file: String) {
        if (bytes.size < HEADER_BYTES) {
            throw CorruptManifestException("manifest is ${bytes.size} byte(s), too short for a header", file, 0)
        }
        for (index in MAGIC.indices) {
            if (bytes[index] != MAGIC[index]) {
                throw CorruptManifestException(
                    "not a rabosh manifest: the file does not begin with ${MAGIC.decodeToString()}",
                    file,
                    0,
                )
            }
        }
        if (Frames.crc32c(bytes, 0, 12) != readInt(bytes, 12)) {
            throw CorruptManifestException("manifest header checksum does not match", file, 0)
        }
        val version = readInt(bytes, 8)
        if (version != VERSION) {
            throw UnsupportedFormatException(
                "$file was written with manifest format version $version; this build reads version $VERSION",
            )
        }
    }

    fun encodeRecord(edit: VersionEdit): ByteArray {
        val payload = encodePayload(edit)
        val record = ByteArray(Frames.HEADER_BYTES + payload.size)
        writeIntAt(record, 0, payload.size)
        // The checksum covers the length field and the payload, in that order — see `Frames`.
        val crc = java.util.zip.CRC32C()
        crc.update(record, 0, 4)
        crc.update(payload, 0, payload.size)
        writeIntAt(record, 4, crc.value.toInt())
        payload.copyInto(record, Frames.HEADER_BYTES)
        return record
    }

    private fun encodePayload(edit: VersionEdit): ByteArray {
        val payload = ByteWriter(256)
        var count = 0
        payload.writeInt(0) // patched with the edit count once it is known
        edit.logNumber?.let { payload.writeByte(TAG_LOG_NUMBER); payload.writeLong(it); count++ }
        edit.nextFileNumber?.let { payload.writeByte(TAG_NEXT_FILE_NUMBER); payload.writeLong(it); count++ }
        edit.lastSequence?.let { payload.writeByte(TAG_LAST_SEQUENCE); payload.writeLong(it); count++ }
        for ((level, segment) in edit.added) {
            payload.writeByte(TAG_ADD_SEGMENT)
            payload.writeInt(level)
            payload.writeLong(segment.number)
            payload.writeLong(segment.fileBytes)
            writeKey(payload, segment.smallestKey)
            writeKey(payload, segment.largestKey)
            payload.writeLong(segment.smallestSequence)
            payload.writeLong(segment.largestSequence)
            payload.writeLong(segment.entryCount)
            count++
        }
        for ((level, number) in edit.removed) {
            payload.writeByte(TAG_REMOVE_SEGMENT)
            payload.writeInt(level)
            payload.writeLong(number)
            count++
        }
        val bytes = payload.toByteArray()
        writeIntAt(bytes, 0, count)
        return bytes
    }

    fun decodePayload(bytes: ByteArray, file: String, at: Long): VersionEdit {
        val edit = VersionEdit()
        val reader = FieldReader(bytes, file, at)
        val count = reader.int("edit count")
        if (count < 0) reader.corrupt("edit count is $count")
        repeat(count) {
            when (val tag = reader.byte("edit tag")) {
                TAG_LOG_NUMBER -> edit.logNumber = reader.long("log number")
                TAG_NEXT_FILE_NUMBER -> edit.nextFileNumber = reader.long("next file number")
                TAG_LAST_SEQUENCE -> edit.lastSequence = reader.long("last sequence")
                TAG_ADD_SEGMENT -> {
                    val level = reader.int("level")
                    val segment = SegmentMetadata(
                        number = reader.long("segment number"),
                        fileBytes = reader.long("segment size"),
                        smallestKey = reader.key("smallest key"),
                        largestKey = reader.key("largest key"),
                        smallestSequence = reader.long("smallest sequence"),
                        largestSequence = reader.long("largest sequence"),
                        entryCount = reader.long("entry count"),
                    )
                    if (level !in 0..LEVEL_COUNT) reader.corrupt("segment level $level is outside 0..$LEVEL_COUNT")
                    edit.added += level to segment
                }

                TAG_REMOVE_SEGMENT -> {
                    val level = reader.int("level")
                    if (level !in 0..LEVEL_COUNT) reader.corrupt("segment level $level is outside 0..$LEVEL_COUNT")
                    edit.removed += level to reader.long("segment number")
                }

                // An unrecognised tag means the file was written by something this build does not
                // understand, not that the edit can be skipped: the edits that follow it in the
                // same record cannot be located without knowing its width.
                else -> reader.corrupt("unknown manifest edit tag $tag")
            }
        }
        return edit
    }

    private fun writeKey(out: ByteWriter, key: Key) {
        out.writeInt(key.size)
        out.write(key.raw)
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int {
        var value = 0
        for (index in 0 until 4) value = value or ((bytes[offset + index].toInt() and 0xFF) shl (8 * index))
        return value
    }

    private fun writeIntAt(bytes: ByteArray, offset: Int, value: Int) {
        for (index in 0 until 4) bytes[offset + index] = (value ushr (8 * index)).toByte()
    }

    /** Reads a payload's fields in order, reporting the file and offset when one runs off the end. */
    private class FieldReader(private val bytes: ByteArray, private val file: String, private val at: Long) {
        private var position = 0

        fun byte(what: String): Int {
            require(1, what)
            return bytes[position++].toInt() and 0xFF
        }

        fun int(what: String): Int {
            require(4, what)
            var value = 0
            for (index in 0 until 4) value = value or ((bytes[position + index].toInt() and 0xFF) shl (8 * index))
            position += 4
            return value
        }

        fun long(what: String): Long {
            require(8, what)
            var value = 0L
            for (index in 0 until 8) value = value or ((bytes[position + index].toLong() and 0xFF) shl (8 * index))
            position += 8
            return value
        }

        fun key(what: String): Key {
            val length = int("$what length")
            if (length < 0) corrupt("$what length is $length")
            require(length, what)
            val raw = bytes.copyOfRange(position, position + length)
            position += length
            return Key.wrap(raw)
        }

        fun corrupt(message: String): Nothing = throw CorruptManifestException(message, file, at + position)

        private fun require(count: Int, what: String) {
            if (position + count > bytes.size) corrupt("truncated $what: the record ends first")
        }
    }
}

/**
 * Appends edits to a manifest.
 *
 * Every append is forced before it returns. A version installed in memory but not on the platter is
 * the same failure the write-ahead log exists to prevent, one level up: after a power loss the
 * store would open on the previous version, whose segments a compaction may already have deleted.
 */
internal class ManifestWriter private constructor(
    private val channel: FileChannel,
    val path: Path,
    val number: Long,
    private var position: Long,
) : AutoCloseable {

    val bytesWritten: Long get() = position

    fun append(edit: VersionEdit) {
        val record = ManifestFormat.encodeRecord(edit)
        require(record.size <= ManifestFormat.MAX_RECORD_BYTES) {
            "manifest record of ${record.size} bytes exceeds ${ManifestFormat.MAX_RECORD_BYTES}"
        }
        val buffer = ByteBuffer.wrap(record)
        while (buffer.hasRemaining()) {
            val written = channel.write(buffer)
            if (written <= 0) throw IOException("manifest write made no progress at $position")
        }
        position += record.size
        channel.force(false)
    }

    override fun close() {
        channel.close()
    }

    companion object {
        /** Creates a new manifest, writes its header, and forces both the file and the directory. */
        fun create(directory: Path, number: Long): ManifestWriter {
            val path = directory.resolve(manifestFileName(number))
            val channel = FileChannel.open(
                path,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
            try {
                val header = ManifestFormat.encodeHeader()
                val buffer = ByteBuffer.wrap(header)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
                syncDirectory(directory)
                return ManifestWriter(channel, path, number, header.size.toLong())
            } catch (failure: Throwable) {
                try {
                    channel.close()
                    Files.deleteIfExists(path)
                } catch (secondary: Throwable) {
                    failure.addSuppressed(secondary)
                }
                throw failure
            }
        }

        /** Reopens an existing manifest for append, truncating a torn trailing record away. */
        fun openForAppend(path: Path, number: Long, validLength: Long): ManifestWriter {
            val channel = FileChannel.open(path, StandardOpenOption.WRITE)
            try {
                if (channel.size() > validLength) {
                    // Same reasoning as the log: leaving the partial record in place would put every
                    // future append behind bytes recovery has already rejected.
                    channel.truncate(validLength)
                    channel.force(true)
                }
                channel.position(validLength)
                return ManifestWriter(channel, path, number, validLength)
            } catch (failure: Throwable) {
                try {
                    channel.close()
                } catch (secondary: Throwable) {
                    failure.addSuppressed(secondary)
                }
                throw failure
            }
        }
    }
}

/** What replaying a manifest produced. */
internal class ManifestReplay(val edits: List<VersionEdit>, val validLength: Long)

/**
 * Replays a manifest.
 *
 * The taxonomy is the log's, minus the parts that do not apply. **An incomplete record at the very
 * end is dropped**: it describes a version that was never installed, because installing it is what
 * the append precedes. **A record that fails to validate with a readable record behind it is
 * corruption**, for the log's reason — the writer could not have reached the later record without
 * having completed this one.
 *
 * What the manifest does not need is the log's sequence-continuity check: records here are ordered
 * by position and carry no numbering of their own, so a lost record in the middle shows up as the
 * checksum failure that the corruption rule already reports.
 */
internal object ManifestReader {
    fun replay(path: Path, mode: LogRecoveryMode): ManifestReplay {
        val file = path.fileName.toString()
        val bytes = Files.readAllBytes(path)
        ManifestFormat.checkHeader(bytes, file)

        val edits = ArrayList<VersionEdit>()
        var position = ManifestFormat.HEADER_BYTES
        var validLength = position.toLong()

        while (position < bytes.size) {
            val frame = readFrame(bytes, position)
            if (frame == null) {
                // Not enough bytes left for a whole record: a writer that died mid-append. Nothing
                // installed this version, so nothing is lost by dropping it.
                break
            }
            if (frame.checksumFailed) {
                // A readable record behind this one means the writer got past it, so these bytes
                // changed after the fact. Random bytes do not pass CRC32C, so a positive probe is
                // strong evidence rather than a guess.
                if (looksLikeRecord(bytes, position + Frames.HEADER_BYTES + frame.length)) {
                    throw CorruptManifestException(
                        "manifest record checksum does not match, and a valid record follows it",
                        file,
                        position.toLong(),
                    )
                }
                break
            }
            val payloadAt = position + Frames.HEADER_BYTES
            edits += ManifestFormat.decodePayload(
                bytes.copyOfRange(payloadAt, payloadAt + frame.length),
                file,
                payloadAt.toLong(),
            )
            position = payloadAt + frame.length
            validLength = position.toLong()
        }

        if (position < bytes.size && mode == LogRecoveryMode.STRICT) {
            throw CorruptManifestException(
                "manifest has ${bytes.size - position} unreadable trailing byte(s)",
                file,
                position.toLong(),
            )
        }
        return ManifestReplay(edits, validLength)
    }

    private class Frame(val length: Int, val checksumFailed: Boolean)

    private fun readFrame(bytes: ByteArray, at: Int): Frame? {
        if (at + Frames.HEADER_BYTES > bytes.size) return null
        val length = readInt(bytes, at).toLong() and 0xFFFF_FFFFL
        if (length > ManifestFormat.MAX_RECORD_BYTES || at + Frames.HEADER_BYTES + length > bytes.size) return null
        val stored = readInt(bytes, at + 4)
        val crc = java.util.zip.CRC32C()
        crc.update(bytes, at, 4)
        crc.update(bytes, at + Frames.HEADER_BYTES, length.toInt())
        return Frame(length.toInt(), crc.value.toInt() != stored)
    }

    /** Whether a well-formed record begins at [at]; the probe that separates damage from a torn tail. */
    private fun looksLikeRecord(bytes: ByteArray, at: Int): Boolean = readFrame(bytes, at)?.checksumFailed == false

    private fun readInt(bytes: ByteArray, offset: Int): Int {
        var value = 0
        for (index in 0 until 4) value = value or ((bytes[offset + index].toInt() and 0xFF) shl (8 * index))
        return value
    }
}

/**
 * Reads and writes `CURRENT`, the file that says which manifest is in force.
 *
 * **It is swapped, never edited.** A new manifest is written whole and forced, then a temporary
 * `CURRENT` is written, forced and moved over the old one atomically, then the directory is forced.
 * Editing `CURRENT` in place would have a window in which it names neither manifest, and that
 * window is the only moment where a store can be lost outright rather than rolled back.
 */
internal object CurrentFile {
    fun read(directory: Path): Long? {
        val path = directory.resolve(CURRENT_FILE_NAME)
        if (!Files.exists(path)) return null
        val text = Files.readString(path).trim()
        if (!text.startsWith(MANIFEST_PREFIX)) {
            throw CorruptManifestException("CURRENT names '$text', which is not a manifest", CURRENT_FILE_NAME)
        }
        return text.removePrefix(MANIFEST_PREFIX).toLongOrNull()
            ?: throw CorruptManifestException("CURRENT names '$text', whose number is unreadable", CURRENT_FILE_NAME)
    }

    fun write(directory: Path, number: Long) {
        val temporary = directory.resolve("$CURRENT_FILE_NAME.tmp")
        val target = directory.resolve(CURRENT_FILE_NAME)
        Files.writeString(
            temporary,
            manifestFileName(number) + "\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        syncDirectory(directory)
    }
}
