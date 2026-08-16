package app.oreshkov.rabosh.samples

import app.oreshkov.rabosh.api.Rabosh
import app.oreshkov.rabosh.api.RaboshOptions
import app.oreshkov.rabosh.catalog.CatalogPath
import app.oreshkov.rabosh.catalog.CatalogStep
import app.oreshkov.rabosh.catalog.InferredField
import app.oreshkov.rabosh.core.Key
import app.oreshkov.rabosh.core.StoreOptions
import app.oreshkov.rabosh.core.WriteBatch
import app.oreshkov.rabosh.index.IndexDefinition
import app.oreshkov.rabosh.query.Query
import app.oreshkov.rabosh.query.QueryStats
import app.oreshkov.rabosh.query.path
import app.oreshkov.rabosh.variant.Variant
import app.oreshkov.rabosh.variant.VariantException
import app.oreshkov.rabosh.variant.VariantKind
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * The three steps again — **on a corpus nobody designed, that we did not write, and that changes
 * under us.**
 *
 * ```
 * ./gradlew :rabosh-samples:runTranscripts
 * ./gradlew :rabosh-samples:runTranscripts --args="C:/scratch/transcripts"   # keep the store
 * ```
 *
 * [ThreeStepsMain] makes the same argument on a corpus this repository generates, which is the
 * honest way to make it reproducible and the dishonest way to make it convincing: the raggedness of
 * [SampleCorpus] is raggedness somebody chose. This program points the identical three steps at
 * Claude Code's own session transcripts under `~/.claude/projects` — JSONL written by a program none
 * of us control, in a shape that is documented nowhere, that grows every time you use the tool, and
 * that gains a new field whenever a release ships one.
 *
 * That is the README's thesis restated as somebody else's problem, and it is the one corpus where
 * *write blind → model later → index later* is a description rather than an argument. Nobody writing
 * a transcript knew what question would be asked of it. The questions arrive months later — which
 * skill fired before that failure, how often does this tool error, what did I ask about this file in
 * March — and by then the shape is whatever it turned out to be.
 *
 * **Three things here are not in [ThreeStepsMain] and are the reason this exists.**
 *
 * The reader is **resumable**, because the corpus is live: the session transcript being written
 * while this runs has a last line that has not finished arriving. [TranscriptCorpus.read] hands back
 * complete lines only, and [TranscriptLedger] remembers how far each file was read, so a second run
 * ingests what a session added and nothing else.
 *
 * A parse failure is **counted and reported, not defaulted**. This is the rule from
 * `.claude/rules/format-permanence.md` — *unknown data decodes to a signalled failure, not to a
 * default* — arriving from the outside for once, rather than being asserted about our own bytes.
 *
 * And the model is **truncated**, which is the finding a designed corpus cannot produce.
 * `CatalogOptions.maxPaths` is 1024, and a transcript corpus has more distinct paths than that. The
 * catalog says so rather than pretending otherwise, and step 2 below prints the estimate it dropped.
 */
object TranscriptsMain {

    /** Commit roughly this much JSON at a time. One commit is one log append and one `force`. */
    private const val BATCH_BYTES = 4L * 1024 * 1024

    /** How many fields of the derived model to print. The whole of it is up to 1024 paths. */
    private const val FIELDS_SHOWN = 24

    /** How many documents to sample when choosing a value to ask about. See [chooseTerm]. */
    private const val SAMPLE_SIZE = 20_000

    @JvmStatic
    fun main(arguments: Array<String>) {
        val projects = arguments.getOrNull(1)?.let(Path::of) ?: TranscriptCorpus.defaultRoot()
        val queue = arguments.getOrNull(2)?.let(Path::of) ?: defaultQueue()
        SampleRun.entryPoint(arguments, "transcripts") { directory -> run(directory, projects, queue) }
    }

    /**
     * The sample. [directory] holds both stores, [projects] is the corpus, [queue] is the hook's.
     *
     * Give it a directory of your own and the store survives the run, which is what makes the second
     * run a resume. Give it none and [SampleRun.entryPoint] uses a temporary one and deletes it — a
     * full ingest every time, which is fine for a look and wasteful as a habit.
     */
    fun run(directory: Path, projects: Path, queue: Path? = null) {
        TranscriptLedger(directory.resolve("ledger")).use { ledger ->
            Rabosh.open(directory.resolve("store"), options()).use { db ->
                writeBlind(db, ledger, projects, queue)
                modelLater(db)
                indexLater(db)
            }
        }
    }

    /**
     * Defaults, with background maintenance off.
     *
     * Everything else is left alone deliberately — [SampleRun.options] shrinks `segmentMaxBytes` so
     * that four thousand synthetic documents still land in a dozen segments, and this corpus needs no
     * such help. What the store does with a few hundred megabytes of real JSON at the real segment
     * size is the number worth having.
     *
     * Maintenance is off so the printed counts are attributable: this program reports segment counts
     * before and after an explicit `compact()`, and a background thread moving segments while it
     * measures would make both numbers true and neither meaningful.
     */
    private fun options(): RaboshOptions = RaboshOptions(
        store = StoreOptions(backgroundMaintenance = false),
    )

    /** `~/.claude/rabosh-transcripts.queue.jsonl` — where `hooks/session-end-queue.sh` appends. */
    private fun defaultQueue(): Path =
        Path.of(System.getProperty("user.home"), ".claude", "rabosh-transcripts.queue.jsonl")

    // --- 1. write blind ---------------------------------------------------------------------------

    /**
     * Read every transcript, hand every line to the store, and state no schema.
     *
     * The loop below knows four things about the format and no more: the files end in `.jsonl`, a
     * line is a document, a line that has not been terminated has not arrived, and a line the parser
     * rejects is a line to report. No field is named, no message type is recognised, nothing is
     * flattened and nothing is dropped for being unfamiliar. That is not laziness — it is the only
     * ingest that survives Claude Code adding an event type next week.
     */
    private fun writeBlind(db: Rabosh, ledger: TranscriptLedger, projects: Path, queue: Path?) {
        SampleRun.heading("1.", "Write blind")

        println("corpus: $projects")
        val files = TranscriptCorpus.transcripts(projects)
        check(files.isNotEmpty()) {
            "no *.jsonl transcripts under $projects - pass a directory as the second argument, or " +
                "run Claude Code at least once so it has something to say"
        }
        println("transcripts: ${files.size} file(s), ${bytes(files.sumOf(Files::size))}")

        val ends = queue?.let(ledger::ingestSessionEnds) ?: 0L
        if (queue != null) {
            println("session-end queue: $queue")
            println("   $ends new record(s); reasons so far: ${ledger.endReasons().ifEmpty { "(none yet)" }}")
            SampleRun.note("a transcript never records that its session ended, or why - only the hook knows")
        }

        val receipts = ledger.receipts()
        val started = System.nanoTime()
        var ingested = 0L
        var rejectedTotal = 0L
        var torn = 0
        var resumed = 0
        var untouched = 0
        var ingestedBytes = 0L
        val batch = WriteBatch()
        var pending = 0L

        for (file in files) {
            val name = TranscriptCorpus.nameOf(projects, file)
            val receipt = receipts[name]
            val size = Files.size(file)
            if (receipt != null && size == receipt.bytes) {
                untouched++
                continue
            }
            // A file that shrank was rewritten rather than appended to, and its earlier keys can no
            // longer be trusted to mean what they meant. Rare, and cheap to be right about: read it
            // from the top and let the identical keys overwrite.
            val skip = if (receipt != null && size > receipt.bytes) receipt.lines else 0L
            if (skip > 0L) resumed++

            // Two counters rather than one: the receipt carries every rejection this file has ever
            // produced, so that a resume does not reset the total, while the run reports only what it
            // rejected itself. Adding the carried figure into the run's total would make a corpus with
            // one bad line report it again on every run for ever.
            var rejectedHere = 0L
            val tally = TranscriptCorpus.read(file, skip) { ordinal, json ->
                val document = decode(json, name, ordinal)
                if (document == null) {
                    rejectedHere++
                } else {
                    batch.put(TranscriptCorpus.key(name, ordinal), document)
                    ingested++
                    ingestedBytes += json.size
                    pending += json.size
                    if (pending >= BATCH_BYTES) {
                        db.write(batch)
                        batch.clear()
                        pending = 0L
                    }
                }
            }
            if (!batch.isEmpty()) {
                db.write(batch)
                batch.clear()
                pending = 0L
            }
            // The receipt is committed only once the documents it describes are, which is what makes
            // an interrupted run cost a re-read rather than a hole.
            ledger.record(
                TranscriptLedger.Receipt(name, tally.lines, tally.bytes, (receipt?.rejected ?: 0L) + rejectedHere),
            )
            rejectedTotal += rejectedHere
            if (tally.tornTailBytes > 0) torn++
        }
        db.flush()
        val elapsed = elapsedSeconds(started)

        println()
        println("ingested $ingested line(s), ${bytes(ingestedBytes)} of JSON, in %.1fs".format(elapsed))
        if (elapsed > 0.0) {
            println("   %.0f documents/s, %s/s".format(ingested / elapsed, bytes((ingestedBytes / elapsed).toLong())))
        }
        println("   $untouched file(s) unchanged since the last run, $resumed resumed mid-file, $torn with a tail still being written")
        println("   $rejectedTotal line(s) the parser rejected")
        println("   stored: ${db.stats}")

        SampleRun.note("a torn tail is held back, not parsed: the next run reads that line complete")
        SampleRun.note("nothing above named a field, and no schema was declared anywhere")

        val beforeCompaction = db.stats.segmentCount
        val compactionStarted = System.nanoTime()
        db.compact()
        println()
        println(
            "compacted: %d -> %d segment(s), %s, in %.1fs".format(
                beforeCompaction, db.stats.segmentCount, bytes(db.stats.segmentBytes), elapsedSeconds(compactionStarted),
            ),
        )
    }

    /**
     * A line, decoded — or `null`, having said out loud that it could not be.
     *
     * The distinction the engine holds about its own bytes applies here to somebody else's: a
     * document that will not decode is a **reported** failure, never a default. The alternative
     * shapes are both worse. Throwing would let one malformed byte in one transcript stop the whole
     * corpus, which for an ingester over a format we do not own is a guarantee of eventually
     * ingesting nothing. And substituting an empty document would put a lie in the store that
     * `schema().render()` would then faithfully report as structure.
     */
    private fun decode(json: ByteArray, name: String, ordinal: Long): Variant? =
        try {
            Variant.fromJson(json)
        } catch (failure: VariantException) {
            println("   ! $name line $ordinal did not decode: ${failure.message}")
            null
        }

    // --- 2. model later ---------------------------------------------------------------------------

    /**
     * Ask the store what it was given.
     *
     * Nothing scans to answer this — the sketches were folded during the flushes step 1 already paid
     * for — and nothing here was declared. Every path printed is a path Claude Code invented and this
     * program has never heard of.
     */
    private fun modelLater(db: Rabosh) {
        SampleRun.heading("2.", "Model later")

        val schema = db.schema()
        println("documents: ${schema.documentCount}, paths: ${schema.fields.size}, coverage: ${schema.coverage}")
        if (schema.isTruncated) {
            println("   ~${schema.truncatedPathEstimate} more paths were seen and dropped: the corpus")
            println("   has more distinct paths than CatalogOptions.maxPaths, and the catalog says so")
            SampleRun.note("that is the number a designed corpus cannot produce, and it is why the cap is a knob")
        }

        println()
        println("the widest ${minOf(FIELDS_SHOWN, schema.fields.size)} of them, by presence:")
        for (field in schema.fields.sortedByDescending { it.presence }.take(FIELDS_SHOWN)) {
            println("   ${describe(field)}")
        }
        SampleRun.note("presence over 100% is a repeated path, and that is the honest number for an array")
        SampleRun.note("value bounds are omitted here on purpose: a bound is a 64-byte slice of your own prompts")

        println()
        println("paths worth an index, best first (a report: nothing is built yet):")
        for (candidate in db.indexCandidates()) println("   $candidate")
    }

    /** One field, without its bounds. See the note in [modelLater] for why the bounds are dropped. */
    private fun describe(field: InferredField): String = buildString {
        append(field.path).append("  presence ").append("%.1f%%".format(field.presence * 100))
        append("  types ").append(field.types.entries.joinToString { "${it.key.name.lowercase()}=${it.value}" })
        if (field.distinctEstimate > 0) {
            append("  distinct ").append(if (field.distinctIsExact) "" else "~").append(field.distinctEstimate)
        }
        append("  avg ").append("%.1f B".format(field.averageBytes))
        if (field.nullFraction > 0) append("  null ").append("%.1f%%".format(field.nullFraction * 100))
    }

    // --- 3. index later ---------------------------------------------------------------------------

    /**
     * Ask one question twice: once against the bytes, once against an index built from them.
     *
     * The question is chosen from the data rather than written into this file. A hard-coded predicate
     * would be a claim about *my* transcripts, and the first thing anyone else running this would
     * discover is that it returns nothing. So step 2's recommendation picks the path, and a sample of
     * the corpus picks a value at that path — and then the program prints what it chose, because a
     * benchmark that will not say what it measured is not evidence.
     */
    private fun indexLater(db: Rabosh) {
        SampleRun.heading("3.", "Index later")

        val term = chooseTerm(db)
        if (term == null) {
            println("no path in the model carries a value worth asking about; nothing to demonstrate")
            return
        }
        val (fieldPath, value, seen) = term
        println("asking: $fieldPath == \"$value\"")
        println("   the path is the best-scoring candidate from step 2 whose distinct count is")
        println("   between $MIN_DISTINCT and one in $SELECTIVITY_RATIO documents; the value is its least common")
        println("   recurring one in a $SAMPLE_SIZE-document sample ($seen occurrence(s) there)")

        val query = Query.where(path(fieldPath) eq value)
        val (keysBefore, statsBefore, msBefore) = execute(db, query)
        println()
        println("before any index exists:")
        report(keysBefore, statsBefore, msBefore)

        val recommended = db.indexCandidates().firstOrNull { it.path.toString() == fieldPath }
        val definition = recommended?.let(IndexDefinition::of) ?: IndexDefinition.inverted(fieldPath)
        val buildStarted = System.nanoTime()
        println()
        println("creating an index over $fieldPath, against the segments already written")
        db.createIndex(definition)
        println("   built in %.1fs, over ${db.stats.segmentCount} segment(s)".format(elapsedSeconds(buildStarted)))

        val (keysAfter, statsAfter, msAfter) = execute(db, query)
        println()
        println("after the index is built:")
        report(keysAfter, statsAfter, msAfter)

        println()
        check(keysBefore == keysAfter) {
            "the index changed the answer, which is the one thing it may never do: " +
                "${keysBefore.size} rows before, ${keysAfter.size} after"
        }
        println("the same ${keysAfter.size} key(s), in the same order, from a different amount of work:")
        println(
            "   documents read %d -> %d, segments scanned %d -> %d, segments from sidecars %d -> %d"
                .format(
                    statsBefore.documentsRead, statsAfter.documentsRead,
                    statsBefore.segmentsScanned, statsAfter.segmentsScanned,
                    statsBefore.segmentsIndexed, statsAfter.segmentsIndexed,
                ),
        )
        println("   elapsed %.0f ms -> %.0f ms".format(msBefore, msAfter))

        println()
        println("how the planner answers it, and why:")
        println()
        println(db.explain(query).render().trimEnd().prependIndent("   "))
    }

    /** A path from step 2, a value found at it, and how often the sample saw that value. */
    private data class Term(val path: String, val value: String, val occurrences: Int)

    /**
     * Picks the question, from the corpus.
     *
     * The path comes from `indexCandidates()`, so the demonstration acts on step 2's recommendation
     * rather than reaching past it. The value comes from a bounded scan of the store: whichever
     * string value at that path occurs **least** often while still occurring more than once. Least,
     * not most, because a predicate that matches a third of the corpus is answered by reading a third
     * of the corpus whether an index exists or not — the interesting case for an inverted index is
     * the selective one, and choosing the popular value would flatter the *scan*.
     *
     * **The band on the distinct count is where the dogfooding actually bit.** Taking the top-scoring
     * candidate outright picked `$.toolUseResult.structuredPatch[*].lines[*]` — the individual lines
     * of every diff Claude Code has ever shown — and then asked the store for a line of Kotlin. That
     * recommendation is not wrong: the path is present, stably typed and enormously distinct, which
     * is exactly what the scorer rewards, and an index over it would answer that query beautifully.
     * It is just not a question anybody has. So the choice here adds the one thing a scorer cannot
     * know and a caller always does — roughly how many rows the answer should have — and the band
     * expresses it: at least [MIN_DISTINCT] values, so the predicate is not a coin flip, and at most
     * one value per [SELECTIVITY_RATIO] documents, so a term is a category rather than an identifier.
     */
    private fun chooseTerm(db: Rabosh): Term? {
        val documents = db.schema().documentCount
        for (candidate in db.indexCandidates()) {
            val distinct = candidate.field.distinctEstimate
            if (distinct < MIN_DISTINCT || distinct > documents / SELECTIVITY_RATIO) {
                println("   (skipping ${candidate.path}: ~$distinct distinct values in $documents documents)")
                continue
            }
            val counts = HashMap<String, Int>()
            var sampled = 0
            db.scan().use { cursor ->
                while (sampled < SAMPLE_SIZE && cursor.next()) {
                    sampled++
                    for (value in stringsAt(cursor.document, candidate.path)) {
                        if (value.length <= MAX_TERM_LENGTH) counts[value] = (counts[value] ?: 0) + 1
                    }
                }
            }
            val chosen = counts.entries.filter { it.value > 1 }.minByOrNull { it.value } ?: continue
            return Term(candidate.path.toString(), chosen.key, chosen.value)
        }
        return null
    }

    /**
     * Every string value [document] carries at [path].
     *
     * Plural, and that is the whole reason this is written out rather than delegating to
     * `Variant.select`. A [CatalogPath] can contain [CatalogStep.AnyElement] — the `[*]` that makes
     * `$.message.content[*].name` one path rather than an unbounded family of them — and a
     * `VariantPath` cannot: its steps are a field or a fixed index, because `select` returns one
     * value and a wildcard does not have one. The catalog counts occurrences and the accessor
     * retrieves a value; they are different questions and they need different path languages. Walking
     * the wildcard here is what lets a *repeated* path be a candidate at all, and repeated paths are
     * where the interesting terms in this corpus live.
     *
     * Non-strings are dropped rather than stringified: an inverted index brackets by type, so a term
     * that came from a number would be a term the query could never match.
     */
    private fun stringsAt(document: Variant, path: CatalogPath): List<String> {
        var reached = listOf(document)
        for (step in path.steps) {
            val next = ArrayList<Variant>()
            for (node in reached) {
                when (step) {
                    is CatalogStep.Field ->
                        if (node.kind == VariantKind.OBJECT) node.field(step.name)?.let(next::add)
                    is CatalogStep.AnyElement ->
                        if (node.kind == VariantKind.ARRAY) next.addAll(node.elements())
                }
            }
            if (next.isEmpty()) return emptyList()
            reached = next
        }
        return reached.filter { it.kind == VariantKind.STRING }.map { it.stringValue() }
    }

    // --- plumbing ---------------------------------------------------------------------------------

    /** Runs [query] to exhaustion: the keys it found, what it cost, and how long it took. */
    private fun execute(db: Rabosh, query: Query): Triple<List<Key>, QueryStats, Double> {
        val started = System.nanoTime()
        val keys = ArrayList<Key>()
        db.query(query).use { cursor ->
            while (cursor.next()) keys.add(cursor.key)
            return Triple(keys, cursor.stats, (System.nanoTime() - started) / 1_000_000.0)
        }
    }

    private fun report(keys: List<Key>, stats: QueryStats, milliseconds: Double) {
        println("   ${keys.size} row(s) in %.0f ms, first ${keys.firstOrNull()}".format(milliseconds))
        println("   $stats")
    }

    private fun elapsedSeconds(startedAt: Long): Double = (System.nanoTime() - startedAt) / 1_000_000_000.0

    /** Bytes, at a scale a reader can hold in their head. ASCII only, for the reason [SampleRun] gives. */
    private fun bytes(count: Long): String = when {
        count >= 1L shl 30 -> "%.1f GiB".format(Locale.ROOT, count / (1L shl 30).toDouble())
        count >= 1L shl 20 -> "%.1f MiB".format(Locale.ROOT, count / (1L shl 20).toDouble())
        count >= 1L shl 10 -> "%.1f KiB".format(Locale.ROOT, count / (1L shl 10).toDouble())
        else -> "$count B"
    }

    /** Long enough for a tool name, a branch or a message type; short enough not to key on a prompt. */
    private const val MAX_TERM_LENGTH = 64

    /** Below this many distinct values a predicate is not selective enough to be worth timing. */
    private const val MIN_DISTINCT = 4L

    /** Above one distinct value per this many documents, a term is an identifier and not a category. */
    private const val SELECTIVITY_RATIO = 50L
}
