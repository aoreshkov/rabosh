package app.oreshkov.rabosh.variant

/**
 * The closed form of an outline's length, derived from the contract rather than remembered.
 *
 * Per shown child: a name of at most [SUMMARY_VALUE_LIMIT] characters each escaping to at most
 * six (`\u00xx`), plus two quotes and an elision; the same again for a value, whose widest
 * spelling is a string under the byte gate; plus a colon and a separator. Then the brackets and
 * the `…N more` tail, at most an `Int`'s ten digits wide.
 *
 * Shared by `VariantSummaryTest` and `VariantNodeTest`, deliberately: a node's summary *is* a
 * value's summary with a location in front of it, so two copies of this arithmetic would be two
 * definitions of a bound the two are supposed to have in common.
 */
internal fun maxJsonSummaryLength(limit: Int): Int = 2 + limit * (2 * (6 * SUMMARY_VALUE_LIMIT + 3) + 2) + 24
