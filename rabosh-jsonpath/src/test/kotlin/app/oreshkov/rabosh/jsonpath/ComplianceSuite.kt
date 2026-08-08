package app.oreshkov.rabosh.jsonpath

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The JSONPath Compliance Test Suite, loaded from the bytes vendored beside this file.
 *
 * Read `src/test/resources/jsonpath/README.md` before changing anything here: it records the upstream
 * commit, the licence the fixtures travel under, and why the same two files also appear under
 * `rabosh-variant`.
 *
 * `kotlinx-serialization-json` reads the fixtures, in the role it already has in this repository —
 * an oracle sharing no implementation with the code under test.
 */
internal object ComplianceSuite {

    /** The ten `tests/` files, at the paths the suite gives them. */
    val FILES: List<String> = listOf(
        "basic.json",
        "filter.json",
        "index_selector.json",
        "name_selector.json",
        "slice_selector.json",
        "functions/count.json",
        "functions/length.json",
        "functions/match.json",
        "functions/search.json",
        "functions/value.json",
        "whitespace/filter.json",
        "whitespace/functions.json",
        "whitespace/operators.json",
        "whitespace/selectors.json",
        "whitespace/slice.json",
    )

    /** Every case in the suite, in file order. */
    val cases: List<Case> by lazy { FILES.flatMap(::load) }

    private fun load(file: String): List<Case> {
        val text = checkNotNull(javaClass.getResourceAsStream("/jsonpath/$file")) {
            "missing vendored fixture $file; see the README beside it"
        }.use { it.readBytes().decodeToString() }

        return Json.parseToJsonElement(text).jsonObject.getValue("tests").jsonArray
            .map { Case(file, it.jsonObject) }
    }

    /**
     * One case.
     *
     * The suite writes a deterministic case as `result`/`result_paths` and one whose answer depends
     * on object member ordering as `results`/`results_paths` — a list of the permitted nodelists.
     * Both arrive here as a list of alternatives, with [deterministic] saying which shape it was, so
     * that a caller compares one in order and the other as a set without branching on field names.
     */
    internal class Case(val file: String, private val test: JsonObject) {

        val name: String get() = test.getValue("name").jsonPrimitive.content

        val selector: String get() = test.getValue("selector").jsonPrimitive.content

        val isInvalid: Boolean get() = "invalid_selector" in test

        val tags: List<String> get() = test["tags"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

        /** `match` and `search` are defined over RFC 9485 I-Regexp; this build has no matcher. */
        val needsRegex: Boolean get() = "match" in tags || "search" in tags

        /** The document, as the JSON text the suite wrote it as. */
        val document: String? get() = test["document"]?.toString()

        val deterministic: Boolean get() = "results" !in test

        /** The permitted nodelists, each as the JSON text of its values. */
        val results: List<List<String>>
            get() = test["result"]?.let { listOf(it.jsonArray.map(Any::toString)) }
                ?: test.getValue("results").jsonArray.map { alternative ->
                    alternative.jsonArray.map(Any::toString)
                }

        /** The Normalized Paths of those nodelists, aligned with [results]. */
        val resultPaths: List<List<String>>
            get() = test["result_paths"]?.let { listOf(it.jsonArray.map { path -> path.jsonPrimitive.content }) }
                ?: test.getValue("results_paths").jsonArray.map { alternative ->
                    alternative.jsonArray.map { path -> path.jsonPrimitive.content }
                }

        /**
         * The `result_paths` entries, and deliberately **not** `results_paths`.
         *
         * The round-trip assertion counts these, and the count is one of the corpus facts pinned
         * before any case runs. A case whose answer depends on member ordering carries the plural
         * spelling and is checked by the evaluation test instead, where the alternatives mean
         * something; folding it in here would make the count drift with a detail of the suite's
         * bookkeeping rather than with its content.
         */
        val declaredResultPaths: List<String>
            get() = test["result_paths"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

        val hasResults: Boolean get() = "result" in test || "results" in test

        override fun toString(): String = "$file '$name'"
    }
}
