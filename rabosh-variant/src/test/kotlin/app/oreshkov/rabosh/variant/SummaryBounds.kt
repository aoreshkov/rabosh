package app.oreshkov.rabosh.variant

/**
 * The closed form of an outline's length, derived from the contract rather than remembered.
 *
 * Per shown child: a name of at most [SUMMARY_VALUE_LIMIT] characters each escaping to at most
 * six (`\u00xx`), plus two quotes and an elision; the same again for a value, whose widest
 * spelling is a string under the byte gate; plus a colon and a separator. Then the brackets and
 * the `…N more` tail, at most an `Int`'s ten digits wide.
 *
 * **The nested outline is the same arithmetic applied [depth] times**, which is the point of writing
 * it as a fold rather than as a second formula: one expanded level costs a widest *child* where a
 * one-level outline costs a widest *scalar*, and a level below the last is a scalar or an elided
 * `{…N}`, which is smaller. That the recurrence at `depth = 1` reduces to the expression this
 * function used to be is checked in `VariantSummaryTest` rather than asserted here.
 *
 * Shared by `VariantSummaryTest` and `VariantNodeTest`, deliberately: a node's summary *is* a
 * value's summary with a location in front of it, so two copies of this arithmetic would be two
 * definitions of a bound the two are supposed to have in common.
 */
internal fun maxJsonSummaryLength(limit: Int, depth: Int = 1): Int {
    val widestScalar = 6 * SUMMARY_VALUE_LIMIT + 3
    var length = widestScalar
    repeat(depth) { length = 2 + limit * (widestScalar + 1 + length + 1) + 24 }
    return length
}
