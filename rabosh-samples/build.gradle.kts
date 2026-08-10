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
 * `--enable-native-access=ALL-UNNAMED` on the two tasks below is **future-proofing, not a
 * requirement**, and the comment that used to stand here said otherwise. It claimed that
 * `FileChannel.map(mode, offset, size, Arena)` is a restricted method and that without the flag a
 * sample's first line of output is a JVM warning. Neither is true: that overload carries no
 * `@Restricted` and declares no `IllegalCallerException` in JDK 25, and the sample runs silently
 * under `--illegal-native-access=deny` with no grant at all. The engine calls no restricted method.
 *
 * The flag is kept because it costs nothing and is right the day one arrives. What is *not* kept is
 * the reasoning — `runThreeStepsOnModulePath` below is where the claim now lives, checked rather
 * than asserted.
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

/*
 * The same sample again, with the library on the **module path** — which is the only thing that
 * checks `Automatic-Module-Name`.
 *
 * A packaging claim nothing packages is the same defect as documentation nothing executes, and this
 * is the cheapest place to hold the claim: a desktop app built with `jlink`/`jpackage` is the normal
 * shape for an embedded store, and that is a module-path build. Two of the flags below are the
 * assertion and neither can pass vacuously.
 *
 * `--add-modules app.oreshkov.rabosh.api` names the module rather than the file. Without the
 * manifest attribute the jar resolves as an automatic module called `rabosh.api`, derived from its
 * filename, and the JVM fails at startup with `module not found`. Delete the attribute from
 * `rabosh.kotlin-library` and this task stops working — which is the only reason to have it.
 *
 * `--illegal-native-access=deny` with **no** `--enable-native-access` beside it is the second
 * assertion, and it is the one this task is uniquely able to make: *the engine calls no restricted
 * method, so it needs no native-access grant.* That is checkable here and nowhere else. The other
 * two samples pass `--enable-native-access=ALL-UNNAMED`, which covers the classpath and would hide
 * the answer; on the module path the engine's code is in a **named** module, which `ALL-UNNAMED`
 * does not reach — so if `rabosh-core` ever acquired a `MemorySegment.reinterpret`, a `Linker`
 * downcall or a `System.loadLibrary`, this task would fail with `IllegalCallerException` while every
 * other sample carried on passing. Granting native access here would make the check vacuous, which
 * is why the flag is deliberately absent rather than merely unset.
 *
 * `FileChannel.map(mode, offset, size, Arena)` is *not* restricted — it carries no `@Restricted` and
 * declares no `IllegalCallerException` in JDK 25 — so mapping a segment costs no grant. That is the
 * fact `INTEGRATION.md` states, and this is what holds it.
 *
 * The sample itself stays on the classpath, in the unnamed module. That is the realistic shape for a
 * consumer that has not modularised, and it keeps this an `Automatic-Module-Name` check rather than
 * the beginnings of a `module-info.java` — the attribute is reversible and a descriptor is not.
 *
 * `kotlin.stdlib` is named alongside it because it has to be. Resolving one automatic module
 * resolves every *other* automatic module on the path — which is what brings the remaining six
 * rabosh jars in without listing them — but `kotlin-stdlib` ships a real `module-info`, so it is an
 * explicit module and nothing pulls it in implicitly. The sample's own classes are in the unnamed
 * module and call into it directly, so without this the first thing that happens is a
 * `NoClassDefFoundError` on `kotlin.jvm.internal.Intrinsics`.
 */
val modulePath: FileCollection = configurations["runtimeClasspath"]

tasks.register<JavaExec>("runThreeStepsOnModulePath") {
    group = "sample"
    description = "The three steps again, with the library resolved by module name on the module path."
    mainClass = "app.oreshkov.rabosh.samples.ThreeStepsMain"

    // Only the sample's own classes. Everything it depends on is reached by module name below.
    classpath = files(sourceSets["main"].output)
    dependsOn(modulePath)

    jvmArgs(
        "--add-modules", "app.oreshkov.rabosh.api,kotlin.stdlib",
        "--illegal-native-access=deny",
    )
    // Through a provider rather than `jvmArgs(...)` directly: resolving the configuration to build a
    // string would do it while the build is being configured, which the configuration cache stores
    // and would then serve stale. The local is not a stylistic choice — a lambda that read
    // `modulePath` directly would capture the *script object*, which the configuration cache
    // refuses to serialise.
    val jars = modulePath
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("--module-path", jars.asPath) })
}
