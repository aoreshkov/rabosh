plugins {
    // Base, not `rabosh.kotlin-library`: samples are not published, so they are subject to neither
    // `explicitApi()` nor ABI validation, and they carry no POM. Same reason as `rabosh-testkit`
    // and `rabosh-bench`.
    id("rabosh.kotlin-base")
}

description = "Runnable samples: write blind, model later, index later — the README's three steps, narrated."

/*
 * One dependency, and the constraint is deliberate rather than incidental.
 *
 * A sample's job is to show what using *this* library looks like, so anything else on the classpath
 * is something the reader has to discount. No CLI argument parser — `arguments.firstOrNull()` is the
 * whole of what these need. No logging facade — the output *is* the deliverable, and `println` is
 * what a reader would write. And no `rabosh-testkit`, which would be the tempting way to get a
 * corpus generator: it exports JUnit as `api`, which would put a test framework on a sample's runtime
 * path.
 */
dependencies {
    implementation(project(":rabosh-api"))
}

/*
 * The `application` plugin was the obvious choice and is the wrong one here.
 *
 * It supports a single `mainClass`, and there are two samples; adding a third would mean either a
 * dispatcher that exists only to satisfy the plugin or a second module. It also attaches `distZip`
 * and `distTar` to `assemble`, so `./gradlew build` would start producing distribution archives of a
 * demo nobody installs. Plain `JavaExec` costs two lines more per sample and neither.
 *
 * `--enable-native-access=ALL-UNNAMED` is not optional and not copied from habit:
 * `rabosh.kotlin-base` adds it to `Test` tasks only, and the engine maps every segment through
 * `FileChannel.map(mode, offset, size, Arena)`, which is a restricted method on JDK 25. Without it
 * the first line of a sample's output is a JVM warning about the library it is demonstrating.
 */
tasks.register<JavaExec>("runThreeSteps") {
    group = "sample"
    description = "Write blind, model later, index later — the whole arc, narrated, with the numbers."
    mainClass = "app.oreshkov.rabosh.samples.ThreeStepsMain"
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.register<JavaExec>("runIndexLater") {
    group = "sample"
    description = "A background index build, queried while it runs and again after it finishes."
    mainClass = "app.oreshkov.rabosh.samples.IndexLaterMain"
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
