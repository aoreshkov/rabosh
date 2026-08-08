package app.oreshkov.rabosh.jsonpath

// RFC 9535 §2.4: the function extensions, their declared types, and which of them this build can
// evaluate.
//
// The IANA *Function Extensions* registry still holds exactly these five and nothing has been
// registered since 2024-04-19; the RFC instructs the designated experts to be frugal. So the list is
// a `when` over an enum rather than a registry a caller can add to: a sixth function is a change to
// this file, made once, against a specification, and an extension point nobody asked for would be a
// second definition of what a query means — the thing §4(d) of the plan concedes must not happen
// twice in one repository.
//
// **`match` and `search` are declared and not evaluated**, and the split is deliberate. Declaring
// them is what lets `length(@.a, @.b)` and `match(@.a)` be rejected as *wrong arity* rather than as
// unknown names, which is 6 of the 247 invalid cases in the compliance suite. Evaluating them needs
// an I-Regexp matcher — a linear-time one, because a filter runs once per document over a corpus and
// `java.util.regex` backtracks — and that is its own piece of work. Until it exists, a query naming
// one is refused by `JsonPathQuery.compile` with a message that says so, rather than compiling into
// something that would answer.

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
 * @property evaluable whether this build can evaluate it. See the note at the top of this file; a
 *   function with `false` here is rejected at compile time, so nothing downstream may reach one.
 */
internal enum class JsonPathFunction(
    val spelling: String,
    val parameters: List<JsonPathType>,
    val result: JsonPathType,
    val evaluable: Boolean,
) {
    LENGTH("length", listOf(JsonPathType.VALUE), JsonPathType.VALUE, evaluable = true),
    COUNT("count", listOf(JsonPathType.NODES), JsonPathType.VALUE, evaluable = true),
    MATCH("match", listOf(JsonPathType.VALUE, JsonPathType.VALUE), JsonPathType.LOGICAL, evaluable = false),
    SEARCH("search", listOf(JsonPathType.VALUE, JsonPathType.VALUE), JsonPathType.LOGICAL, evaluable = false),
    VALUE("value", listOf(JsonPathType.NODES), JsonPathType.VALUE, evaluable = true),

    ;

    companion object {
        private val BY_SPELLING: Map<String, JsonPathFunction> = entries.associateBy { it.spelling }

        /** The function called [spelling], or `null` if the registry does not hold one. */
        fun ofSpelling(spelling: String): JsonPathFunction? = BY_SPELLING[spelling]
    }
}
