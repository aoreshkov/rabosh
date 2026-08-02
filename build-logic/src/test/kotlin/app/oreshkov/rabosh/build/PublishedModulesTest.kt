package app.oreshkov.rabosh.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * What counts as a published module, and — more to the point — what does not.
 *
 * The fixture is a directory tree rather than a real checkout, for the reason
 * [CentralBundleReportTest] generates its Maven layout: the rule *is* the tree shape, so writing the
 * shape out states what the rule believes in. Running it against the real repository root would
 * assert today's module list, which is a fact that is allowed to change, instead of the rule, which
 * is not.
 */
class PublishedModulesTest {

    private fun module(root: File, name: String, dumpNamed: String? = name) {
        val dir = File(root, name)
        dir.mkdirs()
        if (dumpNamed != null) {
            File(dir, "api").mkdirs()
            File(dir, "api/$dumpNamed.api").writeText("// ABI dump\n")
        }
    }

    @Test
    @DisplayName("a directory with its own ABI dump is published")
    fun findsModulesWithDumps(@TempDir root: File) {
        module(root, "rabosh-core")
        module(root, "rabosh-api")

        assertEquals(setOf("rabosh-api", "rabosh-core"), PublishedModules.under(root))
    }

    @Test
    @DisplayName("the result is sorted, so a bundle and a docs site list modules the same way")
    fun sortsTheResult(@TempDir root: File) {
        module(root, "rabosh-query")
        module(root, "rabosh-catalog")
        module(root, "rabosh-index")

        assertEquals(
            listOf("rabosh-catalog", "rabosh-index", "rabosh-query"),
            PublishedModules.under(root).toList(),
        )
    }

    @Test
    @DisplayName("a module with no dump is not published — rabosh-testkit, rabosh-bench, rabosh-samples")
    fun ignoresModulesWithoutDumps(@TempDir root: File) {
        module(root, "rabosh-core")
        module(root, "rabosh-testkit", dumpNamed = null)
        module(root, "rabosh-bench", dumpNamed = null)

        assertEquals(setOf("rabosh-core"), PublishedModules.under(root))
    }

    /**
     * The dump has to be named after its own directory. `abiValidation()` writes it that way and
     * `checkKotlinAbi` enforces it, so a differently named `.api` file is somebody's stray copy —
     * and treating it as a module would stage an artefact nothing checks the ABI of.
     */
    @Test
    @DisplayName("an ABI dump under another module's name does not count")
    fun requiresTheDumpToMatchTheDirectory(@TempDir root: File) {
        module(root, "rabosh-core", dumpNamed = "rabosh-api")

        assertEquals(emptySet<String>(), PublishedModules.under(root))
    }

    @Test
    @DisplayName("a root that does not exist is empty rather than an exception")
    fun toleratesAMissingRoot(@TempDir root: File) {
        assertEquals(emptySet<String>(), PublishedModules.under(File(root, "absent")))
    }
}
