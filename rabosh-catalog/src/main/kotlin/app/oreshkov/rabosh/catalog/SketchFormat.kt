package app.oreshkov.rabosh.catalog

import app.oreshkov.rabosh.variant.VariantKind
import java.util.zip.CRC32C

/**
 * The on-disk layout of a path-sketch sidecar.
 *
 * ```
 * file    := magic["JKDB-CAT"] version:u32 payloadLength:u32 crc32c:u32       (20 bytes)
 *            payload
 *
 * payload := segmentNumber:u64 documentCount:u64 observationCount:u64
 *            pathCount:u32 path*
 *            overflowPaths:hll overflowObservations:u64
 *
 * path    := nameLength:u32 name              the canonical `$.items[*].sku`, UTF-8
 *            observations:u64 nullObservations:u64 totalBytes:u64
 *            typeCount:u32 (typeId:u8 count:u64)*
 *            numeric:bound text:bound
 *            distinct:hll
 *
 * bound   := 0                                          absent
 *          | 1 min:decimal max:decimal                  numeric
 *          | 2 minLength:u32 min minExact:u8
 *              maxPresent:u8 [maxLength:u32 max maxExact:u8]     text
 * decimal := scale:i32 unscaledLength:u32 unscaled      big-endian two's complement
 * hll     := 1 precision:u8 count:u32 hash:u64[count]   sparse, and exact
 *          | 2 precision:u8 register:u8[1024]           dense
 * ```
 *
 * Little-endian throughout, matching the log, the manifest, the segment and the Variant encoding, so
 * the whole engine has one byte order. **These constants are permanent**: add, never renumber.
 *
 * Four decisions in that layout carry weight.
 *
 * **The file is written whole, never appended to.** A sidecar describes one immutable segment, so
 * there is no second version of it to record and nothing that could leave a torn tail — which is why
 * there is no record frame here of the kind the log and the manifest share. It is written under a
 * temporary name, forced, and moved into place atomically, so a file that exists is a file that is
 * complete. That is the whole of its recovery story.
 *
 * **A path is stored as its canonical text, not as a step list.** It costs a few bytes per path over
 * an encoded form and it makes the file legible in a hex dump — which for a *derived* artefact is
 * the better trade, because the first question anyone asks of a sketch is "what does it think is in
 * there".
 *
 * **Type counts carry an id of the catalog's own**, not `VariantKind.ordinal`. An ordinal is a
 * property of the source order of an enum and would silently change meaning the day a kind is
 * inserted in the middle. [typeId] is an exhaustive `when` with no `else`, so adding a kind breaks
 * the build and forces a number to be chosen rather than inherited.
 *
 * **There is no temporal or binary bound**, only numeric and text. JSON's grammar produces neither a
 * timestamp nor a byte string, so a bound for them would be an untested branch carrying a permanent
 * on-disk shape. The tag byte on [BOUND_NONE] is what lets one arrive later as a new id.
 */
internal object SketchFormat {
    /** `JKDB-CAT` in ASCII, distinct from `JKDB-WAL`, `JKDB-SEG` and `JKDB-MAN`. */
    val MAGIC: ByteArray = "JKDB-CAT".encodeToByteArray()

    /** The only sidecar format version this build writes, and the only one it reads. */
    const val VERSION: Int = 1

    const val HEADER_BYTES: Int = 20

    /** Ceiling on one sidecar, so a corrupt length is rejected rather than allocated. */
    const val MAX_PAYLOAD_BYTES: Int = 1 shl 28

    const val BOUND_NONE: Int = 0
    const val BOUND_NUMERIC: Int = 1
    const val BOUND_TEXT: Int = 2

    const val HLL_SPARSE: Int = 1
    const val HLL_DENSE: Int = 2

    /**
     * See [HyperLogLog.PRECISION]. Repeated here because it is written into every sidecar.
     *
     * The tags that open the byte string a value is hashed under live in [ValueSignature], which is
     * public because `rabosh-index` keys its term dictionaries with the same function. They are as
     * permanent as anything here — every register in every sidecar is a function of them — and they
     * moved rather than being copied for exactly that reason.
     */
    const val HLL_PRECISION: Int = HyperLogLog.PRECISION

    /**
     * The permanent id of a [VariantKind].
     *
     * Exhaustive with no `else`: a kind added to the enum must be given a number here, and the
     * compiler is what makes that unavoidable.
     */
    fun typeId(kind: VariantKind): Int = when (kind) {
        VariantKind.NULL -> 0
        VariantKind.BOOLEAN -> 1
        VariantKind.INTEGER -> 2
        VariantKind.FLOAT -> 3
        VariantKind.DOUBLE -> 4
        VariantKind.DECIMAL -> 5
        VariantKind.STRING -> 6
        VariantKind.BINARY -> 7
        VariantKind.DATE -> 8
        VariantKind.TIME -> 9
        VariantKind.TIMESTAMP -> 10
        VariantKind.UUID -> 11
        VariantKind.ARRAY -> 12
        VariantKind.OBJECT -> 13
    }

    private val BY_ID: Array<VariantKind?> = arrayOfNulls<VariantKind>(32).also { table ->
        VariantKind.entries.forEach { table[typeId(it)] = it }
    }

    /** The kind [id] names, or `null` if this build does not know it. Never a default. */
    fun typeOfId(id: Int): VariantKind? = BY_ID.getOrNull(id)

    /**
     * CRC32C over the version and length fields followed by the payload.
     *
     * One checksum over both halves rather than one each: the length field decides how much is read,
     * and a corrupt length is exactly the fault that becomes a wild read instead of a report. The
     * log and the manifest cover their length fields for the same reason.
     */
    fun checksum(header: ByteArray, headerOffset: Int, headerLength: Int, payload: ByteArray): Int {
        val crc = CRC32C()
        crc.update(header, headerOffset, headerLength)
        crc.update(payload, 0, payload.size)
        return crc.value.toInt()
    }
}

/** A growable little-endian byte buffer, the sidecar's half of the write path. */
internal class SketchWriter(initialCapacity: Int = 4096) {
    private var array = ByteArray(initialCapacity.coerceAtLeast(32))

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

    fun write(source: ByteArray) {
        ensure(source.size)
        source.copyInto(array, size)
        size += source.size
    }

    /** Length-prefixed bytes, which is how every variable-width field in the payload is stored. */
    fun writeBytes(source: ByteArray) {
        writeInt(source.size)
        write(source)
    }

    fun writeString(value: String): Unit = writeBytes(value.encodeToByteArray())

    fun toByteArray(): ByteArray = array.copyOf(size)
}

/**
 * Reads a sidecar payload field by field, reporting the file and the offset when one runs off the
 * end.
 *
 * Every read is named. A sketch that will not decode is a signalled failure that says which file and
 * which field, not an `ArrayIndexOutOfBoundsException` from three frames down — the same rule the
 * storage core follows, for the same reason.
 */
internal class SketchReader(private val bytes: ByteArray, val file: String, private val base: Long) {
    private var position = 0

    val exhausted: Boolean get() = position >= bytes.size

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

    /**
     * A non-negative count read from a `u32`, checked against [limit].
     *
     * Counts and lengths are where a corrupt file turns into a wild allocation, so the bound is
     * applied at the read rather than at the use. The default limit is the bytes that remain: no
     * count in this format can name more elements than there are bytes left to hold them.
     */
    fun count(what: String, limit: Int = bytes.size - position): Int {
        val value = int(what).toLong() and 0xFFFF_FFFFL
        if (value > limit) corrupt("$what is $value, beyond the $limit available")
        return value.toInt()
    }

    fun bytes(length: Int, what: String): ByteArray {
        require(length, what)
        val copy = bytes.copyOfRange(position, position + length)
        position += length
        return copy
    }

    fun lengthPrefixedBytes(what: String): ByteArray = bytes(count("$what length"), what)

    fun string(what: String): String = try {
        lengthPrefixedBytes(what).decodeToString(throwOnInvalidSequence = true)
    } catch (failure: java.nio.charset.CharacterCodingException) {
        corrupt("$what is not valid UTF-8", failure)
    }

    fun corrupt(message: String, cause: Throwable? = null): Nothing =
        throw CorruptSketchException(message, file, base + position, cause)

    private fun require(count: Int, what: String) {
        if (count < 0 || position + count > bytes.size) corrupt("truncated $what: the sidecar ends first")
    }
}
