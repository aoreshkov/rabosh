package app.oreshkov.rabosh.build

import java.io.File
import java.util.zip.ZipFile

/**
 * Whether the archive about to be uploaded to Maven Central is the archive a release was supposed to
 * produce.
 *
 * The Central Portal is the one irreversible step in this project's release: *"Once released/published,
 * you will not be able to remove/update/modify your components."* There is no second chance at
 * `0.1.0`, only a `0.1.1` — so everything that can be known about the bundle before it is uploaded is
 * worth knowing before it is uploaded, and the Portal's own validation is the wrong place to find out.
 * It rejects a missing signature, but it happily accepts a bundle that is *correct and short*: five
 * modules where there should be six, a `javadoc` jar that Dokka silently failed to produce, a module
 * staged at a version the tag did not name. Each of those publishes successfully and is permanent.
 *
 * This is the same defect [BenchmarkRunReport] exists for, one step further along: there the check
 * could pass because its subject never ran, here it could pass because its subject was never
 * *complete*. So the assertion is made against the **artefact** — the zip's own entry list, read back
 * after it is written — rather than against the staging directory Gradle was asked to write, or
 * against the tasks that were asked to run. What is checked is what is uploaded.
 *
 * The universe of modules comes from the committed ABI dumps, `<module>/api/<module>.api`, for the
 * reason [BenchmarkRunReport] takes its own from JMH's generated index rather than a remembered count:
 * a hand-maintained list of published modules in a build script is a second list free to disagree with
 * `settings.gradle.kts`, and it would disagree exactly once, silently, in the direction of publishing
 * less than was intended. Every published module already has an ABI dump — a module that applies
 * `rabosh.kotlin-library` without one fails `checkKotlinAbi` long before it reaches here — so the dumps
 * are the list, maintained by a gate that already exists.
 *
 * Everything here is plain Kotlin over strings, in `build-logic` and with unit tests, for the reason
 * given on [BenchmarkRunReport]: a decision that decides whether a release goes out is worth testing,
 * and an included build's tests are run by `./gradlew -p build-logic check` rather than by the root
 * `build`.
 */
object CentralBundleReport {

    /**
     * The extensions Central requires a signature and checksums for.
     *
     * `.module` is Gradle Module Metadata, which is not something Central requires but *is* something
     * Gradle publishes, so it is deployed like any other artifact and has to be signed like one. The
     * `.asc` files themselves are deliberately absent from this set: Central states that *".asc files
     * don't need checksum files, nor do checksum files need .asc signature files"*, and requiring them
     * would fail a bundle the Portal accepts.
     */
    private val SIGNED_EXTENSIONS = setOf("jar", "pom", "module")

    /**
     * What every deployed file must be accompanied by.
     *
     * `.sha256` and `.sha512` are supported by Central but not mandatory, and Gradle writes them
     * anyway; they are not required here because requiring what is optional turns a future Gradle
     * that stops writing them into a release failure with a misleading message.
     */
    private val REQUIRED_COMPANIONS = listOf("asc", "md5", "sha1")

    /**
     * The classifiers Central requires for a module whose packaging is not `pom`: *"Projects with
     * packaging other than `pom` have to supply JAR files that contain Javadoc and sources."*
     *
     * `null` is the main jar. The `javadoc` one is Dokka's HTML under that classifier — a repository
     * requires that a `javadoc` artefact exists, not which tool made it — and it is the single most
     * likely thing to go quietly missing, because Dokka failing leaves a build that still assembles.
     */
    private val REQUIRED_JARS = listOf(null, "sources", "javadoc")

    /** One deployed file, as the bundle's Maven layout describes it. */
    private data class Deployed(
        val path: String,
        val artifactId: String,
        val version: String,
        val fileName: String,
    )

    /**
     * Everything wrong with a finished bundle, or an empty list.
     *
     * Pure: the caller does the IO, so every state this has to get right — an empty bundle, a missing
     * signature, a module short of its Dokka jar, a stray `maven-metadata.xml`, a module staged at the
     * wrong version — is a unit test rather than an arrangement nobody can reach.
     *
     * @param entries every path inside the archive, `/`-separated, directories excluded
     * @param groupPath the project's group as a path, e.g. `app/oreshkov`
     * @param version the version being released, which the tag decides
     * @param expectedArtifacts the artifactId of every module that must be in the bundle
     */
    fun problems(
        bundlePath: String,
        entries: List<String>,
        groupPath: String,
        version: String,
        expectedArtifacts: Set<String>,
    ): List<String> {
        val problems = mutableListOf<String>()

        // An empty expectation is satisfied by an empty bundle, so a universe that came out empty is
        // its own failure rather than a check that quietly passes. Same rule as an `include` pattern
        // that selects no benchmark.
        if (expectedArtifacts.isEmpty()) {
            problems += "$bundlePath: no published module was found to check the bundle against. The list " +
                "comes from the committed ABI dumps at <module>/api/<module>.api; if those have moved, " +
                "teach this check where they are rather than letting it pass against nothing."
        }

        if (version.endsWith("-SNAPSHOT")) {
            problems += "$bundlePath: the version is $version. Central does not accept a snapshot through " +
                "the release path, and a tag that produced one means the version was not overridden for " +
                "this build."
        }

        if (entries.isEmpty()) {
            problems += "$bundlePath: the bundle is empty. Nothing was staged, so the publish task either " +
                "did not run or wrote somewhere else."
            // Nothing below can say anything useful about an empty archive.
            return problems
        }

        val present = entries.toSet()
        val deployed = mutableListOf<Deployed>()

        for (entry in entries) {
            if (entry.substringAfterLast('/').startsWith("maven-metadata")) {
                problems += "$bundlePath: holds $entry. Repository metadata is the repository's to write, " +
                    "not a deployment's to carry; it is excluded from the archive, so one appearing here " +
                    "means the exclusion no longer matches what Gradle writes."
                continue
            }

            if (!entry.startsWith("$groupPath/")) {
                problems += "$bundlePath: holds $entry, which is outside $groupPath/. Every file in a " +
                    "deployment belongs to the namespace being published."
                continue
            }

            // <groupPath>/<artifactId>/<version>/<file>
            val relative = entry.removePrefix("$groupPath/").split('/')
            if (relative.size != 3) {
                problems += "$bundlePath: holds $entry, which is not a Maven layout path of the form " +
                    "$groupPath/<artifactId>/<version>/<file>."
                continue
            }
            deployed += Deployed(entry, relative[0], relative[1], relative[2])
        }

        // Only over the files that are where a deployment's files go. A path already reported as
        // misplaced would otherwise be reported a second time for lacking the companions it was never
        // going to have, and the first report is the one that says what is wrong.
        // Once per module and version rather than once per file: a module staged at the wrong version
        // is one mistake, and stating it twenty times buries the rest of the report.
        deployed.asSequence()
            .filter { it.version != version }
            .map { it.artifactId to it.version }
            .distinct()
            .sortedBy { it.first }
            .forEach { (artifactId, staged) ->
                problems += "$bundlePath: $artifactId is staged at version $staged, and this release is " +
                    "$version. A bundle spanning two versions publishes both, permanently."
            }

        for (file in deployed) {
            // A signature or a checksum that is absent is caught by the Portal; one that is absent for
            // a file nobody looked at is caught here, which is the point of reading the archive back.
            if (file.fileName.substringAfterLast('.', "") in SIGNED_EXTENSIONS) {
                val missing = REQUIRED_COMPANIONS.filterNot { "${file.path}.$it" in present }
                if (missing.isNotEmpty()) {
                    problems += "$bundlePath: ${file.path} has no ${missing.joinToString(", ") { ".$it" }}. " +
                        "Signing is skipped when no key is present, so an unsigned bundle is what a release " +
                        "run with the signing secrets unset produces."
                }
            }
        }

        val staged = deployed.groupBy { it.artifactId }
        for (artifactId in expectedArtifacts.sorted()) {
            val files = staged[artifactId]?.map { it.fileName }?.toSet()
            if (files.isNullOrEmpty()) {
                problems += "$bundlePath: nothing staged for $artifactId, which has a committed ABI dump and " +
                    "is therefore a published module. A module missing from a release is missing from it " +
                    "permanently."
                continue
            }
            if ("$artifactId-$version.pom" !in files) {
                problems += "$bundlePath: $artifactId has no $artifactId-$version.pom."
            }
            val missingJars = REQUIRED_JARS.filterNot { classifier ->
                val suffix = if (classifier == null) "" else "-$classifier"
                "$artifactId-$version$suffix.jar" in files
            }
            if (missingJars.isNotEmpty()) {
                problems += "$bundlePath: $artifactId is missing its " +
                    missingJars.joinToString(", ") { it ?: "main" } + " jar. Central requires sources and " +
                    "javadoc for any packaging other than pom, and the javadoc jar is Dokka's output — a " +
                    "Dokka failure leaves a build that still assembles."
            }
        }

        val unexpected = staged.keys - expectedArtifacts
        if (unexpected.isNotEmpty()) {
            problems += "$bundlePath: staged ${unexpected.sorted().joinToString()}, which ${
                if (unexpected.size == 1) "is not a published module" else "are not published modules"
            }. Publishing a module by accident is as permanent as publishing one on purpose."
        }

        return problems
    }

    /**
     * [problems], with the archive reading attached. Called from the bundle task's own `doLast`, so a
     * release that staged the wrong thing fails where the archive is made rather than after it has been
     * uploaded.
     */
    fun verify(bundle: File, groupPath: String, version: String, expectedArtifacts: Set<String>): List<String> {
        if (!bundle.isFile) {
            return listOf(
                "${bundle.path}: no bundle was written, so there is nothing to upload and nothing to check.",
            )
        }
        val entries = ZipFile(bundle).use { zip ->
            zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList()
        }
        return problems(
            bundlePath = bundle.path,
            entries = entries,
            groupPath = groupPath,
            version = version,
            expectedArtifacts = expectedArtifacts,
        )
    }
}
