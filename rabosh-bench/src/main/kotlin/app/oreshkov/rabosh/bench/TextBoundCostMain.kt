package app.oreshkov.rabosh.bench

import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.core.Durability
import app.oreshkov.rabosh.core.Key
import app.oreshkov.rabosh.core.StoreOptions
import app.oreshkov.rabosh.index.IndexCatalog
import app.oreshkov.rabosh.index.IndexDefinition
import app.oreshkov.rabosh.index.IndexOptions
import app.oreshkov.rabosh.query.Query
import app.oreshkov.rabosh.query.QueryEngine
import app.oreshkov.rabosh.query.path
import app.oreshkov.rabosh.variant.Variant
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.io.path.fileSize
import kotlin.random.Random

/**
 * The §10.4 sweep: what a column's text bound width buys, and what it costs.
 *
 * ```
 * ./gradlew :rabosh-bench:runTextBoundCost
 * ```
 *
 * **It measures `IndexOptions.columnTextBoundBytes`, not `CatalogOptions.textBoundBytes`**, and the
 * distinction is the finding that had to come before the measurement. Both default to 64 and the item
 * was written against the second, but a `.cat` sketch's bounds are descriptive — rendered, readable,
 * and consulted by nothing in `rabosh-query`. A shredded column's bounds are what `ColumnReader` skips
 * on, at both levels: `mayContain(column.bounds)` rules out a whole segment, and `mayContain(column,
 * block)` rules out a block. Both are truncated at `columnTextBoundBytes`. See [TextBoundCost].
 *
 * ## The sweep, and why it runs over two corpora
 *
 * The axis is the bound width, over a corpus whose values share a **[SHARED_PREFIX_BYTES]-byte
 * prefix** — the protobuf-JSON `@type` shape, `type.googleapis.com/…`, which is where the default was
 * observed to stop earning its keep. Two costs move in opposite directions with the axis: pruning
 * rises, sidecar bytes rise. That is the `ReadCostMain` shape and it is falsifiable in a way a
 * before/after is not — if the bound is the binding constraint the curve has a knee at the prefix, and
 * if it is not the curve is flat.
 *
 * **Block pruning is a locality property, so the fixture decides the answer and the unfavourable case
 * is arranged rather than hoped for.** Two corpora, identical but for the order the values arrive in:
 *
 * - **clustered** — the discriminating suffix ascends with the key, so a block's values occupy a
 *   narrow range and a bound wide enough to see the suffix can rule the block out. The shape a bound
 *   can help.
 * - **interleaved** — the same values, permuted, so every block spans nearly the whole range. No bound
 *   of any width prunes anything here, and reporting only the first would be arranging the favourable
 *   case for a dial.
 *
 * A `main` rather than a JMH suite for the reason `ReadCostMain` and `ElementAccessCostMain` are: this
 * is one run of a curve, and a harness built to repeat one operation has nowhere to put one. Nothing
 * in CI runs it. What it reports are **plan statistics rather than timings** — blocks skipped, bytes
 * on disk — so unlike the two sweeps beside it, its numbers are facts about the engine rather than
 * about the machine; the arithmetic they feed is pinned by `TextBoundCostTest`.
 */
object TextBoundCostMain {

    /** `type.googleapis.com/com.example.game.v1.` — 40 bytes, and every value starts with it. */
    const val SHARED_PREFIX: String = "type.googleapis.com/com.example.game.v1."

    /** The prefix's length in bytes. ASCII, so bytes and characters coincide. */
    const val SHARED_PREFIX_BYTES: Int = 40

    private const val DOCUMENTS = 160_000

    /** Straddling [SHARED_PREFIX_BYTES] deliberately: the step is the thing being looked for. */
    private val BOUND_WIDTHS = intArrayOf(8, 16, 24, 32, 40, 41, 44, 48, 56, 64, 96)

    private const val PROBES = 16

    @JvmStatic
    fun main(arguments: Array<String>) {
        val root = Files.createTempDirectory("rabosh-text-bound")
        try {
            println("what a column's text bound width buys, and what it costs")
            println("  documents      : $DOCUMENTS")
            println("  path           : \$.type, a shredded column")
            println("  shared prefix  : '$SHARED_PREFIX' ($SHARED_PREFIX_BYTES bytes)")
            println("  probe          : \$.type == <an existing value>, $PROBES of them, averaged")
            println()

            for (clustered in booleanArrayOf(true, false)) {
                println(if (clustered) "  clustered — the suffix ascends with the key" else "  interleaved — permuted")
                println("    bound  discriminating  blocks  skip rate  column bytes  bound bytes (model)")

                for (width in BOUND_WIDTHS) {
                    val row = measure(root.resolve("${if (clustered) "c" else "i"}$width"), width, clustered)
                    println(
                        "    %5d  %14d  %6d  %9.3f  %12d  %19d".format(
                            row.boundBytes,
                            TextBoundCost.discriminatingBytes(row.boundBytes, SHARED_PREFIX_BYTES),
                            row.columnBlocks,
                            row.skipRate,
                            row.columnBytes,
                            TextBoundCost.predictedBoundBytes(row.boundBytes, row.columnBlocks),
                        ),
                    )
                }
                println()
            }

            println("A bound at or below the shared prefix prunes nothing at all — every bound in the")
            println("column collapses to one prefix and the incremented maximum covers everything that")
            println("prefix can start. That is a step rather than a decline. Past it, pruning is bought")
            println("only where the values have locality with key order; the interleaved rows are the")
            println("same data in a different order and buy nothing at any width.")
        } finally {
            @OptIn(kotlin.io.path.ExperimentalPathApi::class)
            root.deleteRecursively()
        }
    }

    /** One row: a store built at [boundBytes], probed, and measured. */
    fun measure(directory: Path, boundBytes: Int, clustered: Boolean): TextBoundCost {
        val options = IndexOptions(columnTextBoundBytes = boundBytes)
        IndexCatalog(directory, options).use { catalog ->
            DocumentStore.open(directory, storeOptions(catalog)).use { store ->
                catalog.attach(store)
                load(store, clustered)
                store.compact()
                catalog.createIndex(store, IndexDefinition.column("$.type"))

                val engine = QueryEngine(store, catalog)
                var skipped = 0
                var scanned = 0
                var matched = 0
                store.snapshot().use { snapshot ->
                    for (probe in 0 until PROBES) {
                        // Spread across the key space so no probe is special, and existing values so
                        // the *segment* is never skipped outright — this is a question about blocks.
                        val index = (probe.toLong() * DOCUMENTS / PROBES).toInt()
                        val query = Query.where(path("$.type") eq typeOf(index, clustered))
                        engine.execute(query, snapshot).use { cursor ->
                            while (cursor.next()) matched++
                            skipped += cursor.stats.blocksSkipped
                            scanned += cursor.stats.blocksScanned
                        }
                    }
                }

                // A skip rate over probes that matched nothing would be a measurement of an empty
                // answer: every block is skippable when there is nothing to find. The standing rule
                // that an assertion about work never stands alone, applied to a diagnostic.
                check(matched == PROBES) { "expected one row per probe at $boundBytes bytes, got $matched" }

                return TextBoundCost(
                    boundBytes = boundBytes,
                    sharedPrefixBytes = SHARED_PREFIX_BYTES,
                    probes = PROBES,
                    blocksSkipped = skipped,
                    blocksScanned = scanned,
                    columnBytes = columnBytes(directory),
                )
            }
        }
    }

    /**
     * The value at [index], which is [SHARED_PREFIX] plus six discriminating digits.
     *
     * Under [clustered] the digits ascend with the key, so values and key order agree and a block
     * covers a contiguous range. Otherwise they are permuted by a fixed multiplier coprime with
     * [DOCUMENTS], which is a bijection — **the same multiset of values in a different order**, so the
     * two corpora differ in locality and in nothing else. A different value set would have made the
     * comparison a comparison of corpora.
     */
    fun typeOf(index: Int, clustered: Boolean): String {
        val discriminator = if (clustered) index else (index.toLong() * 97 % DOCUMENTS).toInt()
        return SHARED_PREFIX + "%06d".format(discriminator)
    }

    private fun load(store: DocumentStore, clustered: Boolean) {
        // Present but unindexed, so a document is more than the one path under measurement.
        val random = Random(20260809L)
        for (index in 0 until DOCUMENTS) {
            val json = """{"tenant":"t","seq":${random.nextInt(1000)},"type":"${typeOf(index, clustered)}"}"""
            store.put(Key.of("doc:%07d".format(index)), Variant.fromJson(json))
        }
        store.flush()
    }

    private fun columnBytes(directory: Path): Long =
        Files.newDirectoryStream(directory, "*.col").use { entries -> entries.sumOf { it.fileSize() } }

    private fun storeOptions(catalog: IndexCatalog): StoreOptions = StoreOptions(
        durability = Durability.BUFFERED,
        backgroundMaintenance = false,
        segmentObserver = catalog,
    )
}
