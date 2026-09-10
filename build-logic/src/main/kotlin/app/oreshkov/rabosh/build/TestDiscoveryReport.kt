package app.oreshkov.rabosh.build

/**
 * Whether a `test` task that reported success actually ran any tests.
 *
 * Ten modules, one `test` task each, and a green build says nothing about how many tests were in it.
 * A tag excluded by a typo, a filter widened by accident, a `useJUnitPlatform` block that stops
 * selecting anything — every one of those produces a `test` task that succeeds having executed
 * **zero** tests, and nothing downstream notices, because there is no per-module count anywhere and
 * `ci.yml` uploads the reports only on failure.
 *
 * That is the same defect [BenchmarkRunReport] exists to remove, one task over: *a check that
 * quietly becomes a no-op is the thing being fixed*. The argument was made for `mainBenchmark` and
 * never for `test`, which is the task where it matters more — the benchmarks smoke, the tests are
 * the evidence.
 *
 * **What this asserts is that a module executed at least one test, never how many.** A remembered
 * count would be a number to update rather than a fact to check, and `.claude/rules/testing.md` is
 * explicit that an assertion against a remembered number is not one. The floor is what catches the
 * failure that is otherwise invisible; a suite shrinking from 400 tests to 3 is a code review's job.
 *
 * Everything here is plain Kotlin over strings, in `build-logic` for [BenchmarkRunReport]'s two
 * reasons: a script-level function referenced from a task action is a Gradle script object reference
 * the configuration cache cannot serialise, and a decision that fails the build is worth unit tests.
 */
object TestDiscoveryReport {

    /**
     * The start of a JUnit XML `<testsuite>` element.
     *
     * A regex rather than an XML parse, for the reason `BenchmarkRunReport` gives about JMH's JSON:
     * the only question asked of the document is what its root element claims, and a parser would
     * put a second reading of the format into the build. `[\s>]` after the name so `<testsuites`,
     * which some writers wrap a document in, cannot be mistaken for a suite of its own.
     */
    private val TEST_SUITE = Regex("<testsuite[\\s>]")

    /**
     * An integer XML attribute, e.g. `tests="12"`, searched from the suite element onwards.
     *
     * Not bounded at the element's closing `>`: XML permits an unescaped `>` inside an attribute
     * value, and a test whose display name contains one would end the scan early and make the
     * document read as unparseable. A JUnit result document has exactly one `<testsuite>`, and no
     * `<testcase>` carries either of the two names looked up here, so the first match after the
     * suite element is the suite's own.
     */
    private fun attribute(fromSuite: String, name: String): Int? =
        Regex("\\s$name\\s*=\\s*\"(\\d+)\"").find(fromSuite)?.groupValues?.get(1)?.toIntOrNull()

    /** What one result document says: how many tests it holds, and how many of those were skipped. */
    data class Suite(val tests: Int, val skipped: Int) {
        /** Tests that actually ran. A skipped test is a test that was not evidence of anything. */
        val executed: Int get() = tests - skipped
    }

    /**
     * Reads one JUnit XML result document, or `null` if it states no `<testsuite>` at all.
     *
     * `skipped` is optional in the schema and reads as zero when absent; `tests` is not, and a
     * document without it is `null` rather than an empty suite — under-reporting is the only failure
     * mode that matters here, exactly as in `ApiTierAudit`, because a check that silently reads a
     * file as "no tests" and a check that reads it as "zero tests" fail in opposite directions.
     */
    fun parseSuite(xml: String): Suite? {
        val suite = TEST_SUITE.find(xml)?.let { xml.substring(it.range.first) } ?: return null
        val tests = attribute(suite, "tests") ?: return null
        return Suite(tests = tests, skipped = attribute(suite, "skipped") ?: 0)
    }

    /**
     * Everything wrong with a finished `test` task, or an empty list.
     *
     * Pure: the caller does the IO, so every state this has to get right — an absent results
     * directory, one holding no documents, documents that hold only skipped tests, a document this
     * cannot read — is a unit test rather than an arrangement on disk.
     *
     * @param taskPath the task being checked, for the message
     * @param resultsDirPath where its JUnit XML was to be written, for the message
     * @param documents each result document's file name and content; empty when the directory holds
     *   none, `null` when the directory does not exist at all
     */
    fun problems(
        taskPath: String,
        resultsDirPath: String,
        documents: List<Pair<String, String>>?,
    ): List<String> {
        if (documents == null) {
            return listOf(
                "$taskPath: no test results at $resultsDirPath. A `test` task that ran writes one " +
                    "document per class there, so an absent directory means the task reported success " +
                    "having executed nothing.",
            )
        }
        if (documents.isEmpty()) {
            return listOf(
                "$taskPath: $resultsDirPath holds no test result documents, so this module executed no " +
                    "tests. Either a filter selects nothing — check `excludeTags` and any `--tests` — " +
                    "or the module's test sources stopped being test sources.",
            )
        }

        val problems = mutableListOf<String>()
        val unreadable = documents.filter { (_, xml) -> parseSuite(xml) == null }
        if (unreadable.isNotEmpty()) {
            // Not skipped: a document this cannot read must not be counted as zero tests, because
            // that is the direction in which the check passes for the wrong reason.
            problems += "$taskPath: ${unreadable.joinToString { it.first }} in $resultsDirPath " +
                "state no readable <testsuite>. Teach this check the new format rather than letting " +
                "a document it cannot read stand for a module that ran nothing."
        }

        val suites = documents.mapNotNull { (_, xml) -> parseSuite(xml) }
        val executed = suites.sumOf { it.executed }
        if (executed == 0 && unreadable.isEmpty()) {
            val total = suites.sumOf { it.tests }
            problems += "$taskPath: ${documents.size} result document(s) in $resultsDirPath, " +
                "$total test(s), and every one of them skipped. A module whose whole suite is " +
                "skipped is a module nothing is checking, and it reports success either way."
        }
        return problems
    }
}
