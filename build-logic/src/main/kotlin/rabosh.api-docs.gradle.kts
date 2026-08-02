import app.oreshkov.rabosh.build.PublishedModules

// Applied at the root and nowhere else. Aggregation is a fact about the whole build — one site
// covering every published module — so it belongs to the only project that can see all of them, the
// same reason `rabosh.central-bundle` lives there.
//
// This is the aggregating half of Dokka. The per-module half is already in `rabosh.kotlin-library`,
// which applies the Dokka plugin and packages each module's HTML under the `javadoc` classifier for
// Maven Central. Both halves read the same KDoc; what differs is where it lands. A consumer who has
// resolved the jar gets the module's own documentation from the classifier artefact, and a reader
// who has not resolved anything gets the whole API from the published site.
plugins {
    id("org.jetbrains.dokka")
}

/**
 * The modules the site covers: the published ones, and only those.
 *
 * `rabosh-testkit`, `rabosh-bench` and `rabosh-samples` are deliberately absent. They apply
 * `rabosh.kotlin-base`, have no ABI dump and are not published, so documenting them would put an API
 * on the site that nobody can depend on and that is free to change without any of the guarantees the
 * rest of the site implies.
 *
 * Derived rather than listed, via the same [PublishedModules] rule `rabosh.central-bundle` uses.
 * That equality is the point: a module released to Central and missing from the documentation is a
 * discrepancy nothing else in this build would fail on.
 */
val documentedModules: Set<String> = PublishedModules.under(layout.projectDirectory.asFile)

dependencies {
    documentedModules.forEach { module -> dokka(project(":$module")) }
}

dokka {
    moduleName = "rabosh"

    dokkaPublications.html {
        // `build/dokka/html` is the default and is named here anyway, because the Pages workflow
        // uploads exactly this path. A default that moves silently would break a deployment rather
        // than a build.
        outputDirectory = layout.buildDirectory.dir("dokka/html")
    }
}

// A site with no modules in it is a successful build that produced nothing, which is the defect
// `BenchmarkRunReport` and `CentralBundleReport` both exist for. Cheaper here than either of those,
// because it is answerable at configuration time: aggregation takes its input from a dependency list
// this file just wrote, so an empty list is knowable before any work starts.
require(documentedModules.isNotEmpty()) {
    "No published modules found under ${layout.projectDirectory}: every module with a committed " +
        "api/<module>.api dump is documented, and finding none means the dumps moved rather than " +
        "that the project has no public API."
}
