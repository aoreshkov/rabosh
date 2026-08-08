pluginManagement {
    includeBuild("build-logic")
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "rabosh"

// Modules are listed bottom-up: each may depend only on those above it.
include(
    ":rabosh-variant",
    ":rabosh-core",
    ":rabosh-catalog",
    ":rabosh-index",
    ":rabosh-query",
    ":rabosh-api",
    // Beside the chain rather than in it: `:rabosh-jsonpath` depends on `:rabosh-variant` and
    // nothing else, and nothing above depends on it. That is what keeps RFC 9535's comparison
    // semantics unable to reach the planner, and what lets the compliance claim be scoped to one
    // artefact instead of one sentence.
    ":rabosh-jsonpath",
    ":rabosh-testkit",
    ":rabosh-bench",
    ":rabosh-samples",
)
