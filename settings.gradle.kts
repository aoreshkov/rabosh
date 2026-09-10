pluginManagement {
    includeBuild("build-logic")
    repositories {
        mavenCentral()
        // The plugin portal is a proxy in front of Central, and an unfiltered proxy is a second
        // place any group at all can be served from. Every plugin this build applies is JetBrains'
        // — `org.jetbrains.kotlin.jvm`, `org.jetbrains.dokka`, `org.jetbrains.kotlinx.benchmark`,
        // and the convention plugins, which come from the included build rather than a repository —
        // so the portal is told to answer for nothing else. Central above it stays unfiltered: it
        // is the source of truth rather than the proxy.
        //
        // The failure this produces is the useful one. A plugin from some other group is not
        // resolved and quietly added; it is not found, at the point somebody writes it down, which
        // is the same conversation `CLAUDE.md`'s dependency policy asks for in prose.
        gradlePluginPortal {
            content { includeGroupByRegex("""org\.jetbrains(\..+)?""") }
        }
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
