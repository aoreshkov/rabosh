plugins {
    id("rabosh.kotlin-library")
}

description = "RFC 9535 JSONPath over a Variant document: compile a query, expand it to nodes."

dependencies {
    // The codec, and nothing else. A query language and — one day — a regular-expression engine have
    // no business in the module every consumer of the encoding already carries.
    api(project(":rabosh-variant"))

    testImplementation(project(":rabosh-testkit"))

    // A *test*-only edge onto a module further up the chain, which is the opposite direction from the
    // one `settings.gradle.kts` protects: the differential asserts that this walk and
    // `CatalogPath.forEachNodeIn` agree node for node, and the module making the claim is the one
    // that should fail when it stops being true. Nothing in `main` sees it, so the published artefact
    // is unaffected and no plan can reach this grammar.
    testImplementation(project(":rabosh-catalog"))

    // Reads the vendored compliance fixtures. Test-only, never shipped.
    testImplementation(libs.kotlinx.serialization.json)
}
