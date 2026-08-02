package app.oreshkov.rabosh.samples

import app.oreshkov.rabosh.api.Rabosh
import app.oreshkov.rabosh.api.RaboshOptions
import app.oreshkov.rabosh.core.StoreOptions
import app.oreshkov.rabosh.core.WriteBatch
import app.oreshkov.rabosh.variant.Variant
import java.nio.file.Files
import java.nio.file.Path

/**
 * The scaffolding both samples share: where they write, what they open with, and how they narrate.
 *
 * Kept out of the samples themselves so that each one reads as the program a user would write. What
 * is here is the part that is about *being a sample* — a scratch directory, a heading, a store tuned
 * small enough that a laptop-sized corpus still has the structure a real one does — rather than the
 * part that is about using the engine.
 */
internal object SampleRun {

    /**
     * Runs [body] against a directory, and cleans up after it.
     *
     * The optional argument is a directory to use; without one a temporary directory is made and
     * **deleted afterwards, with the deletion checked**. That check is not tidiness. On Windows a
     * mapped file cannot be deleted at all, so a mapping left live by a mis-ordered `close` fails
     * here immediately and deterministically — the same instrument `RaboshLifecycleTest` uses, and
     * the reason it is worth a sample doing rather than skipping.
     */
    fun entryPoint(arguments: Array<String>, name: String, body: (Path) -> Unit) {
        val supplied = arguments.firstOrNull()?.let(Path::of)
        val directory = supplied ?: Files.createTempDirectory("rabosh-sample-$name")
        try {
            body(directory)
        } finally {
            // Only a directory this made. One the caller named is theirs to keep.
            if (supplied == null) deleteAndVerify(directory)
        }
    }

    /**
     * Store options sized for a sample rather than for a laptop's idea of a big number.
     *
     * Two departures from the defaults, and both are about making the *structure* visible on a corpus
     * small enough to run in a few seconds. `segmentMaxBytes` is cut so that a few thousand documents
     * land in a dozen segments rather than one — an index is a set of **per-segment** sidecars, so a
     * single-segment store cannot show partial coverage, cannot show a background build making
     * progress, and cannot show a query mixing indexed segments with scanned ones. And background
     * maintenance is off so that a flush happens exactly when the sample says it does: these programs
     * print segment counts, and anything that reasons about segments needs to be the only thing
     * moving them.
     */
    fun options(): RaboshOptions = RaboshOptions(
        store = StoreOptions(
            segmentMaxBytes = 64L * 1024,
            backgroundMaintenance = false,
        ),
    )

    /**
     * Writes [count] events in batches, flushing after each.
     *
     * A batch is one commit — one log append and one `force` however many documents it carries —
     * which is why this can use the default `SYNC` durability and still finish in a moment. The flush
     * after each batch is what seals segments, and segments are what the samples go on to talk about.
     */
    fun load(db: Rabosh, count: Int, batchSize: Int) {
        var written = 0
        while (written < count) {
            val batch = WriteBatch()
            val end = minOf(written + batchSize, count)
            for (index in written until end) {
                batch.put(SampleCorpus.key(index), Variant.fromJson(SampleCorpus.json(index)))
            }
            db.write(batch)
            db.flush()
            written = end
        }
    }

    /**
     * A numbered section heading, so the three steps are visible in the output as well as the code.
     *
     * Deliberately ASCII. On Windows `System.out` encodes to the console's codepage rather than to
     * UTF-8, so box-drawing characters and em dashes arrive as question marks — and for these
     * programs the output *is* the deliverable, so it has to look right on the terminal the reader
     * actually has. The same rule applies to every string these samples print.
     */
    fun heading(step: String, title: String) {
        println()
        println("-- $step $title ${"-".repeat(maxOf(4, 72 - step.length - title.length))}")
        println()
    }

    /** An aside: the reasoning a reader needs beside the output, not another line of output. */
    fun note(text: String) {
        println("   - $text")
    }

    private fun deleteAndVerify(directory: Path) {
        if (!Files.exists(directory)) return
        directory.toFile().deleteRecursively()
        check(!Files.exists(directory)) {
            "the sample directory at $directory could not be deleted, which on Windows means a mapped " +
                "file is still open: a leaked mapping in the store or in one of the catalogs"
        }
    }
}
