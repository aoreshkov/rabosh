package app.oreshkov.rabosh.bench

import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.core.Durability
import app.oreshkov.rabosh.core.Snapshot
import app.oreshkov.rabosh.core.StoreOptions
import app.oreshkov.rabosh.variant.Variant
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.random.Random

/**
 * What happens to a read when the store stops fitting in memory.
 *
 * The question phase 9 raised and could not answer, because **every fixture it had fitted in the page
 * cache**. Answering it needs a different fixture rather than a different benchmark, and this is that
 * fixture: one store grown past the machine's RAM, measured at checkpoints along the way.
 *
 * Two things make the measurement mean something.
 *
 * **The probes are uniform over the whole key space**, so the working set is the whole store. A
 * benchmark that probed a hot subset would measure the subset and report it as a property of the
 * store — which is exactly how a cache gets justified by a fixture that never needed one.
 *
 * **It is measured on one growing store rather than several fresh ones.** The alternative is to build
 * a 2 GiB store, then a 4 GiB store, and so on, which pays the ingest cost over and over and lets the
 * level structure differ between the points being compared. Here the same store is measured as it
 * crosses the boundary, so the only thing that changes is how much of it memory can hold.
 *
 * ```
 * ./gradlew :rabosh-bench:runPageCache            # default target, 40 GiB of segments
 * ./gradlew :rabosh-bench:runPageCache --args=8   # 8 GiB, for a machine with less disk or patience
 * ```
 *
 * This writes tens of gigabytes and runs for a long time. It is a `main` for the reason
 * [AmplificationMain] and [ReadCostMain] are: one run of a curve, measured and printed.
 */
object PageCacheMain {

    /** Segment bytes to reach. Well past 31.5 GiB of RAM, and far short of the free disk. */
    private const val DEFAULT_TARGET_GIB = 40L

    private const val PROBES = 20_000
    private const val SCAN_DOCUMENTS = 50_000

    @JvmStatic
    fun main(arguments: Array<String>) {
        val targetGiB = arguments.firstOrNull()?.toLongOrNull() ?: DEFAULT_TARGET_GIB
        val target = targetGiB * 1024 * 1024 * 1024
        val ram = Runtime.getRuntime().let { _ -> physicalMemoryBytes() }

        println("target ${targetGiB} GiB of segments; machine reports ${human(ram)} of RAM")
        println("probes are uniform over the whole key space, so the working set is the whole store")
        println()
        println(
            String.format(
                Locale.ROOT,
                "  %10s %8s %9s %12s %12s %10s",
                "on disk", "vs RAM", "segments", "get", "scan", "ingested",
            ),
        )

        val directory = Corpus.scratch("pagecache")
        try {
            DocumentStore.open(
                directory,
                StoreOptions(
                    durability = Durability.BUFFERED,
                    memtableMaxBytes = 256L * 1024 * 1024,
                    backgroundMaintenance = true,
                ),
            ).use { store ->
                var written = 0
                var nextCheckpoint = 1L * 1024 * 1024 * 1024
                val ingestStart = System.nanoTime()

                while (true) {
                    // A batch between size checks: `stats` is cheap but not free, and asking it per
                    // document would put its cost into the ingest figure.
                    repeat(200_000) {
                        store.put(Corpus.key(written), Variant.fromJson(Corpus.json(written)))
                        written++
                    }
                    val bytes = store.stats.segmentBytes
                    // The target is its own stopping condition, not a thing to notice on the way past
                    // a checkpoint. Checkpoints double, so a 40 GiB target sitting between the 32 and
                    // 64 GiB ones would otherwise be stepped straight over and the run would carry on
                    // to 64 — which is exactly what the first run of this did.
                    val reachedTarget = bytes >= target
                    if (bytes < nextCheckpoint && !reachedTarget) continue

                    store.flush()
                    report(store, written, System.nanoTime() - ingestStart, ram)
                    if (reachedTarget) break
                    nextCheckpoint = nextCheckpoint * 2
                }
            }
        } finally {
            Corpus.deleteRecursively(directory)
        }
    }

    private fun report(store: DocumentStore, written: Int, ingestNanos: Long, ram: Long) {
        val stats = store.stats
        store.snapshot().use { snapshot ->
            val get = timeGets(store, snapshot, written)
            val scan = timeScan(store, snapshot)
            println(
                String.format(
                    Locale.ROOT,
                    "  %10s %7.2fx %9d %10.2fus %8.1f MB/s %10s",
                    human(stats.segmentBytes),
                    stats.segmentBytes.toDouble() / ram,
                    stats.segmentCount,
                    get,
                    scan,
                    human(Corpus.sizeBytes(written)),
                ),
            )
        }
        // The ingest figure is printed once at the end rather than per row: it is not what this
        // measures, and a reader who sees it in a column will compare rows that are not comparable.
        if (stats.segmentBytes > 0) {
            System.err.println(
                String.format(Locale.ROOT, "  (ingest so far: %.0f s)", ingestNanos / 1_000_000_000.0),
            )
        }
    }

    /** Microseconds per lookup, uniformly over every key written so far. */
    private fun timeGets(store: DocumentStore, snapshot: Snapshot, written: Int): Double {
        val random = Random(20260730)
        // Warm the JIT without warming the page cache: a handful of probes against a store this size
        // moves nothing, which is the point.
        repeat(200) { store.get(Corpus.key(random.nextInt(written)), snapshot) }

        var found = 0
        val start = System.nanoTime()
        repeat(PROBES) {
            if (store.get(Corpus.key(random.nextInt(written)), snapshot) != null) found++
        }
        val elapsed = System.nanoTime() - start
        check(found == PROBES) { "every key probed was written; $found of $PROBES came back" }
        return elapsed / 1_000.0 / PROBES
    }

    /** Megabytes of JSON per second over a sequential scan, which reads pages the get path does not. */
    private fun timeScan(store: DocumentStore, snapshot: Snapshot): Double {
        var documents = 0
        var bytes = 0L
        val start = System.nanoTime()
        store.scan(snapshot = snapshot).use { cursor ->
            while (cursor.next() && documents < SCAN_DOCUMENTS) {
                bytes += cursor.document.byteSize
                documents++
            }
        }
        val elapsed = System.nanoTime() - start
        return bytes.toDouble() / (1024 * 1024) / (elapsed / 1_000_000_000.0)
    }

    /**
     * Physical memory, or a stated fallback.
     *
     * `Runtime.maxMemory` is the *heap* and says nothing about the page cache, which is what this
     * whole measurement is about — so the figure comes from the OS bean, and if that is unavailable
     * the ratio column is marked rather than guessed at.
     */
    private fun physicalMemoryBytes(): Long {
        val bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
        check(bean is com.sun.management.OperatingSystemMXBean) {
            "cannot read physical memory on this JVM; the vs-RAM column would be a guess"
        }
        return bean.totalMemorySize
    }

    private fun human(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.ROOT, "%.2f GiB", bytes.toDouble() / (1L shl 30))
        bytes >= 1L shl 20 -> String.format(Locale.ROOT, "%.1f MiB", bytes.toDouble() / (1L shl 20))
        else -> String.format(Locale.ROOT, "%.0f KiB", bytes.toDouble() / (1L shl 10))
    }

    @Suppress("unused")
    private fun free(directory: Path): Long = Files.getFileStore(directory).usableSpace
}
