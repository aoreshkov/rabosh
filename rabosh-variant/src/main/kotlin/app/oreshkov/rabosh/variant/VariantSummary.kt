package app.oreshkov.rabosh.variant

// Ways of looking at a value whose size is the reason you cannot look at it properly.
//
// `VariantJson.kt` renders a value in full and its cost is the value's; everything here costs the
// same on a four-megabyte document as on a boolean. That is the whole contract, and it is why these
// live in their own file rather than beside a renderer whose KDoc promises the opposite.
//
// Nothing here produces JSON. See `toJsonSummaryString`.

/** Top-level children a summary shows before eliding the rest. */
public const val DEFAULT_SUMMARY_LIMIT: Int = 8

/**
 * Characters of any one string or field name a summary shows before eliding the rest.
 *
 * Public so a caller — and a test — can state a bound on a summary's length from the contract
 * rather than from a remembered number.
 */
public const val SUMMARY_VALUE_LIMIT: Int = 64

/**
 * Encoded bytes above which a scalar is described rather than decoded.
 *
 * Four bytes is the widest UTF-8 encoding of one code point, so this is exactly the largest value
 * that could still be shown in full — a value above it cannot fit [SUMMARY_VALUE_LIMIT] characters
 * whatever it holds, and decoding it would be work whose result is thrown away. That is the
 * difference between a summary that is merely *short* and one that is *cheap*: without this gate,
 * outlining eight hundred-megabyte strings decodes eight hundred megabytes in order to print eight
 * sixty-four-character prefixes, which is the exact work this file exists to avoid.
 *
 * [Variant.byteSize] is O(1), so the decision itself costs nothing.
 */
private const val SUMMARY_VALUE_BYTES: Long = 4L * SUMMARY_VALUE_LIMIT

/** The marker for anything a summary left out. One character, so a bound can be arithmetic. */
internal const val ELISION: Char = '…'

/**
 * One line describing this value's shape and size, whatever it holds and however large it is.
 *
 * ```
 * Variant(object, children=12, bytes=4200000)
 * Variant(array, children=1024, bytes=812)
 * Variant(string, bytes=104)
 * ```
 *
 * The child count is omitted for a scalar rather than reported as zero, for the reason
 * `ReadableBitmap.describe` has two forms: `children=0` on a string is noise in the one place there
 * is no room for any. The shape rhymes with [VariantMetadata.toString] on purpose — these are
 * siblings and should read like it.
 *
 * Reads the header and, for a container, its last offset. Nothing else, at any size.
 *
 * Never throws. A value whose bytes do not decode is described as unreadable in exactly the words
 * [Variant.toString] uses for the same failure — one spelling, two callers, because a reader
 * meeting the second should not have to wonder whether it means something different.
 */
public fun Variant.toSummaryString(): String = runCatching {
    buildString {
        append("Variant(").append(kind.name.lowercase())
        when (basicType) {
            VariantBasicType.OBJECT, VariantBasicType.ARRAY -> append(", children=").append(childCount)
            VariantBasicType.PRIMITIVE, VariantBasicType.SHORT_STRING -> Unit
        }
        append(", bytes=").append(byteSize).append(')')
    }
}.getOrElse { failure -> unreadable(offset, failure) }

/**
 * The first [limit] top-level children, with everything below them elided.
 *
 * ```
 * {"id":42,"name":"ada","tags":[…1024],"blob":…4200000 bytes,…9 more}
 * ```
 *
 * **This is not JSON**, and making it into JSON would be worse rather than better. It is
 * JSON-*shaped* so that anyone who reads JSON can skim it, and every elision is spelled with a `…`
 * that JSON has no production for — deliberately, so the output cannot be mistaken for the value.
 * The alternative is to elide into something JSON can express, and an elided array rendered `[]` is
 * *readable and wrong*: nothing distinguishes it from an empty one. Use [toJsonString] when you
 * want JSON.
 *
 * A container child shows its shape and its own child count (`{…12}`, `[…1024]`, or `{}` and `[]`
 * when genuinely empty); a scalar too large to show is reported by size (`…4200000 bytes`); the
 * children past [limit] are counted (`…9 more`). A small value elides nothing and therefore *does*
 * come out as JSON — that agreement is a consequence of sharing one scalar renderer with
 * [toJsonString] rather than a promise, and the suite asserts it as a consequence.
 *
 * Both the length of the result and the bytes read are bounded by a function of [limit] and
 * [SUMMARY_VALUE_LIMIT] alone. Both are the point: this exists to be called on a value whose size
 * is the reason you cannot call [toJsonString].
 *
 * @param limit top-level children to show. `0` shows only the count, which is the "just tell me the
 *   shape" form. Unlimited is deliberately not offered — that is [toJsonString], which already
 *   exists, and a second spelling of it would be the worse one.
 * @throws IllegalArgumentException if [limit] is negative.
 * @throws VariantFormatException if the bytes do not decode. A summary *reports* unreadable data
 *   rather than eliding it: eliding would be a default invented for exactly the thing that has to be
 *   signalled, and would report "here are eight children" about bytes that have none. Reach for
 *   [toSummaryString] when you need something that cannot throw.
 * @throws JsonWriteException for a non-finite `double` among the children shown, as [toJsonString]
 *   does for the same value. It cannot throw for depth: it does not recurse.
 */
public fun Variant.toJsonSummaryString(limit: Int = DEFAULT_SUMMARY_LIMIT): String =
    buildString { appendJsonSummaryTo(this, limit) }

/** Appends [toJsonSummaryString] to [out], avoiding an intermediate `String`. */
public fun Variant.appendJsonSummaryTo(out: StringBuilder, limit: Int = DEFAULT_SUMMARY_LIMIT) {
    require(limit >= 0) { "a summary limit is not negative, was $limit" }
    when (basicType) {
        VariantBasicType.OBJECT -> appendContainerSummary(out, limit, '{', '}')
        VariantBasicType.ARRAY -> appendContainerSummary(out, limit, '[', ']')
        VariantBasicType.PRIMITIVE, VariantBasicType.SHORT_STRING -> appendScalarSummary(this, out)
    }
}

private fun Variant.appendContainerSummary(out: StringBuilder, limit: Int, open: Char, close: Char) {
    val isObject = basicType == VariantBasicType.OBJECT
    val count = childCount
    val shown = minOf(limit, count)

    out.append(open)
    for (index in 0 until shown) {
        if (index > 0) out.append(',')
        if (isObject) {
            out.appendJsonString(fieldName(index), SUMMARY_VALUE_LIMIT)
            out.append(':')
        }
        appendChildSummary(if (isObject) fieldValue(index) else element(index), out)
    }
    if (shown < count) {
        if (shown > 0) out.append(',')
        out.append(ELISION).append(count - shown).append(" more")
    }
    out.append(close)
}

/**
 * A child is shown one level deep and no further.
 *
 * A container child is reduced to its shape and count without a single value byte being read — that
 * is what makes the outline's cost independent of what is underneath it, and it is also why this
 * does not recurse and therefore has no depth guard to fail.
 */
private fun appendChildSummary(child: Variant, out: StringBuilder) {
    when (child.basicType) {
        VariantBasicType.OBJECT -> out.appendElidedContainer('{', '}', child.childCount)
        VariantBasicType.ARRAY -> out.appendElidedContainer('[', ']', child.childCount)
        VariantBasicType.PRIMITIVE, VariantBasicType.SHORT_STRING -> appendScalarSummary(child, out)
    }
}

private fun StringBuilder.appendElidedContainer(open: Char, close: Char, count: Int) {
    append(open)
    if (count > 0) append(ELISION).append(count)
    append(close)
}

/**
 * Shown through the *same* renderer a document uses, so a value and a summary of it cannot spell
 * one scalar two ways — or described by size when showing it would mean decoding more than the
 * result could hold. See [SUMMARY_VALUE_BYTES].
 */
private fun appendScalarSummary(scalar: Variant, out: StringBuilder) {
    val bytes = scalar.byteSize
    if (bytes > SUMMARY_VALUE_BYTES) {
        out.append(ELISION).append(bytes).append(" bytes")
    } else {
        writeJsonScalar(scalar, out, SUMMARY_VALUE_LIMIT)
    }
}

/** How an unreadable value describes itself. Shared by [Variant.toString] and [toSummaryString]. */
internal fun unreadable(offset: Long, failure: Throwable): String =
    "Variant(offset=$offset, unreadable: ${failure.message})"
