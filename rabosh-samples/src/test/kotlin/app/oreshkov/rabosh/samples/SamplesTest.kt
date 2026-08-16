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
 * somebody pasted into a README. The rule in `.claude/rules/testing.md` is that a documented example
 * is a test, because a snippet nothing executes is a snippet that rots — and a sample is a documented
 * example that is *longer*, so it rots faster.
 *
 * **What is asserted is the output, not the absence of a throw.** A sample that runs to completion
 * and prints `0 rows` has failed at the only job it has, and it is exactly what a broken planner, an
 * index that quietly covers nothing, or a corpus generator that stopped producing the field being
 * filtered on would leave behind. So every assertion here is about content the sample is supposed to
 * have produced. The counterpart rule from `.claude/rules/testing.md` applies too — assertions about
 * *work* never stand alone — and it is satisfied structurally: each sample `check`s its own
 * before/after row sets for equality inside the program a reader is looking at, so a work assertion
 * here cannot pass while the answer is wrong.
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
     * The drain sample, which is where `deleteRange` and `checkpoint` are accepted as a *caller's
     * program* rather than as two methods with tests.
     *
     * The sample `check`s the properties only it can see — every event shipped exactly once, in key
     * order, none twice — inside the program a reader is looking at, which is the phase-8 rule and
     * the reason those are not repeated here. What this adds is the part no `check` inside it covers:
     * that the loop actually ran more than once, that retention actually retired what it shipped, and
     * that the buffer ended empty rather than merely reporting that it had.
     */
    @Test
    fun `the drain sample ships every event once and leaves the buffer empty`(@TempDir directory: Path) {
        val output = capturingStdout { DrainMain.run(directory) }

        // More than one round, or the watermark was never exercised: a single-round drain would pass
        // every assertion the sample makes about ordering while never resuming from anything.
        val rounds = Regex("""^\s+round (\d+): shipped (\d+), retired (\d+),""", RegexOption.MULTILINE)
            .findAll(output)
            .map { Triple(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }
            .toList()
        assertTrue(rounds.size > 1, "the drain must resume from a watermark at least once: $rounds")
        assertEquals(rounds.indices.map { it + 1 }, rounds.map { it.first }, "rounds must be consecutive")
        for ((round, shipped, retired) in rounds) {
            assertTrue(shipped > 0, "round $round shipped nothing")
            assertEquals(shipped, retired, "round $round retired a different number than it shipped")
        }

        // The checkpoint was taken while the writer was running, and the copy opened.
        assertTrue("checkpoint at sequence" in output, "the sample should report the checkpoint it took")
        assertTrue(
            "holds the prefix as of that sequence, and nothing after it" in output,
            "the sample should have opened its own checkpoint and checked what is in it",
        )

        // And the buffer is empty at the end — asserted on the reported counts, because "deleted" and
        // "reclaimed" are two different claims and only the second shows up as bytes.
        val left = Regex("""retired all of them, (\d+) left in the buffer""").find(output)
        assertEquals("0", left?.groupValues?.get(1), "the buffer should be empty:\n$output")
        assertTrue("0 segment(s), 0 bytes" in output, "compaction should have reclaimed the space:\n$output")
    }

    /**
     * The transcript ingester, against a corpus this test writes rather than the developer's own.
     *
     * `TranscriptsMain` reads `~/.claude/projects` by default, and a test that did the same would
     * pass or fail for reasons that have nothing to do with the commit — different on every machine,
     * empty on CI, and a privacy problem the first time an assertion printed a diff. So it is given a
     * corpus [synthesiseCorpus] builds, shaped like Claude Code's but with the three awkward cases
     * arranged deliberately, exactly as the pruning fixtures arrange disjoint ranges: a **torn tail**
     * that a live writer would leave, a **malformed interior line** that must be reported rather than
     * defaulted, and a **sub-agent transcript** a directory deeper than its session.
     *
     * The run happens twice, and the second one is the assertion the first cannot make. An ingester
     * whose resume does not work is indistinguishable from one whose resume does — it ingests
     * everything, the keys are the same, every answer is right — until you count. This is a
     * regression test for exactly that: `Key.successor()` appends `0x00`, so the ledger's prefix scan
     * returned nothing, so every receipt was invisible and every run re-read the whole corpus. The
     * only symptom was the document count going up.
     */
    @Test
    fun `the transcripts sample reports its damage, resumes, and does not re-ingest`(
        @TempDir directory: Path,
    ) {
        val projects = directory.resolve("projects")
        val queue = directory.resolve("queue.jsonl")
        synthesiseCorpus(projects, queue)

        val first = capturingStdout { TranscriptsMain.run(directory.resolve("one"), projects, queue) }

        // The two damaged shapes were noticed and named, rather than parsed or silently skipped.
        assertTrue("1 line(s) the parser rejected" in first, "the malformed line should be reported:\n$first")
        assertTrue(
            "1 with a tail still being written" in first,
            "the unterminated last line should be held back, not parsed:\n$first",
        )

        // The hook's contribution: reasons no transcript records.
        assertTrue("clear=2" in first && "logout=1" in first, "the queue's end reasons should be counted:\n$first")

        // Step 2 derived a model of somebody else's JSON, including the path a sub-agent file writes.
        assertTrue("\$.type" in first, "the rendered model should name \$.type")
        assertTrue("\$.message.content[*].type" in first, "the model should reach into the content array")

        // Step 3: the same rows, less work. The row equality is `check`ed inside the sample itself.
        val (readBefore, readAfter) = transition(first, "documents read")
        assertTrue(readAfter < readBefore, "the index should have removed document reads: $readBefore -> $readAfter")
        val (indexedBefore, indexedAfter) = transition(first, "segments from sidecars")
        assertEquals(0, indexedBefore, "no segment can be answered from a sidecar before the index exists")
        assertTrue(indexedAfter > 0, "segments should be answered from sidecars once the index is built")

        // And the run that only a second run can check.
        val second = capturingStdout { TranscriptsMain.run(directory.resolve("one"), projects, queue) }
        assertTrue("ingested 0 line(s)" in second, "nothing changed, so nothing should be ingested:\n$second")
        assertTrue("0 new record(s)" in second, "the queue should resume where it stopped:\n$second")
        assertEquals(
            documentCount(first),
            documentCount(second),
            "re-running must not add documents; a broken resume looks exactly like a working one " +
                "except for this number",
        )
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

    /** Reads `"documents: <n>, paths: …"` out of step 2. */
    private fun documentCount(output: String): Long {
        val match = Regex("""documents: (\d+), paths:""").find(output)
        requireNotNull(match) { "the sample no longer prints a document count; output was:\n$output" }
        return match.groupValues[1].toLong()
    }

    /**
     * A corpus shaped like Claude Code's, with the awkward cases put in on purpose.
     *
     * Not a copy of the real format and not trying to be — the ingester names no field, so what it
     * needs from a fixture is the *structure* a transcript has: a directory per project, a file per
     * session, sub-agents a level deeper, a repeated `content` array, and a `type` that varies. What
     * it does need exactly right is the damage, because that is what the assertions are about.
     *
     * `$.type` is given five values at deliberately uneven frequencies so the sample's "least common
     * recurring value" has one answer rather than a tie, and so the distinct count lands inside the
     * band `chooseTerm` requires: at least four values, and at most one per fifty documents.
     */
    private fun synthesiseCorpus(projects: Path, queue: Path) {
        val alpha = projects.resolve("project-alpha")
        val beta = projects.resolve("project-beta")
        Files.createDirectories(alpha.resolve("session-a/subagents"))
        Files.createDirectories(beta)

        // 400 documents over five types: 200/150/40/8/2. The rarest recurring one is unambiguous.
        val types = List(200) { "user" } + List(150) { "assistant" } + List(40) { "system" } +
            List(8) { "summary" } + List(2) { "file-history-snapshot" }
        val lines = types.mapIndexed { index, type ->
            """{"type":"$type","uuid":"u-$index","sessionId":"session-a","cwd":"/w",""" +
                """"gitBranch":"main","message":{"role":"${if (index % 2 == 0) "user" else "assistant"}",""" +
                """"content":[{"type":"text"},{"type":"tool_use","name":"tool-${index % 6}"}]}}"""
        }
        Files.write(alpha.resolve("session-a.jsonl"), lines.map { it })

        // A sub-agent transcript, a directory deeper: the reason the walk is recursive.
        Files.write(
            alpha.resolve("session-a/subagents/agent-01.jsonl"),
            listOf("""{"type":"user","uuid":"sub-0","agentId":"agent-01"}"""),
        )

        // The damaged file: a blank line, a line the parser must reject, and no terminator at the end
        // - which is what a transcript being appended to right now looks like from the outside.
        Files.writeString(
            beta.resolve("session-b.jsonl"),
            """
            {"type":"user","uuid":"b-0","sessionId":"session-b"}

            {"type":"user","uuid":"b-1","truncated":
            {"type":"user","uuid":"b-2","sessionId":"session-b"}
            {"type":"user","uuid":"b-3","sessionId":"session-b"
            """.trimIndent(),
        )

        // The hook's queue, in the shape `SessionEnd` hands it over.
        Files.write(
            queue,
            listOf("clear", "clear", "logout", "resume").map {
                """{"session_id":"s","hook_event_name":"SessionEnd","reason":"$it"}"""
            },
        )
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
