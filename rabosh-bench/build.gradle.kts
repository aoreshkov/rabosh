import app.oreshkov.rabosh.build.BenchmarkRunReport
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

plugins {
    // Base, not `rabosh.kotlin-library`: benchmarks are not published.
    id("rabosh.kotlin-base")
    alias(libs.plugins.benchmark)
}

description = "JMH benchmark suites: ingest throughput, point-get latency, scan throughput, amplification."

/*
 * A benchmark measures the engine, not the facade: `BitmapBenchmark` times container transitions and
 * `AmplificationMain` opens a `DocumentStore` directly, both of which are outside the stable core on
 * purpose. Same line as `rabosh.kotlin-library` gives the published modules, and deliberately not
 * given to `rabosh-samples`, which is the one module that has to compile as a consumer does.
 */
kotlin {
    compilerOptions {
        optIn.add("app.oreshkov.rabosh.RaboshExperimental")
    }
}

dependencies {
    implementation(project(":rabosh-api"))
    implementation(libs.kotlinx.benchmark.runtime)
    implementation(libs.kotlinx.serialization.json)
}

benchmark {
    configurations {
        // `./gradlew :rabosh-bench:mainBenchmark` — the real thing, on a quiet machine.
        named("main") {
            warmups = 3
            iterations = 5
            iterationTime = 2
            iterationTimeUnit = "s"
        }

        // `./gradlew :rabosh-bench:smokeBenchmark` — what CI runs. One warmup and one short
        // iteration: it proves the suites still compile, start, and measure the thing they name.
        // The numbers are not comparable and are not meant to be — a benchmark used as a regression
        // gate on a shared runner is a test of the runner.
        //
        // `QueryBenchmark` and `ReadBenchmark` used to be excluded here, because their `@Setup` builds
        // 200 000 documents and at smoke size the setup *is* the run. The exclusion was the wrong fix
        // and it left the sentence above false of two of the five suites: neither had ever *started* in
        // CI, so phase 16's assertion — which requires a result per class the configuration selected —
        // was being satisfied over a universe that quietly omitted them. A benchmark nothing selects is
        // the same hole as a benchmark that did not run, one step further out. So the corpus is a
        // parameter now and this shrinks it, rather than dropping the suites.
        register("smoke") {
            warmups = 1
            iterations = 1
            iterationTime = 200
            iterationTimeUnit = "ms"
            param("documentCount", 2_000)
            include("VariantBenchmark")
            include("BitmapBenchmark")
            include("IngestBenchmark")
            include("QueryBenchmark")
            include("ReadBenchmark")
        }
    }

    targets {
        register("main")
    }
}

/*
 * The tasks that actually run JMH, which are **not** the ones you type.
 *
 * kotlinx-benchmark makes two tasks per (target, configuration): a `JavaExec` named
 * `<target><Configuration>Benchmark`, and a lifecycle task that depends on it — `benchmark` for the
 * `main` configuration and `smokeBenchmark` for `smoke`. The names collide confusingly, because the
 * exec task for the `main` configuration is itself called `mainBenchmark`: matching on the names a
 * developer types therefore attaches to the exec task for one configuration and to the *lifecycle*
 * task for the other. That is not a cosmetic difference. A `doFirst` on a lifecycle task runs after
 * the task it depends on — after the benchmark it was meant to prepare for — and a `doLast` there has
 * no `args` at all. The `jmh.lock` cleanup below spent a commit in exactly that state, doing nothing
 * for the smoke configuration it had been written for.
 *
 * So the names are derived from the plugin's own rule rather than typed, and the collection is
 * narrowed by type. Adding a configuration cannot forget it.
 */
val benchmarkExecTasks: Set<String> = benchmark.targets.names.flatMapTo(mutableSetOf()) { target ->
    benchmark.configurations.names.map { configuration ->
        val infix = if (configuration == "main") "" else configuration.replaceFirstChar { it.titlecase() }
        "$target${infix}Benchmark"
    }
}

/*
 * Attaching by name is only sound while the plugin still uses that name, and a name that matches
 * nothing attaches nothing — silently, which is the failure this whole file is now about. So the
 * derivation is checked rather than trusted. `names` on a filtered collection realises no task.
 */
afterEvaluate {
    val missing = benchmarkExecTasks - tasks.withType<JavaExec>().names
    check(missing.isEmpty()) {
        "kotlinx-benchmark did not create the JavaExec task(s) this build expects: " +
            "${missing.joinToString()}. The jmh.lock cleanup and the run assertion attach by name, so " +
            "a naming change has to fail here rather than quietly leave the benchmarks unchecked."
    }
}

tasks.withType<JavaExec>().matching { it.name in benchmarkExecTasks }.configureEach {
    /*
     * Clear a `jmh.lock` that no JMH instance is holding, before a benchmark starts.
     *
     * JMH takes an exclusive lock in the temp directory so that two runs cannot measure over each
     * other, and it does not always remove the file when it exits. A leftover one fails the *next* run
     * with "Another JMH instance might be running… exiting" — and JMH reports that as its own failure,
     * which never reaches Gradle's exit code, so the build prints BUILD SUCCESSFUL and produces no
     * results. That cost two runs in one session before it was understood.
     *
     * Staleness is decided by **trying to take the lock**, not by the file's age or its mere
     * existence. If a run really is in flight it holds this lock, the probe fails, and the file is
     * left exactly where it is so JMH can report the collision it exists to report. Deleting on sight
     * would defeat the lock; a timestamp heuristic would either delete a live one or keep a dead one
     * depending on how long the live run had been going, and `-Djmh.ignoreLock=true` is the same
     * mistake with a flag. The probe is also the release: closing the channel drops the lock, so the
     * file is unlocked before it is deleted rather than unlinked from under a handle we still hold.
     *
     * Written inline rather than as a shared function on purpose — a script-level function referenced
     * from a task action is a Gradle script object reference, which the configuration cache cannot
     * serialise. The `doLast` below needs the same property and gets it the other way, from a class in
     * `build-logic`, which is also what lets that one have tests.
     */
    doFirst {
        val lock = Path.of(System.getProperty("java.io.tmpdir"), "jmh.lock")
        if (Files.exists(lock)) {
            val probe = runCatching {
                FileChannel.open(lock, StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
                    channel.tryLock() == null
                }
            }
            when {
                probe.isFailure ->
                    logger.lifecycle(
                        "jmh.lock at $lock could not be probed (${probe.exceptionOrNull()?.message}); " +
                            "leaving it alone",
                    )

                probe.getOrThrow() ->
                    logger.lifecycle("jmh.lock at $lock is held by a running JMH instance; leaving it alone")

                else -> {
                    Files.deleteIfExists(lock)
                    logger.lifecycle("removed a stale jmh.lock at $lock, left behind by an earlier run")
                }
            }
        }
    }

    /*
     * A benchmark that did not run must not pass.
     *
     * The `doFirst` above removes the cause that found this; the symptom is still here without the
     * assertion below. kotlinx-benchmark's runner catches a JMH failure, prints it and returns
     * normally, so a run that never started leaves a succeeding task and no results.
     * `.claude/rules/testing.md` says `smokeBenchmark` "proves they still compile, start and measure
     * the thing they name" — it cannot prove that unless something asserts the artefact, which is
     * what `BenchmarkRunReport` does.
     *
     * The evidence is the runner configuration in `args`: it is the very file the plugin told JMH to
     * write its results to, so nothing here reconstructs a path or guesses at a timestamp. And the
     * assertion fails loudly when it cannot read that file, because a check that quietly becomes a
     * no-op is the defect it exists to remove.
     *
     * This gates *that* a benchmark ran, never how fast it was: numbers from a shared runner are not
     * a regression gate.
     */
    val taskName = name
    val benchmarkList = layout.buildDirectory
        .file("benchmarks/main/resources/META-INF/BenchmarkList")
        .get()
        .asFile

    doLast {
        val runnerConfig = (this as JavaExec).args.orEmpty().firstOrNull()
            ?: throw GradleException(
                "$taskName was given no runner configuration, so there is no telling what it was asked " +
                    "to produce. This check reads the file kotlinx-benchmark passes its runner; teach " +
                    "it the new contract rather than dropping it.",
            )
        val problems = BenchmarkRunReport.verify(taskName, File(runnerConfig), benchmarkList)
        if (problems.isNotEmpty()) throw GradleException(problems.joinToString("\n\n"))
    }
}

/**
 * Holds JMH's lock so that a benchmark started elsewhere fails. See `JmhLockMain`.
 *
 * Grouped under `verification` rather than `benchmark` because it measures nothing: it is how the
 * `doLast` above is exercised in the state it exists for.
 *
 * ```
 * ./gradlew :rabosh-bench:holdJmhLock --args=90    # one terminal
 * ./gradlew :rabosh-bench:smokeBenchmark           # another: must FAIL
 * ```
 */
tasks.register<JavaExec>("holdJmhLock") {
    group = "verification"
    description = "Holds \${java.io.tmpdir}/jmh.lock for --args=<seconds>, so a benchmark cannot start."
    mainClass = "app.oreshkov.rabosh.bench.JmhLockMain"
    classpath = sourceSets["main"].runtimeClasspath
}

/** Space and write amplification: one run, measured and printed. See `AmplificationMain`. */
tasks.register<JavaExec>("runAmplification") {
    group = "benchmark"
    description = "Reports bytes on disk per byte of JSON ingested, by file kind."
    mainClass = "app.oreshkov.rabosh.bench.AmplificationMain"
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Xmx2g")
}

/** Where a point lookup's time goes, over a grid of block and segment sizes. See `ReadCostMain`. */
tasks.register<JavaExec>("runReadCost") {
    group = "benchmark"
    description = "Point-get latency against block size and segment size, page-cache resident."
    mainClass = "app.oreshkov.rabosh.bench.ReadCostMain"
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Xmx2g")
}

/** Where an indexed query's per-row time goes, mechanism by mechanism. See `QueryCostMain`. */
tasks.register<JavaExec>("runQueryCost") {
    group = "benchmark"
    description = "Per-row cost of an indexed query, decomposed, and against segment count."
    mainClass = "app.oreshkov.rabosh.bench.QueryCostMain"
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Xmx2g")
}

/**
 * Where a corpus keeps things: distinct location shapes, and how far a type scatters across them.
 * See `CorpusShapeMain`.
 *
 * ```
 * ./gradlew :rabosh-bench:runCorpusShape --args="C:/path/to/corpus.json @type --shapes"
 * ```
 */
tasks.register<JavaExec>("runCorpusShape") {
    group = "benchmark"
    description = "Distinct CatalogPath shapes in a corpus, and how far each discriminated type scatters."
    mainClass = "app.oreshkov.rabosh.bench.CorpusShapeMain"
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Xmx4g")
}

/**
 * What exploding a nested document into one document per element would cost. See `ExplodeCostMain`.
 *
 * Takes a corpus path, so unlike the diagnostics above it measures somebody's data rather than a
 * generated fixture — which is the point, and which is why nothing in CI runs it. The arithmetic it
 * uses is covered by `ExplodeCostTest` in the ordinary build.
 *
 * ```
 * ./gradlew :rabosh-bench:runExplodeCost --args="C:/path/to/corpus.json @type"
 * ```
 */
tasks.register<JavaExec>("runExplodeCost") {
    group = "benchmark"
    description = "Stored bytes per original byte if every discriminated element became a document."
    mainClass = "app.oreshkov.rabosh.bench.ExplodeCostMain"
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Xmx4g")
}

/**
 * How much the uncorrelated conjunction over-returns. See `CorrelationCostMain`.
 *
 * The gate on a composite-term index: a sweep over elements per document, run over the shape that
 * argues for the feature and the shape that argues against it. Generated rather than corpus-driven,
 * because the two shapes bracket what real data can do; the arithmetic is covered by
 * `CorrelationCostTest` in the ordinary build.
 *
 * ```
 * ./gradlew :rabosh-bench:runCorrelationCost
 * ```
 */
tasks.register<JavaExec>("runCorrelationCost") {
    group = "benchmark"
    description = "False-positive rate of an uncorrelated conjunction, against elements per document."
    mainClass = "app.oreshkov.rabosh.bench.CorrelationCostMain"
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Xmx2g")
}

/**
 * What an element ordinal space could remove from a query. See `ElementAccessCostMain`.
 *
 * The gate on §10.6: the element walk timed against the document read it rides on, swept over
 * elements per document. The arithmetic it feeds is covered by `ElementAccessCostTest` in the
 * ordinary build; the timings are a diagnostic and nothing asserts them.
 *
 * ```
 * ./gradlew :rabosh-bench:runElementAccessCost
 * ```
 */
tasks.register<JavaExec>("runElementAccessCost") {
    group = "benchmark"
    description = "Cost of the element walk against the document read it rides on."
    mainClass = "app.oreshkov.rabosh.bench.ElementAccessCostMain"
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Xmx4g")
}

/**
 * What a shredded column's text bound width buys in pruning, and costs in bytes. See
 * `TextBoundCostMain`.
 *
 * The §10.4 sweep, over `IndexOptions.columnTextBoundBytes` — the dial that decides skipping, which is
 * **not** `CatalogOptions.textBoundBytes` however alike the two look. Generated rather than
 * corpus-driven, and run over a clustered corpus and its own permutation, because block pruning is a
 * locality property and the unfavourable case has to be arranged. The arithmetic is covered by
 * `TextBoundCostTest` in the ordinary build.
 *
 * ```
 * ./gradlew :rabosh-bench:runTextBoundCost
 * ```
 */
tasks.register<JavaExec>("runTextBoundCost") {
    group = "benchmark"
    description = "Blocks a column's text bound rules out, against the bytes the bound costs."
    mainClass = "app.oreshkov.rabosh.bench.TextBoundCostMain"
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Xmx4g")
}

/**
 * Reads against a store grown past the machine's RAM. See `PageCacheMain`.
 *
 * Writes tens of gigabytes and runs for a long time; nothing in CI goes near it.
 */
tasks.register<JavaExec>("runPageCache") {
    group = "benchmark"
    description = "Point-get and scan throughput as a store grows past the page cache."
    mainClass = "app.oreshkov.rabosh.bench.PageCacheMain"
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Xmx4g")
}
