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
    ":rabosh-testkit",
    ":rabosh-bench",
    ":rabosh-samples",
)
