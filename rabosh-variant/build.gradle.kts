plugins {
    id("rabosh.kotlin-library")
}

description = "Apache Open Variant binary encoding: codec, zero-copy path navigation, streaming JSON builder."

dependencies {
    // No runtime dependencies, here or anywhere in the engine. See the version catalogue.

    // The testkit's *main* source set depends on this module, and this module's *test* source set
    // depends on the testkit. That is not a cycle — `:rabosh-testkit:compileKotlin` needs only
    // this module's main classes — and it is what lets the codec be checked with the property
    // harness the testkit exists to provide.
    testImplementation(project(":rabosh-testkit"))

    // Reference oracle for the hand-written JSON parser. Test-only, never shipped.
    testImplementation(libs.kotlinx.serialization.json)
}
