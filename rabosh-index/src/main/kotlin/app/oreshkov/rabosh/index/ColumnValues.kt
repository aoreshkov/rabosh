package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.variant.Variant
import app.oreshkov.rabosh.variant.VariantKind
import java.math.BigDecimal
import java.util.Arrays

/**
 * The family a scalar belongs to when a column decides what to shred.
 *
 * Type selection runs over **families**, not over `VariantKind`, and that is not tidiness. `INTEGER`
 * and `DECIMAL` are one family because `INTEGER ⊑ DECIMAL`: choosing by raw kind would make the
 * ordinary JSON array `[1, 2.5, 3]` entirely residual, since `INTEGER` wins two to one and the
 * decimal is then unshreddable. Worse, it would make the chosen type flip between a segment holding
 * 51% integers and one holding 49%, so the same query would be fast on one and slow on the next for a
 * reason nothing in the data explains.
 */
internal enum class ColumnFamily {
    NUMERIC,
    BOOLEAN,
    TEXT,

    /** Anything with no column type: doubles, binaries, temporals, UUIDs, containers. */
    OTHER,

    ;

    companion object {
        /**
         * The family [kind] belongs to.
         *
         * Exhaustive with no `else`, so a kind added to [VariantKind] must be placed here rather than
         * silently landing in [OTHER].
         */
        fun of(kind: VariantKind): ColumnFamily = when (kind) {
            VariantKind.INTEGER, VariantKind.DECIMAL -> NUMERIC
            VariantKind.BOOLEAN -> BOOLEAN
            VariantKind.STRING -> TEXT

            // Doubles are the rare numeric kind in this encoding and have no column type; the rest
            // JSON does not produce at all. All go to residual, which is what residual is for.
            VariantKind.FLOAT, VariantKind.DOUBLE,
            VariantKind.BINARY, VariantKind.DATE, VariantKind.TIME, VariantKind.TIMESTAMP,
            VariantKind.UUID, VariantKind.ARRAY, VariantKind.OBJECT, VariantKind.NULL,
            -> OTHER
        }
    }
}

/**
 * One scalar as a column holds it, before a physical type has been chosen.
 *
 * Accumulated per value while a segment is observed; the type decision happens once, at the end, when
 * the whole multiset is known. A `null` [number], [text] and `boolean` together mean the JSON null,
 * which occupies a value position so that positions line up with the counts a `STARTS` bitmap
 * implies.
 */
internal class ColumnValue private constructor(
    val family: ColumnFamily,
    val isNull: Boolean,
    val number: BigDecimal?,
    val text: ByteArray?,
    val boolean: Boolean,
    /**
     * The kind the document held this value as, or `null` where it did not come from one.
     *
     * Builder state only — nothing about it reaches the file. It exists so that
     * [ColumnFormat.FIDELITY_EXACT_VALUES] can be decided: a `BigDecimal` of scale 0 could have come
     * from an `INTEGER` or from a `DECIMAL`, and reconstructing the wrong one changes what the caller
     * is handed back. Everything constructed here rather than read from a document reports `null` and
     * is therefore treated as not provable.
     */
    val sourceKind: VariantKind?,
) {
    companion object {
        val NULL: ColumnValue = ColumnValue(ColumnFamily.OTHER, true, null, null, false, VariantKind.NULL)

        fun ofNumber(value: BigDecimal, sourceKind: VariantKind? = null): ColumnValue =
            ColumnValue(ColumnFamily.NUMERIC, false, value, null, false, sourceKind)

        fun ofText(utf8: ByteArray): ColumnValue =
            ColumnValue(ColumnFamily.TEXT, false, null, utf8, false, VariantKind.STRING)

        fun ofBoolean(value: Boolean): ColumnValue =
            ColumnValue(ColumnFamily.BOOLEAN, false, null, null, value, VariantKind.BOOLEAN)

        val OTHER: ColumnValue = ColumnValue(ColumnFamily.OTHER, false, null, null, false, null)

        /** How [value] would be held, or [NULL] / [OTHER] for kinds with no column type. */
        fun of(value: Variant): ColumnValue = when (value.kind) {
            VariantKind.NULL -> NULL
            VariantKind.INTEGER -> ofNumber(BigDecimal.valueOf(value.longValue()), VariantKind.INTEGER)
            VariantKind.DECIMAL -> ofNumber(value.decimalValue(), VariantKind.DECIMAL)
            VariantKind.BOOLEAN -> ofBoolean(value.booleanValue())
            VariantKind.STRING -> ofText(value.stringValue().encodeToByteArray())

            VariantKind.FLOAT, VariantKind.DOUBLE,
            VariantKind.BINARY, VariantKind.DATE, VariantKind.TIME, VariantKind.TIMESTAMP,
            VariantKind.UUID, VariantKind.ARRAY, VariantKind.OBJECT,
            -> OTHER
        }
    }
}

/**
 * The physical type chosen for a column, and the arithmetic that makes its order the value order.
 *
 * For the numeric family this is a **fixed-point** decision: one [scale] for the whole column, every
 * value rescaled up to it — always exact, never lossy — and the unscaled integers stored at the
 * narrowest width that holds them all. The consequence is the one the phase exists for: at a common
 * scale, comparing unscaled integers *is* comparing values, so a range predicate is two integer
 * comparisons and a block bound is two integers.
 *
 * A value that does not fit 64 unscaled bits at [scale] is **not representable** and its ordinal goes
 * to residual. That is the escape, not a truncation: silently narrowing a value would make a bound
 * wrong, and a wrong bound deletes documents from a result.
 */
internal class ColumnType private constructor(
    val id: Int,
    /** Digits after the point, for the numeric types. Zero for everything else. */
    val scale: Int,
) {
    val isNumeric: Boolean get() = ColumnFormat.isNumeric(id)

    /** Bytes one value occupies, or `-1` when the type is not fixed-width. */
    val fixedWidth: Int
        get() = when (id) {
            ColumnFormat.COLUMN_TYPE_INT64, ColumnFormat.COLUMN_TYPE_DECIMAL64 -> 8
            ColumnFormat.COLUMN_TYPE_DECIMAL32 -> 4
            else -> -1
        }

    /**
     * [value] as the unscaled integer this column stores, or `null` if it does not fit.
     *
     * `setScale` with no rounding mode throws rather than rounds when a value would lose digits,
     * which cannot happen here because [scale] is the maximum over the column — but relying on that
     * silently would be exactly the kind of assumption that stops being true.
     */
    fun unscaledOrNull(value: BigDecimal): Long? {
        val rescaled = try {
            value.setScale(scale)
        } catch (inexact: ArithmeticException) {
            return null
        }
        val unscaled = rescaled.unscaledValue()
        if (unscaled.bitLength() >= Long.SIZE_BITS) return null
        val asLong = unscaled.toLong()
        if (id == ColumnFormat.COLUMN_TYPE_DECIMAL32 && (asLong > Int.MAX_VALUE || asLong < Int.MIN_VALUE)) {
            return null
        }
        return asLong
    }

    /** The value an unscaled integer stands for. */
    fun valueOf(unscaled: Long): BigDecimal = BigDecimal.valueOf(unscaled, scale)

    override fun toString(): String =
        ColumnFormat.columnTypeName(id) + (if (isNumeric && scale > 0) "(scale $scale)" else "")

    companion object {
        fun of(id: Int, scale: Int): ColumnType = ColumnType(id, scale)

        /**
         * Chooses the physical type for a column from the values it holds.
         *
         * The family with the most values wins; ties break towards the lower family ordinal, so the
         * choice is a **pure function of the value multiset**. That matters beyond determinism: a
         * column written by a flush and the same column rebuilt by a backfill must be byte-identical,
         * and a type decision that depended on iteration order or on wall-clock anything would break
         * that without breaking any equality assertion.
         *
         * Returns `null` when nothing is shreddable — every value is a double, a container or absent —
         * and the caller then leaves the segment uncovered rather than writing an empty column.
         */
        fun choose(values: List<ColumnValue>): ColumnType? {
            val counts = IntArray(ColumnFamily.entries.size)
            for (value in values) if (!value.isNull) counts[value.family.ordinal]++

            var best: ColumnFamily? = null
            for (family in ColumnFamily.entries) {
                if (family == ColumnFamily.OTHER) continue
                if (counts[family.ordinal] == 0) continue
                if (best == null || counts[family.ordinal] > counts[best.ordinal]) best = family
            }
            if (best == null) return null

            return when (best) {
                ColumnFamily.BOOLEAN -> ColumnType(ColumnFormat.COLUMN_TYPE_BOOLEAN, 0)
                ColumnFamily.TEXT -> ColumnType(ColumnFormat.COLUMN_TYPE_STRING, 0)
                ColumnFamily.NUMERIC -> chooseNumeric(values)
                ColumnFamily.OTHER -> null
            }
        }

        /**
         * The narrowest numeric type that holds the column.
         *
         * The scale is the maximum over the values, because rescaling *up* is exact and rescaling
         * down is not. The width is then whatever the widest unscaled value needs. A value that does
         * not fit is not consulted for the width — it is going to residual — but the scale is chosen
         * before that is known, which is deliberate: the scale must be a function of the values
         * alone, or two builds could disagree.
         */
        private fun chooseNumeric(values: List<ColumnValue>): ColumnType {
            var scale = 0
            var allIntegral = true
            for (value in values) {
                val number = value.number ?: continue
                val stripped = number.stripTrailingZeros()
                val valueScale = maxOf(stripped.scale(), 0)
                if (valueScale > scale) scale = valueScale
                if (valueScale > 0) allIntegral = false
            }
            if (scale > ColumnFormat.MAX_SCALE) scale = ColumnFormat.MAX_SCALE

            // Scale zero and everything fits a Long: the commonest column in any store, and it gets
            // an id whose reader never looks at a scale field.
            if (allIntegral && scale == 0) return ColumnType(ColumnFormat.COLUMN_TYPE_INT64, 0)

            val wide = ColumnType(ColumnFormat.COLUMN_TYPE_DECIMAL64, scale)
            val narrow = ColumnType(ColumnFormat.COLUMN_TYPE_DECIMAL32, scale)
            // 32 bits only if every representable value fits it; one value that does not would make
            // the whole column narrower than its data, and residual is for values, not for widths.
            for (value in values) {
                val number = value.number ?: continue
                if (wide.unscaledOrNull(number) == null) continue
                if (narrow.unscaledOrNull(number) == null) return wide
            }
            return narrow
        }
    }
}

/** Encodes and decodes a column's `VALUES` section. */
internal object ColumnValues {

    /**
     * Lays out [values] in [type]'s physical form.
     *
     * A null slot holds the type's **zero** — and that zero must never reach a bound. A filler leaking
     * into a min or a max widens the bound, which is correctness-safe and therefore invisible to every
     * differential test; it would simply make the column stop skipping, quietly, forever.
     */
    fun encode(type: ColumnType, values: List<ColumnValue>): ByteArray {
        val out = IndexWriter(values.size * 8 + 64)
        when (type.id) {
            ColumnFormat.COLUMN_TYPE_INT64, ColumnFormat.COLUMN_TYPE_DECIMAL64 -> {
                for (value in values) out.writeLong(if (value.isNull) 0 else type.unscaledOrNull(value.number!!)!!)
            }

            ColumnFormat.COLUMN_TYPE_DECIMAL32 -> {
                for (value in values) {
                    out.writeU32(if (value.isNull) 0 else type.unscaledOrNull(value.number!!)!!.toInt())
                }
            }

            ColumnFormat.COLUMN_TYPE_BOOLEAN -> {
                val trues = Bitmap()
                values.forEachIndexed { position, value -> if (!value.isNull && value.boolean) trues.add(position) }
                out.write(trues.encode())
            }

            ColumnFormat.COLUMN_TYPE_STRING -> {
                var offset = 0
                for (value in values) {
                    out.writeU32(offset)
                    offset += if (value.isNull) 0 else value.text!!.size
                }
                out.writeU32(offset)
                for (value in values) if (!value.isNull) out.write(value.text!!)
            }

            else -> throw UnsupportedIndexFormatException("no encoder for column type ${type.id}")
        }
        return out.toByteArray()
    }

    /**
     * An upper bound on the bytes [values] will occupy under [type], computed without encoding them.
     *
     * Checked before writing so the string blob cap is enforced by refusing to build rather than by
     * overflowing a `u32` offset. It is **exact** for every fixed-width type and for strings — the two
     * that can overflow — and a loose bound for booleans, whose bitmap encoding depends on how the
     * values cluster. Erring high is the safe direction for a cap.
     */
    fun maxEncodedSize(type: ColumnType, values: List<ColumnValue>): Long = when (type.id) {
        ColumnFormat.COLUMN_TYPE_INT64, ColumnFormat.COLUMN_TYPE_DECIMAL64 -> values.size.toLong() * 8
        ColumnFormat.COLUMN_TYPE_DECIMAL32 -> values.size.toLong() * 4
        // A bitmap is never larger than a flat bitset plus its directory, whatever the containers.
        ColumnFormat.COLUMN_TYPE_BOOLEAN -> values.size.toLong() / 8 + 64
        ColumnFormat.COLUMN_TYPE_STRING ->
            (values.size + 1).toLong() * 4 + values.sumOf { (it.text?.size ?: 0).toLong() }

        else -> throw UnsupportedIndexFormatException("no encoder for column type ${type.id}")
    }
}

/**
 * Reads a column's `VALUES` section in place.
 *
 * Fixed-width types resolve a position with a multiply; the string type reads two offsets and slices.
 * Nothing is decoded until a position is asked for, so a block the bounds ruled out costs nothing at
 * all — which is the whole of "bounds prune blocks".
 */
internal class ColumnValueReader(
    private val type: ColumnType,
    private val bytes: IndexBytes,
    private val valueCount: Int,
    private val file: String,
) {
    private val blobOffset: Int = if (type.id == ColumnFormat.COLUMN_TYPE_STRING) (valueCount + 1) * 4 else 0

    private val booleans: BitmapView? =
        if (type.id == ColumnFormat.COLUMN_TYPE_BOOLEAN) {
            BitmapView.open(bytes.source, bytes.sourceOffset, bytes.length, file)
        } else {
            null
        }

    init {
        val required = when (type.id) {
            ColumnFormat.COLUMN_TYPE_INT64, ColumnFormat.COLUMN_TYPE_DECIMAL64 -> valueCount.toLong() * 8
            ColumnFormat.COLUMN_TYPE_DECIMAL32 -> valueCount.toLong() * 4
            ColumnFormat.COLUMN_TYPE_STRING -> (valueCount + 1).toLong() * 4
            else -> 0
        }
        if (required > bytes.length) {
            bytes.corrupt("a $type column of $valueCount value(s) needs $required byte(s), not ${bytes.length}")
        }
    }

    /** The unscaled integer at [position]. Numeric types only. */
    fun unscaledAt(position: Int): Long {
        require(position in 0 until valueCount) { "position $position of $valueCount" }
        return when (type.id) {
            ColumnFormat.COLUMN_TYPE_INT64, ColumnFormat.COLUMN_TYPE_DECIMAL64 ->
                bytes.i64(position * 8, "value $position")

            ColumnFormat.COLUMN_TYPE_DECIMAL32 -> bytes.i32(position * 4, "value $position").toLong()
            else -> error("$type is not numeric")
        }
    }

    /** The number at [position]. */
    fun numberAt(position: Int): BigDecimal = type.valueOf(unscaledAt(position))

    /** The boolean at [position]. */
    fun booleanAt(position: Int): Boolean = booleans!!.contains(position)

    /** The UTF-8 bytes at [position]. */
    fun textAt(position: Int): ByteArray {
        require(position in 0 until valueCount) { "position $position of $valueCount" }
        val from = bytes.u32(position * 4, "string offset $position", bytes.length)
        val to = bytes.u32((position + 1) * 4, "string offset ${position + 1}", bytes.length)
        if (to < from) bytes.corrupt("string offsets at $position run backwards", position * 4)
        return bytes.bytes(blobOffset + from, to - from, "string $position")
    }

    /** Checks every value decodes and, for strings, that the offsets ascend and cover the blob. */
    fun verify() {
        when (type.id) {
            ColumnFormat.COLUMN_TYPE_STRING -> {
                var previous = 0
                for (position in 0..valueCount) {
                    val offset = bytes.u32(position * 4, "string offset $position", bytes.length)
                    if (offset < previous) bytes.corrupt("string offset $position runs backwards", position * 4)
                    previous = offset
                }
                if (blobOffset + previous != bytes.length) {
                    bytes.corrupt("the string blob ends at ${blobOffset + previous}, not at ${bytes.length}")
                }
            }

            ColumnFormat.COLUMN_TYPE_BOOLEAN -> {
                booleans!!.verify()
                if (!booleans.isEmpty && booleans.last() >= valueCount) {
                    bytes.corrupt("a boolean column names position ${booleans.last()} beyond $valueCount")
                }
            }

            else -> for (position in 0 until valueCount) unscaledAt(position)
        }
    }
}

/** Unsigned byte-order comparison, which is the order text bounds and the engine's keys use. */
internal fun compareText(left: ByteArray, right: ByteArray): Int = Arrays.compareUnsigned(left, right)
