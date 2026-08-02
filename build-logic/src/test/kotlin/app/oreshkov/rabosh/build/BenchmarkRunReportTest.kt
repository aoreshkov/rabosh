package app.oreshkov.rabosh.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Every way [BenchmarkRunReport] must fail a build, and the two ways it must not.
 *
 * The fixtures are real artefacts — a trimmed `META-INF/BenchmarkList`, one JMH result object per
 * class from an actual `smokeBenchmark` and `mainBenchmark` run, and the two runner configurations
 * kotlinx-benchmark wrote for them. Only a local JDK path was edited out.
 *
 * What this cannot cover is the wiring: that the task reads the right files and throws. That is
 * arranged by hand with `holdJmhLock`, because the reproduction the check exists for is a lock held
 * by another process.
 */
class BenchmarkRunReportTest {

    private val benchmarkList = resource("/BenchmarkList")
    private val smokeConfig = resource("/runner-config-smoke.txt")
    private val mainConfig = resource("/runner-config-main.txt")
    private val smokeReport = resource("/smoke-report.json")
    private val mainReport = resource("/main-report.json")

    // --- the two that must pass -------------------------------------------------------------

    @Test
    @DisplayName("a smoke run that measured all three included classes passes")
    fun smokeRunPasses() {
        assertEquals(emptyList<String>(), problemsFor(smokeConfig, smokeReport))
    }

    @Test
    @DisplayName("a main run with no include patterns must cover every class JMH generated")
    fun mainRunPasses() {
        assertEquals(emptyList<String>(), problemsFor(mainConfig, mainReport))
    }

    // --- the run that never happened --------------------------------------------------------

    @Test
    @DisplayName("no results file at all is a benchmark that did not run")
    fun absentReport() {
        val problems = problemsFor(smokeConfig, report = null)
        assertEquals(1, problems.size, problems.toString())
        assertTrue(problems.single().contains("produced no results"), problems.single())
        assertTrue(problems.single().contains("main.json"), problems.single())
    }

    @Test
    @DisplayName("a results file left by an earlier run does not count as this one")
    fun staleReport() {
        // The report path is fixed when the task is configured, so a configuration-cache hit reuses
        // it: without this rule a successful run would keep covering for every failed one after it.
        val problems = problemsFor(smokeConfig, smokeReport, reportIsFromThisRun = false)
        assertEquals(1, problems.size, problems.toString())
        assertTrue(problems.single().contains("older than the run"), problems.single())
    }

    @Test
    @DisplayName("a results file holding nothing is not a run")
    fun emptyReport() {
        val problems = problemsFor(smokeConfig, "[\n]\n")
        assertEquals(1, problems.size, problems.toString())
        assertTrue(problems.single().contains("no benchmark at all"), problems.single())
    }

    // --- the run that half happened ---------------------------------------------------------

    @Test
    @DisplayName("a class dropped from an otherwise successful run is named")
    fun classMissingFromResults() {
        // JMH's failOnError defaults to false, so a suite that throws in @Setup is simply absent from
        // the results and the run reports success. That is the quiet half of this check.
        val problems = problemsFor(
            smokeConfig,
            reportOf(
                "app.oreshkov.rabosh.bench.BitmapBenchmark.andCardinality",
                "app.oreshkov.rabosh.bench.VariantBenchmark.fieldByName",
            ),
        )
        assertEquals(1, problems.size, problems.toString())
        assertTrue(problems.single().contains("no result for 1 of the 3"), problems.single())
        assertTrue(problems.single().contains("IngestBenchmark"), problems.single())
        assertTrue(!problems.single().contains("BitmapBenchmark"), problems.single())
    }

    @Test
    @DisplayName("an include pattern that selects nothing is its own failure")
    fun includeMatchingNothing() {
        // Without this, a typo makes the expected set empty and an empty expectation is satisfied by
        // an empty run — the check would pass by measuring nothing against nothing.
        val problems = problemsFor(
            smokeConfig.replace("include:VariantBenchmark", "include:VaraintBenchmark"),
            reportOf(
                "app.oreshkov.rabosh.bench.BitmapBenchmark.andCardinality",
                "app.oreshkov.rabosh.bench.IngestBenchmark.delete",
            ),
        )
        assertEquals(1, problems.size, problems.toString())
        assertTrue(problems.single().contains("'VaraintBenchmark'"), problems.single())
        assertTrue(problems.single().contains("selects no benchmark"), problems.single())
    }

    // --- the check that cannot run must not pass --------------------------------------------

    @Test
    @DisplayName("an absent runner configuration fails rather than passing by default")
    fun absentRunnerConfig() {
        val problems = problemsFor(runnerConfig = null, report = smokeReport)
        assertEquals(1, problems.size, problems.toString())
        assertTrue(problems.single().contains("no runner configuration"), problems.single())
    }

    @Test
    @DisplayName("a runner configuration naming no report file fails")
    fun runnerConfigWithoutReportFile() {
        // kotlinx-benchmark creates this file empty at configuration time and fills it in at the start
        // of the run, so an empty one at the end means the run did not begin.
        val problems = problemsFor("", smokeReport)
        assertEquals(1, problems.size, problems.toString())
        assertTrue(problems.single().contains("names no reportFile"), problems.single())
    }

    @Test
    @DisplayName("a report format this check cannot read fails rather than being skipped")
    fun nonJsonReportFormat() {
        val problems = problemsFor(smokeConfig.replace("main.json", "main.csv"), smokeReport)
        assertEquals(1, problems.size, problems.toString())
        assertTrue(problems.single().contains("reads the JSON results file"), problems.single())
    }

    @Test
    @DisplayName("a missing benchmark index leaves nothing to check against, and fails")
    fun absentBenchmarkList() {
        val problems = problemsFor(smokeConfig, smokeReport, benchmarkList = null)
        assertEquals(1, problems.size, problems.toString())
        assertTrue(problems.single().contains("benchmark index"), problems.single())
    }

    @Test
    @DisplayName("a benchmark index line this build cannot read fails rather than shrinking the universe")
    fun unreadableBenchmarkList() {
        val problems = problemsFor(smokeConfig, smokeReport, benchmarkList = "JMH probably-not\n")
        assertEquals(1, problems.size, problems.toString())
        assertTrue(problems.single().contains("not in the expected form"), problems.single())
    }

    // --- the pieces --------------------------------------------------------------------------

    @Test
    @DisplayName("the runner configuration splits at the first colon, so a Windows path survives")
    fun parsesRunnerConfig() {
        val config = checkNotNull(BenchmarkRunReport.parseRunnerConfig(smokeConfig))
        assertEquals("smoke", config.configurationName)
        assertEquals(
            "C:\\build\\reports\\benchmarks\\smoke\\2026-07-31T16.05.57.9064946\\main.json",
            config.reportFile,
        )
        assertEquals(listOf("VariantBenchmark", "BitmapBenchmark", "IngestBenchmark"), config.includes)
        assertEquals(emptyList<String>(), config.excludes)
        assertNull(BenchmarkRunReport.parseRunnerConfig("name:main\ntraceFormat:text\n"))
    }

    @Test
    @DisplayName("the benchmark index reads as class.method")
    fun readsBenchmarkList() {
        val names = BenchmarkRunReport.benchmarkNames(benchmarkList)
        assertEquals(10, names.size)
        assertEquals("app.oreshkov.rabosh.bench.BitmapBenchmark.andCardinality", names.first())
        assertEquals(5, BenchmarkRunReport.classesOf(names).size)
    }

    @Test
    @DisplayName("selection matches as JMH matches: a regex found anywhere, and an exclude wins")
    fun selectsAsJmhDoes() {
        val names = BenchmarkRunReport.benchmarkNames(benchmarkList)
        assertEquals(names, BenchmarkRunReport.selectedNames(names, emptyList(), emptyList()))
        // A bare substring is a valid regex and JMH `find`s it — this is why "IngestBenchmark" works
        // as an include without being anchored or fully qualified.
        assertEquals(
            setOf("app.oreshkov.rabosh.bench.IngestBenchmark"),
            BenchmarkRunReport.classesOf(BenchmarkRunReport.selectedNames(names, listOf("IngestBenchmark"), emptyList())),
        )
        assertEquals(
            setOf("app.oreshkov.rabosh.bench.IngestBenchmark"),
            BenchmarkRunReport.classesOf(
                BenchmarkRunReport.selectedNames(names, listOf("Benchmark\\.(delete|parseAndPut)"), emptyList()),
            ),
        )
        assertEquals(
            emptyList<String>(),
            BenchmarkRunReport.selectedNames(names, listOf("IngestBenchmark"), listOf("Ingest")),
        )
    }

    @Test
    @DisplayName("an include that is not a regex is reported rather than thrown")
    fun invalidPattern() {
        val problems = problemsFor(smokeConfig.replace("include:BitmapBenchmark", "include:Bitmap["), smokeReport)
        assertEquals(1, problems.size, problems.toString())
        assertTrue(problems.single().contains("valid regular expressions"), problems.single())
    }

    // --- helpers -----------------------------------------------------------------------------

    private fun problemsFor(
        runnerConfig: String?,
        report: String?,
        benchmarkList: String? = this.benchmarkList,
        reportIsFromThisRun: Boolean = true,
    ): List<String> = BenchmarkRunReport.problems(
        taskName = "smokeBenchmark",
        runnerConfigPath = "/tmp/benchmarks1.txt",
        runnerConfigText = runnerConfig,
        benchmarkListPath = "/build/benchmarks/main/resources/META-INF/BenchmarkList",
        benchmarkListText = benchmarkList,
        reportText = report,
        reportIsFromThisRun = reportIsFromThisRun,
    )

    /** JMH's spelling of a result, which is the only part of the report this check reads. */
    private fun reportOf(vararg names: String): String =
        names.joinToString(",\n", prefix = "[\n", postfix = "\n]\n") {
            """    {
        "jmhVersion" : "1.37",
        "benchmark" : "$it",
        "mode" : "thrpt"
    }"""
        }

    private fun resource(name: String): String =
        checkNotNull(BenchmarkRunReportTest::class.java.getResource(name)) { "Missing test resource $name" }
            .readText()
}
