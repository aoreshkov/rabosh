package app.oreshkov.rabosh.variant

/**
 * A value together with where it is — RFC 9535's *node*: "the pair of a value along with its
 * location within the query argument".
 *
 * ```
 * {"items":[{"sku":"a"},{"sku":"b"}]}
 *
 *   $['items'][0]['sku']   "a"
 *   $['items'][1]['sku']   "b"
 * ```
 *
 * **The pairing is the whole point.** Everything the engine hands back until now is one half or the
 * other: a key, a projected value, a `Variant`. A caller that narrowed to a document with
 * `$.items[*].sku` and then wanted to know *which* element matched had nowhere to put the answer, so
 * it wrote its own walk — and the walk is where the mistakes are. This type is what a walk can
 * return.
 *
 * **A view, not a copy**, on the same terms as a projected row: [value] borrows the bytes it was
 * built over, so it is valid exactly as long as whatever maps them — a snapshot, a document a caller
 * is holding — is open. Anything kept beyond that must be copied, with [Variant.toByteArray].
 * [location], by contrast, is an ordinary value and outlives everything.
 *
 * **No `equals`, deliberately, and this is not a data class.** A [VariantPath] compares
 * structurally and a [Variant] does not compare at all — it is a reference into a segment — so a
 * generated `equals` would read as value equality while answering half of one. Compare the halves:
 * the locations directly, and the values through whichever of [Variant.toByteArray] or
 * [Variant.toJsonString] the question actually means.
 */
public class VariantNode(
    /** Where this value is. Exactly one location, which is what makes [Variant.select] its inverse. */
    public val location: VariantPath,
    /** The value there. A borrowed view; see the class documentation. */
    public val value: Variant,
) {
    /**
     * Where it is and roughly what it holds: `$['items'][0] {"sku":"a",…3 more}`.
     *
     * The location in [VariantPath.toNormalizedPath]'s form — the spelling another reader can parse —
     * followed by a space and [Variant.toJsonSummaryString]'s outline of the value.
     *
     * The cost contract is that file's, with the one honest addition: the result's length and the
     * bytes read are bounded by a function of [limit], [SUMMARY_VALUE_LIMIT] **and the length of
     * [location]**. A path is not bounded by anything, so claiming otherwise would be claiming a
     * bound this cannot hold. What it does hold is the half that matters: the *value* costs the same
     * whether it is a boolean or four megabytes.
     *
     * @param limit top-level children of [value] to show.
     * @throws IllegalArgumentException if [limit] is negative, or if [location] holds a field name
     *   with an unpaired surrogate — see [VariantPath.toNormalizedPath].
     * @throws VariantFormatException if the value's bytes do not decode. A summary reports
     *   unreadable data rather than eliding it; [toString] is the form that cannot throw.
     */
    public fun toJsonSummaryString(limit: Int = DEFAULT_SUMMARY_LIMIT): String = buildString {
        append(location.toNormalizedPath())
        append(' ')
        value.appendJsonSummaryTo(this, limit)
    }

    /**
     * One line, never throwing: `$.items[0] Variant(object, children=1, bytes=12)`.
     *
     * The location is spelled the engine's way here rather than the standard's, for one reason —
     * [VariantPath.toString] cannot throw and [VariantPath.toNormalizedPath] can, and a `toString`
     * that can fail is a `toString` that fails inside a debugger. [toJsonSummaryString] is the one
     * that renders a location for somebody else to read.
     */
    override fun toString(): String = "$location ${value.toSummaryString()}"
}
