package app.oreshkov.rabosh.jsonpath

// RFC 9535 §2.4: the function extensions and their declared types.
//
// The IANA *Function Extensions* registry still holds exactly these five and nothing has been
// registered since 2024-04-19; the RFC instructs the designated experts to be frugal. So the list is
// a `when` over an enum rather than a registry a caller can add to: a sixth function is a change to
// this file, made once, against a specification, and an extension point nobody asked for would be a
// second definition of what a query means — the thing §4(d) of the plan concedes must not happen
// twice in one repository.
//
// **All five are evaluated.** `match` and `search` were declared and refused for one release, which
// is what let `match(@.a)` be rejected as *wrong arity* rather than as an unknown name while the
// I-Regexp matcher was missing. The seam was good and it is gone: the `evaluable` flag it needed went
// with it, because a flag with one value is a claim nothing can check. What decides the two now is
// [JsonPathType.LOGICAL] — they are the only functions declared to answer one, and that is a fact
// about RFC 9535 rather than about this build.

/** RFC 9535 §2.4.1's three declared types. */
internal enum class JsonPathType {
    /** A JSON value, or `Nothing`. */
    VALUE,

    /** `LogicalTrue` or `LogicalFalse`. */
    LOGICAL,

    /** A nodelist. */
    NODES,
}

/**
 * The five registered function extensions.
 *
 * @property spelling the name as it is written in a query.
 * @property parameters the declared parameter types, in order — the arity is this list's size.
 * @property result the declared result type.
 */
internal enum class JsonPathFunction(
    val spelling: String,
    val parameters: List<JsonPathType>,
    val result: JsonPathType,
) {
    LENGTH("length", listOf(JsonPathType.VALUE), JsonPathType.VALUE),
    COUNT("count", listOf(JsonPathType.NODES), JsonPathType.VALUE),
    MATCH("match", listOf(JsonPathType.VALUE, JsonPathType.VALUE), JsonPathType.LOGICAL),
    SEARCH("search", listOf(JsonPathType.VALUE, JsonPathType.VALUE), JsonPathType.LOGICAL),
    VALUE("value", listOf(JsonPathType.NODES), JsonPathType.VALUE),

    ;

    /** Whether this function's second argument is an I-Regexp. True of `match` and `search` only. */
    val takesPattern: Boolean get() = this == MATCH || this == SEARCH

    companion object {
        private val BY_SPELLING: Map<String, JsonPathFunction> = entries.associateBy { it.spelling }

        /** The function called [spelling], or `null` if the registry does not hold one. */
        fun ofSpelling(spelling: String): JsonPathFunction? = BY_SPELLING[spelling]
    }
}
