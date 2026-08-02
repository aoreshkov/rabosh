package app.oreshkov.rabosh.catalog

import java.io.IOException
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Locale
import java.util.TreeMap

/** Suffix of a sketch sidecar. One per segment, named after the segment's file number. */
internal const val SKETCH_SUFFIX: String = ".cat"

private const val TEMPORARY_SUFFIX = ".cat.tmp"

/**
 * Zero-padded to ten digits, so a sidecar sorts next to the segment it belongs to in a directory
 * listing — the same rule the engine's own file names follow.
 */
internal fun sketchFileName(segmentNumber: Long): String =
    String.format(Locale.ROOT, "%010d%s", segmentNumber, SKETCH_SUFFIX)

internal fun temporarySketchFileName(segmentNumber: Long): String =
    String.format(Locale.ROOT, "%010d%s", segmentNumber, TEMPORARY_SUFFIX)

/** The segment number a sidecar name carries, or `null` if the name is not one. */
internal fun sketchSegmentNumber(name: String): Long? {
    if (!name.endsWith(SKETCH_SUFFIX)) return null
    return name.removeSuffix(SKETCH_SUFFIX).toLongOrNull()?.takeIf { it >= 0 }
}

/**
 * Reads and writes the `.cat` sidecar. See [SketchFormat] for the layout.
 *
 * **A sidecar is written whole and moved into place.** Temporary name, force, `ATOMIC_MOVE`, force
 * the directory. There is therefore no partially written sidecar to recover from, which is why
 * nothing here has the log's or the manifest's torn-tail taxonomy: a file that exists is complete,
 * and a file that does not exist is a segment that has not been sketched.
 *
 * **It is not forced before the manifest names the segment**, unlike the segment itself. That
 * ordering exists to stop the manifest from naming a file the platter does not have; a sketch is
 * derived, and a sketch the platter does not have costs a rescan rather than a document. What must
 * not happen — and does not — is a sidecar that reads as *collected and empty* when it was never
 * written: absence is absence, and [SchemaCatalog] reports it as uncovered.
 */
internal object SketchFile {

    /** Writes [sketch] for [segmentNumber] into [directory], replacing any sidecar already there. */
    fun write(directory: Path, segmentNumber: Long, sketch: SegmentSketch) {
        val payload = encode(segmentNumber, sketch)
        val file = ByteArray(SketchFormat.HEADER_BYTES + payload.size)
        SketchFormat.MAGIC.copyInto(file)
        writeIntAt(file, 8, SketchFormat.VERSION)
        writeIntAt(file, 12, payload.size)
        payload.copyInto(file, SketchFormat.HEADER_BYTES)
        // The checksum covers the version and the length as well as the payload: the field that says
        // how much to read must not be the one left unprotected.
        writeIntAt(file, 16, SketchFormat.checksum(file, 8, 8, payload))

        val temporary = directory.resolve(temporarySketchFileName(segmentNumber))
        val target = directory.resolve(sketchFileName(segmentNumber))
        FileChannel.open(
            temporary,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        ).use { channel ->
            val buffer = ByteBuffer.wrap(file)
            while (buffer.hasRemaining()) {
                if (channel.write(buffer) <= 0) throw IOException("sketch write made no progress")
            }
            channel.force(true)
        }
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** Reads the sidecar for [segmentNumber], or `null` if there is not one. */
    fun read(directory: Path, segmentNumber: Long): SegmentSketch? {
        val name = sketchFileName(segmentNumber)
        val path = directory.resolve(name)
        val bytes = try {
            Files.readAllBytes(path)
        } catch (missing: java.nio.file.NoSuchFileException) {
            return null
        }
        return decode(bytes, name, segmentNumber)
    }

    /** Deletes the sidecar for [segmentNumber] if it is there. */
    fun delete(directory: Path, segmentNumber: Long) {
        Files.deleteIfExists(directory.resolve(sketchFileName(segmentNumber)))
        Files.deleteIfExists(directory.resolve(temporarySketchFileName(segmentNumber)))
    }

    // --- encoding ------------------------------------------------------------------------------

    fun encode(segmentNumber: Long, sketch: SegmentSketch): ByteArray {
        val out = SketchWriter()
        out.writeLong(segmentNumber)
        out.writeLong(sketch.documentCount)
        out.writeLong(sketch.observationCount)
        val entries = sketch.entries()
        out.writeInt(entries.size)
        for ((path, pathSketch) in entries) {
            out.writeString(path.toString())
            out.writeLong(pathSketch.observations)
            out.writeLong(pathSketch.nullObservations)
            out.writeLong(pathSketch.totalBytes)
            writeTypeCounts(out, pathSketch)
            writeBounds(out, pathSketch.bounds)
            pathSketch.distinctSketch().writeTo(out)
        }
        sketch.droppedPathSketch().writeTo(out)
        out.writeLong(sketch.droppedObservations)
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray, file: String, expectedSegmentNumber: Long?): SegmentSketch {
        if (bytes.size < SketchFormat.HEADER_BYTES) {
            throw CorruptSketchException(
                "sidecar is ${bytes.size} byte(s), too short for a header",
                file,
                0,
            )
        }
        for (index in SketchFormat.MAGIC.indices) {
            if (bytes[index] != SketchFormat.MAGIC[index]) {
                throw CorruptSketchException(
                    "not a rabosh sketch: the file does not begin with ${SketchFormat.MAGIC.decodeToString()}",
                    file,
                    0,
                )
            }
        }
        val version = readIntAt(bytes, 8)
        if (version != SketchFormat.VERSION) {
            throw UnsupportedSketchFormatException(
                "$file was written with sketch format version $version; " +
                    "this build reads version ${SketchFormat.VERSION}",
            )
        }
        val length = readIntAt(bytes, 12).toLong() and 0xFFFF_FFFFL
        if (length > SketchFormat.MAX_PAYLOAD_BYTES ||
            SketchFormat.HEADER_BYTES + length != bytes.size.toLong()
        ) {
            throw CorruptSketchException(
                "sidecar declares a $length-byte payload in a ${bytes.size}-byte file",
                file,
                12,
            )
        }
        val payload = bytes.copyOfRange(SketchFormat.HEADER_BYTES, bytes.size)
        val stored = readIntAt(bytes, 16)
        if (stored != SketchFormat.checksum(bytes, 8, 8, payload)) {
            throw CorruptSketchException("sidecar checksum does not match", file, 16)
        }

        val reader = SketchReader(payload, file, SketchFormat.HEADER_BYTES.toLong())
        val segmentNumber = reader.long("segment number")
        if (expectedSegmentNumber != null && segmentNumber != expectedSegmentNumber) {
            // The name and the contents have to agree. A sidecar copied or renamed into place would
            // otherwise be folded in as if it described a segment it has never seen.
            reader.corrupt("sidecar names segment $segmentNumber but is filed under $expectedSegmentNumber")
        }
        val documentCount = reader.long("document count")
        val observationCount = reader.long("observation count")
        val pathCount = reader.count("path count")

        val entries = TreeMap<CatalogPath, PathSketch>()
        repeat(pathCount) {
            val path = try {
                CatalogPath.parse(reader.string("path"))
            } catch (malformed: IllegalArgumentException) {
                reader.corrupt("sidecar holds a path that does not parse", malformed)
            }
            val observations = reader.long("observations")
            val nulls = reader.long("null observations")
            val totalBytes = reader.long("total bytes")
            val typeCounts = readTypeCounts(reader)
            val bounds = readBounds(reader)
            val distinct = HyperLogLog.readFrom(reader)
            entries[path] = PathSketch(observations, nulls, typeCounts, totalBytes, bounds, distinct)
        }

        val dropped = HyperLogLog.readFrom(reader)
        val droppedObservations = reader.long("dropped observations")
        if (!reader.exhausted) reader.corrupt("sidecar has trailing bytes after its last field")

        return SegmentSketch(documentCount, observationCount, entries, dropped, droppedObservations)
    }

    private fun writeTypeCounts(out: SketchWriter, sketch: PathSketch) {
        val counts = sketch.typeCountsArray()
        val present = app.oreshkov.rabosh.variant.VariantKind.entries.filter { counts[it.ordinal] > 0 }
        out.writeInt(present.size)
        for (kind in present) {
            out.writeByte(SketchFormat.typeId(kind))
            out.writeLong(counts[kind.ordinal])
        }
    }

    private fun readTypeCounts(reader: SketchReader): LongArray {
        val counts = LongArray(app.oreshkov.rabosh.variant.VariantKind.entries.size)
        val count = reader.count("type count", counts.size)
        repeat(count) {
            val id = reader.byte("type id")
            // An id this build does not know means a newer writer, not a count that can be skipped:
            // the fields after it cannot be located without knowing what it was.
            val kind = SketchFormat.typeOfId(id)
                ?: throw UnsupportedSketchFormatException(
                    "${reader.file} holds an unknown value-kind id $id; this build cannot read it",
                )
            if (counts[kind.ordinal] != 0L) reader.corrupt("type $kind appears twice for one path")
            counts[kind.ordinal] = reader.long("type $kind count")
        }
        return counts
    }

    private fun writeBounds(out: SketchWriter, bounds: ValueBounds) {
        val numeric = bounds.numeric
        if (numeric == null) {
            out.writeByte(SketchFormat.BOUND_NONE)
        } else {
            out.writeByte(SketchFormat.BOUND_NUMERIC)
            writeDecimal(out, numeric.min)
            writeDecimal(out, numeric.max)
        }

        val text = bounds.text
        if (text == null) {
            out.writeByte(SketchFormat.BOUND_NONE)
        } else {
            out.writeByte(SketchFormat.BOUND_TEXT)
            out.writeBytes(text.minUtf8())
            out.writeByte(if (text.minIsExact) 1 else 0)
            val max = text.maxUtf8()
            if (max == null) {
                out.writeByte(0)
            } else {
                out.writeByte(1)
                out.writeBytes(max)
                out.writeByte(if (text.maxIsExact) 1 else 0)
            }
        }
    }

    private fun readBounds(reader: SketchReader): ValueBounds {
        val numeric = when (val tag = reader.byte("numeric bound tag")) {
            SketchFormat.BOUND_NONE -> null
            SketchFormat.BOUND_NUMERIC -> NumericRange(readDecimal(reader), readDecimal(reader))
            else -> reader.corrupt("unknown numeric bound tag $tag")
        }
        val text = when (val tag = reader.byte("text bound tag")) {
            SketchFormat.BOUND_NONE -> null
            SketchFormat.BOUND_TEXT -> {
                val min = reader.lengthPrefixedBytes("text lower bound")
                val minExact = reader.byte("text lower bound exactness") != 0
                val hasMax = reader.byte("text upper bound presence") != 0
                if (hasMax) {
                    val max = reader.lengthPrefixedBytes("text upper bound")
                    TextRange(min, max, minExact, reader.byte("text upper bound exactness") != 0)
                } else {
                    TextRange(min, null, minExact, false)
                }
            }

            else -> reader.corrupt("unknown text bound tag $tag")
        }
        return if (numeric == null && text == null) ValueBounds.EMPTY else ValueBounds(numeric, text)
    }

    private fun writeDecimal(out: SketchWriter, value: BigDecimal) {
        out.writeInt(value.scale())
        out.writeBytes(value.unscaledValue().toByteArray())
    }

    private fun readDecimal(reader: SketchReader): BigDecimal {
        val scale = reader.int("decimal scale")
        val unscaled = reader.lengthPrefixedBytes("decimal unscaled value")
        if (unscaled.isEmpty()) reader.corrupt("decimal has no unscaled value")
        return BigDecimal(BigInteger(unscaled), scale)
    }

    private fun writeIntAt(bytes: ByteArray, offset: Int, value: Int) {
        for (index in 0 until 4) bytes[offset + index] = (value ushr (8 * index)).toByte()
    }

    private fun readIntAt(bytes: ByteArray, offset: Int): Int {
        var value = 0
        for (index in 0 until 4) value = value or ((bytes[offset + index].toInt() and 0xFF) shl (8 * index))
        return value
    }
}
