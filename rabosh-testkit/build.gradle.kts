plugins {
    // Base, not `rabosh.kotlin-library`: the testkit is not published, so it is
    // subject to neither the explicit-API rule nor ABI validation.
    id("rabosh.kotlin-base")
}

description =
    "Test infrastructure: seeded property harness, generators, out-of-process kill harness, " +
        "fault-injecting filesystem, reference models."

/*
 * The reference models are *of* the experimental tier — a `BitSet` oracle for `Bitmap`, a `TreeMap`
 * oracle for the LSM — so this module works below the stable core by definition. The published
 * modules get the same line from `rabosh.kotlin-library`.
 *
 * `rabosh-samples` deliberately has neither, and that asymmetry is the whole gate: a sample is a
 * consumer, a testkit is not.
 */
kotlin {
    compilerOptions {
        optIn.add("app.oreshkov.rabosh.RaboshExperimental")
    }
}

// Consumed as testImplementation by the other modules, so its dependencies are
// api-scoped: a test that uses the harness needs the harness's types on its own path.
dependencies {
    api(project(":rabosh-variant"))
    api(platform(libs.junit.bom))
    api(kotlin("test"))
    api(libs.junit.jupiter)

    // Oracle for the harness's own tests: proves the JSON generators and writer emit valid JSON
    // before any Variant property is built on them. Deliberately not exposed to consumers —
    // generators must not share an implementation with the oracle they are checked against.
    testImplementation(libs.kotlinx.serialization.json)
}
