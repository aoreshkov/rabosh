package app.oreshkov.rabosh.build

import java.io.File

/**
 * Which modules of this build are published, derived from their committed ABI dumps.
 *
 * The argument for deriving it is on [CentralBundleReport] and is not repeated here. What this file
 * adds is that the derivation now has **two** callers — `rabosh.central-bundle` stages them for
 * Maven Central, `rabosh.api-docs` aggregates their KDoc into one site — and two copies of the rule
 * are the same defect the rule exists to prevent, one level up. A hand-maintained list would
 * disagree with `settings.gradle.kts`; two hand-copied derivations would disagree with each other,
 * and the visible symptom would be a released module missing from the documentation, which nothing
 * fails on.
 *
 * Plain Kotlin over [File] rather than a Gradle `Project` extension, for the reason
 * [BenchmarkRunReport] is: it makes the rule testable without a build, and it keeps the value a
 * precompiled script plugin reads out of the script object, which is what the configuration cache
 * can serialise.
 */
object PublishedModules {

    /**
     * The published module names under [root], sorted.
     *
     * A directory qualifies when it holds `api/<its own name>.api`. The dump being named after its
     * directory is what `abiValidation()` writes and what `checkKotlinAbi` enforces, so this asks
     * the question a gate already answers rather than inventing a second convention.
     *
     * A non-existent or unreadable [root] yields an empty set rather than throwing. That is the
     * honest answer to "which modules are published" for a directory with none, and both callers
     * turn an empty set into a loud failure of their own — an empty Central bundle is reported by
     * [CentralBundleReport], and an aggregation over nothing produces a site with no modules in it.
     */
    fun under(root: File): Set<String> =
        root.listFiles().orEmpty()
            .filter { File(it, "api/${it.name}.api").isFile }
            .mapTo(sortedSetOf()) { it.name }
}
