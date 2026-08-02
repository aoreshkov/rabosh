package app.oreshkov.rabosh.variant

/**
 * The two-bit `basic_type` field that opens every Variant value byte.
 *
 * See the [Variant binary encoding specification](https://github.com/apache/parquet-format/blob/master/VariantEncoding.md).
 */
public enum class VariantBasicType(public val id: Int) {
    /** A value from [VariantPrimitiveType], identified by the six header bits. */
    PRIMITIVE(0),

    /** A string of 0..63 bytes whose length is carried directly in the six header bits. */
    SHORT_STRING(1),

    /** An object: field ids, field offsets, then values. */
    OBJECT(2),

    /** An array: field offsets, then values. */
    ARRAY(3),

    ;

    public companion object {
        private val BY_ID: Array<VariantBasicType> = entries.sortedBy { it.id }.toTypedArray()

        /** Decodes the low two bits of a Variant value header byte. */
        public fun ofHeader(header: Byte): VariantBasicType = BY_ID[header.toInt() and 0x03]
    }
}

/**
 * What a value *means*, independent of how many bytes it took to say it.
 *
 * The specification calls this an equivalence class: `int8` and `int64` are the same kind of thing
 * to a caller, and so are a short string and a long one. Code that branches on content should
 * branch on this; code that touches bytes wants [VariantPrimitiveType].
 */
public enum class VariantKind {
    NULL,
    BOOLEAN,
    INTEGER,
    FLOAT,
    DOUBLE,
    DECIMAL,
    STRING,
    BINARY,
    DATE,
    TIME,
    TIMESTAMP,
    UUID,
    ARRAY,
    OBJECT,
}

/**
 * The primitive type ids carried in the upper six bits of a value header byte when
 * [VariantBasicType.PRIMITIVE] is in effect.
 *
 * Ids are fixed by the specification and must not be renumbered — they appear on disk.
 */
public enum class VariantPrimitiveType(public val id: Int, public val kind: VariantKind) {
    NULL(0, VariantKind.NULL),
    BOOLEAN_TRUE(1, VariantKind.BOOLEAN),
    BOOLEAN_FALSE(2, VariantKind.BOOLEAN),
    INT8(3, VariantKind.INTEGER),
    INT16(4, VariantKind.INTEGER),
    INT32(5, VariantKind.INTEGER),
    INT64(6, VariantKind.INTEGER),
    DOUBLE(7, VariantKind.DOUBLE),
    DECIMAL4(8, VariantKind.DECIMAL),
    DECIMAL8(9, VariantKind.DECIMAL),
    DECIMAL16(10, VariantKind.DECIMAL),
    DATE(11, VariantKind.DATE),
    TIMESTAMP_TZ(12, VariantKind.TIMESTAMP),
    TIMESTAMP_NTZ(13, VariantKind.TIMESTAMP),
    FLOAT(14, VariantKind.FLOAT),
    BINARY(15, VariantKind.BINARY),
    STRING(16, VariantKind.STRING),
    TIME_NTZ(17, VariantKind.TIME),
    TIMESTAMP_NANOS_TZ(18, VariantKind.TIMESTAMP),
    TIMESTAMP_NANOS_NTZ(19, VariantKind.TIMESTAMP),
    UUID(20, VariantKind.UUID),

    ;

    public companion object {
        /** Highest primitive id defined by the specification. */
        public const val MAX_ID: Int = 20

        private val BY_ID: Array<VariantPrimitiveType?> =
            arrayOfNulls<VariantPrimitiveType>(MAX_ID + 1).also { table ->
                entries.forEach { table[it.id] = it }
            }

        /**
         * Returns the primitive type for [id], or `null` if the id is not one this
         * specification version defines. Callers must treat `null` as unreadable data
         * rather than as an absent value.
         */
        public fun ofId(id: Int): VariantPrimitiveType? = BY_ID.getOrNull(id)

        /** Decodes the upper six bits of a Variant value header byte. */
        public fun ofHeader(header: Byte): VariantPrimitiveType? = ofId((header.toInt() and 0xFF) ushr 2)
    }
}
