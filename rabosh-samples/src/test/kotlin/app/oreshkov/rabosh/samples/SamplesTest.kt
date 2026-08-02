package app.oreshkov.rabosh.samples

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * The samples, run.
 *
 * This is the whole reason `rabosh-samples` is a module in the build rather than a folder of files
 * somebody pasted into a README. `CLAUDE.md`'s rule is that a documented example is a test, because a
 * snippet nothing executes is a snippet that rots — and a sample is a documented example that is
 * *longer*, so it rots faster.
 *
 * **What is asserted is the output, not the absence of a throw.** A sample that runs to completion
 * and prints `0 rows` has failed at the only job it has, and it is exactly what a broken planner, an
 * index that quietly covers nothing, or a corpus generator that stopped producing the field being
 * filtered on would leave behind. So every assertion here is about content the sample is supposed to
 * have produced. The counterpart rule from `CLAUDE.md` applies too — assertions about *work* never
 * stand alone — and it is satisfied structurally: each sample `check`s its own before/after row sets
 * for equality inside the program a reader is looking at, so a work assertion here cannot pass while
 * the answer is wrong.
 *
 * Output is captured by replacing `System.out`, which the samples print to directly on purpose: a
 * sample threading a `PrintStream` through every function to be testable would be demonstrating that
 * instead of the engine. Safe because no `maxParallelForks` is configured, so test classes share one
 * JVM sequentially.
 */
class SamplesTest {

    @Test
    fun `the three steps sample writes blind, models, indexes, and does not change its answer`(
        @TempDir directory: Path,
    ) {
        val output = capturingStdout { ThreeStepsMain.run(directory) }

        // Step 2: a model nobody declared, covering paths the corpus writes.
        assertTrue("\$.service" in output, "the rendered model should name \$.service")
        assertTrue("\$.latencyMs" in output, "the rendered model should name \$.latencyMs")
        assertTrue("\$.tags[*]" in output, "the rendered model should name the repeated path")

        // Step 3: the planner used the index it was given, and said so.
        assertTrue("INVERTED" in output, "the explained plan should name the inverted index it used")

        // The claim the sample exists to make: same rows, less work. `ThreeStepsMain` checks the
        // first half itself; this checks the second, which no `check` inside it asserts.
        val (readBefore, readAfter) = transition(output, "documents read")
        assertTrue(
            readAfter < readBefore,
            "the index should have removed document reads, not merely have been built: " +
                "$readBefore -> $readAfter",
        )
        val (indexedBefore, indexedAfter) = transition(output, "segments from sidecars")
        assertEquals(0, indexedBefore, "no segment can be answered from a sidecar before the index exists")
        assertTrue(indexedAfter > 0, "segments should be answered from sidecars once the index is built")

        // And the shredded column: a range answered, and its rows served without opening a document.
        val (projected, returned) = ofTotal(output, "rows served from columns without opening a document")
        assertTrue(returned > 0, "the range query should have matched something to project")
        assertEquals(returned, projected, "every row of the range query should have been served from columns")
    }

    @Test
    fun `the index later sample answers the same query uncovered, half covered and covered`(
        @TempDir directory: Path,
    ) {
        val output = capturingStdout { IndexLaterMain.run(directory) }

        assertTrue("IndexBuildProgress" in output, "the sample should report the build's progress")
        assertTrue("COMPLETED" in output, "the resumed build should finish")
        assertTrue("coverage:" in output, "the half-built state should report its coverage")

        // Three reports of the same query, in three coverage states. The sample `check`s that all
        // three found the same keys; this asserts there were three and that they found anything.
        val rowCounts = Regex("""^\s+(\d+) rows,""", RegexOption.MULTILINE)
            .findAll(output)
            .map { it.groupValues[1].toInt() }
            .toList()
        assertEquals(3, rowCounts.size, "expected the query to be reported in three coverage states")
        assertTrue(rowCounts.all { it > 0 }, "a sample that finds no rows demonstrates nothing: $rowCounts")
        assertEquals(1, rowCounts.distinct().size, "the three states must agree: $rowCounts")
    }

    /**
     * The entry point itself, not just the body.
     *
     * `main` is what the Gradle task invokes, and it owns the argument handling and the cleanup that
     * `run` does not — so a sample whose `run` works and whose `main` throws would pass every
     * assertion above.
     */
    @Test
    fun `main runs against a directory it is given and leaves it in place`(@TempDir directory: Path) {
        val target = directory.resolve("store")
        Files.createDirectories(target)

        capturingStdout { ThreeStepsMain.main(arrayOf(target.toString())) }

        assertTrue(Files.exists(target), "a directory the caller named is the caller's to keep")
        assertTrue(
            Files.list(target).use { it.findAny().isPresent },
            "the sample should have written a store into the directory it was given",
        )
    }

    /**
     * A temporary directory the sample made is a directory the sample removes.
     *
     * `SampleRun.entryPoint` verifies its own deletion and throws if it fails, which on Windows is
     * what a leaked mapping looks like — the same instrument `RaboshLifecycleTest` uses. This runs
     * `main` with no argument so that path is the one taken.
     */
    @Test
    fun `main with no argument cleans up after itself`() {
        val before = temporaryDirectoryNames()
        capturingStdout { ThreeStepsMain.main(emptyArray()) }
        assertEquals(
            before,
            temporaryDirectoryNames(),
            "the sample should have deleted the scratch directory it created",
        )
    }

    // --- helpers ------------------------------------------------------------------------------------

    private fun capturingStdout(body: () -> Unit): String {
        val buffer = ByteArrayOutputStream()
        val original = System.out
        try {
            System.setOut(PrintStream(buffer, true, StandardCharsets.UTF_8))
            body()
        } finally {
            System.setOut(original)
        }
        val output = buffer.toString(StandardCharsets.UTF_8)
        assertTrue(output.isNotBlank(), "a sample that prints nothing has demonstrated nothing")
        return output
    }

    /** Reads `"<label> <a> -> <b>"` out of the sample's summary line. */
    private fun transition(output: String, label: String): Pair<Int, Int> {
        val match = Regex("""${Regex.escape(label)} (\d+) -> (\d+)""").find(output)
        requireNotNull(match) { "the sample no longer prints a '$label a -> b' summary; output was:\n$output" }
        return match.groupValues[1].toInt() to match.groupValues[2].toInt()
    }

    /** Reads `"<label>: <a> of <b>"` out of the sample's output. */
    private fun ofTotal(output: String, label: String): Pair<Int, Int> {
        val match = Regex("""${Regex.escape(label)}: (\d+) of (\d+)""").find(output)
        requireNotNull(match) { "the sample no longer prints a '$label: a of b' line; output was:\n$output" }
        return match.groupValues[1].toInt() to match.groupValues[2].toInt()
    }

    private fun temporaryDirectoryNames(): Set<String> {
        val temporary = Path.of(System.getProperty("java.io.tmpdir"))
        return Files.list(temporary).use { entries ->
            entries.map { it.fileName.toString() }
                .filter { it.startsWith("rabosh-sample-") }
                .toList()
                .toSet()
        }
    }
}
