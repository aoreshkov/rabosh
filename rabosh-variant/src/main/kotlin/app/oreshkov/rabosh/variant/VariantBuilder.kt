package app.oreshkov.rabosh.variant

import java.math.BigDecimal
import java.math.BigInteger
import kotlin.uuid.Uuid

/**
 * What to do when an object is given the same field name twice.
 *
 * The specification is unambiguous — "it is an error for an object to contain two fields with the
 * same name" — so the encoder has to resolve the collision one way or the other before it writes.
 */
public enum class DuplicateFieldPolicy {
    /**
     * The last occurrence wins, which is what JSON.parse, `kotlinx-serialization` and every SQL
     * engine's JSON reader do. The default, because ingest should not fail on a document a browser
     * would accept.
     */
    LAST_WINS,

    /** Reject the document. For callers that would rather hear about it than store one of two values. */
    REJECT,
}

/**
 * Encodes Variant values.
 *
 * The builder is a *streaming* encoder: values are appended to one growable buffer in the order
 * they arrive, and a container's header — which cannot be written first, because it depends on how
 * many elements there turn out to be, how wide their offsets are and how large the biggest field
 * id is — is inserted in front of the children once they are complete. That keeps encoding to a
 * single pass with no intermediate tree.
 *
 * ```kotlin
 * val builder = VariantBuilder()
 * builder.startObject()
 * builder.field("id"); builder.appendLong(7)
 * builder.field("tags"); builder.startArray(); builder.appendString("a"); builder.endArray()
 * builder.endObject()
 * val variant = builder.buildVariant()
 * ```
 *
 * The [dictionary] is deliberately a constructor parameter rather than private state: an SSTable
 * shares one dictionary across every document it holds, so the usual ingest loop creates the
 * dictionary once, then [reset]s the builder per document.
 *
 * Not thread-safe; the engine has a single writer.
 */
public class VariantBuilder(
    /** Field-name dictionary. Share one across a segment to pay for each name once. */
    public val dictionary: VariantDictionaryBuilder = VariantDictionaryBuilder(),
    private val duplicateFields: DuplicateFieldPolicy = DuplicateFieldPolicy.LAST_WINS,
) {
    private val out = GrowableBytes()
    private val containers = ArrayList<Container>()
    private val fields = ArrayList<FieldEntry>()
    private val elements = IntList()
    private var pendingFieldId = NO_FIELD
    private var rootWritten = false

    /** Number of value bytes written so far. */
    public val size: Int get() = out.size

    /** Discards the value under construction, keeping the [dictionary] for the next document. */
    public fun reset() {
        out.clear()
        containers.clear()
        fields.clear()
        elements.clear()
        pendingFieldId = NO_FIELD
        rootWritten = false
    }

    // --- containers ------------------------------------------------------------------------

    /** Opens an object. Every [field] must be followed by exactly one value. */
    public fun startObject() {
        startValue()
        containers += Container(VariantBasicType.OBJECT, out.size, fields.size)
    }

    /** Names the next value's field. */
    public fun field(name: String) {
        val container = containers.lastOrNull()
        check(container != null && container.basicType == VariantBasicType.OBJECT) {
            "field(\"$name\") outside an object"
        }
        check(pendingFieldId == NO_FIELD) { "field(\"$name\") called twice without a value between" }
        pendingFieldId = dictionary.intern(name)
    }

    /** Closes the object opened by the matching [startObject]. */
    public fun endObject() {
        val container = popContainer(VariantBasicType.OBJECT)
        check(pendingFieldId == NO_FIELD) { "object ended with a field name but no value" }

        val entries = fields.subList(container.base, fields.size)
        // The specification requires field ids in lexicographic order of their *names*, in UTF-8
        // byte order — which is not the order the names were interned in, and not Kotlin's
        // `String` order either once astral characters are involved.
        entries.sortWith { left, right ->
            compareUtf8(dictionary.nameBytes(left.id), dictionary.nameBytes(right.id))
        }
        val kept = deduplicate(entries)

        val dataSize = out.size - container.startPosition
        val count = kept.size
        val idSize = unsignedWidth(maxId(kept))
        val offsetSize = unsignedWidth(dataSize)
        val isLarge = count > MAX_SMALL_COUNT
        val countSize = if (isLarge) 4 else 1

        val headerSize = 1 + countSize + count * idSize + (count + 1) * offsetSize
        out.insertGap(container.startPosition, headerSize)

        var position = container.startPosition
        val valueHeader = (if (isLarge) 1 shl 4 else 0) or ((idSize - 1) shl 2) or (offsetSize - 1)
        out.writeLeAt(position, (VariantBasicType.OBJECT.id or (valueHeader shl 2)).toLong(), 1)
        position += 1
        out.writeLeAt(position, count.toLong(), countSize)
        position += countSize
        for (entry in kept) {
            out.writeLeAt(position, entry.id.toLong(), idSize)
            position += idSize
        }
        for (entry in kept) {
            out.writeLeAt(position, entry.offset.toLong(), offsetSize)
            position += offsetSize
        }
        out.writeLeAt(position, dataSize.toLong(), offsetSize)

        entries.clear()
    }

    /** Opens an array. */
    public fun startArray() {
        startValue()
        containers += Container(VariantBasicType.ARRAY, out.size, elements.size)
    }

    /** Closes the array opened by the matching [startArray]. */
    public fun endArray() {
        val container = popContainer(VariantBasicType.ARRAY)

        val dataSize = out.size - container.startPosition
        val count = elements.size - container.base
        val offsetSize = unsignedWidth(dataSize)
        val isLarge = count > MAX_SMALL_COUNT
        val countSize = if (isLarge) 4 else 1

        val headerSize = 1 + countSize + (count + 1) * offsetSize
        out.insertGap(container.startPosition, headerSize)

        var position = container.startPosition
        val valueHeader = (if (isLarge) 1 shl 2 else 0) or (offsetSize - 1)
        out.writeLeAt(position, (VariantBasicType.ARRAY.id or (valueHeader shl 2)).toLong(), 1)
        position += 1
        out.writeLeAt(position, count.toLong(), countSize)
        position += countSize
        for (index in 0 until count) {
            out.writeLeAt(position, elements[container.base + index].toLong(), offsetSize)
            position += offsetSize
        }
        out.writeLeAt(position, dataSize.toLong(), offsetSize)

        elements.truncate(container.base)
    }

    // --- scalars ---------------------------------------------------------------------------

    /** Appends the Variant null primitive. */
    public fun appendNull() {
        startValue()
        out.writeByte(primitiveHeader(VariantPrimitiveType.NULL))
    }

    public fun appendBoolean(value: Boolean) {
        startValue()
        out.writeByte(
            primitiveHeader(
                if (value) VariantPrimitiveType.BOOLEAN_TRUE else VariantPrimitiveType.BOOLEAN_FALSE,
            ),
        )
    }

    /** Appends [value] as the narrowest integer type that holds it. */
    public fun appendLong(value: Long) {
        startValue()
        when (value) {
            in Byte.MIN_VALUE.toLong()..Byte.MAX_VALUE.toLong() -> {
                out.writeByte(primitiveHeader(VariantPrimitiveType.INT8))
                out.writeLe(value, 1)
            }

            in Short.MIN_VALUE.toLong()..Short.MAX_VALUE.toLong() -> {
                out.writeByte(primitiveHeader(VariantPrimitiveType.INT16))
                out.writeLe(value, 2)
            }

            in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() -> {
                out.writeByte(primitiveHeader(VariantPrimitiveType.INT32))
                out.writeLe(value, 4)
            }

            else -> {
                out.writeByte(primitiveHeader(VariantPrimitiveType.INT64))
                out.writeLe(value, 8)
            }
        }
    }

    /** Appends [value] as a `double`, non-finite values included; JSON cannot read those back. */
    public fun appendDouble(value: Double) {
        startValue()
        out.writeByte(primitiveHeader(VariantPrimitiveType.DOUBLE))
        out.writeLe(value.toRawBits(), 8)
    }

    public fun appendFloat(value: Float) {
        startValue()
        out.writeByte(primitiveHeader(VariantPrimitiveType.FLOAT))
        out.writeLe(value.toRawBits().toLong() and 0xFFFF_FFFFL, 4)
    }

    /**
     * Appends [value] as the narrowest exact decimal type.
     *
     * @throws IllegalArgumentException if the scale is outside `[0, 38]` or the precision exceeds
     *   38 digits — the encoding cannot represent those, and rounding silently would be worse.
     */
    public fun appendDecimal(value: BigDecimal) {
        require(value.scale() in 0..MAX_DECIMAL_SCALE) {
            "decimal scale ${value.scale()} outside 0..$MAX_DECIMAL_SCALE"
        }
        require(value.precision() <= MAX_DECIMAL_PRECISION) {
            "decimal precision ${value.precision()} exceeds $MAX_DECIMAL_PRECISION"
        }
        startValue()
        val unscaled = value.unscaledValue()
        when {
            value.precision() <= DECIMAL4_MAX_PRECISION -> {
                out.writeByte(primitiveHeader(VariantPrimitiveType.DECIMAL4))
                out.writeByte(value.scale())
                out.writeLe(unscaled.toLong(), 4)
            }

            value.precision() <= DECIMAL8_MAX_PRECISION -> {
                out.writeByte(primitiveHeader(VariantPrimitiveType.DECIMAL8))
                out.writeByte(value.scale())
                out.writeLe(unscaled.toLong(), 8)
            }

            else -> {
                out.writeByte(primitiveHeader(VariantPrimitiveType.DECIMAL16))
                out.writeByte(value.scale())
                writeInt128(unscaled)
            }
        }
    }

    /**
     * Appends a JSON number literal, choosing its physical type by the rule *exact if possible,
     * narrowest if exact*:
     *
     * 1. An integer that fits `Long` becomes the narrowest of `int8`/`int16`/`int32`/`int64`.
     * 2. Anything else that fits [MAX_DECIMAL_PRECISION] digits with a scale in
     *    `[0, MAX_DECIMAL_SCALE]` becomes the narrowest of `decimal4`/`decimal8`/`decimal16`,
     *    exactly.
     * 3. Only what fits neither becomes a `double` — the sole lossy path in the codec, and it
     *    exists because JSON's number grammar is unbounded while the encoding is not. `1e400` has
     *    to become *something*, and widening is what every other Variant writer does.
     *
     * Trailing zeros are stripped first, so `1.500` and `1.5` encode identically, and `1.0e10`
     * becomes the integer `10000000000` rather than a decimal with a negative scale — the format
     * has no negative scales.
     *
     * @throws IllegalArgumentException if [literal] is not a JSON number.
     */
    public fun appendNumberLiteral(literal: String) {
        when (val encoding = decideNumber(literal)) {
            is NumberEncoding.Integer -> appendLong(encoding.value)
            is NumberEncoding.Decimal -> appendDecimal(encoding.value)
            is NumberEncoding.Double -> appendDouble(encoding.value)
        }
    }

    /** Appends [value], using the short-string form when its UTF-8 form is under 64 bytes. */
    public fun appendString(value: String) {
        val utf8 = value.toUtf8("string value")
        appendUtf8String(utf8, 0, utf8.size)
    }

    public fun appendBinary(value: ByteArray) {
        startValue()
        out.writeByte(primitiveHeader(VariantPrimitiveType.BINARY))
        out.writeLe(value.size.toLong(), 4)
        out.write(value)
    }

    /** Appends a `date` as days since 1970-01-01. */
    public fun appendDate(epochDay: Int) {
        startValue()
        out.writeByte(primitiveHeader(VariantPrimitiveType.DATE))
        out.writeLe(epochDay.toLong() and 0xFFFF_FFFFL, 4)
    }

    /** Appends a `time without time zone` as microseconds since midnight. */
    public fun appendTimeNtz(microsOfDay: Long) {
        appendTemporal(VariantPrimitiveType.TIME_NTZ, microsOfDay)
    }

    /**
     * Appends a timestamp in microseconds since the epoch.
     *
     * @param adjustedToUtc `true` for an instant, `false` for a wall-clock reading with no zone.
     */
    public fun appendTimestampMicros(micros: Long, adjustedToUtc: Boolean) {
        appendTemporal(
            if (adjustedToUtc) VariantPrimitiveType.TIMESTAMP_TZ else VariantPrimitiveType.TIMESTAMP_NTZ,
            micros,
        )
    }

    /** Appends a timestamp in nanoseconds since the epoch. See [appendTimestampMicros]. */
    public fun appendTimestampNanos(nanos: Long, adjustedToUtc: Boolean) {
        appendTemporal(
            if (adjustedToUtc) VariantPrimitiveType.TIMESTAMP_NANOS_TZ else VariantPrimitiveType.TIMESTAMP_NANOS_NTZ,
            nanos,
        )
    }

    public fun appendUuid(value: Uuid) {
        startValue()
        out.writeByte(primitiveHeader(VariantPrimitiveType.UUID))
        // Big-endian: the specification's one exception to little-endian, matching RFC 4122.
        out.write(value.toByteArray())
    }

    // --- copying ---------------------------------------------------------------------------

    /**
     * Appends [value], re-expressed against this builder's [dictionary].
     *
     * This is how a stored document moves into a segment. Documents arrive carrying a dictionary of
     * their own; a segment holds **one** dictionary for all of them, so every field id has to be
     * translated, and translating an id changes the lexicographic ordering the specification
     * requires of the id list. Re-emitting through the builder is what gets both right: [field]
     * interns the name into the target dictionary and [endObject] re-sorts by name, exactly as it
     * does for a freshly parsed document.
     *
     * **Scalars are copied byte for byte**, header included, rather than read out and re-appended.
     * A roundtrip through [appendLong] or [appendDecimal] would re-derive the physical type, so a
     * value stored as `int32` by a caller who chose that width would come back as `int8` — a silent
     * rewrite of somebody's bytes. Copying also means an unknown primitive id fails here, in
     * [Variant.byteSize], instead of being quietly re-encoded as something else.
     *
     * Recursion follows the value's nesting, which ingest bounds at [DEFAULT_MAX_JSON_DEPTH].
     *
     * @throws VariantFormatException if [value] is unreadable — a truncated extent or a primitive
     *   type id this build does not know.
     */
    public fun append(value: Variant) {
        when (value.basicType) {
            VariantBasicType.OBJECT -> {
                startObject()
                for (index in 0 until value.fieldCount) {
                    field(value.fieldName(index))
                    append(value.fieldValue(index))
                }
                endObject()
            }

            VariantBasicType.ARRAY -> {
                startArray()
                for (index in 0 until value.elementCount) {
                    append(value.element(index))
                }
                endArray()
            }

            // `byteSize` validates the extent against the source before a byte of it is trusted,
            // which is the whole check this path needs: what follows is an opaque copy.
            VariantBasicType.PRIMITIVE, VariantBasicType.SHORT_STRING -> {
                val length = value.byteSize
                startValue()
                out.write(value.segment, value.offset, length.toInt())
            }
        }
    }

    // --- results ---------------------------------------------------------------------------

    /** The encoded value bytes. The builder may be [reset] and reused afterwards. */
    public fun build(): ByteArray {
        check(containers.isEmpty()) { "${containers.size} container(s) left open" }
        check(rootWritten) { "no value was appended" }
        return out.toByteArray()
    }

    /**
     * The encoded value together with a snapshot of the dictionary.
     *
     * Safe to call while the dictionary keeps growing: ids are append-only, so a value stays
     * readable against any later, larger dictionary built from the same builder.
     */
    public fun buildVariant(): Variant = Variant(dictionary.build(), build())

    // --- internals -------------------------------------------------------------------------

    internal fun appendUtf8String(utf8: ByteArray, start: Int, length: Int) {
        startValue()
        if (length < SHORT_STRING_LIMIT) {
            out.writeByte(VariantBasicType.SHORT_STRING.id or (length shl 2))
        } else {
            out.writeByte(primitiveHeader(VariantPrimitiveType.STRING))
            out.writeLe(length.toLong(), 4)
        }
        out.write(utf8, start, length)
    }

    private fun appendTemporal(type: VariantPrimitiveType, value: Long) {
        startValue()
        out.writeByte(primitiveHeader(type))
        out.writeLe(value, 8)
    }

    private fun writeInt128(value: BigInteger) {
        val bigEndian = value.toByteArray()
        check(bigEndian.size <= Variant.DECIMAL16_BYTES) { "int128 overflow: ${bigEndian.size} bytes" }
        // Sign-extend into 16 bytes, then reverse: the encoding stores the unscaled value
        // little-endian, while BigInteger hands it over big-endian.
        val padding: Byte = if (value.signum() < 0) -1 else 0
        val buffer = ByteArray(Variant.DECIMAL16_BYTES) { padding }
        bigEndian.copyInto(buffer, Variant.DECIMAL16_BYTES - bigEndian.size)
        buffer.reverse()
        out.write(buffer)
    }

    private fun primitiveHeader(type: VariantPrimitiveType): Int =
        VariantBasicType.PRIMITIVE.id or (type.id shl 2)

    private fun startValue() {
        val container = containers.lastOrNull()
        when {
            container == null -> {
                check(!rootWritten) { "a Variant holds exactly one root value" }
                rootWritten = true
            }

            container.basicType == VariantBasicType.OBJECT -> {
                check(pendingFieldId != NO_FIELD) { "a value inside an object must follow field(name)" }
                fields += FieldEntry(pendingFieldId, out.size - container.startPosition)
                pendingFieldId = NO_FIELD
            }

            else -> elements.add(out.size - container.startPosition)
        }
    }

    private fun popContainer(expected: VariantBasicType): Container {
        val container = containers.removeLastOrNull()
        check(container != null) { "end${expected.name.lowercase().replaceFirstChar(Char::uppercase)}() without a start" }
        check(container.basicType == expected) { "expected to close a ${container.basicType}, not a $expected" }
        return container
    }

    /**
     * Removes duplicate names from a name-sorted field list.
     *
     * Under [DuplicateFieldPolicy.LAST_WINS] the surviving entry is the last one appended — the
     * sort is stable, so within a run of equal names insertion order is intact. The dropped values
     * stay in the buffer as unreferenced bytes; the offset list simply never points at them, which
     * costs a few bytes on a rare input and avoids a memmove on every object.
     */
    private fun deduplicate(entries: List<FieldEntry>): List<FieldEntry> {
        var duplicates = 0
        for (index in 1 until entries.size) {
            if (entries[index].id == entries[index - 1].id) duplicates++
        }
        if (duplicates == 0) return entries
        if (duplicateFields == DuplicateFieldPolicy.REJECT) {
            val offender = (1 until entries.size).first { entries[it].id == entries[it - 1].id }
            throw IllegalArgumentException(
                "duplicate field '${dictionary.nameBytes(entries[offender].id).decodeToString()}'",
            )
        }
        val kept = ArrayList<FieldEntry>(entries.size - duplicates)
        for (index in entries.indices) {
            if (index + 1 == entries.size || entries[index].id != entries[index + 1].id) kept += entries[index]
        }
        return kept
    }

    private fun maxId(entries: List<FieldEntry>): Int {
        var largest = 0
        for (entry in entries) largest = maxOf(largest, entry.id)
        return largest
    }

    private class Container(val basicType: VariantBasicType, val startPosition: Int, val base: Int)

    private class FieldEntry(val id: Int, val offset: Int)

    private companion object {
        const val NO_FIELD = -1

        /** Strings shorter than this fold their length into the header byte. */
        const val SHORT_STRING_LIMIT = 64

        /** Above this many elements, `num_elements` needs four bytes rather than one. */
        const val MAX_SMALL_COUNT = 255

        const val DECIMAL4_MAX_PRECISION = 9
        const val DECIMAL8_MAX_PRECISION = 18
    }
}

/** A growable `int` list; the offsets an array container accumulates, without boxing every one. */
internal class IntList(initialCapacity: Int = 16) {
    private var array = IntArray(initialCapacity)

    var size: Int = 0
        private set

    operator fun get(index: Int): Int = array[index]

    fun add(value: Int) {
        if (size == array.size) array = array.copyOf(array.size * 2)
        array[size++] = value
    }

    fun truncate(newSize: Int) {
        size = newSize
    }

    fun clear() {
        size = 0
    }
}
