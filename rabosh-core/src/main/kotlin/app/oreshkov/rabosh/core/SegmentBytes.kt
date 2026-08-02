package app.oreshkov.rabosh.core

import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteOrder
import java.util.Arrays

/**
 * Little-endian, unaligned access layouts.
 *
 * Both properties have to be stated: the `JAVA_*` layouts default to *native* byte order and to
 * natural alignment, and an aligned layout over an unaligned address throws rather than reading
 * slowly. Nothing in a segment is aligned — a block starts wherever the previous one ended.
 */
private val LE_INT: ValueLayout.OfInt = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN)
private val LE_LONG: ValueLayout.OfLong = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN)

/**
 * A source of bytes that can say where it came from when it turns out to be unreadable.
 *
 * Every read of a mapped segment goes through one of these, and the name is not decoration: a
 * `MemorySegment` throws `IndexOutOfBoundsException` on its own, which says nothing about which
 * file, which block, or what was being decoded. The engine's rule is that unreadable data becomes a
 * signalled failure naming the file, so the file name has to travel with the reads.
 *
 * These are deliberately *not* the equivalents in `rabosh-variant`, which are internal to that
 * module and raise `VariantFormatException`. Publishing byte utilities out of a module whose public
 * surface is a codec would be the worse trade; the duplication is thirty lines and it keeps the
 * failure types honest — a bad block header is a storage fault, not a Variant one.
 */
internal class SegmentBytes(val segment: MemorySegment, val file: String) {

    val byteSize: Long get() = segment.byteSize()

    fun requireRange(offset: Long, length: Long, what: String) {
        if (offset < 0 || length < 0 || offset > segment.byteSize() - length) {
            throw CorruptSegmentException(
                "truncated $what: needs $length byte(s) at $offset but the file holds ${segment.byteSize()}",
                file,
                offset,
            )
        }
    }

    fun u8(offset: Long, what: String): Int {
        requireRange(offset, 1, what)
        return segment.get(ValueLayout.JAVA_BYTE, offset).toInt() and 0xFF
    }

    fun i32(offset: Long, what: String): Int {
        requireRange(offset, 4, what)
        return segment.get(LE_INT, offset)
    }

    fun i64(offset: Long, what: String): Long {
        requireRange(offset, 8, what)
        return segment.get(LE_LONG, offset)
    }

    /**
     * A non-negative `Int` read from an unsigned 32-bit field.
     *
     * Lengths and counts are stored as `u32`, and a corrupt one that reads back negative is exactly
     * the value that turns into a wild allocation or a wild offset. It is rejected here instead.
     */
    fun length(offset: Long, what: String, limit: Long = segment.byteSize()): Int {
        val value = i32(offset, what).toLong() and 0xFFFF_FFFFL
        if (value > limit) {
            throw CorruptSegmentException("$what is $value, beyond the $limit byte(s) available", file, offset)
        }
        return value.toInt()
    }

    fun bytes(offset: Long, length: Int, what: String): ByteArray {
        requireRange(offset, length.toLong(), what)
        val target = ByteArray(length)
        MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, offset, target, 0, length)
        return target
    }

    /** Copies `[offset, offset + length)` into [target] at [targetOffset], growing nothing. */
    fun copyInto(offset: Long, target: ByteArray, targetOffset: Int, length: Int, what: String) {
        requireRange(offset, length.toLong(), what)
        MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, offset, target, targetOffset, length)
    }

    fun matches(offset: Long, expected: ByteArray, what: String): Boolean {
        requireRange(offset, expected.size.toLong(), what)
        for (index in expected.indices) {
            if (segment.get(ValueLayout.JAVA_BYTE, offset + index) != expected[index]) return false
        }
        return true
    }

    /**
     * Checks a block's type byte and checksum.
     *
     * Every block is verified when it is read, not only when the segment is opened. That is a
     * CRC32C over a few kilobytes on a path that is about to do a binary search and decode a
     * document, and it is the difference between "a flipped bit is reported" and "a flipped bit is
     * returned as a document". The engine's rule about unreadable data leaves no room for the
     * second, and the hardware instruction leaves little room to argue about the cost.
     *
     * The checksum covers the type byte as well as the contents: the field that decides how a block
     * is to be interpreted must not be the one left unprotected.
     */
    fun verifyBlock(handle: BlockHandle, what: String) {
        val trailerAt = handle.offset + handle.length
        requireRange(trailerAt, SegmentFormat.BLOCK_TRAILER_BYTES.toLong(), "$what trailer")
        val blockType = u8(trailerAt, "$what type")
        if (blockType != SegmentFormat.BLOCK_TYPE_PLAIN) {
            throw UnsupportedFormatException(
                "$file holds a $what of block type $blockType; this build reads only " +
                    "type ${SegmentFormat.BLOCK_TYPE_PLAIN}",
            )
        }
        val stored = i32(trailerAt + 1, "$what checksum")
        val crc = java.util.zip.CRC32C()
        crc.update(segment.asSlice(handle.offset, handle.length.toLong()).asByteBuffer())
        crc.update(blockType)
        if (crc.value.toInt() != stored) corrupt("$what checksum does not match", handle.offset)
    }

    /** Verifies a block and returns a reader over its key-ordered entries. */
    fun readBlock(handle: BlockHandle, what: String): BlockReader {
        verifyBlock(handle, what)
        return BlockReader(this, handle.offset, handle.length)
    }

    /**
     * A reader over a block whose checksum `SegmentTable.open` already checked.
     *
     * **For the index, bloom and dictionary blocks only**, and the distinction is the one that class
     * already documents: those three are checked once, when the segment is mapped, because there are
     * three of them; data blocks are checked as they are read, because a segment holds thousands and
     * there is no "once" for them.
     *
     * The index block was nevertheless being re-verified on **every point lookup**, which made a get
     * cost a CRC32C over one entry per data block in the segment — measured at 3.0 µs of a 4.15 µs
     * lookup at the default sizes, and growing linearly with segment size. That is what this exists
     * to stop; see `ReadCostMain`.
     *
     * What is given up is stated rather than glossed: a bit that flips *after* the segment was mapped
     * is no longer caught here. It never was for the bloom or the dictionary, which are read through
     * the same once-checked contract, so the index block was the odd one out rather than the careful
     * one — and a page-cache flip is not what the block checksums are for. They catch a bad write and
     * a bad disk, both of which happen before a reader ever maps the file.
     */
    fun readBlockCheckedAtOpen(handle: BlockHandle): BlockReader =
        BlockReader(this, handle.offset, handle.length)

    fun corrupt(message: String, offset: Long = -1, cause: Throwable? = null): Nothing =
        throw CorruptSegmentException(message, file, offset, cause)
}

/**
 * A growable little-endian byte buffer.
 *
 * `ByteBuffer` cannot grow and `ByteArrayOutputStream` cannot write a little-endian `int` or patch
 * a value already written — a block builder needs both, because a block's restart array and its
 * count are appended after every entry in it is known.
 */
internal class ByteWriter(initialCapacity: Int = 4096) {
    private var array = ByteArray(initialCapacity.coerceAtLeast(16))

    var size: Int = 0
        private set

    /** The backing array, valid over `[0, size)` only. Any write may replace it. */
    val backing: ByteArray get() = array

    fun clear() {
        size = 0
    }

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

    fun writeInt(value: Int) {
        ensure(4)
        for (index in 0 until 4) array[size + index] = (value ushr (8 * index)).toByte()
        size += 4
    }

    fun writeLong(value: Long) {
        ensure(8)
        for (index in 0 until 8) array[size + index] = (value ushr (8 * index)).toByte()
        size += 8
    }

    fun write(source: ByteArray, start: Int = 0, length: Int = source.size - start) {
        ensure(length)
        source.copyInto(array, size, start, start + length)
        size += length
    }

    /** Appends `[offset, offset + length)` of [source] without an intermediate array. */
    fun write(source: MemorySegment, offset: Long, length: Int) {
        ensure(length)
        MemorySegment.copy(source, ValueLayout.JAVA_BYTE, offset, array, size, length)
        size += length
    }

    fun toByteArray(): ByteArray = array.copyOf(size)
}

/**
 * Order over encoded internal keys: user key ascending, then sequence **descending**.
 *
 * The same order [InternalKey] imposes in the memtable, and it has to be, because a merge walks
 * both at once. User-key bytes compare unsigned — the one order that agrees with UTF-8 and with
 * [Key.compareTo]; a signed comparison would put every byte above 0x7F before every byte below it
 * and quietly sort a segment differently from the memtable that fed it.
 *
 * Sequence descending puts the newest version of a key first, so a reader stops at the first entry
 * it finds rather than scanning to the end of that key's versions.
 */
internal fun compareEncodedKeys(
    left: ByteArray,
    leftLength: Int,
    right: ByteArray,
    rightLength: Int,
): Int {
    val leftUser = leftLength - SegmentFormat.TAG_BYTES
    val rightUser = rightLength - SegmentFormat.TAG_BYTES
    val byUser = Arrays.compareUnsigned(left, 0, leftUser, right, 0, rightUser)
    if (byUser != 0) return byUser
    // Tags are read rather than compared as bytes: the sequence half sorts descending, which no
    // byte-wise comparison of a little-endian field produces.
    return SegmentFormat.compareTags(
        SegmentFormat.readTag(left, leftUser),
        SegmentFormat.readTag(right, rightUser),
    )
}

internal fun compareEncodedKeys(left: ByteArray, right: ByteArray): Int =
    compareEncodedKeys(left, left.size, right, right.size)
