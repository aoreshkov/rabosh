import app.oreshkov.rabosh.build.TestDiscoveryReport
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Precompiled script plugins do not get type-safe `libs` accessors, so the
// catalogue is read through the public API instead.
val catalog: VersionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun catalogVersion(name: String): String =
    catalog.findVersion(name).orElseThrow { GradleException("Missing version '$name' in libs.versions.toml") }
        .requiredVersion

fun catalogLibrary(name: String): Provider<MinimalExternalModuleDependency> =
    catalog.findLibrary(name).orElseThrow { GradleException("Missing library '$name' in libs.versions.toml") }

kotlin {
    jvmToolchain(catalogVersion("jvmToolchain").toInt())

    compilerOptions {
        allWarningsAsErrors = true
    }
}

dependencies {
    add("testImplementation", platform(catalogLibrary("junit-bom")))
    add("testImplementation", kotlin("test"))
    add("testImplementation", catalogLibrary("junit-jupiter"))
    add("testRuntimeOnly", catalogLibrary("junit-platform-launcher"))
}

// Whether the tagged scale suites run. Off by default: a ten-million-document index build is worth
// asserting, and it is not worth adding a quarter of an hour to every commit on two CI platforms.
// The scaled-down version of the same assertion runs every build, because "identical results" is a
// correctness claim and correctness claims are not optional.
val runScaleTests: Boolean = providers.systemProperty("rabosh.index.scale").orNull?.toBoolean() ?: false

tasks.withType<Test>().configureEach {
    useJUnitPlatform {
        if (!runScaleTests) excludeTags("scale")
    }

    // Property tests print the seed of a failing case; make sure it reaches the console.
    testLogging {
        events(TestLogEvent.FAILED, TestLogEvent.SKIPPED)
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = false
    }

    // No `--enable-native-access` here, and its absence is the assertion.
    //
    // It used to be granted to every test JVM, described as harmless future-proofing. It was
    // neither harmless nor a no-op as a *statement*: `INTEGRATION.md` tells a consumer the engine
    // needs no grant, `:rabosh-samples:runThreeStepsOnModulePath` proves that under
    // `--illegal-native-access=deny`, and a blanket grant on `Test` meant the whole suite ran under
    // a permission the contract says it does not need. A restricted call introduced tomorrow would
    // pass every test here and be caught only if that one sample happened to execute it.
    //
    // `FileChannel.map(mode, offset, size, Arena)` is not a restricted method — it carries no
    // `@Restricted` and declares no `IllegalCallerException` — and neither do `Arena.ofShared`,
    // `Arena.allocate` or `MemorySegment.ofArray`. Do not add the flag back on the assumption that
    // mapping needs it; if a test ever fails for the want of it, that failure is the finding.

    // Pin the locale of every test JVM. `java.util.Formatter` takes its digit set from the default
    // locale, so under one whose numbering system is not `latn` — `ar-SA`, say — `"%08d".format(n)`
    // emits Arabic-Indic digits. Two things break at once in a suite built on byte identity:
    // generated keys change bytes, and the `\d` in an output regex matches ASCII only by default,
    // so the match fails. It is green on every CI runner and red only on a contributor's machine,
    // which is the one kind of non-determinism CI can never reproduce. One dial here rather than a
    // `Locale.ROOT` at each of a dozen call sites; `rabosh-samples` still needs its own, because
    // its output is the deliverable and it runs outside a `Test` task.
    systemProperty("user.language", "en")
    systemProperty("user.country", "US")

    // Forward the test dials into the test JVM. Without this, a `-D` on the Gradle command line
    // reaches only the daemon, and `./gradlew test -Drabosh.property.seed=…` — the documented way to
    // replay a CI failure exactly — silently does nothing. `rabosh.golden.write` is here for the
    // same reason: regenerating a golden store must be a thing that visibly happens.
    for (key in listOf("rabosh.property.seed", "rabosh.property.iterations", "rabosh.golden.write")) {
        providers.systemProperty(key).orNull?.let { systemProperty(key, it) }
    }

    /*
     * A module that executed no tests must not report success.
     *
     * Ten modules, one `test` task each, no aggregated count anywhere, and `ci.yml` uploads the
     * reports only on failure — so a module whose tests stop being *discovered* produces a green
     * build with fewer tests in it and nothing says so. An excluded tag typo, a filter that widens,
     * a source set that stops being one: each of those succeeds having run nothing.
     *
     * This is `BenchmarkRunReport`'s argument moved to the task where it matters more. The evidence
     * is the artefact — the JUnit XML this task was configured to write — never the log, and the
     * check fails rather than passing when it cannot read that artefact, because a check that
     * quietly becomes a no-op is the defect being removed.
     *
     * It asserts a **floor of one**, never a count: a remembered number is something to update
     * rather than a fact to check. A `test` task with no test classes at all is `NO-SOURCE` and its
     * actions never run, so this cannot fire on a module that legitimately has no suite.
     *
     * The path is captured as a `Provider` and resolved inside the action: a `File` read at
     * configuration time is a value the configuration cache has to carry, and `.get()` there is the
     * eager resolution this build otherwise avoids.
     */
    val taskPath = path
    val resultsDir = reports.junitXml.outputLocation
    doLast {
        val dir = resultsDir.get().asFile
        val documents = dir.listFiles { file -> file.isFile && file.name.endsWith(".xml") }
            ?.sortedBy { it.name }
            ?.map { it.name to it.readText() }
        val problems = TestDiscoveryReport.problems(taskPath, dir.path, documents)
        if (problems.isNotEmpty()) throw GradleException(problems.joinToString("\n"))
    }
}
