package app.oreshkov.rabosh.bench

import app.oreshkov.rabosh.catalog.SchemaCatalog
import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.core.Durability
import app.oreshkov.rabosh.core.StoreOptions
import app.oreshkov.rabosh.index.CompositeSegmentObserver
import app.oreshkov.rabosh.index.IndexCatalog
import app.oreshkov.rabosh.index.IndexDefinition
import app.oreshkov.rabosh.variant.Variant
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * Space and write amplification, which are ratios rather than rates.
 *
 * Deliberately not a JMH benchmark. Amplification is "how many bytes on disk per byte ingested" and
 * "how many bytes written per byte ingested", and a harness built to run an operation a million times
 * and divide has nowhere to put either. What it needs instead is one run, measured, and printed —
 * so this is a `main`, run on demand:
 *
 * ```
 * ./gradlew :rabosh-bench:runAmplification
 * ```
 *
 * The numbers it prints are the evidence for three deferred questions at once: whether block
 * compression is worth a dependency, what a sidecar actually costs beside the documents it indexes,
 * and how much of a store is the second copy of the key space that a base sidecar is.
 */
object AmplificationMain {

    private const val DOCUMENT_COUNT = 200_000

    @JvmStatic
    fun main(arguments: Array<String>) {
        val count = arguments.firstOrNull()?.toIntOrNull() ?: DOCUMENT_COUNT
        val directory = Corpus.scratch("amplification")
        try {
            report(directory, count)
        } finally {
            Corpus.deleteRecursively(directory)
        }
    }

    private fun report(directory: Path, count: Int) {
        val ingested = Corpus.sizeBytes(count)
        val schema = SchemaCatalog(directory)
        IndexCatalog(directory).use { indexes ->
            DocumentStore.open(
                directory,
                StoreOptions(
                    durability = Durability.BUFFERED,
                    memtableMaxBytes = 32L * 1024 * 1024,
                    backgroundMaintenance = false,
                    segmentObserver = CompositeSegmentObserver(listOf(schema, indexes)),
                ),
            ).use { store ->
                schema.attach(store)
                indexes.attach(store)

                val loadNanos = timed {
                    for (index in 0 until count) {
                        store.put(Corpus.key(index), Variant.fromJson(Corpus.json(index)))
                        if (index % 50_000 == 49_999) store.flush()
                    }
                    store.flush()
                }
                println(header(count, ingested))
                row("after load", directory, ingested, loadNanos)

                val compactNanos = timed { store.compact() }
                row("after compact", directory, ingested, compactNanos)

                val indexNanos = timed {
                    indexes.createIndex(store, IndexDefinition.inverted("$.team"))
                    indexes.createIndex(store, IndexDefinition.inverted("$.tags[*]"))
                    indexes.createIndex(store, IndexDefinition.column("$.score"))
                    indexes.createIndex(store, IndexDefinition.column("$.price"))
                }
                row("after indexes", directory, ingested, indexNanos)

                // The worst case for a posting file, measured rather than reasoned about: `$.id` is
                // distinct per document, so every term's posting list holds exactly one ordinal and
                // the bitmap header is nearly all of it. This is the open question about a singleton
                // encoding, in bytes.
                val postingsBefore = bytesOf(directory, ".pst")
                val uniqueNanos = timed { indexes.createIndex(store, IndexDefinition.inverted("$.id")) }
                val uniqueCost = bytesOf(directory, ".pst") - postingsBefore
                row("after unique index", directory, ingested, uniqueNanos)

                println()
                println("by kind, as a share of the JSON ingested:")
                for ((suffix, label) in KINDS) {
                    val bytes = bytesOf(directory, suffix)
                    if (bytes > 0) {
                        println(
                            String.format(
                                Locale.ROOT,
                                "  %-28s %10s  %5.2fx",
                                label,
                                human(bytes),
                                bytes.toDouble() / ingested,
                            ),
                        )
                    }
                }

                println()
                println(
                    String.format(
                        Locale.ROOT,
                        "one posting list per document (\$.id, %d distinct): %s, %.1f bytes/document",
                        count,
                        human(uniqueCost),
                        uniqueCost.toDouble() / count,
                    ),
                )
                println(
                    String.format(
                        Locale.ROOT,
                        "every other index together:                      %s, %.1f bytes/document",
                        human(postingsBefore),
                        postingsBefore.toDouble() / count,
                    ),
                )
            }
        }
    }

    private val KINDS = listOf(
        ".seg" to "segments (documents)",
        ".wal" to "write-ahead logs",
        ".cat" to "catalog sketches",
        ".idx" to "index base sidecars",
        ".pst" to "posting files",
        ".col" to "shredded columns",
    )

    private fun header(count: Int, ingested: Long): String =
        "$count documents, ${human(ingested)} of JSON\n" +
            String.format(Locale.ROOT, "  %-16s %10s %8s %10s", "stage", "on disk", "ratio", "elapsed")

    private fun row(label: String, directory: Path, ingested: Long, nanos: Long) {
        val bytes = bytesOf(directory, suffix = null)
        println(
            String.format(
                Locale.ROOT,
                "  %-16s %10s %7.2fx %9dms",
                label,
                human(bytes),
                bytes.toDouble() / ingested,
                nanos / 1_000_000,
            ),
        )
    }

    private fun bytesOf(directory: Path, suffix: String?): Long =
        Files.newDirectoryStream(directory).use { entries ->
            entries.sumOf { entry ->
                val name = entry.fileName.toString()
                if (suffix == null || name.endsWith(suffix)) Files.size(entry) else 0L
            }
        }

    private fun human(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.ROOT, "%.2f GiB", bytes.toDouble() / (1L shl 30))
        bytes >= 1L shl 20 -> String.format(Locale.ROOT, "%.2f MiB", bytes.toDouble() / (1L shl 20))
        bytes >= 1L shl 10 -> String.format(Locale.ROOT, "%.2f KiB", bytes.toDouble() / (1L shl 10))
        else -> "$bytes B"
    }

    private inline fun timed(body: () -> Unit): Long {
        val start = System.nanoTime()
        body()
        return System.nanoTime() - start
    }
}
