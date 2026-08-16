package app.oreshkov.rabosh.samples

import app.oreshkov.rabosh.core.Key
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString

/**
 * Where Claude Code's transcripts are, what a line of one is worth as a key, and how to read a file
 * that is still being appended to.
 *
 * This is the only part of [TranscriptsMain] that knows anything about somebody else's format, and
 * what it knows is deliberately almost nothing: that the files are called `*.jsonl`, that a line is a
 * JSON document, and that the last one may not have arrived yet. Nothing here parses a field, names a
 * message type or assumes a version. That is the point of the exercise — the shape is the *store's*
 * problem to report afterwards, not the reader's problem to state in advance.
 *
 * @see TranscriptsMain for the program that uses it, and for what the shape turned out to be.
 */
internal object TranscriptCorpus {

    private const val SUFFIX = ".jsonl"
    private const val NEWLINE = '\n'.code.toByte()
    private const val CARRIAGE_RETURN = '\r'.code.toByte()

    /** Read in 64 KiB bites. Large enough that a 5 MB transcript is a few dozen reads. */
    private const val CHUNK_BYTES = 64 * 1024

    /**
     * `~/.claude/projects`, which is where Claude Code writes one directory per project and one
     * `<session-id>.jsonl` per session inside it — plus `subagents/agent-*.jsonl` one level further
     * down when a session spawned any.
     *
     * Derived from `user.home` rather than from an environment variable because `CLAUDE_CONFIG_DIR`
     * is set only when it has been overridden, and a sample that reads the corpus of a machine it is
     * not running on is a sample that reads nothing.
     */
    fun defaultRoot(): Path = Path.of(System.getProperty("user.home"), ".claude", "projects")

    /**
     * Every transcript under [root], in key order.
     *
     * Recursive on purpose: sub-agent transcripts live a directory deeper than session transcripts,
     * and they are the same format written by the same writer. Sorting by [nameOf] rather than by
     * modification time means the ingest order and the key order agree, which is what lets a re-run
     * be a resume rather than a shuffle.
     */
    fun transcripts(root: Path): List<Path> {
        if (!Files.isDirectory(root)) return emptyList()
        return Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(SUFFIX) }
                .toList()
        }.sortedBy { nameOf(root, it) }
    }

    /**
     * The name a transcript is known by: its path under [root], separators normalised, `.jsonl` cut.
     *
     * `C--work-projects-kotlin-public-rabosh/252eb58e-…` for a session, and
     * `…/252eb58e-…/subagents/agent-a2e7…` for one of its sub-agents. Two properties are being bought
     * here and both are load-bearing. It is **stable**, so the same line lands on the same key on
     * every run and a re-ingest overwrites rather than duplicates. And it **sorts the way the corpus
     * nests**, so a scan bounded by `Key.of("<project>/")` and its `successor()` is one project, and a
     * scan bounded by a session prefix picks up that session's sub-agents with it.
     *
     * Note what this costs: the project is in the *key*, not in the document. Filtering by project is
     * therefore a range scan and not a query predicate, and no index will ever help with it — which
     * is the right trade when the answer is a contiguous run of keys, and the reason key design is a
     * design activity rather than a naming one.
     */
    fun nameOf(root: Path, file: Path): String =
        root.relativize(file).invariantSeparatorsPathString.removeSuffix(SUFFIX)

    /**
     * The key of the [ordinal]-th line of the transcript called [name].
     *
     * Zero-padded to eight digits so that lexicographic key order is line order. A transcript with
     * more than 100 million lines would break that, and would be a larger problem than its keys.
     */
    fun key(name: String, ordinal: Long): Key = Key.of("$name/%08d".format(ordinal))

    /** What [read] found: how far it got, and what it refused to hand over. */
    data class Tally(
        /** Terminated lines in the file, **including** the ones [read] skipped. The resume point. */
        val lines: Long,
        /** Bytes up to and including the last newline. Everything before the torn tail. */
        val bytes: Long,
        /** Bytes after the last newline: a line that is still being written. Never handed over. */
        val tornTailBytes: Int,
        /** Terminated lines that were empty. Counted so the ordinals still add up. */
        val blankLines: Long,
    )

    /**
     * Hands [body] every complete line of [file] after the first [skip] of them, and reports where it
     * stopped.
     *
     * **A line is only complete once its newline has arrived, and the last one may not have.** This is
     * a live file: Claude Code appends to the session transcript as the session runs, and a read that
     * races an append sees a prefix of a JSON document. Handing that prefix to the parser would turn a
     * timing accident into a decode failure and — worse — a *resumable* decode failure, because the
     * next run would see the same file as already ingested up to that line. So the trailing fragment
     * is held back rather than parsed: it is not counted in [Tally.lines], so the next run reads it
     * again, complete. That is the same bargain `LogRecoveryMode.TOLERATE_TORN_TAIL` strikes for the
     * engine's own write-ahead log, for the same reason, and it is worth noticing that the engine and
     * an ingester built on it end up needing the identical rule.
     *
     * Blank lines are skipped but still consume an ordinal. Resuming is by line count, so a counter
     * that skipped them would drift the moment one appeared.
     */
    fun read(file: Path, skip: Long, body: (ordinal: Long, json: ByteArray) -> Unit): Tally {
        val line = ByteArrayOutputStream(CHUNK_BYTES)
        val chunk = ByteArray(CHUNK_BYTES)
        var ordinal = 0L
        var blank = 0L
        var bytes = 0L

        Files.newInputStream(file).use { input ->
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                var start = 0
                for (index in 0 until read) {
                    if (chunk[index] != NEWLINE) continue
                    line.write(chunk, start, index - start)
                    start = index + 1
                    bytes += line.size() + 1L
                    // The copy is taken for skipped lines too, which is a little wasted work on a
                    // resume and buys the blank-line count being the same number whether the line
                    // was ingested or skipped over. Resuming is I/O-bound; the arithmetic is not.
                    val json = trimmed(line)
                    when {
                        json.isEmpty() -> blank++
                        ordinal >= skip -> body(ordinal, json)
                    }
                    ordinal++
                    line.reset()
                }
                line.write(chunk, start, read - start)
            }
        }
        return Tally(lines = ordinal, bytes = bytes, tornTailBytes = line.size(), blankLines = blank)
    }

    /**
     * The line's bytes without a trailing `\r`.
     *
     * A carriage return is JSON whitespace and the parser would accept it, so this is not a
     * correctness fix — it is so that a file written with CRLF and the same file written with LF
     * produce byte-identical documents, and therefore a byte-identical store. A sample that claims
     * determinism has to earn it on both line endings.
     */
    private fun trimmed(line: ByteArrayOutputStream): ByteArray {
        val bytes = line.toByteArray()
        val end = if (bytes.isNotEmpty() && bytes[bytes.size - 1] == CARRIAGE_RETURN) bytes.size - 1 else bytes.size
        return if (end == bytes.size) bytes else bytes.copyOf(end)
    }
}
