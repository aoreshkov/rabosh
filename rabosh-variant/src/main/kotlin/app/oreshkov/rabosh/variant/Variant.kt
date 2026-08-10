package app.oreshkov.rabosh.variant

import java.lang.foreign.MemorySegment
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.uuid.Uuid

/**
 * A view over one encoded Variant value.
 *
 * Nothing is decoded on construction and nothing is copied: a `Variant` is a [metadata] reference,
 * a [MemorySegment] and an [offset] into it. Navigating into a field or an element produces
 * another view over the same bytes, which is what makes reading a single path out of a large
 * document cost the path, not the document.
 *
 * The segment may be a heap array during ingest or a mapped file region once segments are on disk;
 * the reader does not care which, and that is the point — the same code reads a memtable entry and
 * a page of an SSTable.
 *
 * A view is immutable and cheap to create. It is safe to share between threads as long as the
 * underlying segment is not being written, which for an immutable segment is always.
 *
 * Every accessor validates before it reads. Bytes that do not decode raise
 * [VariantFormatException] — the engine never fabricates a default for data it cannot read.
 */
public class Variant public constructor(
    /** Dictionary resolving this value's field ids. Usually shared by a whole segment. */
    public val metadata: VariantMetadata,
    internal val segment: MemorySegment,
    /** Offset of this value's header byte within [segment]. */
    public val offset: Long = 0,
) {
    /** Reads a value out of a heap array. */
    public constructor(metadata: VariantMetadata, value: ByteArray) :
        this(metadata, MemorySegment.ofArray(value), 0)

    private val header: Int get() = segment.u8(offset, "value header")

    /** The two-bit basic type opening this value. */
    public val basicType: VariantBasicType get() = VariantBasicType.ofHeader(header.toByte())

    /**
     * The exact on-disk primitive type, or `null` when this value is not a primitive — a short
     * string, an object or an array.
     *
     * @throws VariantFormatException if the header names a primitive id this build does not know.
     */
    public val primitiveType: VariantPrimitiveType?
        get() {
            val head = header
            if (VariantBasicType.ofHeader(head.toByte()) != VariantBasicType.PRIMITIVE) return null
            return primitiveOrThrow(head)
        }

    /** What this value means, independent of the width it was stored in. */
    public val kind: VariantKind
        get() {
            val head = header
            return when (VariantBasicType.ofHeader(head.toByte())) {
                VariantBasicType.PRIMITIVE -> primitiveOrThrow(head).kind
                VariantBasicType.SHORT_STRING -> VariantKind.STRING
                VariantBasicType.OBJECT -> VariantKind.OBJECT
                VariantBasicType.ARRAY -> VariantKind.ARRAY
            }
        }

    /** `true` for the Variant null primitive. Note that a missing field is absent, not null. */
    public val isNull: Boolean get() = header == (VariantPrimitiveType.NULL.id shl 2)

    /**
     * Total encoded length of this value in bytes, including its header.
     *
     * For a container this reads the last field offset rather than walking children, so it is O(1)
     * whatever the value holds.
     *
     * The extent is checked against the segment before it is returned. This is what a caller uses
     * to step from one stored value to the next, so a length that runs past the end of the data
     * must be reported as unreadable here rather than turning into a wild offset one step later.
     */
    public val byteSize: Long
        get() {
            val head = header
            return when (VariantBasicType.ofHeader(head.toByte())) {
                VariantBasicType.PRIMITIVE -> (1L + primitivePayloadSize(head)).also { checkExtent(it) }
                VariantBasicType.SHORT_STRING -> (1L + (head ushr 2)).also { checkExtent(it) }
                VariantBasicType.OBJECT -> containerEnd(layout(head, VariantBasicType.OBJECT)) - offset
                VariantBasicType.ARRAY -> containerEnd(layout(head, VariantBasicType.ARRAY)) - offset
            }
        }

    /**
     * Number of top-level children: fields for an object, elements for an array, `0` for a scalar.
     *
     * The one counter that answers for every shape. [elementCount] and [fieldCount] stay, and stay
     * throwing, because their job is to *assert* the shape; this one's job is to let a caller who
     * does not know the shape ask anyway, which is what summarising a value needs. It is one branch
     * over those two answers rather than a third definition of either.
     *
     * The branch is on [basicType] rather than [kind], deliberately: a primitive whose type id this
     * build does not know still has no children, and that answer never depended on the byte this
     * build cannot read — so it is not a default invented for unknown data, it is an answer that
     * never needed it. Ask [kind] if you want the failure.
     *
     * O(1) for every shape.
     *
     * @throws VariantFormatException if the bytes do not decode.
     */
    public val childCount: Int
        get() = when (basicType) {
            VariantBasicType.OBJECT -> fieldCount
            VariantBasicType.ARRAY -> elementCount
            VariantBasicType.PRIMITIVE, VariantBasicType.SHORT_STRING -> 0
        }

    private fun checkExtent(length: Long) = segment.requireRange(offset, length, "value")

    /** Copies this value's bytes out of the segment. Pair with [VariantMetadata.toByteArray]. */
    public fun toByteArray(): ByteArray = segment.bytes(offset, byteSize.toInt(), "value")

    /**
     * This document rebuilt with a dictionary of its own, holding only the names it actually uses.
     *
     * **The trap this exists to remove.** A document read out of a segment carries *that segment's*
     * shared dictionary — one dictionary per segment is the single largest space saving in the
     * engine — so [metadata] describes thousands of documents and not this one. Hand
     * `(metadata, toByteArray())` to something expecting a self-contained Variant and it will be
     * correct but enormous; hand it `toByteArray()` alone and every field name in it resolves to the
     * wrong string, or to nothing. Neither failure is loud.
     *
     * ```kotlin
     * // Writing one document into a Parquet Variant column, where the pair must stand alone:
     * val standalone = row.document().detached()
     * writer.write(standalone.metadata.toByteArray(), standalone.toByteArray())
     * ```
     *
     * **This is not always what you want, and the alternative is cheaper.** A consumer that can take
     * a *shared* dictionary — an Iceberg writer handling a whole segment's worth of rows, say —
     * should be handed `variant.metadata` once and `variant.toByteArray()` per document, which
     * copies no names at all and is what the engine's own layout is optimised for. Reach for this
     * when the consumer wants one document, self-contained.
     *
     * The bytes are the Apache Parquet Variant encoding either way; what changes is only which
     * dictionary the value's field ids index into.
     *
     * @throws VariantFormatException if this value's bytes do not decode. Copying is byte-for-byte
     *   for scalars, so an unknown primitive id is reported here rather than re-encoded as
     *   something else.
     */
    public fun detached(): Variant = VariantBuilder().also { it.append(this) }.buildVariant()

    // --- scalars ---------------------------------------------------------------------------

    /** @throws VariantTypeException unless this value is a boolean. */
    public fun booleanValue(): Boolean = when (requirePrimitive(VariantKind.BOOLEAN)) {
        VariantPrimitiveType.BOOLEAN_TRUE -> true
        else -> false
    }

    /**
     * The value of an `int8`/`int16`/`int32`/`int64`, widened to `Long`.
     *
     * @throws VariantTypeException unless this value is an integer.
     */
    public fun longValue(): Long = when (val type = requirePrimitive(VariantKind.INTEGER)) {
        VariantPrimitiveType.INT8 -> segment.i8(offset + 1, "int8").toLong()
        VariantPrimitiveType.INT16 -> segment.i16(offset + 1, "int16").toLong()
        VariantPrimitiveType.INT32 -> segment.i32(offset + 1, "int32").toLong()
        VariantPrimitiveType.INT64 -> segment.i64(offset + 1, "int64")
        else -> throw VariantTypeException("$type is not an integer")
    }

    /**
     * The value of a `float` or `double`, widened to `Double`.
     *
     * Integers and decimals are deliberately *not* accepted: a silent widening here is how a query
     * engine starts answering `=` comparisons with rounding errors. Branch on [kind] instead.
     *
     * @throws VariantTypeException unless this value is a float or a double.
     */
    public fun doubleValue(): Double {
        val type = primitiveOrThrow(header)
        return when (type) {
            VariantPrimitiveType.DOUBLE -> segment.f64(offset + 1, "double")
            VariantPrimitiveType.FLOAT -> segment.f32(offset + 1, "float").toDouble()
            else -> throw VariantTypeException("$type is not a floating-point value")
        }
    }

    /** @throws VariantTypeException unless this value is a decimal. */
    public fun decimalValue(): BigDecimal {
        val type = requirePrimitive(VariantKind.DECIMAL)
        val scale = segment.u8(offset + 1, "decimal scale")
        if (scale > MAX_DECIMAL_SCALE) {
            throw VariantFormatException("decimal scale $scale exceeds the maximum of $MAX_DECIMAL_SCALE", offset + 1)
        }
        val unscaled = when (type) {
            VariantPrimitiveType.DECIMAL4 -> BigInteger.valueOf(segment.i32(offset + 2, "decimal4").toLong())
            VariantPrimitiveType.DECIMAL8 -> BigInteger.valueOf(segment.i64(offset + 2, "decimal8"))
            // 16-byte two's complement, little-endian by the Variant specification — which is the
            // opposite of Parquet's own DECIMAL physical type, so the reversal below is required,
            // not cosmetic.
            else -> BigInteger(segment.bytes(offset + 2, DECIMAL16_BYTES, "decimal16").reversedArray())
        }
        return BigDecimal(unscaled, scale)
    }

    /**
     * The text of a short string or a long string.
     *
     * @throws VariantTypeException unless this value is a string.
     * @throws VariantFormatException if the bytes are not valid UTF-8.
     */
    public fun stringValue(): String {
        val head = header
        return when (VariantBasicType.ofHeader(head.toByte())) {
            VariantBasicType.SHORT_STRING -> segment.utf8(offset + 1, head ushr 2, "short string")
            VariantBasicType.PRIMITIVE -> {
                if (primitiveOrThrow(head) != VariantPrimitiveType.STRING) {
                    throw VariantTypeException("${primitiveOrThrow(head)} is not a string")
                }
                segment.utf8(offset + 5, lengthPrefix("string"), "string")
            }

            else -> throw VariantTypeException("$kind is not a string")
        }
    }

    /** @throws VariantTypeException unless this value is binary. */
    public fun binaryValue(): ByteArray {
        requirePrimitive(VariantKind.BINARY)
        return segment.bytes(offset + 5, lengthPrefix("binary"), "binary")
    }

    /** @throws VariantTypeException unless this value is a uuid. */
    public fun uuidValue(): Uuid {
        requirePrimitive(VariantKind.UUID)
        // The one big-endian field in the format; the specification says so explicitly.
        return Uuid.fromByteArray(segment.bytes(offset + 1, UUID_BYTES, "uuid"))
    }

    /**
     * Days since 1970-01-01 for a `date`.
     *
     * @throws VariantTypeException unless this value is a date.
     */
    public fun epochDay(): Int {
        requirePrimitive(VariantKind.DATE)
        return segment.i32(offset + 1, "date")
    }

    /**
     * The raw counter behind a `time` or `timestamp`: microseconds for
     * [VariantPrimitiveType.TIME_NTZ], [VariantPrimitiveType.TIMESTAMP_TZ] and
     * [VariantPrimitiveType.TIMESTAMP_NTZ], nanoseconds for the two `NANOS` types.
     *
     * The unit is deliberately not normalised away — [primitiveType] carries it, and converting
     * here would either lose nanosecond precision or invent it.
     *
     * @throws VariantTypeException unless this value is a time or a timestamp.
     */
    public fun temporalValue(): Long {
        val type = primitiveOrThrow(header)
        if (type.kind != VariantKind.TIME && type.kind != VariantKind.TIMESTAMP) {
            throw VariantTypeException("$type is not a time or timestamp")
        }
        return segment.i64(offset + 1, type.name.lowercase())
    }

    // --- arrays ----------------------------------------------------------------------------

    /** Number of elements. @throws VariantTypeException unless this value is an array. */
    public val elementCount: Int get() = layout(header, VariantBasicType.ARRAY).count

    /** The element at [index]. @throws VariantTypeException unless this value is an array. */
    public fun element(index: Int): Variant {
        val layout = layout(header, VariantBasicType.ARRAY)
        require(index in 0 until layout.count) { "element $index outside 0..<${layout.count}" }
        return Variant(metadata, segment, layout.dataAt + layout.offsetAt(index))
    }

    /** The elements, in order. @throws VariantTypeException unless this value is an array. */
    public fun elements(): List<Variant> {
        val layout = layout(header, VariantBasicType.ARRAY)
        return List(layout.count) { Variant(metadata, segment, layout.dataAt + layout.offsetAt(it)) }
    }

    // --- objects ---------------------------------------------------------------------------

    /** Number of fields. @throws VariantTypeException unless this value is an object. */
    public val fieldCount: Int get() = layout(header, VariantBasicType.OBJECT).count

    /** Dictionary id of the field at [index]; fields are ordered by name, not by id. */
    public fun fieldId(index: Int): Int {
        val layout = layout(header, VariantBasicType.OBJECT)
        require(index in 0 until layout.count) { "field $index outside 0..<${layout.count}" }
        return layout.fieldId(index)
    }

    /** Name of the field at [index]. */
    public fun fieldName(index: Int): String = metadata.name(fieldId(index))

    /** Value of the field at [index]. */
    public fun fieldValue(index: Int): Variant {
        val layout = layout(header, VariantBasicType.OBJECT)
        require(index in 0 until layout.count) { "field $index outside 0..<${layout.count}" }
        return Variant(metadata, segment, layout.dataAt + layout.offsetAt(index))
    }

    /**
     * The field called [name], or `null` if the object does not have one.
     *
     * A binary search over the field ids, comparing the target's UTF-8 bytes against the dictionary
     * in place — no `String` is built and no value byte is touched, so a lookup costs
     * `log2(fieldCount)` byte comparisons regardless of how large the values are.
     *
     * This relies on the specification's guarantee that field ids appear in lexicographic order of
     * their names. A file that violates it may report a present field as absent; it will not
     * produce a wrong value.
     *
     * @throws VariantTypeException unless this value is an object.
     */
    public fun field(name: String): Variant? {
        val layout = layout(header, VariantBasicType.OBJECT)
        val target = name.toUtf8("field name")
        var low = 0
        var high = layout.count - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val comparison = metadata.compareName(layout.fieldId(middle), target)
            when {
                comparison < 0 -> low = middle + 1
                comparison > 0 -> high = middle - 1
                else -> return Variant(metadata, segment, layout.dataAt + layout.offsetAt(middle))
            }
        }
        return null
    }

    /** The fields, in the encoded (lexicographic) order. */
    public fun fields(): List<Pair<String, Variant>> {
        val layout = layout(header, VariantBasicType.OBJECT)
        return List(layout.count) { index ->
            metadata.name(layout.fieldId(index)) to
                Variant(metadata, segment, layout.dataAt + layout.offsetAt(index))
        }
    }

    // --- path navigation -------------------------------------------------------------------

    /**
     * Follows [path] from this value, or returns `null` if any step does not exist.
     *
     * A path that does not match is *absent*, not an error: asking for `$.user.email` of a document
     * that has no user is an ordinary outcome for a query over schemaless data, and making it throw
     * would mean every predicate had to be written twice.
     */
    public fun select(path: VariantPath): Variant? {
        var current: Variant = this
        for (step in path.steps) {
            val next = when (step) {
                is VariantPathStep.Field ->
                    if (current.basicType == VariantBasicType.OBJECT) current.field(step.name) else null

                is VariantPathStep.Index ->
                    if (current.basicType == VariantBasicType.ARRAY && step.index < current.elementCount) {
                        current.element(step.index)
                    } else {
                        null
                    }
            }
            current = next ?: return null
        }
        return current
    }

    /** Follows a path expression such as `$.items[0].name`. See [VariantPath.parse]. */
    public fun select(path: String): Variant? = select(VariantPath.parse(path))

    // --- internals -------------------------------------------------------------------------

    private fun primitiveOrThrow(head: Int): VariantPrimitiveType {
        if (VariantBasicType.ofHeader(head.toByte()) != VariantBasicType.PRIMITIVE) {
            throw VariantTypeException("${VariantBasicType.ofHeader(head.toByte())} is not a primitive")
        }
        val id = head ushr 2
        // A type id this build does not know means the file was written by a newer implementation.
        // That is unreadable data, not an absent value, and it is reported as such.
        return VariantPrimitiveType.ofId(id)
            ?: throw VariantFormatException("unknown Variant primitive type id $id", offset)
    }

    private fun requirePrimitive(expected: VariantKind): VariantPrimitiveType {
        val type = primitiveOrThrow(header)
        if (type.kind != expected) throw VariantTypeException("$type is not a $expected value")
        return type
    }

    private fun lengthPrefix(what: String): Int {
        val length = segment.i32(offset + 1, "$what length")
        if (length < 0) throw VariantFormatException("negative $what length $length", offset + 1)
        return length
    }

    private fun primitivePayloadSize(head: Int): Long = when (val type = primitiveOrThrow(head)) {
        VariantPrimitiveType.NULL, VariantPrimitiveType.BOOLEAN_TRUE, VariantPrimitiveType.BOOLEAN_FALSE -> 0L
        VariantPrimitiveType.INT8 -> 1L
        VariantPrimitiveType.INT16 -> 2L
        VariantPrimitiveType.INT32, VariantPrimitiveType.FLOAT, VariantPrimitiveType.DATE -> 4L
        VariantPrimitiveType.INT64,
        VariantPrimitiveType.DOUBLE,
        VariantPrimitiveType.TIMESTAMP_TZ,
        VariantPrimitiveType.TIMESTAMP_NTZ,
        VariantPrimitiveType.TIME_NTZ,
        VariantPrimitiveType.TIMESTAMP_NANOS_TZ,
        VariantPrimitiveType.TIMESTAMP_NANOS_NTZ,
        -> 8L

        VariantPrimitiveType.DECIMAL4 -> 1L + Int.SIZE_BYTES
        VariantPrimitiveType.DECIMAL8 -> 1L + Long.SIZE_BYTES
        VariantPrimitiveType.DECIMAL16 -> 1L + DECIMAL16_BYTES
        VariantPrimitiveType.UUID -> UUID_BYTES.toLong()
        VariantPrimitiveType.BINARY, VariantPrimitiveType.STRING ->
            Int.SIZE_BYTES + lengthPrefix(type.name.lowercase()).toLong()
    }

    /**
     * Objects and arrays differ only in whether a field-id list is present, so one layout
     * calculation serves both.
     */
    private inner class ContainerLayout(
        val count: Int,
        val idSize: Int,
        val offsetSize: Int,
        val idsAt: Long,
        val offsetsAt: Long,
        val dataAt: Long,
        /** Total size of the value region, from the container's last offset. */
        val dataSize: Long,
    ) {
        /**
         * Offsets are not required to ascend — the specification allows values to sit in any order
         * — but none may point past the region the container declares. Left unchecked, one corrupt
         * offset turns into a read of whatever value happens to follow, which is a wrong answer
         * rather than a reported failure.
         */
        fun offsetAt(index: Int): Long {
            val at = offsetsAt + index.toLong() * offsetSize
            val value = segment.unsignedLe(at, offsetSize, "element offset $index")
            if (value > dataSize) {
                throw VariantFormatException("element offset $value is past the container's $dataSize bytes", at)
            }
            return value
        }

        fun fieldId(index: Int): Int =
            metadata.requireId(
                segment.unsignedLe(idsAt + index.toLong() * idSize, idSize, "field id $index"),
                idsAt + index.toLong() * idSize,
            )
    }

    private fun layout(head: Int, expected: VariantBasicType): ContainerLayout {
        val actual = VariantBasicType.ofHeader(head.toByte())
        if (actual != expected) throw VariantTypeException("$kind is not ${expected.name.lowercase()}")

        val valueHeader = head ushr 2
        val offsetSize = (valueHeader and 0x03) + 1
        val isObject = expected == VariantBasicType.OBJECT
        // Objects carry a field-id width in bits 2-3 and `is_large` in bit 4; arrays have no ids,
        // so `is_large` sits in bit 2. Bits above are reserved and must be ignored.
        val idSize = if (isObject) ((valueHeader ushr 2) and 0x03) + 1 else 0
        val isLarge = if (isObject) (valueHeader ushr 4) and 0x01 == 1 else (valueHeader ushr 2) and 0x01 == 1

        val countSize = if (isLarge) 4 else 1
        val countValue = segment.unsignedLe(offset + 1, countSize, "element count")
        if (countValue > MAX_CONTAINER_ELEMENTS) {
            throw VariantFormatException("container declares $countValue elements", offset + 1)
        }
        val count = countValue.toInt()

        val idsAt = offset + 1 + countSize
        val offsetsAt = idsAt + count.toLong() * idSize
        val dataAt = offsetsAt + (count.toLong() + 1) * offsetSize
        segment.requireRange(offset, dataAt - offset, "container header")

        // The last offset is the region's total size. Reading it here — once per accessor rather
        // than once per element — is what lets every element offset be bounds-checked afterwards.
        val dataSize = segment.unsignedLe(offsetsAt + count.toLong() * offsetSize, offsetSize, "container size")
        segment.requireRange(dataAt, dataSize, "container values")

        return ContainerLayout(count, idSize, offsetSize, idsAt, offsetsAt, dataAt, dataSize)
    }

    private fun containerEnd(layout: ContainerLayout): Long = layout.dataAt + layout.dataSize

    /**
     * Renders as JSON when it can, which is what a failing assertion or a debugger wants to show.
     * Values JSON cannot express fall back to a structural description rather than throwing —
     * `toString` failing is never helpful.
     *
     * Above [TO_STRING_BYTE_LIMIT] it falls back to [toSummaryString] instead, because at that size
     * the JSON has already stopped being what anybody wanted: a debugger cannot show it and a log
     * line cannot hold it. The crossover is not a knob and there is nothing to configure — a caller
     * who wants the JSON of a large value calls [toJsonString], which is unchanged at every size.
     * That is what keeps this a change of ergonomics rather than of answers.
     */
    override fun toString(): String = runCatching {
        if (byteSize > TO_STRING_BYTE_LIMIT) toSummaryString() else toJsonString()
    }.getOrElse { failure -> unreadable(offset, failure) }

    public companion object {
        /**
         * Encoded bytes above which [toString] describes rather than renders.
         *
         * `internal`, because it is a safety valve and not an option: exposing it would invite a
         * caller to tune the one method whose job is to be callable without thinking. 64 KiB is
         * chosen to sit far above anything a person reads and far below anything that hurts, so a
         * developer meets the fallback only on a value they could not have read anyway.
         */
        internal const val TO_STRING_BYTE_LIMIT: Long = 64L * 1024

        /**
         * Parses [json] into a self-contained Variant — value bytes plus a dictionary holding only
         * the names that document uses.
         *
         * For ingest, share one dictionary across a segment instead; see [JsonParser.parseInto].
         *
         * @throws JsonParseException if [json] is not valid JSON, with the position of the fault.
         */
        public fun fromJson(json: String): Variant = JsonParser().parse(json)

        /** See the [String] overload. [json] must be UTF-8. */
        public fun fromJson(json: ByteArray): Variant = JsonParser().parse(json)

        /** `num_elements` is a 4-byte unsigned field, and every count is used as an `Int` index. */
        internal const val MAX_CONTAINER_ELEMENTS: Long = Int.MAX_VALUE.toLong()
        internal const val DECIMAL16_BYTES: Int = 16
        internal const val UUID_BYTES: Int = 16
    }
}
