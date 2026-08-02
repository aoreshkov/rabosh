package app.oreshkov.rabosh.index

import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteOrder

/**
 * Little-endian, unaligned access layouts.
 *
 * Both properties have to be stated. The `JAVA_*` layouts default to *native* byte order and to
 * natural alignment, and an aligned layout over an unaligned address throws rather than reading
 * slowly — and nothing in a bitmap block is aligned, because the block itself begins wherever the
 * sidecar that carries it put it.
 */
private val LE_SHORT: ValueLayout.OfShort = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN)
private val LE_INT: ValueLayout.OfInt = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN)
private val LE_LONG: ValueLayout.OfLong = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN)

/**
 * A bitmap block's bytes, which can say where they came from when they turn out to be unreadable.
 *
 * Reads are relative to the block's own start, so the caller works in the offsets the format
 * document uses while failures are reported in offsets into the file. That difference is the reason
 * this exists at all: a bitmap sits at an arbitrary offset inside a sidecar, and "a bitmap did not
 * decode" is not a report anybody can act on.
 *
 * This is the *third* copy of "named little-endian reads over a `MemorySegment`" in the engine, after
 * the internal helpers in `rabosh-variant` and `SegmentBytes` in `rabosh-core`, and the duplication
 * is deliberate for the reason it was the second time: the failure type is the diagnosis. A malformed
 * bitmap is neither a Variant fault nor a segment fault, and a shared `rabosh-bytes` module would
 * publish an artifact whose entire surface is internal helpers in order to save forty lines.
 */
internal class IndexBytes(
    private val segment: MemorySegment,
    /** Offset of the block within [segment]. */
    private val base: Long,
    /** Length of the block. Nothing outside it is readable through this. */
    val length: Int,
    /** Name of the file the block came from, for failure messages. */
    val file: String,
    /**
     * How damage is reported.
     *
     * A sidecar's directory and a bitmap inside it are read with the same arithmetic and repaired in
     * different ways, so the *reads* are shared and the *diagnosis* is not. Defaulting to the bitmap
     * failure keeps every existing caller unchanged.
     */
    private val fail: (String, String, Long, Throwable?) -> IndexException = ::CorruptBitmapException,
) {
    init {
        require(length >= 0) { "length must not be negative, was $length" }
        if (base < 0 || base > segment.byteSize() - length) {
            throw fail(
                "a $length-byte region at $base does not fit the ${segment.byteSize()}-byte source",
                file,
                base,
                null,
            )
        }
    }

    /** A view of the same source over `[offset, offset + count)` of this region. */
    fun slice(offset: Int, count: Int, what: String): IndexBytes {
        requireRange(offset, count, what)
        return IndexBytes(segment, base + offset, count, file, fail)
    }

    /** Copies `count` bytes starting at [offset]. */
    fun bytes(offset: Int, count: Int, what: String): ByteArray {
        val copy = ByteArray(count)
        copyInto(offset, copy, 0, count, what)
        return copy
    }

    /**
     * Copies `count` bytes at [offset] into [target] at [targetOffset].
     *
     * [bytes] with the allocation left to the caller, for the one place where the destination already
     * exists: `KeyBlockReader` reconstructs a front-coded key by writing each entry's unshared bytes
     * over the tail of the key it inherited, and a walk of sixteen entries that allocated a fresh array
     * per step to copy into another fresh array is sixteen times the garbage for one key. Nothing about
     * the bounds check differs — it is the same one, in the same place, because both forms go through
     * it.
     */
    fun copyInto(offset: Int, target: ByteArray, targetOffset: Int, count: Int, what: String) {
        requireRange(offset, count, what)
        MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, base + offset, target, targetOffset, count)
    }

    /** The underlying source, for the one caller that maps a bitmap in place rather than copying. */
    val source: MemorySegment get() = segment

    /** Offset of this region within [source]. */
    val sourceOffset: Long get() = base

    fun requireRange(offset: Int, count: Int, what: String) {
        if (offset < 0 || count < 0 || offset > length - count) {
            corrupt("truncated $what: needs $count byte(s) at $offset but the bitmap holds $length", offset)
        }
    }

    fun u8(offset: Int, what: String): Int {
        requireRange(offset, 1, what)
        return segment.get(ValueLayout.JAVA_BYTE, base + offset).toInt() and 0xFF
    }

    fun u16(offset: Int, what: String): Int {
        requireRange(offset, 2, what)
        return segment.get(LE_SHORT, base + offset).toInt() and 0xFFFF
    }

    /** A signed 32-bit field read as-is. For checksums, where every bit pattern is legal. */
    fun i32(offset: Int, what: String): Int {
        requireRange(offset, 4, what)
        return segment.get(LE_INT, base + offset)
    }

    fun i64(offset: Int, what: String): Long {
        requireRange(offset, 8, what)
        return segment.get(LE_LONG, base + offset)
    }

    /**
     * A non-negative `Int` read from an unsigned 32-bit field, checked against [limit].
     *
     * Counts and offsets are where a corrupt file turns into a wild read, so the bound is applied at
     * the read rather than at the use — the same rule `SegmentBytes.length` and `SketchReader.count`
     * follow. A `u32` that reads back negative is exactly the value that would slip through a signed
     * comparison later.
     */
    fun u32(offset: Int, what: String, limit: Int): Int {
        requireRange(offset, 4, what)
        val value = segment.get(LE_INT, base + offset).toLong() and 0xFFFF_FFFFL
        if (value > limit) corrupt("$what is $value, beyond the $limit permitted", offset)
        return value.toInt()
    }

    /**
     * An LEB128 varint at [offset], packed with its width so a caller can step past it.
     *
     * Returns `(value shl 3) or width`, which is the whole of why this reads a varint rather than a
     * fixed-width field: a front-coded term entry is walked sequentially, so the *next* read starts
     * where this one ended, and returning two numbers from one read is the only way to say that
     * without allocating. Widths are 1..5, so three bits hold one and [limit] is checked against the
     * value rather than against the packed pair.
     *
     * **A non-minimal encoding is corruption, not a slow path.** LEB128 lets `0x80 0x00` mean zero
     * exactly as `0x00` does, so accepting both would let one dictionary encode to two different files
     * — and byte identity between a flush-written sidecar and a backfill-rebuilt one is what lets the
     * suites compare sidecars as *files* rather than as contents. The same reason `BitmapView.verify`
     * reports a wastefully encoded block instead of accepting it: a format with two spellings of one
     * value has no canonical form, and every argument that rests on one quietly stops holding.
     */
    fun varint(offset: Int, what: String, limit: Int): Long {
        var value = 0L
        var shift = 0
        var width = 0
        while (true) {
            if (width == 5) corrupt("$what runs past five bytes, which no 32-bit varint needs", offset)
            val byte = u8(offset + width, what)
            width++
            value = value or ((byte and 0x7F).toLong() shl shift)
            if (byte < 0x80) {
                // The last byte carrying no bits is the non-minimal case: `0x80 0x00`, and every
                // longer spelling of it. A single zero byte is minimal and must stay legal.
                if (byte == 0 && width > 1) {
                    corrupt("$what is padded to $width bytes; a varint has exactly one spelling", offset)
                }
                break
            }
            shift += 7
        }
        if (value > limit) corrupt("$what is $value, beyond the $limit permitted", offset)
        return (value shl 3) or width.toLong()
    }

    /** Copies `count` little-endian words starting at [offset] into [target]. */
    fun words(offset: Int, target: LongArray, count: Int, what: String) {
        requireRange(offset, count * 8, what)
        MemorySegment.copy(segment, LE_LONG, base + offset, target, 0, count)
    }

    /**
     * Reports damage, translating a block-relative [offset] into an offset into the file.
     *
     * The translation is the point of this class. A caller works in the offsets the format document uses,
     * and a failure has to name where in the *file* the trouble is, because a bitmap sits at an arbitrary
     * offset inside a sidecar that holds many of them.
     */
    fun corrupt(message: String, offset: Int = -1, cause: Throwable? = null): Nothing =
        throw fail(message, file, if (offset >= 0) base + offset else -1, cause)
}

/** The value of a varint read by [IndexBytes.varint]. */
internal fun varintValue(packed: Long): Int = (packed ushr 3).toInt()

/** How many bytes that varint occupied, so a sequential walk knows where the next field begins. */
internal fun varintWidth(packed: Long): Int = (packed and 0x7L).toInt()

/**
 * A growable little-endian byte buffer, the bitmap's half of the write path.
 *
 * The same shape as `ByteWriter` in `rabosh-core` and `SketchWriter` in `rabosh-catalog`, and it
 * exists here for the extra thing a bitmap needs: [patchU32], because the container directory is
 * written before the containers whose offsets it names.
 */
internal class IndexWriter(initialCapacity: Int = 256) {
    private var array = ByteArray(initialCapacity.coerceAtLeast(BitmapFormat.HEADER_BYTES))

    var size: Int = 0
        private set

    private fun ensure(extra: Int) {
        val required = size.toLong() + extra
        if (required <= array.size) return
        var capacity = array.size.toLong()
        while (capacity < required) capacity = capacity shl 1
        array = array.copyOf(capacity.toInt())
    }

    fun writeByte(value: Int) {
        ensure(1)
        array[size++] = value.toByte()
    }

    fun writeU16(value: Int) {
        ensure(2)
        for (index in 0 until 2) array[size + index] = (value ushr (8 * index)).toByte()
        size += 2
    }

    fun writeU32(value: Int) {
        ensure(4)
        for (index in 0 until 4) array[size + index] = (value ushr (8 * index)).toByte()
        size += 4
    }

    fun writeLong(value: Long) {
        ensure(8)
        for (index in 0 until 8) array[size + index] = (value ushr (8 * index)).toByte()
        size += 8
    }

    fun write(source: ByteArray) {
        ensure(source.size)
        source.copyInto(array, size)
        size += source.size
    }

    /**
     * An LEB128 varint, always in its shortest form.
     *
     * The only variable-width field the engine writes, and it is confined to a posting file's
     * front-coded term region — where a shared and an unshared length are one byte each at the
     * lengths real terms have, against eight for the `u32` pair the `KEYS` section uses. That is the
     * difference between reaching phase 17's target and missing it, and it is worth a variable-width
     * field precisely here because the region is walked sequentially from a restart point rather than
     * indexed into.
     *
     * Shortest form is not an optimisation but the format's canonicality: [IndexBytes.varint] rejects
     * a padded encoding, so this writer and that reader together give every value exactly one
     * spelling.
     */
    fun writeVarint(value: Int) {
        require(value >= 0) { "a varint holds a non-negative value, was $value" }
        var remaining = value
        while (remaining >= 0x80) {
            writeByte((remaining and 0x7F) or 0x80)
            remaining = remaining ushr 7
        }
        writeByte(remaining)
    }

    /** Bytes [writeVarint] would emit for [value]. For laying out a region before writing it. */
    fun varintSize(value: Int): Int {
        var width = 1
        var remaining = value ushr 7
        while (remaining != 0) {
            width++
            remaining = remaining ushr 7
        }
        return width
    }

    /** Length-prefixed bytes, which is how every variable-width field in a sidecar is stored. */
    fun writeBytes(source: ByteArray) {
        writeU32(source.size)
        write(source)
    }

    fun writeString(value: String): Unit = writeBytes(value.encodeToByteArray())

    /** Zero-fills [count] bytes, for the reserved fields that keep a record's width fixed. */
    fun pad(count: Int) {
        ensure(count)
        java.util.Arrays.fill(array, size, size + count, 0)
        size += count
    }

    /** Overwrites the four bytes at [offset], which must already have been written. */
    fun patchU32(offset: Int, value: Int) {
        require(offset >= 0 && offset + 4 <= size) { "cannot patch 4 bytes at $offset of $size" }
        for (index in 0 until 4) array[offset + index] = (value ushr (8 * index)).toByte()
    }

    /** Overwrites the eight bytes at [offset], which must already have been written. */
    fun patchU64(offset: Int, value: Long) {
        require(offset >= 0 && offset + 8 <= size) { "cannot patch 8 bytes at $offset of $size" }
        for (index in 0 until 8) array[offset + index] = (value ushr (8 * index)).toByte()
    }

    /** CRC32C over `[from, to)` of what has been written so far. */
    fun checksum(from: Int, to: Int): Int {
        require(from in 0..to && to <= size) { "cannot checksum [$from, $to) of $size" }
        val crc = java.util.zip.CRC32C()
        crc.update(array, from, to - from)
        return crc.value.toInt()
    }

    /** CRC32C over two ranges, for a header whose own checksum field sits between them. */
    fun checksum(firstFrom: Int, firstTo: Int, secondFrom: Int, secondTo: Int): Int {
        require(firstFrom in 0..firstTo && firstTo <= size) { "cannot checksum [$firstFrom, $firstTo) of $size" }
        require(secondFrom in 0..secondTo && secondTo <= size) {
            "cannot checksum [$secondFrom, $secondTo) of $size"
        }
        val crc = java.util.zip.CRC32C()
        crc.update(array, firstFrom, firstTo - firstFrom)
        crc.update(array, secondFrom, secondTo - secondFrom)
        return crc.value.toInt()
    }

    fun toByteArray(): ByteArray = array.copyOf(size)
}
