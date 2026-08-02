@file:OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)

// Convention for every published module: the public surface is explicit, its ABI is checked against
// a committed dump so an accidental binary-incompatible change fails the build, and it carries the
// metadata a consumer needs to know what they have.
//
// Modules that are not published (rabosh-testkit, rabosh-bench) apply `rabosh.kotlin-base`
// instead.
plugins {
    id("rabosh.kotlin-base")
    id("org.jetbrains.dokka")
    `maven-publish`
    signing
}

kotlin {
    explicitApi()

    // Kotlin's built-in ABI validation (2.4+), used in place of the standalone
    // binary-compatibility-validator plugin, whose ASM cannot read Java 25 bytecode.
    //
    // This registers `checkKotlinAbi` — which `check`, and therefore `build`, depends on — and
    // `updateKotlinAbi`, which rewrites the committed dump in `api/`. The `checkLegacyAbi` and
    // `updateLegacyAbi` names KGP also registers are deprecated shims: they depend on the tasks
    // above and warn, and KGP's own plan for them is warn, then throw, then remove. Use the
    // `…KotlinAbi` pair everywhere — the dump location and its format are unchanged, so the two
    // names are interchangeable today and will not be.
    abiValidation()
}

java {
    // Sources are part of what a library is: a stack trace through `Variant.select` is worth
    // stepping into, and the reasoning this codebase keeps in its KDoc is worth reading in place.
    withSourcesJar()
}

/**
 * Dokka's HTML, packaged under the `javadoc` classifier.
 *
 * HTML rather than the Javadoc format, because the API is Kotlin: nullability, default arguments and
 * extension receivers all survive Dokka's rendering and none of them survives a Javadoc one. What a
 * repository requires is that a `javadoc` artefact exists, not which tool made it.
 */
val dokkaJavadocJar = tasks.register<Jar>("dokkaJavadocJar") {
    group = "documentation"
    description = "Packages the Dokka HTML documentation for publication."
    archiveClassifier = "javadoc"
    from(tasks.named("dokkaGeneratePublicationHtml"))
}

publishing {
    repositories {
        // Not a remote. Every module publishes into one directory under the *root's* build directory,
        // which the root's `bundleForCentral` zips into the single archive the Central Portal takes —
        // its API is one upload of one bundle, not a deploy per artefact, so the deployment either
        // validates whole or fails whole. That is the property worth having: the Portal's validation
        // runs against everything this release is, before any of it is published, and a release that
        // is *correct and short* is caught by `CentralBundleReport` before the upload rather than by
        // nobody afterwards.
        maven {
            name = "centralStaging"
            url = uri(rootProject.layout.buildDirectory.dir("staging-deploy"))
        }
    }

    publications.register<MavenPublication>("maven") {
        from(components["java"])
        artifact(dokkaJavadocJar)

        pom {
            // Each module sets its own `description` *after* this plugin is applied, so it is read
            // lazily rather than captured here as whatever it was at this moment.
            name = providers.provider { project.name }
            description = providers.provider {
                project.description ?: "An embedded JSON storage engine for the JVM."
            }
            url = "https://github.com/aoreshkov/rabosh"
            inceptionYear = "2026"

            licenses {
                license {
                    name = "The Apache License, Version 2.0"
                    url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                    distribution = "repo"
                }
            }

            developers {
                // No email address, deliberately: a POM is published forever, and an address in one
                // is a permanent decision made on someone's behalf. A name and a URL are what a
                // consumer needs to find the project.
                developer {
                    id = "aoreshkov"
                    name = "Atanas Oreshkov"
                    url = "https://github.com/aoreshkov"
                }
            }

            scm {
                url = "https://github.com/aoreshkov/rabosh"
                connection = "scm:git:https://github.com/aoreshkov/rabosh.git"
                developerConnection = "scm:git:ssh://git@github.com/aoreshkov/rabosh.git"
            }

            issueManagement {
                system = "GitHub Issues"
                url = "https://github.com/aoreshkov/rabosh/issues"
            }
        }
    }
}

/*
 * PGP signing, from the environment and only from the environment.
 *
 * Central requires a `.asc` beside every deployed file, so signing is not optional at a release — but
 * it is configured as optional *here*, because the alternative is worse in both directions. A build
 * that required a key would break `publishToMavenLocal`, which CI runs on every push precisely to
 * catch a broken POM at the commit that broke it, and it would make a contributor need a signing key
 * to run `./gradlew build`. A build that carried a keyring file would put a private key in a
 * repository that is published forever.
 *
 * So the key arrives in memory from a secret and nowhere else: `useInMemoryPgpKeys` rather than
 * `signing.keyId`/`signing.secretKeyRingFile`, which would want a file on the runner. What this
 * deliberately does *not* do is fail when the key is absent — and that would be a silent hole if
 * nothing else looked, which is why `CentralBundleReport` fails a bundle with no `.asc` in it. The
 * release path's guarantee comes from reading the artefact, not from an assertion made here about an
 * environment variable.
 */
signing {
    val signingKey = providers.environmentVariable("SIGNING_KEY")
    val signingPassword = providers.environmentVariable("SIGNING_PASSWORD")

    isRequired = signingKey.isPresent
    if (signingKey.isPresent) {
        useInMemoryPgpKeys(signingKey.get(), signingPassword.getOrElse(""))
        sign(publishing.publications["maven"])
    }
}
