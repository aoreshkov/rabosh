dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // Same filter and same reason as the root `settings.gradle.kts`: the portal proxies Central,
        // so leaving it open is a second unfiltered source for every group. Everything resolved here
        // is `org.jetbrains*` (the Kotlin and Dokka plugin artefacts) or `org.junit*` — and the
        // latter comes from Central, which is where it is published.
        gradlePluginPortal {
            content { includeGroupByRegex("""org\.jetbrains(\..+)?""") }
        }
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
