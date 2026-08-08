package app.oreshkov.rabosh.bench

import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.core.Durability
import app.oreshkov.rabosh.core.Key
import app.oreshkov.rabosh.core.StoreOptions
import app.oreshkov.rabosh.index.IndexCatalog
import app.oreshkov.rabosh.index.IndexDefinition
import app.oreshkov.rabosh.query.Query
import app.oreshkov.rabosh.query.QueryEngine
import app.oreshkov.rabosh.query.and
import app.oreshkov.rabosh.query.elemMatch
import app.oreshkov.rabosh.query.path
import app.oreshkov.rabosh.variant.Variant
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.random.Random

/**
 * The gate on an element ordinal space: what the element walk costs, against the row it rides on.
 *
 * ```
 * ./gradlew :rabosh-bench:runElementAccessCost
 * ```
 *
 * §10.6's gate is *Tier 0's walk too slow **and** Tier 1's composite term insufficient*. This prices
 * the first, and [ElementAccessCost] argues that the second is a multiplier on it rather than an
 * independent condition. Three measurements per row, over the same corpus and the same snapshot:
 *
 * - **read** — a full scan whose predicate every document satisfies at the top level. The floor: read,
 *   decode, hand back. No element is visited.
 * - **walk** — the same scan with an `elemMatch`, so every element of every document is visited and a
 *   per-element predicate is evaluated. The Tier 0 path.
 * - **lookup** — the same `elemMatch` with a composite index over it. The Tier 1 path, which returns
 *   the same rows having opened no document at all.
 *
 * The difference between the first two is the walk. Its share of the second is the **ceiling** on
 * what an element ordinal space could ever remove from a query, because an index that knew which
 * element matched would still have to read the document to return it.
 *
 * A `main` rather than a JMH suite, for the reason `ReadCostMain` and `QueryCostMain` are: this is one
 * run of a curve over elements per document, and a harness built to repeat one operation has nowhere
 * to put one. Nothing in CI runs it and nothing asserts its timings — the arithmetic it feeds is
 * covered by `ElementAccessCostTest`.
 */
object ElementAccessCostMain {

    private const val DOCUMENTS = 20_000
    private const val WARMUPS = 3
    private const val RUNS = 5
    private val ELEMENT_COUNTS = intArrayOf(1, 2, 4, 8, 16, 32, 64)

    @JvmStatic
    fun main(arguments: Array<String>) {
        val root = Files.createTempDirectory("rabosh-element-access")
        try {
            println("what an element ordinal space could remove from a query")
            println("  documents      : $DOCUMENTS")
            println("  question       : elemMatch(\$.items[*], sku == 'sku-0' and qty == 'qty-0')")
            println("  floor          : the same scan with a top-level predicate, visiting no element")
            println()
            println("  elements  read/doc  traverse/doc  walk/doc  traversal/elem  matcher/elem  lookup/doc")

            for (elements in ELEMENT_COUNTS) {
                val measured = measure(root.resolve("k$elements"), elements)
                val traversal = (measured.traverseNanosPerDocument - measured.cost.readNanosPerDocument) / elements
                val matcher = (measured.cost.walkNanosPerDocument - measured.traverseNanosPerDocument) / elements
                println(
                    "  %8d  %8.1f  %12.1f  %8.1f  %14.1f  %12.1f  %10.1f".format(
                        elements,
                        measured.cost.readNanosPerDocument,
                        measured.traverseNanosPerDocument,
                        measured.cost.walkNanosPerDocument,
                        traversal,
                        matcher,
                        measured.lookupNanosPerDocument,
                    ),
                )
            }

            println()
            println("traversal/elem is what visiting an element costs; matcher/elem is what evaluating")
            println("a per-element predicate adds on top. An element ordinal space removes both and")
            println("keeps the read, so read/doc is the floor it cannot go below. Reassembly cannot")
            println("separate it from today, because today already reads a parent once.")
        } finally {
            @OptIn(kotlin.io.path.ExperimentalPathApi::class)
            root.deleteRecursively()
        }
    }

    /** The walk measurement, and the indexed lookup's own per-document cost beside it. */
    private class Measured(
        val cost: ElementAccessCost,
        /** The same elements visited by one flat walk, with no per-element matcher. */
        val traverseNanosPerDocument: Double,
        val lookupNanosPerDocument: Double,
    )

    private fun measure(directory: Path, elements: Int): Measured {
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, options(catalog)).use { store ->
                catalog.attach(store)
                load(store, elements)
                store.compact()

                val engine = QueryEngine(store, catalog)
                val floor = Query.where(path("$.tenant") eq "t")
                val correlated = Query.where(
                    elemMatch("$.items[*]", and(path("$.sku") eq "sku-0", path("$.qty") eq "qty-0")),
                )

                // The decomposing row. This visits *the same elements* as the `elemMatch` — one
                // `TermExtractor` over two `[*]` paths — and differs in exactly one mechanism: no
                // per-element matcher, because a conjunction settles each leaf from any element. So
                // the gap between it and the walk is the per-element evaluation, and the gap between
                // it and the floor is the traversal itself. Without this row a slow walk cannot be
                // told apart from a slow *implementation* of one, which is the error phase 13 caught
                // phase 12 making.
                val uncorrelated = Query.where(
                    and(path("$.items[*].sku") eq "sku-0", path("$.items[*].qty") eq "qty-0"),
                )

                val read = time { store.snapshot().use { engine.keys(floor, it) } }
                val traverse = time { store.snapshot().use { engine.keys(uncorrelated, it) } }
                val walk = time { store.snapshot().use { engine.keys(correlated, it) } }

                catalog.createIndex(store, IndexDefinition.composite("$.items[*]", "$.sku", "$.qty"))
                val matched = store.snapshot().use { engine.keys(correlated, it) }.size
                val lookup = time { store.snapshot().use { engine.keys(correlated, it) } }

                check(matched > 0) { "the fixture matched nothing at $elements element(s)" }
                return Measured(
                    ElementAccessCost(
                        readNanosPerDocument = read / DOCUMENTS,
                        walkNanosPerDocument = walk / DOCUMENTS,
                        elementsPerDocument = elements,
                    ),
                    traverseNanosPerDocument = traverse / DOCUMENTS,
                    lookupNanosPerDocument = lookup / DOCUMENTS,
                )
            }
        }
    }

    /** Median of [RUNS] after [WARMUPS], in nanoseconds. A median, because a mean follows a GC pause. */
    private fun time(body: () -> Unit): Double {
        repeat(WARMUPS) { body() }
        val samples = DoubleArray(RUNS) {
            val started = System.nanoTime()
            body()
            (System.nanoTime() - started).toDouble()
        }
        samples.sort()
        return samples[RUNS / 2]
    }

    private fun load(store: DocumentStore, elements: Int) {
        val random = Random(20260809L)
        for (index in 0 until DOCUMENTS) {
            val items = (0 until elements).joinToString(",") {
                """{"sku":"sku-${random.nextInt(SKUS)}","qty":"qty-${random.nextInt(QUANTITIES)}"}"""
            }
            store.put(Key.of("doc:%07d".format(index)), Variant.fromJson("""{"tenant":"t","items":[$items]}"""))
        }
        store.flush()
    }

    private fun options(catalog: IndexCatalog): StoreOptions = StoreOptions(
        durability = Durability.BUFFERED,
        backgroundMaintenance = false,
        segmentObserver = catalog,
    )

    private const val SKUS = 40
    private const val QUANTITIES = 8
}
