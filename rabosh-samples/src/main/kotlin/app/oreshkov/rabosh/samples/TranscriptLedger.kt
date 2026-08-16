package app.oreshkov.rabosh.samples

import app.oreshkov.rabosh.api.Rabosh
import app.oreshkov.rabosh.api.RaboshOptions
import app.oreshkov.rabosh.core.Key
import app.oreshkov.rabosh.core.StoreOptions
import app.oreshkov.rabosh.core.WriteBatch
import app.oreshkov.rabosh.variant.Variant
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * The ingester's own bookkeeping: how far each transcript has been read, and which sessions the
 * `SessionEnd` hook saw end.
 *
 * **This is a second store, in its own directory, and the separation is the whole design.** The
 * transcript store holds documents whose shape nobody chose — that is what makes
 * `schema().render()` over it worth printing. The moment a receipt of our own design lands in the
 * same store, the rendered model is half somebody else's JSON and half ours, `indexCandidates()`
 * starts recommending indexes over our bookkeeping, and the demonstration quietly stops being a
 * demonstration. Two directories cost two `Rabosh` handles and keep the corpus honest.
 *
 * The other half of the argument is lifetimes. Receipts are rewritten every run, in place, one per
 * transcript; transcript lines are written once and never touched again. Mixing a hot, tiny,
 * overwrite-heavy key space into a cold, large, append-only one is the shape that makes compaction
 * rewrite the cold data to reclaim the hot data's tombstones — the same reason
 * `.claude/rules/index-sidecar-format.md` splits sidecars by lifetime.
 *
 * One process owns both, which is what `INTEGRATION.md` requires: a store directory admits one
 * writer, and this program is it for the duration of a run.
 */
internal class TranscriptLedger(directory: Path) : AutoCloseable {

    private val db: Rabosh = Rabosh.open(
        directory,
        // Small by every measure — a few hundred receipts — so the derived machinery that pays for
        // itself on the corpus would be pure overhead here. No schema catalog, no index catalog: we
        // wrote these documents and we know their shape.
        RaboshOptions(
            store = StoreOptions(backgroundMaintenance = false),
            schema = false,
            indexes = false,
        ),
    )

    /** What has already been read out of one transcript, and how far. */
    data class Receipt(
        val name: String,
        /** Terminated lines consumed. The number [TranscriptCorpus.read] is told to skip next time. */
        val lines: Long,
        /** Bytes consumed, up to and including the last newline. Compared against the file's size. */
        val bytes: Long,
        /** Lines that were handed to the parser and rejected. Carried so the count does not reset. */
        val rejected: Long,
    )

    /** Every receipt, by transcript name. Read once at startup: there is one per file, not per line. */
    fun receipts(): Map<String, Receipt> {
        val receipts = HashMap<String, Receipt>()
        db.scanPrefix(Key.of(RECEIPT_PREFIX)).use { cursor ->
            while (cursor.next()) {
                val document = cursor.document
                val name = document.field("name")?.stringValue() ?: continue
                receipts[name] = Receipt(
                    name = name,
                    lines = document.field("lines")?.longValue() ?: 0L,
                    bytes = document.field("bytes")?.longValue() ?: 0L,
                    rejected = document.field("rejected")?.longValue() ?: 0L,
                )
            }
        }
        return receipts
    }

    /**
     * Records [receipt], with the wall-clock time it was taken.
     *
     * One commit per transcript rather than one per run. A run interrupted halfway leaves the
     * transcripts it finished marked as finished, and the engine's ordering rule is what makes that
     * safe to believe: the receipt is a separate commit that can only be written *after* the batch it
     * describes has been acknowledged, so the failure mode is a transcript re-read, never a
     * transcript skipped.
     */
    fun record(receipt: Receipt) {
        db.put(
            Key.of("$RECEIPT_PREFIX${receipt.name}"),
            Variant.fromJson(
                """
                {"name":${quote(receipt.name)},"lines":${receipt.lines},"bytes":${receipt.bytes},
                 "rejected":${receipt.rejected},"recordedAt":${quote(Instant.now().toString())}}
                """.trimIndent().replace("\n", ""),
            ),
        )
    }

    /**
     * Ingests the `SessionEnd` hook's queue — the one thing the transcripts themselves do not record.
     *
     * A transcript says what happened in a session. It does not say that the session *ended*, or why:
     * `clear`, `resume`, `logout`, `prompt_input_exit`, `bypass_permissions_disabled`, `other` are
     * facts the hook is handed and nothing on disk otherwise keeps. The hook appends its stdin
     * verbatim, so the queue is JSONL — which means the reader written for transcripts reads it with
     * no changes at all, and the receipt mechanism resumes it with no changes either.
     *
     * Returns how many records were new this run.
     */
    fun ingestSessionEnds(queue: Path): Long {
        if (!Files.isRegularFile(queue)) return 0L
        val name = "session-end-queue"
        val from = receipts()[name]?.lines ?: 0L
        val batch = WriteBatch()
        var added = 0L
        val tally = TranscriptCorpus.read(queue, from) { ordinal, json ->
            batch.put(Key.of("$END_PREFIX%08d".format(ordinal)), Variant.fromJson(json))
            added++
        }
        if (!batch.isEmpty()) db.write(batch)
        record(Receipt(name = name, lines = tally.lines, bytes = tally.bytes, rejected = 0L))
        return added
    }

    /** Every session end recorded so far, as `reason -> count`. The hook's whole contribution. */
    fun endReasons(): Map<String, Long> {
        val reasons = HashMap<String, Long>()
        db.scanPrefix(Key.of(END_PREFIX)).use { cursor ->
            while (cursor.next()) {
                val reason = cursor.document.field("reason")?.stringValue() ?: "(absent)"
                reasons[reason] = (reasons[reason] ?: 0L) + 1L
            }
        }
        return reasons
    }

    override fun close() {
        db.close()
    }

    private companion object {
        const val RECEIPT_PREFIX = "receipt/"
        const val END_PREFIX = "session-end/"

        /*
         * There was an `endOf(prefix)` here — the prefix with its last byte raised — and both of the
         * things it went through are worth keeping written down, because this sample is where the
         * engine learned that a prefix is not a range.
         *
         * It replaced a `Key.successor()` call, which appends `0x00` and is the spelling of an
         * *exclusive lower* bound. As an upper bound that is the tightest one possible: `scan("receipt/",
         * "receipt/\u0000")` is a range containing at most the empty-suffix key itself, which is not
         * a document anybody wrote, so the scan returned nothing and every transcript looked unread.
         * That is what this ingester did until it was measured — the second run re-ingested the whole
         * corpus and reported it as new, and only the document count coming out doubled said so.
         *
         * The replacement then claimed to be "exact for every prefix here", and was not. Raising the
         * last byte gives the **exclusive** upper bound, and `scan`'s `to` is inclusive — so
         * `[receipt/, receipt0]` also returns a key spelled exactly `receipt0`, in whatever namespace
         * sorts next. Latent here only because no such key is written; that is luck, not a design.
         *
         * Both are gone. `db.scanPrefix(prefix)` names the prefix and computes no bound at all.
         */
        /**
         * A JSON string literal.
         *
         * Only the six escapes JSON requires, because only those can appear: a transcript name is a
         * relative path, so the backslash is the one that actually shows up on Windows and the rest
         * are here so this cannot become wrong later. No dependency is worth a line of JSON.
         */
        fun quote(text: String): String = buildString(text.length + 2) {
            append('"')
            for (character in text) {
                when (character) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (character < ' ') append("\\u%04x".format(character.code)) else append(character)
                }
            }
            append('"')
        }
    }
}
