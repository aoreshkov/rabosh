plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.dokka.gradle.plugin)

    // `BenchmarkRunReport` decides whether a benchmark task failed, so it has tests of its own.
    // Plain JUnit rather than `kotlin("test")`: build-logic compiles against the Kotlin embedded in
    // `kotlin-dsl`, and pinning kotlin-test to the catalogue's version here would invite a stdlib
    // mismatch for no gain. These run in CI through `./gradlew -p build-logic check` — an included
    // build's tests are not part of the root `build`.
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
