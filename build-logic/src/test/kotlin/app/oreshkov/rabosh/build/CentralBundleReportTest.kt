package app.oreshkov.rabosh.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Every way [CentralBundleReport] must stop a release, and the one way it must not.
 *
 * The fixture is generated rather than committed, and that is a deliberate difference from
 * [BenchmarkRunReportTest], whose fixtures are real artefacts. A JMH report is a format this project
 * does not own and cannot derive, so a real one is the only honest fixture. A Maven layout *is*
 * derivable — it is `<group>/<artifact>/<version>/<artifact>-<version><classifier>.<ext>` and its
 * companions — so writing it out here states the layout the check believes in, and a build that
 * changed what Gradle stages would fail against a fixture nobody had quietly regenerated.
 *
 * What this cannot cover is whether Gradle stages that layout. Nothing in a unit test can: it is a
 * fact about `maven-publish`, and the release workflow's dry run is what establishes it against the
 * Portal's own validator without spending a version.
 */
class CentralBundleReportTest {

    private val group = "app/oreshkov"
    private val version = "0.1.0"
    private val modules = setOf(
        "rabosh-variant", "rabosh-core", "rabosh-catalog", "rabosh-index", "rabosh-query", "rabosh-api",
    )

    /** The bundle a correct release produces: every module, every required jar, every companion. */
    private fun wellFormed(
        artifacts: Set<String> = modules,
        atVersion: String = version,
        classifiers: List<String?> = listOf(null, "sources", "javadoc"),
        companions: List<String> = listOf("asc", "md5", "sha1", "sha256", "sha512"),
    ): List<String> = artifacts.flatMap { artifactId ->
        val base = "$group/$artifactId/$atVersion/$artifactId-$atVersion"
        val files = classifiers.map { classifier ->
            if (classifier == null) "$base.jar" else "$base-$classifier.jar"
        } + listOf("$base.pom", "$base.module")
        files.flatMap { file -> listOf(file) + companions.map { "$file.$it" } }
    }

    // --- the one that must pass -------------------------------------------------------------

    @Test
    @DisplayName("a bundle holding every module, jar and companion passes")
    fun wellFormedBundlePasses() {
        assertEquals(emptyList<String>(), problemsFor(wellFormed()))
    }

    // --- the bundle that was never made ------------------------------------------------------

    @Test
    @DisplayName("an empty bundle is a publish task that did not run")
    fun emptyBundle() {
        val problems = problemsFor(entries = emptyList())
        assertEquals(1, problems.size, problems.toString())
        assertTrue(problems.single().contains("bundle is empty"), problems.single())
    }

    @Test
    @DisplayName("no published module to check against is a vacuous check, not a pass")
    fun noExpectedArtifacts() {
        // An empty expectation is satisfied by anything, so it has to be its own failure — the same
        // rule as an include pattern that selects no benchmark.
        val problems = problemsFor(wellFormed(), expected = emptySet())
        assertTrue(problems.any { it.contains("no published module was found") }, problems.toString())
    }

    // --- the release that is short of something ----------------------------------------------

    @Test
    @DisplayName("a module missing from the bundle fails, because it is missing permanently")
    fun missingModule() {
        val problems = problemsFor(wellFormed(artifacts = modules - "rabosh-query"))
        assertEquals(1, problems.size, problems.toString())
        assertTrue(problems.single().contains("nothing staged for rabosh-query"), problems.single())
    }

    @Test
    @DisplayName("a Dokka failure leaves a build that assembles and a bundle Central will reject")
    fun missingJavadocJar() {
        val problems = problemsFor(wellFormed(classifiers = listOf(null, "sources")))
        assertEquals(modules.size, problems.size, problems.toString())
        assertTrue(problems.all { it.contains("missing its javadoc jar") }, problems.toString())
    }

    @Test
    @DisplayName("a missing sources jar is caught for the same reason")
    fun missingSourcesJar() {
        val problems = problemsFor(wellFormed(classifiers = listOf(null, "javadoc")))
        assertTrue(problems.all { it.contains("missing its sources jar") }, problems.toString())
    }

    // --- the release that is unsigned ---------------------------------------------------------

    @Test
    @DisplayName("an unsigned bundle is what a run with the signing secrets unset produces")
    fun unsigned() {
        val problems = problemsFor(wellFormed(companions = listOf("md5", "sha1")))
        assertTrue(problems.isNotEmpty())
        assertTrue(problems.all { it.contains(".asc") }, problems.first())
    }

    @Test
    @DisplayName("checksums are required per file, and the message names which are absent")
    fun missingChecksums() {
        val problems = problemsFor(wellFormed(companions = listOf("asc", "sha1")))
        assertTrue(problems.all { it.contains(".md5") }, problems.first())
        assertTrue(problems.none { it.contains(".sha1") }, problems.first())
    }

    @Test
    @DisplayName("sha256 and sha512 are supported but not required, so their absence passes")
    fun optionalChecksumsAreOptional() {
        assertEquals(emptyList<String>(), problemsFor(wellFormed(companions = listOf("asc", "md5", "sha1"))))
    }

    @Test
    @DisplayName("a signature file does not itself need a signature or checksums")
    fun signaturesNeedNoCompanions() {
        // Central: ".asc files don't need checksum files, nor do checksum files need .asc signature
        // files." Requiring them would fail a bundle the Portal accepts.
        val entries = wellFormed(companions = listOf("asc", "md5", "sha1"))
        assertTrue(entries.none { it.endsWith(".asc.md5") })
        assertEquals(emptyList<String>(), problemsFor(entries))
    }

    // --- the release that is the wrong release ------------------------------------------------

    @Test
    @DisplayName("a snapshot version cannot go through the release path")
    fun snapshotVersion() {
        val problems = problemsFor(wellFormed(atVersion = "0.1.0-SNAPSHOT"), version = "0.1.0-SNAPSHOT")
        assertTrue(problems.any { it.contains("Central does not accept a snapshot") }, problems.toString())
    }

    @Test
    @DisplayName("a module staged at another version would publish both, permanently")
    fun mixedVersions() {
        val entries = wellFormed(artifacts = modules - "rabosh-api") +
            wellFormed(artifacts = setOf("rabosh-api"), atVersion = "0.0.9")
        val problems = problemsFor(entries)
        assertTrue(problems.any { it.contains("staged at version 0.0.9") }, problems.toString())
    }

    @Test
    @DisplayName("a module nobody meant to publish is as permanent as one that was meant")
    fun unexpectedModule() {
        val problems = problemsFor(wellFormed(artifacts = modules + "rabosh-testkit"))
        assertEquals(1, problems.size, problems.toString())
        assertTrue(problems.single().contains("rabosh-testkit"), problems.single())
    }

    // --- the files that do not belong in a deployment ------------------------------------------

    @Test
    @DisplayName("repository metadata is the repository's to write")
    fun mavenMetadata() {
        val entries = wellFormed() + "$group/rabosh-core/maven-metadata.xml"
        val problems = problemsFor(entries)
        assertEquals(1, problems.size, problems.toString())
        assertTrue(problems.single().contains("maven-metadata.xml"), problems.single())
    }

    @Test
    @DisplayName("a file outside the namespace being published is reported rather than uploaded")
    fun foreignNamespace() {
        val problems = problemsFor(wellFormed() + "com/example/thing/1.0/thing-1.0.jar")
        assertEquals(1, problems.size, problems.toString())
        assertTrue(problems.single().contains("outside $group/"), problems.single())
    }

    @Test
    @DisplayName("a path that is not a Maven layout is reported rather than guessed at")
    fun notMavenLayout() {
        val problems = problemsFor(wellFormed() + "$group/rabosh-core/0.1.0/extra/stray.jar")
        assertEquals(1, problems.size, problems.toString())
        assertTrue(problems.single().contains("not a Maven layout path"), problems.single())
    }

    private fun problemsFor(
        entries: List<String>,
        version: String = this.version,
        expected: Set<String> = modules,
    ): List<String> = CentralBundleReport.problems(
        bundlePath = "build/central-bundle.zip",
        entries = entries,
        groupPath = group,
        version = version,
        expectedArtifacts = expected,
    )
}
