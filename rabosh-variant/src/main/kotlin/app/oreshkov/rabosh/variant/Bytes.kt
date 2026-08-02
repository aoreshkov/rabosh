package app.oreshkov.rabosh.variant

import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException

/**
 * Little-endian, unaligned access layouts.
 *
 * The Variant encoding is little-endian by specification and its fields are not aligned to their
 * width — a `double` may start at any byte. Both properties have to be stated explicitly: the
 * `JAVA_*` layouts default to *native* byte order and to natural alignment, and an aligned layout
 * over an unaligned address throws rather than reading slowly.
 */
private val LE_SHORT: ValueLayout.OfShort = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN)
private val LE_INT: ValueLayout.OfInt = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN)
private val LE_LONG: ValueLayout.OfLong = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN)
private val LE_FLOAT: ValueLayout.OfFloat = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN)
private val LE_DOUBLE: ValueLayout.OfDouble = ValueLayout.JAVA_DOUBLE_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN)

/**
 * Fails unless `[offset, offset + length)` lies inside this segment.
 *
 * Every read goes through this first. `MemorySegment` is bounds-checked by the JDK, but its
 * `IndexOutOfBoundsException` says nothing about *what* was being decoded — and a corrupt file
 * must surface as [VariantFormatException], not as a stray runtime exception from three frames
 * down.
 */
internal fun MemorySegment.requireRange(offset: Long, length: Long, what: String) {
    if (offset < 0 || length < 0 || offset > byteSize() - length) {
        throw VariantFormatException(
            "truncated $what: needs $length byte(s) but the segment holds ${byteSize()}",
            offset,
        )
    }
}

/** Unsigned byte at [offset]. */
internal fun MemorySegment.u8(offset: Long, what: String): Int {
    requireRange(offset, 1, what)
    return get(ValueLayout.JAVA_BYTE, offset).toInt() and 0xFF
}

/**
 * Unsigned little-endian integer of [width] bytes, where `width` is 1..4.
 *
 * Returns `Long` deliberately: a four-byte unsigned field reaches 4294967295, and folding that
 * into a negative `Int` is exactly the kind of silent wrap that turns a corrupt length into an
 * out-of-bounds read.
 */
internal fun MemorySegment.unsignedLe(offset: Long, width: Int, what: String): Long {
    requireRange(offset, width.toLong(), what)
    var result = 0L
    for (index in 0 until width) {
        val byte = get(ValueLayout.JAVA_BYTE, offset + index).toLong() and 0xFF
        result = result or (byte shl (8 * index))
    }
    return result
}

internal fun MemorySegment.i8(offset: Long, what: String): Byte {
    requireRange(offset, 1, what)
    return get(ValueLayout.JAVA_BYTE, offset)
}

internal fun MemorySegment.i16(offset: Long, what: String): Short {
    requireRange(offset, 2, what)
    return get(LE_SHORT, offset)
}

internal fun MemorySegment.i32(offset: Long, what: String): Int {
    requireRange(offset, 4, what)
    return get(LE_INT, offset)
}

internal fun MemorySegment.i64(offset: Long, what: String): Long {
    requireRange(offset, 8, what)
    return get(LE_LONG, offset)
}

internal fun MemorySegment.f32(offset: Long, what: String): Float {
    requireRange(offset, 4, what)
    return get(LE_FLOAT, offset)
}

internal fun MemorySegment.f64(offset: Long, what: String): Double {
    requireRange(offset, 8, what)
    return get(LE_DOUBLE, offset)
}

/** Copies `[offset, offset + length)` out of the segment. */
internal fun MemorySegment.bytes(offset: Long, length: Int, what: String): ByteArray {
    requireRange(offset, length.toLong(), what)
    val target = ByteArray(length)
    MemorySegment.copy(this, ValueLayout.JAVA_BYTE, offset, target, 0, length)
    return target
}

/**
 * Decodes `[offset, offset + length)` as UTF-8.
 *
 * Strict: malformed UTF-8 raises [VariantFormatException] rather than decoding to replacement
 * characters. A file that claims to hold text but does not is unreadable, and quietly substituting
 * `U+FFFD` would let corruption propagate into query results.
 */
internal fun MemorySegment.utf8(offset: Long, length: Int, what: String): String {
    val raw = bytes(offset, length, what)
    return try {
        raw.decodeToString(throwOnInvalidSequence = true)
    } catch (failure: CharacterCodingException) {
        throw VariantFormatException("$what is not valid UTF-8", offset, failure)
    }
}

/**
 * Compares `[offset, offset + length)` against [target] as unsigned bytes.
 *
 * The comparison the specification mandates for field names — UTF-8 bytes, unsigned — done
 * without materialising a `String`, so a field lookup allocates nothing per comparison.
 */
internal fun MemorySegment.compareUtf8(offset: Long, length: Int, target: ByteArray, what: String): Int {
    requireRange(offset, length.toLong(), what)
    val shared = minOf(length, target.size)
    for (index in 0 until shared) {
        val left = get(ValueLayout.JAVA_BYTE, offset + index).toInt() and 0xFF
        val right = target[index].toInt() and 0xFF
        if (left != right) return left - right
    }
    return length - target.size
}

/** Unsigned lexicographic order over UTF-8 byte strings; the specification's field name order. */
internal fun compareUtf8(left: ByteArray, right: ByteArray): Int {
    val shared = minOf(left.size, right.size)
    for (index in 0 until shared) {
        val a = left[index].toInt() and 0xFF
        val b = right[index].toInt() and 0xFF
        if (a != b) return a - b
    }
    return left.size - right.size
}

/** Smallest byte width in 1..4 that can hold [value] as an unsigned integer. */
internal fun unsignedWidth(value: Int): Int = when {
    value <= 0xFF -> 1
    value <= 0xFFFF -> 2
    value <= 0xFF_FFFF -> 3
    else -> 4
}

/**
 * Encodes [value] as UTF-8, rejecting unpaired surrogates instead of substituting `?` for them.
 *
 * `String.toByteArray()` silently replaces a lone surrogate; that byte would then be read back as
 * a different string, which is a roundtrip bug disguised as a successful write.
 */
internal fun String.toUtf8(what: String): ByteArray = try {
    encodeToByteArray(throwOnInvalidSequence = true)
} catch (failure: CharacterCodingException) {
    throw IllegalArgumentException("$what contains an unpaired surrogate and cannot be UTF-8 encoded", failure)
}

/**
 * A growable byte buffer with the two operations the encoder needs that `ByteArrayOutputStream`
 * does not offer: little-endian writes of an arbitrary 1..4 byte width, and [insertGap], which
 * opens space *before* already-written bytes.
 *
 * [insertGap] is what makes single-pass container encoding possible. An object's header cannot be
 * written until its values are known — their count, their total size, and the widest field id —
 * so the values are written first and the header is inserted in front of them afterwards.
 */
internal class GrowableBytes(initialCapacity: Int = 128) {
    private var array = ByteArray(initialCapacity.coerceAtLeast(16))

    var size: Int = 0
        private set

    /**
     * The backing array, valid over `[0, size)` only.
     *
     * Exposed so the JSON parser can hand a decoded string straight to the encoder without copying
     * it a third time. Anything read past [size] is stale, and any write to the buffer may replace
     * the array — so a caller must use it and drop it.
     */
    val backing: ByteArray get() = array

    fun clear() {
        size = 0
    }

    fun truncate(newSize: Int) {
        require(newSize in 0..size) { "cannot truncate to $newSize from $size" }
        size = newSize
    }

    private fun ensure(extra: Int) {
        val required = size + extra
        if (required <= array.size) return
        var capacity = array.size
        while (capacity < required) capacity = capacity shl 1
        array = array.copyOf(capacity)
    }

    fun writeByte(value: Int) {
        ensure(1)
        array[size++] = value.toByte()
    }

    fun write(source: ByteArray, start: Int = 0, length: Int = source.size - start) {
        ensure(length)
        source.copyInto(array, size, start, start + length)
        size += length
    }

    /**
     * Appends `[offset, offset + length)` of [source] without materialising a `ByteArray` first.
     *
     * The path that re-encodes a stored document against a segment's dictionary copies every
     * primitive through here, so it runs once per value on every flush and every compaction — an
     * intermediate array per value would be the single largest allocation source in the engine.
     */
    fun write(source: MemorySegment, offset: Long, length: Int) {
        ensure(length)
        MemorySegment.copy(source, ValueLayout.JAVA_BYTE, offset, array, size, length)
        size += length
    }

    /** Writes [value] little-endian in [width] bytes, `width` in 1..8. */
    fun writeLe(value: Long, width: Int) {
        ensure(width)
        for (index in 0 until width) {
            array[size + index] = (value ushr (8 * index)).toByte()
        }
        size += width
    }

    /** Overwrites [width] bytes at [position]; the region must already be part of the buffer. */
    fun writeLeAt(position: Int, value: Long, width: Int) {
        require(position >= 0 && position + width <= size) { "write at $position exceeds size $size" }
        for (index in 0 until width) {
            array[position + index] = (value ushr (8 * index)).toByte()
        }
    }

    /** Opens [length] uninitialised bytes at [position], shifting everything after it right. */
    fun insertGap(position: Int, length: Int) {
        require(position in 0..size) { "gap at $position outside 0..$size" }
        ensure(length)
        array.copyInto(array, position + length, position, size)
        size += length
    }

    fun toByteArray(): ByteArray = array.copyOf(size)
}
