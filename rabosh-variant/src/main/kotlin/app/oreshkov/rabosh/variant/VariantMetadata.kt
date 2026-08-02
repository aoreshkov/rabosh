package app.oreshkov.rabosh.variant

import java.lang.foreign.MemorySegment

/** The only metadata version this implementation writes, and the only one it reads. */
internal const val VARIANT_METADATA_VERSION: Int = 1

/**
 * The string dictionary half of a Variant: every field name used by the values that reference it.
 *
 * The specification keeps metadata separate from values precisely so it can be shared, and rabosh
 * takes that as far as it goes — **one dictionary per SSTable**, held in the segment footer, with
 * stored documents carrying value bytes only. For the repetitive JSON that real systems produce
 * this is the largest single space saving in the design, and it turns path resolution into one
 * lookup per segment per query rather than a string comparison per document.
 *
 * The reader is zero-copy. It holds a [MemorySegment] — a heap array during ingest, a mapped file
 * region once segments are on disk — and computes offsets into it. Nothing is decoded until a
 * name is actually asked for, and [Variant] field lookup never asks, because it compares UTF-8
 * bytes in place.
 */
public class VariantMetadata private constructor(
    internal val segment: MemorySegment,
    private val base: Long,
    /** Number of strings in the dictionary. */
    public val size: Int,
    private val offsetSize: Int,
    private val firstOffsetAt: Long,
    private val bytesAt: Long,
    /** Whether the dictionary is known to be sorted and free of duplicates. */
    public val sortedAndUnique: Boolean,
    /** Total encoded length in bytes. */
    public val byteSize: Long,
) {
    /** Field name for dictionary [id]. */
    public fun name(id: Int): String {
        val start = offsetAt(id)
        val end = offsetAt(id + 1)
        return segment.utf8(bytesAt + start, (end - start).toInt(), "dictionary string $id")
    }

    /**
     * Dictionary id for [name], or `-1` when the dictionary does not hold it.
     *
     * Binary search when the dictionary is [sortedAndUnique], linear otherwise — an append-only
     * dictionary shared across a whole segment is in insertion order, and insertion order is not
     * name order.
     */
    public fun indexOf(name: String): Int {
        val target = name.toUtf8("field name")
        if (sortedAndUnique) {
            var low = 0
            var high = size - 1
            while (low <= high) {
                val middle = (low + high) ushr 1
                val comparison = compareName(middle, target)
                when {
                    comparison < 0 -> low = middle + 1
                    comparison > 0 -> high = middle - 1
                    else -> return middle
                }
            }
            return -1
        }
        for (id in 0 until size) {
            if (compareName(id, target) == 0) return id
        }
        return -1
    }

    /** Compares dictionary entry [id] against [target] in UTF-8 byte order, without decoding it. */
    internal fun compareName(id: Int, target: ByteArray): Int {
        val start = offsetAt(id)
        val end = offsetAt(id + 1)
        return segment.compareUtf8(bytesAt + start, (end - start).toInt(), target, "dictionary string $id")
    }

    /** Checks that [id] is a field id this dictionary can resolve. */
    internal fun requireId(id: Long, at: Long): Int {
        if (id < 0 || id >= size) {
            throw VariantFormatException("field id $id is outside the dictionary of $size string(s)", at)
        }
        return id.toInt()
    }

    private fun offsetAt(index: Int): Long =
        segment.unsignedLe(firstOffsetAt + index.toLong() * offsetSize, offsetSize, "dictionary offset $index")

    /** The encoded metadata bytes, copied out of the underlying segment. */
    public fun toByteArray(): ByteArray = segment.bytes(base, byteSize.toInt(), "metadata")

    override fun toString(): String = "VariantMetadata(size=$size, bytes=$byteSize, sorted=$sortedAndUnique)"

    public companion object {
        /** A dictionary with no entries. Every document of scalars or arrays can share it. */
        public val EMPTY: VariantMetadata = of(byteArrayOf(0x11, 0x00, 0x00))

        /**
         * Reads metadata that begins at [offset].
         *
         * Structure is validated eagerly — version, widths, and the whole offset list. That is one
         * pass over the offsets at open time, paid once per segment, and it buys the guarantee
         * that no later accessor can walk outside the region.
         */
        public fun read(segment: MemorySegment, offset: Long = 0): VariantMetadata {
            val header = segment.u8(offset, "metadata header")
            val version = header and 0x0F
            if (version != VARIANT_METADATA_VERSION) {
                throw VariantFormatException(
                    "unsupported Variant metadata version $version; this build reads version $VARIANT_METADATA_VERSION",
                    offset,
                )
            }
            // Bit 5 is reserved and, per the specification, must be ignored rather than rejected.
            val sorted = (header ushr 4) and 0x01 == 1
            val offsetSize = ((header ushr 6) and 0x03) + 1

            val sizeValue = segment.unsignedLe(offset + 1, offsetSize, "dictionary size")
            if (sizeValue > Int.MAX_VALUE) {
                throw VariantFormatException("dictionary of $sizeValue strings exceeds the addressable maximum", offset)
            }
            val size = sizeValue.toInt()

            val firstOffsetAt = offset + 1 + offsetSize
            val bytesAt = firstOffsetAt + (size.toLong() + 1) * offsetSize
            segment.requireRange(firstOffsetAt, bytesAt - firstOffsetAt, "dictionary offsets")

            var previous = segment.unsignedLe(firstOffsetAt, offsetSize, "dictionary offset 0")
            if (previous != 0L) {
                throw VariantFormatException("first dictionary offset is $previous, expected 0", firstOffsetAt)
            }
            for (index in 1..size) {
                val at = firstOffsetAt + index.toLong() * offsetSize
                val current = segment.unsignedLe(at, offsetSize, "dictionary offset $index")
                if (current < previous) {
                    throw VariantFormatException(
                        "dictionary offset $index goes backwards: $current after $previous",
                        at,
                    )
                }
                previous = current
            }
            segment.requireRange(bytesAt, previous, "dictionary strings")

            return VariantMetadata(
                segment = segment,
                base = offset,
                size = size,
                offsetSize = offsetSize,
                firstOffsetAt = firstOffsetAt,
                bytesAt = bytesAt,
                sortedAndUnique = sorted,
                byteSize = bytesAt + previous - offset,
            )
        }

        /** Reads metadata from a heap array. */
        public fun of(bytes: ByteArray): VariantMetadata = read(MemorySegment.ofArray(bytes), 0)
    }
}

/**
 * Builds a dictionary by interning field names, and encodes it once the values that use it are
 * written.
 *
 * Append-only by construction: an id, once handed out, is embedded in value bytes that may already
 * have been flushed, so ids can never be renumbered. A dictionary is therefore in insertion order,
 * and [VariantMetadata.sortedAndUnique] is set only when insertion order happened to be
 * lexicographic anyway — which is the common case for a single document whose fields are interned
 * in sorted order, and never the case for a segment-wide dictionary.
 *
 * Not thread-safe. The engine has a single writer by design.
 */
public class VariantDictionaryBuilder {
    private val idsByName = HashMap<String, Int>()
    private val names = ArrayList<ByteArray>()
    private var bytesLength = 0
    private var sorted = true

    /** Number of interned strings. */
    public val size: Int get() = names.size

    /** Returns the id of [name], interning it if this is its first use. */
    public fun intern(name: String): Int {
        idsByName[name]?.let { return it }
        val utf8 = name.toUtf8("field name")
        if (names.isNotEmpty() && compareUtf8(names[names.size - 1], utf8) >= 0) sorted = false
        val id = names.size
        names += utf8
        idsByName[name] = id
        bytesLength += utf8.size
        return id
    }

    /** UTF-8 bytes of the name interned as [id]. */
    internal fun nameBytes(id: Int): ByteArray = names[id]

    /** Encodes the dictionary in the specification's metadata layout. */
    public fun toByteArray(): ByteArray {
        // One width covers both `dictionary_size` and every offset, so it is driven by whichever
        // of the two is larger.
        val offsetSize = unsignedWidth(maxOf(size, bytesLength))
        val out = GrowableBytes(1 + offsetSize * (size + 2) + bytesLength)
        val sortedBit = if (sorted) 1 else 0
        out.writeByte(VARIANT_METADATA_VERSION or (sortedBit shl 4) or ((offsetSize - 1) shl 6))
        out.writeLe(size.toLong(), offsetSize)
        var running = 0
        out.writeLe(0, offsetSize)
        for (name in names) {
            running += name.size
            out.writeLe(running.toLong(), offsetSize)
        }
        for (name in names) out.write(name)
        return out.toByteArray()
    }

    /** Encodes the dictionary and returns a reader over it. */
    public fun build(): VariantMetadata = VariantMetadata.of(toByteArray())
}
