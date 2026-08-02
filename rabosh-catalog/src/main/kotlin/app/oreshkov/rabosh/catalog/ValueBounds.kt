package app.oreshkov.rabosh.catalog

import app.oreshkov.rabosh.variant.Variant
import app.oreshkov.rabosh.variant.VariantKind
import java.math.BigDecimal
import java.util.Arrays

/** The smallest and largest number seen at a path, exactly. */
public class NumericRange internal constructor(
    /** Smallest value. */
    public val min: BigDecimal,
    /** Largest value. */
    public val max: BigDecimal,
) {
    internal fun merge(other: NumericRange): NumericRange =
        NumericRange(minOf(min, other.min), maxOf(max, other.max))

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is NumericRange && min.compareTo(other.min) == 0 && max.compareTo(other.max) == 0)

    override fun hashCode(): Int = 31 * min.stripTrailingZeros().hashCode() + max.stripTrailingZeros().hashCode()

    override fun toString(): String = "$min..$max"
}

/**
 * The smallest and largest text seen at a path, possibly truncated.
 *
 * Truncation always **widens**, never narrows, so a truncated range is still a correct range: [min]
 * is a prefix of the true minimum and therefore no larger than it, and [max] is a prefix with its
 * last byte incremented and therefore no smaller than the true maximum. That is what keeps a bound
 * usable for skipping — a narrowed bound would let a query decide a segment cannot hold a value it
 * does hold, which is the one failure a statistic must never cause.
 *
 * A value long enough that no upper bound can be represented — sixty-four bytes of `0xFF`, which no
 * UTF-8 text produces — leaves [max] `null`, meaning no claim is made.
 */
public class TextRange internal constructor(
    private val minBytes: ByteArray,
    private val maxBytes: ByteArray?,
    /** Whether [min] is the true minimum rather than a prefix of it. */
    public val minIsExact: Boolean,
    /** Whether [max] is the true maximum rather than a widened prefix of it. */
    public val maxIsExact: Boolean,
) {
    /** The lower bound as text. Always valid UTF-8: truncation cuts on a code-point boundary. */
    public val min: String get() = minBytes.decodeToString()

    /**
     * The upper bound as text, or `null` when none could be represented.
     *
     * May end in a replacement character: an incremented byte is a bound in byte order, which is the
     * order the engine sorts in, and need not be text. Use [maxUtf8] when the bytes are what matter.
     */
    public val max: String? get() = maxBytes?.decodeToString()

    /** The lower bound's bytes. */
    public fun minUtf8(): ByteArray = minBytes.copyOf()

    /** The upper bound's bytes, or `null` when none could be represented. */
    public fun maxUtf8(): ByteArray? = maxBytes?.copyOf()

    internal fun merge(other: TextRange): TextRange {
        val minComparison = Arrays.compareUnsigned(minBytes, other.minBytes)
        val lower = if (minComparison <= 0) minBytes else other.minBytes
        val lowerExact = when {
            minComparison < 0 -> minIsExact
            minComparison > 0 -> other.minIsExact
            // Equal bytes: if either side knows this is the true value, the merged bound knows it.
            else -> minIsExact || other.minIsExact
        }

        val mine = maxBytes
        val theirs = other.maxBytes
        // One side claiming no upper bound means the merged range claims none either.
        if (mine == null || theirs == null) return TextRange(lower, null, lowerExact, false)

        val maxComparison = Arrays.compareUnsigned(mine, theirs)
        val upper = if (maxComparison >= 0) mine else theirs
        val upperExact = when {
            maxComparison > 0 -> maxIsExact
            maxComparison < 0 -> other.maxIsExact
            else -> maxIsExact || other.maxIsExact
        }
        return TextRange(lower, upper, lowerExact, upperExact)
    }

    override fun equals(other: Any?): Boolean =
        this === other || (
            other is TextRange &&
                minBytes.contentEquals(other.minBytes) &&
                maxBytes.contentEquals(other.maxBytes) &&
                minIsExact == other.minIsExact &&
                maxIsExact == other.maxIsExact
            )

    override fun hashCode(): Int {
        var result = minBytes.contentHashCode()
        result = 31 * result + (maxBytes?.contentHashCode() ?: 0)
        result = 31 * result + minIsExact.hashCode()
        return 31 * result + maxIsExact.hashCode()
    }

    override fun toString(): String {
        val low = if (minIsExact) min else "$min…"
        val high = max?.let { if (maxIsExact) it else "…$it" } ?: "unbounded"
        return "$low..$high"
    }

    internal companion object {
        /**
         * The range a single value contributes, truncated to [limit] bytes.
         *
         * The minimum is cut on a code-point boundary, which can only make it smaller. The maximum
         * is cut the same way and then incremented at its last byte below `0xFF`, which can only
         * make it larger — and if there is no such byte, no upper bound is claimed at all.
         */
        fun of(value: ByteArray, limit: Int): TextRange {
            if (value.size <= limit) return TextRange(value.copyOf(), value.copyOf(), true, true)
            val prefix = value.copyOfRange(0, utf8BoundaryAtOrBefore(value, limit))
            return TextRange(prefix, incremented(prefix), false, false)
        }

        /**
         * The largest index at or before [limit] that starts a UTF-8 code point.
         *
         * Cutting in the middle of a multi-byte sequence would leave bytes that do not decode, and
         * this type's whole job is to be displayed. It is still a prefix in byte order either way,
         * so the bound stays correct; this only keeps it legible.
         */
        private fun utf8BoundaryAtOrBefore(value: ByteArray, limit: Int): Int {
            var at = limit
            while (at > 0 && (value[at].toInt() and 0xC0) == 0x80) at--
            return at
        }

        /** The smallest byte string strictly greater than every string beginning with [prefix]. */
        private fun incremented(prefix: ByteArray): ByteArray? {
            for (index in prefix.indices.reversed()) {
                if (prefix[index] != 0xFF.toByte()) {
                    val bound = prefix.copyOfRange(0, index + 1)
                    bound[index] = (bound[index] + 1).toByte()
                    return bound
                }
            }
            return null
        }
    }
}

/**
 * What is known about the range of values at one path.
 *
 * Two slots, kept **independent** rather than collapsed into one range. A path that holds strings in
 * most documents and integers in a few has a meaningful range for each, and a single range would
 * either need an order across types — which does not exist — or would have to give up and report
 * nothing, losing both.
 *
 * There is no temporal or binary slot. JSON's grammar produces neither, so the branch would be
 * untested code carrying a permanent on-disk shape; the sidecar's bound tag is what lets one arrive
 * later as a new id rather than a new format version.
 */
public class ValueBounds internal constructor(
    /** Range over integers, decimals and floating-point values, or `null` if none were seen. */
    public val numeric: NumericRange?,
    /** Range over strings, or `null` if none were seen. */
    public val text: TextRange?,
) {
    /** `true` when nothing bounded has been seen at this path. */
    public val isEmpty: Boolean get() = numeric == null && text == null

    internal fun merge(other: ValueBounds): ValueBounds = ValueBounds(
        numeric = merge(numeric, other.numeric) { a, b -> a.merge(b) },
        text = merge(text, other.text) { a, b -> a.merge(b) },
    )

    override fun equals(other: Any?): Boolean =
        this === other || (other is ValueBounds && numeric == other.numeric && text == other.text)

    override fun hashCode(): Int = 31 * numeric.hashCode() + text.hashCode()

    override fun toString(): String = when {
        isEmpty -> "ValueBounds(none)"
        else -> "ValueBounds(" + listOfNotNull(numeric?.let { "numeric $it" }, text?.let { "text $it" })
            .joinToString() + ")"
    }

    internal companion object {
        val EMPTY: ValueBounds = ValueBounds(null, null)

        private inline fun <T> merge(left: T?, right: T?, combine: (T, T) -> T): T? = when {
            left == null -> right
            right == null -> left
            else -> combine(left, right)
        }
    }
}

/**
 * Accumulates [ValueBounds] from the scalars at one path.
 *
 * Shared rather than reimplemented, for the reason [ValueSignature] is: a bound is what a query uses
 * to decide **a segment cannot contain a match**, and the rule that makes that safe — *truncation
 * always widens, never narrows* — is subtle enough that a second implementation would eventually get
 * it backwards. When it did, the failure would be a document silently missing from a result rather
 * than anything that looks like a fault. The catalog uses this to describe a path; `rabosh-index`
 * uses it to bound a shredded column, and the two must agree about what a bound means.
 *
 * **Only numbers and text have a bound**, matching the two tags the sidecar formats carry. A boolean,
 * a byte string or a timestamp contributes nothing here — it is still *present*, which is a separate
 * fact recorded separately — and the tag byte on the absent case is what lets another kind arrive
 * later as a new id rather than a new format version.
 *
 * Not thread-safe; one of these belongs to one accumulation.
 *
 * @param textBoundBytes how many bytes of a string the bound may keep before truncating. Truncation
 *   widens, so a smaller limit costs precision and never correctness.
 */
public class ValueBoundsBuilder(private val textBoundBytes: Int) {
    private var numeric: NumericRange? = null
    private var text: TextRange? = null

    init {
        require(textBoundBytes > 0) { "textBoundBytes must be positive, was $textBoundBytes" }
    }

    /** Whether nothing bounded has been seen yet. */
    public val isEmpty: Boolean get() = numeric == null && text == null

    /**
     * Widens the bounds to include [value], if it is of a kind that has one.
     *
     * Exhaustive with no `else`, so a kind added to [app.oreshkov.rabosh.variant.VariantKind] must be
     * given an answer here rather than silently inheriting one.
     */
    public fun add(value: Variant) {
        when (value.kind) {
            VariantKind.INTEGER -> addNumber(BigDecimal.valueOf(value.longValue()))

            VariantKind.DECIMAL -> addNumber(value.decimalValue())

            VariantKind.FLOAT, VariantKind.DOUBLE -> {
                val number = value.doubleValue()
                // NaN and the infinities have no place in an ordered bound — they would make the
                // range meaningless for skipping. They are still distinct *values*, which is
                // `ValueSignature`'s business rather than this one's.
                if (number.isFinite()) addNumber(BigDecimal.valueOf(number))
            }

            VariantKind.STRING -> addText(value.stringValue().encodeToByteArray())

            // No bound, by design rather than omission: JSON's grammar produces neither a timestamp
            // nor a byte string, so a bound for them would be an untested branch carrying a permanent
            // on-disk shape. Containers and nulls are not scalars in the first place.
            VariantKind.BOOLEAN, VariantKind.BINARY,
            VariantKind.DATE, VariantKind.TIME, VariantKind.TIMESTAMP, VariantKind.UUID,
            VariantKind.NULL, VariantKind.ARRAY, VariantKind.OBJECT,
            -> Unit
        }
    }

    /** Widens the numeric bound to include [value]. */
    public fun addNumber(value: BigDecimal) {
        val range = NumericRange(value, value)
        numeric = numeric?.merge(range) ?: range
    }

    /** Widens the text bound to include the string whose UTF-8 form is [utf8]. */
    public fun addText(utf8: ByteArray) {
        val range = TextRange.of(utf8, textBoundBytes)
        text = text?.merge(range) ?: range
    }

    /** Widens these bounds to include everything [other] covers. */
    public fun add(other: ValueBounds) {
        other.numeric?.let { numeric = numeric?.merge(it) ?: it }
        other.text?.let { text = text?.merge(it) ?: it }
    }

    public fun build(): ValueBounds = if (isEmpty) ValueBounds.EMPTY else ValueBounds(numeric, text)
}
