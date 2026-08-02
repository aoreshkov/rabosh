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

    // The engine maps segments off-heap via the FFM API.
    jvmArgs("--enable-native-access=ALL-UNNAMED")

    // Forward the test dials into the test JVM. Without this, a `-D` on the Gradle command line
    // reaches only the daemon, and `./gradlew test -Drabosh.property.seed=…` — the documented way to
    // replay a CI failure exactly — silently does nothing. `rabosh.golden.write` is here for the
    // same reason: regenerating a golden store must be a thing that visibly happens.
    for (key in listOf("rabosh.property.seed", "rabosh.property.iterations", "rabosh.golden.write")) {
        providers.systemProperty(key).orNull?.let { systemProperty(key, it) }
    }
}
