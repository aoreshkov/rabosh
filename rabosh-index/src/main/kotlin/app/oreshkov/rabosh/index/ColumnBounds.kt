package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.catalog.ValueBounds
import java.math.BigDecimal
import java.math.BigInteger

/**
 * What a column claims about the range of values at its path.
 *
 * Decoded into this rather than back into the catalog's `NumericRange`/`TextRange`, and deliberately.
 * Those types carry *exactness* flags that say whether a bound is the true extreme or a widened
 * prefix, and rebuilding one from bytes through the public builder would have to guess at them —
 * claiming a widened bound was exact is precisely the kind of small lie that turns into a wrong
 * answer. Pruning needs four values and a "no upper claim" flag, so that is what this holds.
 *
 * **A `null` upper bound means no claim, which is `+∞`, not an empty range.** A decoder treating it as
 * empty would skip segments that hold a match. `TextRange` leaves it null when a value is long enough
 * that no incremented prefix represents it — sixty-four bytes of `0xFF` — and the flag is explicit in
 * the encoding rather than inferred from a length for exactly this reason.
 */
internal class ColumnSegmentBounds(
    val numericMin: BigDecimal?,
    val numericMax: BigDecimal?,
    val textMin: ByteArray?,
    val textMax: ByteArray?,
) {
    /** Whether a numeric value in `[min, max]` could exist here. See the type-bracketing rule. */
    fun mayContainNumeric(min: BigDecimal?, max: BigDecimal?): Boolean {
        val low = numericMin ?: return false
        val high = numericMax ?: return false
        if (min != null && max != null && min > max) return false
        if (min != null && high < min) return false
        if (max != null && low > max) return false
        return true
    }

    /** Whether a string in `[min, max]` could exist here, compared in unsigned byte order. */
    fun mayContainText(min: ByteArray?, max: ByteArray?): Boolean {
        val low = textMin ?: return false
        if (max != null && compareText(low, max) > 0) return false
        // A null upper claim is +∞: nothing above it can be ruled out.
        val high = textMax ?: return true
        if (min != null && compareText(high, min) < 0) return false
        return true
    }

    override fun toString(): String = buildString {
        append("ColumnSegmentBounds(")
        if (numericMin != null) append("numeric $numericMin..$numericMax")
        if (textMin != null) {
            if (numericMin != null) append(", ")
            append("text ").append(textMin.decodeToString()).append("..")
            append(textMax?.decodeToString() ?: "unbounded")
        }
        if (numericMin == null && textMin == null) append("none")
        append(')')
    }

    companion object {
        val NONE: ColumnSegmentBounds = ColumnSegmentBounds(null, null, null, null)
    }
}

/**
 * The `bound` and `decimal` shapes, written and read.
 *
 * Byte-identical to the sketch sidecar's, and **duplicated rather than shared** — which bends the rule
 * that put `ValueSignature` and `ValueBoundsBuilder` in `rabosh-catalog`, so the bending is argued
 * rather than assumed.
 *
 * What *is* shared is the semantics: `ValueBoundsBuilder` accumulates both files' bounds, because the
 * rule that makes a bound safe to skip on — truncation always widens, never narrows — is subtle, and a
 * second implementation getting it backwards would delete documents from a result with nothing looking
 * like a fault. Same silent-wrongness argument as `ValueSignature`'s.
 *
 * What is not shared is the bytes. `SketchFormat` is internal to `rabosh-catalog` and its layout is a
 * permanent on-disk shape; publishing it as API to save eighty lines would make every change to a
 * private format an ABI event. And the failure differs in kind: a codec that disagreed produces a file
 * that does not decode — loudly, on read, in the module that wrote it — rather than a quiet wrong
 * answer. `ColumnBoundsTest` pins that the two encodings still agree.
 *
 * **Every `BigDecimal` is `stripTrailingZeros()`'d before it is written.** `NumericRange.equals`
 * compares by value, so `1.0` and `1.00` are the same bound, while the encoding is scale plus unscaled
 * digits and they are different bytes. Without the normalisation a column written by a flush and the
 * same column rebuilt by a backfill could differ for a reason no equality assertion would surface.
 */
internal object ColumnBounds {

    /** Writes the numeric slot then the text slot. Two tags, because there is no order across them. */
    fun writePair(out: IndexWriter, bounds: ValueBounds) {
        val numeric = bounds.numeric
        if (numeric == null) {
            out.writeByte(ColumnFormat.BOUND_NONE)
        } else {
            out.writeByte(ColumnFormat.BOUND_NUMERIC)
            writeDecimal(out, numeric.min)
            writeDecimal(out, numeric.max)
        }

        val text = bounds.text
        if (text == null) {
            out.writeByte(ColumnFormat.BOUND_NONE)
        } else {
            out.writeByte(ColumnFormat.BOUND_TEXT)
            out.writeBytes(text.minUtf8())
            out.writeByte(if (text.minIsExact) 1 else 0)
            val max = text.maxUtf8()
            if (max == null) {
                out.writeByte(0)
            } else {
                out.writeByte(1)
                out.writeBytes(max)
                out.writeByte(if (text.maxIsExact) 1 else 0)
            }
        }
    }

    /** Reads the pair [writePair] wrote, returning it with the offset just past it. */
    fun readPair(bytes: IndexBytes, at: Int): Pair<ColumnSegmentBounds, Int> {
        var cursor = at
        var numericMin: BigDecimal? = null
        var numericMax: BigDecimal? = null
        var textMin: ByteArray? = null
        var textMax: ByteArray? = null

        when (val tag = bytes.u8(cursor, "numeric bound tag")) {
            ColumnFormat.BOUND_NONE -> cursor += 1

            ColumnFormat.BOUND_NUMERIC -> {
                cursor += 1
                val (min, afterMin) = readDecimal(bytes, cursor)
                val (max, afterMax) = readDecimal(bytes, afterMin)
                if (min > max) bytes.corrupt("a numeric bound runs backwards: $min..$max", at)
                numericMin = min
                numericMax = max
                cursor = afterMax
            }

            else -> throw UnsupportedIndexFormatException(
                "a numeric column bound in ${bytes.file} carries tag $tag, which this build does not know",
            )
        }

        when (val tag = bytes.u8(cursor, "text bound tag")) {
            ColumnFormat.BOUND_NONE -> cursor += 1

            ColumnFormat.BOUND_TEXT -> {
                cursor += 1
                val minLength = bytes.u32(cursor, "text bound min length", bytes.length - cursor - 4)
                cursor += 4
                textMin = bytes.bytes(cursor, minLength, "text bound min")
                cursor += minLength + 1
                val hasMax = bytes.u8(cursor, "text bound max flag") != 0
                cursor += 1
                if (hasMax) {
                    val maxLength = bytes.u32(cursor, "text bound max length", bytes.length - cursor - 4)
                    cursor += 4
                    textMax = bytes.bytes(cursor, maxLength, "text bound max")
                    cursor += maxLength + 1
                    if (compareText(textMin, textMax) > 0) bytes.corrupt("a text bound runs backwards", at)
                }
            }

            else -> throw UnsupportedIndexFormatException(
                "a text column bound in ${bytes.file} carries tag $tag, which this build does not know",
            )
        }

        return ColumnSegmentBounds(numericMin, numericMax, textMin, textMax) to cursor
    }

    /** `scale:i32 unscaledLength:u32 unscaled` — big-endian two's complement, as the sketch writes it. */
    fun writeDecimal(out: IndexWriter, value: BigDecimal) {
        val normalised = value.stripTrailingZeros()
        out.writeU32(normalised.scale())
        out.writeBytes(normalised.unscaledValue().toByteArray())
    }

    fun readDecimal(bytes: IndexBytes, at: Int): Pair<BigDecimal, Int> {
        val scale = bytes.i32(at, "decimal scale")
        val length = bytes.u32(at + 4, "decimal unscaled length", bytes.length - at - 8)
        if (length == 0) bytes.corrupt("a decimal has no unscaled bytes", at + 4)
        val unscaled = bytes.bytes(at + 8, length, "decimal unscaled")
        return BigDecimal(BigInteger(unscaled), scale) to (at + 8 + length)
    }
}
