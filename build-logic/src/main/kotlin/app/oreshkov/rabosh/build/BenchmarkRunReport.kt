package app.oreshkov.rabosh.build

import java.io.File

/**
 * Whether a benchmark task actually measured what its configuration selected.
 *
 * kotlinx-benchmark's JVM runner wraps the whole JMH run in a `try`/`catch`, prints the stack trace
 * and returns normally — so a JMH failure exits **zero** and Gradle prints `BUILD SUCCESSFUL` over a
 * benchmark that never started. A stale `jmh.lock`, a fork that will not launch and a missing
 * `--enable-native-access` all look identical from the outside: no results, green build. And because
 * JMH's `failOnError` defaults to false, a suite that throws in `@Setup` is dropped from the results
 * while the run still reports success — a report file that exists and is quietly short of a class.
 *
 * This is the same defect as an assertion whose fault never fired: the check passes because its
 * subject never ran. So the task asserts the **artefact**, not the log — parsing JMH's output for an
 * exception name would be a string match against a message that is not ours to depend on, while
 * "did it write what a run writes" is a fact.
 *
 * Everything here is plain Kotlin over strings. It lives in `build-logic` rather than inline in
 * `rabosh-bench/build.gradle.kts` for two reasons: a script-level function referenced from a task
 * action is a Gradle script object reference, which the configuration cache cannot serialise, and a
 * decision that decides whether the build fails is worth unit tests of its own.
 *
 * **This gates that a benchmark ran, never how fast it was.** Benchmark numbers from a shared runner
 * are not a regression gate — that would be a test of the runner.
 */
object BenchmarkRunReport {

    /**
     * The `"benchmark" : "<class>.<method>"` field of a JMH JSON result.
     *
     * A regex rather than a JSON parse, deliberately: the only question asked of the report is which
     * names it states, and answering it this way keeps a JSON parser off the buildscript classpath of
     * a project whose whole point is that it has no dependencies.
     */
    private val REPORTED_BENCHMARK = Regex("\"benchmark\"\\s*:\\s*\"([^\"]+)\"")

    /**
     * The part of the runner configuration this check needs: what was selected, and where the results
     * were to be written.
     */
    data class RunnerConfig(
        val configurationName: String,
        val reportFile: String,
        val includes: List<String>,
        val excludes: List<String>,
    )

    /**
     * Reads the `key:value` file kotlinx-benchmark writes for its runner, splitting at the *first*
     * colon exactly as [kotlinx.benchmark.RunnerConfiguration] does — a Windows `reportFile` starts
     * `C:\`, so splitting anywhere else would truncate the path.
     *
     * Returns `null` when the file does not name a report or a configuration, which means the check
     * cannot run rather than that it passed.
     */
    fun parseRunnerConfig(text: String): RunnerConfig? {
        val values = text.lineSequence()
            .filter { it.contains(':') }
            .groupBy({ it.substringBefore(':') }, { it.substringAfter(':', "") })
        val reportFile = values["reportFile"]?.singleOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val configurationName = values["configurationName"]?.singleOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return RunnerConfig(
            configurationName = configurationName,
            reportFile = reportFile,
            includes = values["include"].orEmpty(),
            excludes = values["exclude"].orEmpty(),
        )
    }

    /**
     * Every `<class>.<method>` JMH generated a harness for, read from its own index —
     * `META-INF/BenchmarkList` in the generated resources.
     *
     * That file is the universe the include patterns select from, which is what lets the expectation
     * come from *what the configuration asked for* rather than from a remembered count of last run's
     * rows. Each line is `JMH S <n> <class> S <n> <generated> S <n> <method> …`; class and method
     * names cannot contain a space, so their positions are fixed.
     *
     * Throws rather than skipping a line it cannot read: a format this no longer understands must
     * fail the build, not quietly shrink the universe until every class is accounted for.
     */
    fun benchmarkNames(benchmarkList: String): List<String> =
        benchmarkList.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val tokens = line.split(' ').filter { it.isNotEmpty() }
                check(tokens.size >= 10 && tokens[0] == "JMH" && tokens[1] == "S" && tokens[4] == "S" && tokens[7] == "S") {
                    "Cannot read JMH's benchmark index; this line is not in the expected form: $line"
                }
                "${tokens[3]}.${tokens[9]}"
            }
            .toList()

    /**
     * The benchmarks a configuration selects, matched as JMH matches them: each pattern is a regex
     * *found* anywhere in the full `<class>.<method>` name, no includes means everything, and an
     * exclude wins.
     *
     * Reproduced rather than approximated — a different notion of "selected" would be a second
     * definition of what the configuration asked for, and the two would only have to disagree once.
     */
    fun selectedNames(names: List<String>, includes: List<String>, excludes: List<String>): List<String> =
        names.filter { name ->
            (includes.isEmpty() || includes.any { it.toRegex().containsMatchIn(name) }) &&
                excludes.none { it.toRegex().containsMatchIn(name) }
        }

    /** The classes of [names], which is the granularity a missing result is reported at. */
    fun classesOf(names: Collection<String>): Set<String> =
        names.mapTo(sortedSetOf()) { it.substringBeforeLast('.') }

    /** Every benchmark the JSON report states a result for. */
    fun reportedNames(reportJson: String): Set<String> =
        REPORTED_BENCHMARK.findAll(reportJson).mapTo(mutableSetOf()) { it.groupValues[1] }

    /**
     * Everything wrong with a finished benchmark run, or an empty list.
     *
     * Pure: the caller does the IO, so every state this has to get right — an absent report, one left
     * behind by an earlier run, an empty one, one short of a class — is a unit test rather than an
     * arrangement.
     *
     * @param runnerConfigText the runner configuration, or `null` if the file does not exist
     * @param benchmarkListText JMH's generated index, or `null` if the file does not exist
     * @param reportText the results file's content, or `null` if the file does not exist
     * @param reportIsFromThisRun whether the results file is at least as new as the runner
     *   configuration, which kotlinx-benchmark rewrites at the start of every run
     */
    fun problems(
        taskName: String,
        runnerConfigPath: String,
        runnerConfigText: String?,
        benchmarkListPath: String,
        benchmarkListText: String?,
        reportText: String?,
        reportIsFromThisRun: Boolean,
    ): List<String> {
        if (runnerConfigText == null) {
            return listOf(
                "$taskName: no runner configuration at $runnerConfigPath, so there is no saying what the " +
                    "run was asked to produce. This check reads the file kotlinx-benchmark hands its " +
                    "runner; if that has changed, teach the check the new contract rather than dropping it.",
            )
        }
        val config = parseRunnerConfig(runnerConfigText)
            ?: return listOf(
                "$taskName: the runner configuration at $runnerConfigPath names no reportFile or no " +
                    "configurationName, so this check cannot tell whether the benchmark ran. It must not " +
                    "pass by default; teach it the new contract rather than dropping it.",
            )
        if (!config.reportFile.endsWith(".json", ignoreCase = true)) {
            return listOf(
                "$taskName: this check reads the JSON results file, and configuration " +
                    "'${config.configurationName}' writes ${config.reportFile}. Leave reportFormat at its " +
                    "\"json\" default, or teach the check to read the other format.",
            )
        }

        val problems = mutableListOf<String>()

        val reported: Set<String>? = when {
            reportText == null -> {
                problems += "$taskName: configuration '${config.configurationName}' produced no results. It was " +
                    "to write ${config.reportFile} and that file does not exist, so JMH did not finish — " +
                    "kotlinx-benchmark reports a JMH failure without failing the build, which is exactly " +
                    "what a benchmark that never started looks like from here."
                null
            }

            !reportIsFromThisRun -> {
                problems += "$taskName: the results at ${config.reportFile} are older than the run that was " +
                    "about to write them, so they were left by an earlier run and this one produced " +
                    "nothing. The report path is fixed when the task is configured, so a configuration-cache " +
                    "hit reuses it — which is what would otherwise let a stale file stand in for a run."
                null
            }

            // Empty is reported once, as itself: listing every selected class underneath it would be
            // the same fact told twice, and the first telling is the one that says what happened.
            else -> reportedNames(reportText).takeIf { it.isNotEmpty() } ?: run {
                problems += "$taskName: the results at ${config.reportFile} hold no benchmark at all. " +
                    "A run that measured nothing must not pass."
                null
            }
        }

        val names = when {
            benchmarkListText.isNullOrBlank() -> {
                problems += "$taskName: JMH's benchmark index at $benchmarkListPath is missing or empty, so " +
                    "there is nothing to check the results against. It is generated from the benchmark " +
                    "sources; an empty one means none were found."
                null
            }

            else -> runCatching { benchmarkNames(benchmarkListText) }.getOrElse { failure ->
                problems += "$taskName: ${failure.message}"
                null
            }
        }

        if (names != null) {
            val badPatterns = (config.includes + config.excludes).filter { runCatching { it.toRegex() }.isFailure }
            if (badPatterns.isNotEmpty()) {
                problems += "$taskName: configuration '${config.configurationName}' has patterns that are not " +
                    "valid regular expressions: ${badPatterns.joinToString()}"
            } else {
                // A pattern that matches nothing makes the expected set empty, and an empty expectation is
                // satisfied by an empty run. So a typo in an `include` has to be its own failure.
                val emptyIncludes = config.includes.filter { pattern ->
                    names.none { pattern.toRegex().containsMatchIn(it) }
                }
                if (emptyIncludes.isNotEmpty()) {
                    problems += "$taskName: configuration '${config.configurationName}' includes " +
                        "${emptyIncludes.joinToString { "'$it'" }}, which selects no benchmark at all. " +
                        "An include that matches nothing makes this check vacuous."
                }

                val selected = classesOf(selectedNames(names, config.includes, config.excludes))
                if (selected.isEmpty()) {
                    problems += "$taskName: configuration '${config.configurationName}' selects no benchmark " +
                        "from the ${names.size} JMH generated a harness for."
                } else if (reported != null) {
                    val missing = selected - classesOf(reported)
                    if (missing.isNotEmpty()) {
                        problems += "$taskName: no result for ${missing.size} of the ${selected.size} benchmark " +
                            "classes configuration '${config.configurationName}' selected:" +
                            missing.joinToString("") { "\n    $it" } +
                            "\n  ${config.reportFile} names ${reported.size} benchmark(s). A class that fails " +
                            "in @Setup is dropped from the results while the run still reports success."
                    }
                }
            }
        }

        return problems
    }

    /**
     * [problems], with the file reading attached. Called from the benchmark task's own `doLast`, so a
     * task that did not measure fails where it ran rather than somewhere downstream.
     *
     * @param runnerConfig the file kotlinx-benchmark passes its runner as the sole argument; it is
     *   rewritten at the start of every run, which is what makes it the evidence that the results
     *   beside it are this run's and not an earlier one's
     */
    fun verify(taskName: String, runnerConfig: File, benchmarkList: File): List<String> {
        val runnerConfigText = runnerConfig.takeIf { it.isFile }?.readText()
        val report = runnerConfigText?.let { parseRunnerConfig(it) }?.let { File(it.reportFile) }
        return problems(
            taskName = taskName,
            runnerConfigPath = runnerConfig.path,
            runnerConfigText = runnerConfigText,
            benchmarkListPath = benchmarkList.path,
            benchmarkListText = benchmarkList.takeIf { it.isFile }?.readText(),
            reportText = report?.takeIf { it.isFile }?.readText(),
            reportIsFromThisRun = report != null && report.isFile &&
                report.lastModified() >= runnerConfig.lastModified(),
        )
    }
}
