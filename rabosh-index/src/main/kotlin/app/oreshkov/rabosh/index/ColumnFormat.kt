package app.oreshkov.rabosh.index

import java.util.Locale

/**
 * The on-disk layout of a shredded typed column.
 *
 * ```
 * %010d.%04d.col := header entry[sectionCount] section*        framing: SectionDirectory
 *
 * section   := META      segmentNumber:u64 largestSequence:u64                    kind 1
 *                        indexId:u32 documentCount:u32
 *                        valueCount:u32 shreddedCount:u32
 *                        columnType:u8 scale:u8 statsEncoding:u8 reserved:u8
 *                        pathLength:u32 path
 *                        numeric:bound text:bound
 *            | PRESENCE  bitmap over ordinals with >= 1 value at the path         kind 2
 *            | RESIDUAL  bitmap over ordinals with >= 1 unshreddable value        kind 3
 *            | STARTS    bitmap over value positions: the first value of          kind 4
 *                        each shredded ordinal, ascending
 *            | NULLS     bitmap over value positions holding the JSON null        kind 5
 *            | VALUES    valueCount values in columnType's physical form          kind 6
 *            | STATS     blockCount:u32 reserved:u32 block[blockCount]            kind 7
 *            | FIDELITY  flags:u32                                                kind 8
 *
 * VALUES    := INT64      i64[valueCount]                                  columnType 1
 *            | DECIMAL32  i32[valueCount], unscaled at META.scale          columnType 2
 *            | DECIMAL64  i64[valueCount], unscaled at META.scale          columnType 3
 *            |                                        (columnType 4 reserved DECIMAL128)
 *            | BOOLEAN    a BitmapFormat bitmap of the true positions      columnType 5
 *            | STRING     offset:u32[valueCount + 1] utf8[]                columnType 6
 *            |                                        (columnType 7 reserved DOUBLE)
 *
 * block     := TYPED   nullCount:u32 min max, in columnType's form         statsEncoding 1
 *            | PREFIX  nullCount:u32 minLength:u32 min maxLength:u32 max   statsEncoding 2
 *
 * bound     := 0                                          absent
 *            | 1 min:decimal max:decimal                  numeric
 *            | 2 minLength:u32 min minExact:u8
 *                maxPresent:u8 [maxLength:u32 max maxExact:u8]   text
 * decimal   := scale:i32 unscaledLength:u32 unscaled      big-endian two's complement
 * ```
 *
 * Little-endian throughout. **These constants are permanent**: add, never renumber. `bound` and
 * `decimal` are byte-identical to the sketch sidecar's, deliberately — the same shape, written twice,
 * for the reason given below.
 *
 * Seven choices carry weight.
 *
 * **[SECTION_FIDELITY] exists because a column cannot always give back the value it was given.** The
 * numeric family is stored at one common scale per segment, so a segment holding `{"price":10}` beside
 * `{"price":9.99}` picks scale 2 and reads the first back as `10.00` — numerically the same value,
 * and *not* what the document says. That is fine for deciding a predicate, which is all a column did
 * before phase 12, and it is not fine for **returning** a value to a caller, which would be an index
 * changing an answer. So the builder proves exactness where it can and records it, and projection is
 * refused where it cannot.
 *
 * The section is optional and its flags are stated **positively**, which is what makes it additive in
 * both directions: an older build skips a kind it does not know, and this build reads a file without
 * the section as claiming nothing and falls back to the document. An absent section is therefore the
 * safe answer rather than the dangerous one — the opposite arrangement would have made every column
 * ever written silently claim a fidelity nobody checked.
 *
 * **The section kinds start at 1 and are this file's own**, not a continuation of the base sidecar's.
 * A section of a base sidecar is a fact about a *segment*; a section here is a fact about one path
 * within one index over one segment. The *framing* is shared, because that is where the safety
 * argument lives — a fixed-width directory carrying each extent is what makes an unknown kind
 * skippable — but the vocabulary is not, because every future column encoding would otherwise burn a
 * globally scarce id to save nothing. `BitmapFormat`'s container kinds and `IndexFormat`'s section
 * kinds already both start at 1 and are never confused, because the file disambiguates.
 *
 * **The numeric family is one physical type at one scale, and that is where the ordering comes from.**
 * `columnScale` is the largest scale any numeric value at the path carries; every value is rescaled up
 * to it, which is always exact and never lossy; and the unscaled integers are stored at the narrowest
 * width that holds them. At a common scale **unscaled integer order is value order**, which is
 * precisely what a column exists to provide and precisely what `ValueSignature`'s lookup order
 * deliberately is not. [COLUMN_TYPE_INT64] is the same thing at scale 0, kept as its own id because
 * the commonest case deserves a reader that never touches a scale field.
 *
 * **DECIMAL is the primary numeric type here, not an afterthought.** `decideNumber` in
 * `rabosh-variant` sends a JSON number to `INTEGER` only when its scale is zero and it fits a `Long`,
 * and to `DOUBLE` only past precision 38 — so `19.99`, `3.14159` and `-0.5` are all decimals. A column
 * type set built around `DOUBLE` would leave a price or a coordinate entirely unshredded.
 * [COLUMN_TYPE_DOUBLE] is reserved and unwritten for the mirror-image reason: it is the *rare* kind.
 *
 * **A value needing more than 64 unscaled bits falls to residual.** Nothing here does 128-bit
 * arithmetic; [COLUMN_TYPE_DECIMAL128] is reserved for the build that wants to. The loss is values
 * past about eighteen significant digits, and the escape is the one the term budget already uses —
 * not covered rather than partially covered.
 *
 * **[COLUMN_BLOCK_SHIFT] is a constant, not a field.** Block *i* covers value positions
 * `[8192i, 8192i + 8192)`, so finding a value's block is a shift and its statistics entry is
 * arithmetic — exactly the argument that fixes `IndexFormat.KEY_RESTART_INTERVAL`. A per-file block
 * size would produce two shapes a reader has to branch on for no benefit.
 *
 * **The bound codec is duplicated from `SketchFormat` rather than shared, and the rule bends here on
 * purpose.** `ValueBoundsBuilder` *is* shared, because a bound computed two ways can be silently
 * wrong in a way that deletes documents from a result. The bytes are a different matter: sharing them
 * would mean publishing a permanent on-disk shape as public API out of a module whose format is
 * otherwise entirely internal, and a codec that disagreed would fail loudly, on decode, in the module
 * that wrote it. A cross-module test pins that the two encodings agree.
 */
internal object ColumnFormat {
    /** `JKDB-COL` in ASCII, distinct from every other magic in the engine. */
    val MAGIC: ByteArray = "JKDB-COL".encodeToByteArray()

    /** The only version this build writes, and the only one it reads. */
    const val VERSION: Int = 1

    // --- section kinds, this file's own namespace ----------------------------------------------

    const val SECTION_META: Int = 1
    const val SECTION_PRESENCE: Int = 2
    const val SECTION_RESIDUAL: Int = 3
    const val SECTION_STARTS: Int = 4
    const val SECTION_NULLS: Int = 5
    const val SECTION_VALUES: Int = 6
    const val SECTION_STATS: Int = 7

    /**
     * `flags:u32` — what this column can be used for beyond deciding a predicate. Added in phase 12.
     *
     * A column written before it exists carries no such section, and a reader that finds none reads
     * every flag as clear. That is the whole of the compatibility story in both directions: an older
     * build skips a kind it does not know, and this build treats an older file as claiming nothing.
     * Which is why the flags are stated **positively** — "this is provably exact" rather than "this
     * may be lossy" — so an absent section is the conservative answer rather than the dangerous one.
     */
    const val SECTION_FIDELITY: Int = 8

    /**
     * Bit 0 of [SECTION_FIDELITY]: every shredded value reconstructs to the document's own value.
     *
     * Clear for a numeric column whose values do not all carry the column's common scale, because the
     * scale is a property of the *column* and the original is not recoverable from what is stored —
     * see `ColumnBuilder`. Always set for `STRING` and `BOOLEAN`, which transform nothing. Remaining
     * bits are reserved and written zero; add a bit, never repurpose one.
     */
    const val FIDELITY_EXACT_VALUES: Int = 1

    /** The name of a section kind, or `null` if this build does not know it. Never a default. */
    fun sectionName(kind: Int): String? = when (kind) {
        SECTION_META -> "META"
        SECTION_PRESENCE -> "PRESENCE"
        SECTION_RESIDUAL -> "RESIDUAL"
        SECTION_STARTS -> "STARTS"
        SECTION_NULLS -> "NULLS"
        SECTION_VALUES -> "VALUES"
        SECTION_STATS -> "STATS"
        SECTION_FIDELITY -> "FIDELITY"
        else -> null
    }

    // --- column types --------------------------------------------------------------------------

    /** Signed 64-bit integers, scale 0. */
    const val COLUMN_TYPE_INT64: Int = 1

    /** Unscaled 32-bit integers at `META.scale`. */
    const val COLUMN_TYPE_DECIMAL32: Int = 2

    /** Unscaled 64-bit integers at `META.scale`. */
    const val COLUMN_TYPE_DECIMAL64: Int = 3

    /** Reserved. Needs 128-bit arithmetic this build does not do; such values go to residual. */
    const val COLUMN_TYPE_DECIMAL128: Int = 4

    /** A bitmap of the value positions holding `true`. */
    const val COLUMN_TYPE_BOOLEAN: Int = 5

    /** `offset:u32[valueCount + 1]` then a UTF-8 blob. */
    const val COLUMN_TYPE_STRING: Int = 6

    /** Reserved. `DOUBLE` is the rare numeric kind here; such values go to residual. */
    const val COLUMN_TYPE_DOUBLE: Int = 7

    /** The name of a column type, or `null` if this build does not know it. Never a default. */
    fun columnTypeName(type: Int): String? = when (type) {
        COLUMN_TYPE_INT64 -> "INT64"
        COLUMN_TYPE_DECIMAL32 -> "DECIMAL32"
        COLUMN_TYPE_DECIMAL64 -> "DECIMAL64"
        COLUMN_TYPE_DECIMAL128 -> "DECIMAL128"
        COLUMN_TYPE_BOOLEAN -> "BOOLEAN"
        COLUMN_TYPE_STRING -> "STRING"
        COLUMN_TYPE_DOUBLE -> "DOUBLE"
        else -> null
    }

    /** Whether this build can read a column of [type]. Reserved ids decode to a signalled failure. */
    fun isSupported(type: Int): Boolean = when (type) {
        COLUMN_TYPE_INT64, COLUMN_TYPE_DECIMAL32, COLUMN_TYPE_DECIMAL64,
        COLUMN_TYPE_BOOLEAN, COLUMN_TYPE_STRING,
        -> true

        else -> false
    }

    /** Whether values of [type] are compared as numbers. See the type-bracketing rule. */
    fun isNumeric(type: Int): Boolean =
        type == COLUMN_TYPE_INT64 || type == COLUMN_TYPE_DECIMAL32 || type == COLUMN_TYPE_DECIMAL64

    // --- statistics ------------------------------------------------------------------------------

    /** A block bound in the column's own physical form: fixed width, arithmetic access. */
    const val STATS_TYPED: Int = 1

    /** A block bound as two length-prefixed byte strings. Strings only. */
    const val STATS_PREFIX: Int = 2

    fun statsEncodingName(encoding: Int): String? = when (encoding) {
        STATS_TYPED -> "TYPED"
        STATS_PREFIX -> "PREFIX"
        else -> null
    }

    /**
     * Value positions per statistics block, as a power of two.
     *
     * A block is a range of **value positions**, not of ordinals, which is what composes with the
     * starts/nulls/values coordinate system. 8192 values is 64 KB of `i64`s — small enough that
     * skipping one is worth the 24-byte entry, large enough that the statistics are a rounding error
     * against the data.
     */
    const val COLUMN_BLOCK_SHIFT: Int = 13

    const val COLUMN_BLOCK_VALUES: Int = 1 shl COLUMN_BLOCK_SHIFT

    /** How many blocks [valueCount] values occupy. */
    fun blockCount(valueCount: Int): Int = (valueCount + COLUMN_BLOCK_VALUES - 1) ushr COLUMN_BLOCK_SHIFT

    // --- bounds ------------------------------------------------------------------------------------

    /** Deliberately the same numbering as the sketch sidecar's, which carries the same shape. */
    const val BOUND_NONE: Int = 0
    const val BOUND_NUMERIC: Int = 1
    const val BOUND_TEXT: Int = 2

    // --- ceilings ----------------------------------------------------------------------------------

    /** A `u32` offset array caps the string blob. Beyond it the segment is not covered. */
    const val MAX_STRING_BLOB_BYTES: Int = Int.MAX_VALUE - 8

    /** The widest scale the Variant encoding admits, and therefore the widest a column carries. */
    const val MAX_SCALE: Int = 38
}

/** One index's shredded column over one segment. */
internal const val COLUMN_SUFFIX: String = ".col"

/** `%010d.%04d.col` — segment number, then index id, matching the posting file's shape. */
internal fun columnFileName(segmentNumber: Long, indexId: Int): String =
    String.format(Locale.ROOT, "%010d.%04d%s", segmentNumber, indexId, COLUMN_SUFFIX)

internal fun temporaryColumnFileName(segmentNumber: Long, indexId: Int): String =
    columnFileName(segmentNumber, indexId) + ".tmp"

/** The `(segment, index)` pair [columnFileName] would have produced this name for, or `null`. */
internal fun columnNumbers(name: String): Pair<Long, Int>? {
    if (!name.endsWith(COLUMN_SUFFIX)) return null
    val stem = name.removeSuffix(COLUMN_SUFFIX)
    val separator = stem.lastIndexOf('.')
    if (separator <= 0) return null
    val segment = stem.substring(0, separator).toLongOrNull()?.takeIf { it >= 0 } ?: return null
    val index = stem.substring(separator + 1).toIntOrNull()?.takeIf { it >= 0 } ?: return null
    return segment to index
}
