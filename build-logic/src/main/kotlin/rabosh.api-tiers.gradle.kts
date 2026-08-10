import app.oreshkov.rabosh.build.ApiTierAudit

// Applied at the root and nowhere else, because the question is cross-module: a type marked
// experimental in `rabosh-index` can leak through a signature in `rabosh-query`'s dump, and no
// per-module task can see both.
//
// This is the gate `checkKotlinAbi` cannot be. The JVM dump format writes signatures and never
// annotations, so a declaration changing tier is invisible to it — and the published modules opt in
// to their own marker module-wide, so it is invisible to the *compiler* too. Between them that
// leaves the tier statement in `STABILITY.md` held up by nothing, which is what this fixes.

/**
 * Fails when a public signature exposes an experimental type without being marked itself.
 *
 * The rule lives in [ApiTierAudit] rather than here, for the reason `CentralBundleReport` and
 * `BenchmarkRunReport` do: a decision that fails a build is worth unit tests, and an included build's
 * tests are run by `./gradlew -p build-logic check` rather than by the root `build`.
 *
 * Both inputs are declared, so this is up to date when neither the sources nor the dumps have moved —
 * and it re-runs when either has, which is exactly when the answer can change.
 */
val checkApiTiers = tasks.register("checkApiTiers") {
    group = "verification"
    description = "Checks that no unmarked public signature exposes a @RaboshExperimental type."

    val root = layout.projectDirectory.asFile
    val modules = ApiTierAudit.modulesUnder(root)
    val dumps = ApiTierAudit.dumpsUnder(root)

    inputs.files(dumps.values.filter { it.isFile }).withPropertyName("abiDumps")
    inputs.files(modules.map { File(it, "src/main/kotlin") }.filter { it.isDirectory })
        .withPropertyName("sources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // Nothing is produced, so up-to-date needs somewhere to record that it ran.
    outputs.file(layout.buildDirectory.file("reports/api-tiers/checked.txt"))

    val report = layout.buildDirectory.file("reports/api-tiers/checked.txt")

    doLast {
        val tier = ApiTierAudit.markedIn(modules)
        val leaks = ApiTierAudit.leaks(dumps, tier)

        val summary = buildString {
            appendLine("experimental tier: $tier")
            appendLine("dumps checked: ${dumps.size}")
            appendLine("leaks: ${leaks.size}")
            for (leak in leaks) appendLine("  $leak")
        }
        report.get().asFile.apply { parentFile.mkdirs() }.writeText(summary)

        if (leaks.isNotEmpty()) {
            throw GradleException(
                "${leaks.size} public signature(s) expose a @${ApiTierAudit.MARKER} type without carrying " +
                    "the marker, so a consumer reaches the experimental tier with nothing asking them to " +
                    "opt in:\n" + leaks.joinToString("\n") { "  $it" } +
                    "\n\nMark the declaration, or move the type into the stable core and say so in " +
                    "STABILITY.md. See ApiTierAudit for why the compiler cannot report this.",
            )
        }
        logger.lifecycle("API tiers: ${dumps.size} dump(s) checked against $tier, no leaks.")
    }
}

// `check` is the root's, from `base` by way of the Dokka plugin, and `build` depends on it — so this
// runs under the `./gradlew build` that CI already invokes rather than needing a step of its own.
tasks.named("check") { dependsOn(checkApiTiers) }
