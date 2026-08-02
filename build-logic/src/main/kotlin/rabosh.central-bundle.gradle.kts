import app.oreshkov.rabosh.build.CentralBundleReport
import app.oreshkov.rabosh.build.PublishedModules

// Applied at the root and nowhere else: a Central deployment is one archive holding every published
// module, so the task that makes it is the one place that can see all of them.
//
// Two tasks rather than one, for the reason `smokeBenchmark` is two: an archive task with no source
// files is skipped as NO-SOURCE, and a `doLast` on a skipped task does not run — so a check hung off
// the archive would silently stop checking in exactly the case worth catching, a release where
// nothing was staged. `bundleForCentral` is a lifecycle task that always runs and reads the archive
// back, so "there is no archive" is one of the answers it can give rather than a way for it to be
// skipped.

/**
 * The published modules, taken from their committed ABI dumps.
 *
 * Not a list in this file: a hand-maintained one is a second list free to disagree with
 * `settings.gradle.kts`, and it would disagree once, silently, in the direction of publishing less
 * than intended. Every published module applies `rabosh.kotlin-library` and therefore has
 * `<module>/api/<module>.api` committed — a module without one fails `checkKotlinAbi` long before a
 * release — so the dumps are the list, maintained by a gate that already exists.
 *
 * The rule itself lives in [PublishedModules] rather than here, because `rabosh.api-docs` needs the
 * same answer and a second copy of the derivation would be free to disagree with this one.
 *
 * Read at configuration time because the task graph needs it. That is a filesystem read the
 * configuration cache does not track, and it does not need to: adding or removing a module changes
 * `settings.gradle.kts`, which the cache *does* track.
 */
val publishedModules: Set<String> = PublishedModules.under(layout.projectDirectory.asFile)

val centralBundleZip = tasks.register<Zip>("centralBundleZip") {
    group = "publishing"
    description = "Zips the staged modules into a Central Portal deployment bundle."

    dependsOn(publishedModules.map { ":$it:publishAllPublicationsToCentralStagingRepository" })

    from(layout.buildDirectory.dir("staging-deploy"))

    // Repository metadata is the repository's to write, not a deployment's to carry. Excluded rather
    // than tolerated — and `CentralBundleReport` reports one anyway, so if a future Gradle writes it
    // under a name this pattern misses, that is a failure rather than a surprise on the Portal.
    exclude("**/maven-metadata*")

    destinationDirectory = layout.buildDirectory
    archiveFileName = "central-bundle.zip"
}

tasks.register("bundleForCentral") {
    group = "publishing"
    description = "Builds the Central deployment bundle and verifies it is a complete release."

    dependsOn(centralBundleZip)

    // Copied into locals of *this* block, which is what makes the action below serialisable. A
    // top-level `val` in a precompiled script plugin is a field of the script object, so a task action
    // that reads one holds a Gradle script object reference — and the configuration cache cannot
    // serialise those. The same reason `BenchmarkRunReport` is a class in `build-logic` rather than a
    // function in a build script. `println` rather than `logger` for the same reason: inside a
    // `doLast` the unqualified name resolves against the script, not the task.
    val bundle = centralBundleZip.flatMap { it.archiveFile }
    val group = project.group.toString().replace('.', '/')
    val releaseVersion = project.version.toString()
    val modules = publishedModules

    doLast {
        val problems = CentralBundleReport.verify(
            bundle = bundle.get().asFile,
            groupPath = group,
            version = releaseVersion,
            expectedArtifacts = modules,
        )
        if (problems.isNotEmpty()) {
            // The staging directory accumulates, so a second release built over a first without a
            // `clean` is one of the shapes reported here rather than one silently swept away: the
            // bundle would hold both versions and publish both, permanently. A CI runner starts
            // empty, so this is a local-run message.
            throw GradleException(
                "The Central deployment bundle is not what a release of $releaseVersion should be:" +
                    problems.joinToString("") { "\n  - $it" } +
                    "\n\nNothing has been uploaded. Central deployments are permanent once published, " +
                    "so this fails here rather than after.",
            )
        }
        println(
            "Central bundle: ${bundle.get().asFile}, $releaseVersion, ${modules.size} modules, signed.",
        )
    }
}
