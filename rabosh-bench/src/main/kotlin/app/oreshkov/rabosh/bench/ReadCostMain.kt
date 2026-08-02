package app.oreshkov.rabosh.bench

import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.core.Durability
import app.oreshkov.rabosh.core.Snapshot
import app.oreshkov.rabosh.core.StoreOptions
import app.oreshkov.rabosh.variant.Variant
import java.nio.file.Path
import java.util.Locale

/**
 * Where a point lookup's time goes, measured through the public surface only.
 *
 * Phase 9 measured a present get at 4.18 µs against a bloom-rejected miss at 0.19 µs, with the whole
 * store resident in the page cache — so the 4 µs is **CPU**, not I/O, and phase 13's original
 * question about a store larger than memory cannot explain any of it. This is the experiment that
 * says what it is instead.
 *
 * `SegmentTable.get` reads two blocks, and `SegmentBytes.readBlock` verifies a CRC32C over each one
 * before it is searched. The data block is one [StoreOptions.blockSize]; the **index** block holds one
 * entry per data block, so it is proportional to `segmentBytes / blockSize` and is re-verified on
 * *every* lookup. If that is where the time goes, then point-get cost is a function of segment size
 * and block size and nothing to do with how much memory the machine has.
 *
 * **The block-size sweep is the decisive one, and it is decisive because it moves the two costs in
 * opposite directions.** Growing `blockSize` at a fixed segment size shrinks the index block (fewer
 * entries) and grows the data block (more bytes to CRC), so the two CRCs trade off and the curve
 * has a minimum. A flat line would say re-verification is not the cost and the hypothesis is wrong.
 * The number of segments and levels does not move, so nothing else is varying.
 *
 * ```
 * ./gradlew :rabosh-bench:runReadCost
 * ```
 *
 * Deliberately a `main` rather than a JMH suite, for the reason [AmplificationMain] gives: this is one
 * run of a grid, measured and printed, and a harness built to repeat one operation has nowhere to put
 * a grid. The figures are latencies on one machine and are evidence, never a gate.
 */
object ReadCostMain {

    private const val DOCUMENT_COUNT = 200_000
    private const val PROBES = 1 shl 16
    private const val WARMUP_ROUNDS = 3

    @JvmStatic
    fun main(arguments: Array<String>) {
        val count = arguments.firstOrNull()?.toIntOrNull() ?: DOCUMENT_COUNT
        println("$count documents, ${human(Corpus.sizeBytes(count))} of JSON, everything page-cache resident")
        println()

        // One variable at a time. Growing the block shrinks the index block and grows the data block,
        // so a minimum in this column is the two CRCs trading off against each other.
        println("block size sweep, segments fixed at 8 MiB:")
        header()
        for (blockSize in listOf(1, 2, 4, 8, 16, 32, 64).map { it * 1024 }) {
            measure(count, blockSize = blockSize, segmentMaxBytes = 8L * 1024 * 1024)
        }

        println()
        // Bigger segments mean a bigger index block for the same block size. The level structure moves
        // too, which is a confound this sweep cannot remove — read it as corroboration, not proof.
        println("segment size sweep, blocks fixed at 4 KiB (level structure moves; corroboration only):")
        header()
        for (segmentMiB in listOf(2L, 4L, 8L, 16L, 32L, 64L)) {
            measure(count, blockSize = 4 * 1024, segmentMaxBytes = segmentMiB * 1024 * 1024)
        }
    }

    private fun header() {
        println(
            String.format(
                Locale.ROOT,
                "  %-10s %-10s %8s %10s %10s %12s %12s",
                "block", "segment", "segments", "on disk", "index/seg", "get", "miss",
            ),
        )
    }

    private fun measure(count: Int, blockSize: Int, segmentMaxBytes: Long) {
        val directory = Corpus.scratch("readcost")
        try {
            DocumentStore.open(
                directory,
                StoreOptions(
                    durability = Durability.BUFFERED,
                    memtableMaxBytes = 32L * 1024 * 1024,
                    backgroundMaintenance = false,
                    blockSize = blockSize,
                    segmentMaxBytes = segmentMaxBytes,
                ),
            ).use { store ->
                for (index in 0 until count) {
                    store.put(Corpus.key(index), Variant.fromJson(Corpus.json(index)))
                    if (index % 50_000 == 49_999) store.flush()
                }
                store.flush()
                store.compact()

                val stats = store.stats
                store.snapshot().use { snapshot ->
                    val probes = Corpus.probes(PROBES, count)
                    // Warm the page cache *and* the JIT: an unwarmed first pass would measure the
                    // interpreter and be reported as a property of the block size.
                    repeat(WARMUP_ROUNDS) { timeGets(store, snapshot, probes, count, present = true) }
                    repeat(WARMUP_ROUNDS) { timeGets(store, snapshot, probes, count, present = false) }

                    val get = timeGets(store, snapshot, probes, count, present = true)
                    val miss = timeGets(store, snapshot, probes, count, present = false)

                    // Entries in a segment's index block, which is what the hypothesis says the cost
                    // tracks. Derived rather than read out of the file: the footer is internal, and a
                    // measurement that needed private access would not be a black-box one.
                    val perSegment =
                        if (stats.segmentCount == 0) 0 else stats.segmentBytes / stats.segmentCount / blockSize
                    println(
                        String.format(
                            Locale.ROOT,
                            "  %-10s %-10s %8d %10s %10d %10.2fus %10.2fus",
                            human(blockSize.toLong()),
                            human(segmentMaxBytes),
                            stats.segmentCount,
                            human(stats.segmentBytes),
                            perSegment,
                            get,
                            miss,
                        ),
                    )
                }
            }
        } finally {
            Corpus.deleteRecursively(directory)
        }
    }

    /**
     * Microseconds per lookup over [PROBES] probes.
     *
     * A miss is asked for with a key beyond the corpus, so the bloom filter rejects it and the pair
     * brackets the cost: everything between the two is the block work a present lookup does and a
     * rejected one does not.
     */
    private fun timeGets(
        store: DocumentStore,
        snapshot: Snapshot,
        probes: IntArray,
        count: Int,
        present: Boolean,
    ): Double {
        var sink = 0
        val start = System.nanoTime()
        for (probe in probes) {
            val key = Corpus.key(if (present) probe else probe + count)
            if (store.get(key, snapshot) != null) sink++
        }
        val elapsed = System.nanoTime() - start
        check(if (present) sink == probes.size else sink == 0) { "the probe set is not what it claims" }
        return elapsed / 1_000.0 / probes.size
    }

    private fun human(bytes: Long): String = when {
        bytes >= 1L shl 20 -> String.format(Locale.ROOT, "%.1f MiB", bytes.toDouble() / (1L shl 20))
        bytes >= 1L shl 10 -> String.format(Locale.ROOT, "%.0f KiB", bytes.toDouble() / (1L shl 10))
        else -> "$bytes B"
    }
}
