package app.oreshkov.rabosh.variant

// Ways of looking at a value whose size is the reason you cannot look at it properly.
//
// `VariantJson.kt` renders a value in full and its cost is the value's; everything here costs the
// same on a four-megabyte document as on a boolean. That is the whole contract, and it is why these
// live in their own file rather than beside a renderer whose KDoc promises the opposite.
//
// The nested outline keeps that contract and is worth stating carefully, because it looks like a
// weakening and is not: its cost is a function of the caller's `limit` and `depth` and of nothing
// else, so a four-megabyte document still costs what a boolean costs. What the second parameter buys
// is a *bigger constant*, chosen by the caller and exponential in it — never a cost that the value
// gets a say in.
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
 *   does for the same value. It cannot throw for depth: at one level there is nothing to recurse
 *   into. The overload taking a `depth` is the form that can be asked to go further.
 */
public fun Variant.toJsonSummaryString(limit: Int = DEFAULT_SUMMARY_LIMIT): String =
    buildString { appendJsonSummaryTo(this, limit) }

/** Appends [toJsonSummaryString] to [out], avoiding an intermediate `String`. */
public fun Variant.appendJsonSummaryTo(out: StringBuilder, limit: Int = DEFAULT_SUMMARY_LIMIT) {
    require(limit >= 0) { "a summary limit is not negative, was $limit" }
    appendSummary(this, out, limit, TOP_LEVEL)
}

/**
 * The first [limit] children of every level down to [depth], with everything below them elided.
 *
 * ```
 * limit = 3, depth = 3, on a twelve-field document:
 * {"id":42,"order":{"lines":[{…4},{…4},{…4},…2 more],"total":9.5},"tags":["a","b","c",…1021 more],…9 more}
 * ```
 *
 * Both elision spellings are in there and they say different things: `{…4}` is a container the walk
 * stopped *at*, reporting its own child count, and `…2 more` is what a level had *left over* after
 * showing [limit] of it. The first is where [depth] ran out and the second is where [limit] did.
 *
 * **This is [toJsonSummaryString] reaching further, and it is that function rather than a second
 * one that resembles it.** `depth = 1` *is* the top-level outline — the same walk, the same scalar
 * renderer, the same elision vocabulary — and the suite asserts the two agree for every document and
 * every limit rather than leaving it to be read off the code. So everything the one-level form
 * promises holds here unchanged: it is deliberately not JSON, an elision is always spelled `…`, a
 * container that shows nothing shows its own count, and unreadable bytes are reported rather than
 * elided. Only the reach differs.
 *
 * **The cost contract survives too, and it is worth being exact about what survives.** The bytes
 * read and the characters written are still a function of [limit], [depth] and [SUMMARY_VALUE_LIMIT]
 * alone, and still have no term in them for the value's size — which is the property this whole file
 * exists for, and the reason this is not simply [toJsonString] with a stopping rule. What changes is
 * that the function is **exponential in [depth]**: at most `limit + limit² + … + limit^depth` values
 * are shown, so the default limit at four levels is already some four thousand of them and at eight
 * is past anything a reader wanted.
 *
 * That is why [depth] has no default and is not going to be given one. A caller who wants to see
 * further has to say how much further, and the number they write is the price they are agreeing to;
 * a default here would be a cost decision taken on their behalf, in the one place where the cost is
 * the entire subject. `depth = 1` needs no such decision, which is exactly why it is spelled as its
 * own function rather than as this one's default.
 *
 * @param limit children to show at *each* level, not only at the top. `0` shows every level's count
 *   and none of its children, which collapses the whole outline to the root's `{…N more}`.
 * @param depth levels to expand before eliding a container by shape and count. `1` is
 *   [toJsonSummaryString]. The ceiling is [DEFAULT_MAX_JSON_DEPTH] because this recurses and that is
 *   the depth [toJsonString] already refuses to descend past — one number for how deep this module
 *   will walk a document, not a second one that could disagree with it. The document's own nesting
 *   never enters into it: the walk stops at [depth] whether the value bottoms out above it or runs
 *   far below.
 * @throws IllegalArgumentException if [limit] is negative, or if [depth] is not in
 *   `1..`[DEFAULT_MAX_JSON_DEPTH].
 * @throws VariantFormatException if the bytes do not decode, in any level it reached.
 * @throws JsonWriteException for a non-finite `double` among the values shown.
 */
public fun Variant.toJsonSummaryString(limit: Int = DEFAULT_SUMMARY_LIMIT, depth: Int): String =
    buildString { appendJsonSummaryTo(this, limit, depth) }

/** Appends the nested [toJsonSummaryString] to [out], avoiding an intermediate `String`. */
public fun Variant.appendJsonSummaryTo(
    out: StringBuilder,
    limit: Int = DEFAULT_SUMMARY_LIMIT,
    depth: Int,
) {
    require(limit >= 0) { "a summary limit is not negative, was $limit" }
    require(depth in TOP_LEVEL..DEFAULT_MAX_JSON_DEPTH) {
        "a summary depth is $TOP_LEVEL..$DEFAULT_MAX_JSON_DEPTH, was $depth"
    }
    appendSummary(this, out, limit, depth)
}

/** Levels a summary expands when it expands only the value it was handed. */
private const val TOP_LEVEL: Int = 1

/**
 * One level of the outline, with [depth] more of them left to expand.
 *
 * `depth == 0` is where the walk stops, and it is a decision rather than a case that fell through:
 * a container reached there is reduced to its shape and its own child count without a single value
 * byte being read, which is what keeps the cost independent of whatever is underneath. Every deeper
 * form is that same stop taken later, which is the sense in which there is one outline here and not
 * two — and it is why the recursion is bounded by the argument alone and cannot be led downwards by
 * the document.
 */
private fun appendSummary(variant: Variant, out: StringBuilder, limit: Int, depth: Int) {
    when (variant.basicType) {
        VariantBasicType.OBJECT ->
            if (depth == 0) out.appendElidedContainer('{', '}', variant.childCount)
            else variant.appendContainerSummary(out, limit, depth, '{', '}')

        VariantBasicType.ARRAY ->
            if (depth == 0) out.appendElidedContainer('[', ']', variant.childCount)
            else variant.appendContainerSummary(out, limit, depth, '[', ']')

        VariantBasicType.PRIMITIVE, VariantBasicType.SHORT_STRING -> appendScalarSummary(variant, out)
    }
}

private fun Variant.appendContainerSummary(
    out: StringBuilder,
    limit: Int,
    depth: Int,
    open: Char,
    close: Char,
) {
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
        appendSummary(if (isObject) fieldValue(index) else element(index), out, limit, depth - 1)
    }
    if (shown < count) {
        if (shown > 0) out.append(',')
        out.append(ELISION).append(count - shown).append(" more")
    }
    out.append(close)
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
