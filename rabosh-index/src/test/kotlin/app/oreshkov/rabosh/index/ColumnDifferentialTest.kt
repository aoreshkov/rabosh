package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.core.Key
import app.oreshkov.rabosh.variant.Variant
import java.math.BigDecimal
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * The phase's acceptance criterion, as assertions rather than prose:
 * **a column scan touches no document, and bounds prune blocks.**
 *
 * Two rules govern every test here.
 *
 * **`documentsRead == 0` never stands alone.** It passes trivially for a query that returns nothing,
 * so every occurrence sits in the same assertion block as the differential equality against a full
 * scan. Together they say something; separately neither does.
 *
 * **A skip is distinguished from an empty result.** Asserting `blocksSkipped > 0` on a query that
 * matched nothing proves only that the data was arranged badly. The pruning tests therefore arrange
 * segments with disjoint ranges deliberately and assert both what was skipped and what was found.
 */
class ColumnDifferentialTest {

    /** Prices are decimals — which is what `decideNumber` makes of every fractional JSON number. */
    private fun document(index: Int): Variant = jsonDocument(
        """{"price":${index % 500}.${"%02d".format(index % 100)},"qty":${index % 97},""" +
            """"sku":"sku-${"%05d".format(index % 1000)}","live":${index % 3 == 0}}""",
    )

    private fun loaded(directory: Path, count: Int, catalog: IndexCatalog, store: DocumentStore) {
        (0 until count).forEach { store.put(keyFor(it), document(it)) }
        store.flush()
        store.compact()
    }

    private fun assertMatchesScan(
        store: DocumentStore,
        reader: ColumnReader,
        predicate: ColumnPredicate,
        note: String,
        expectNoDocuments: Boolean = true,
    ): ColumnScan {
        val truth = ColumnQuery.scanKeys(store, reader, predicate)
        val actual = ColumnQuery.keysMatching(store, reader, predicate)
        assertEquals(truth.keys, actual.keys, "$note: the column changed the answer")
        assertTrue(actual.usedColumn, "$note: the column was not used at all")
        if (expectNoDocuments) {
            assertTrue(truth.keys.isNotEmpty(), "$note: the fixture must match something")
            assertEquals(0, actual.documentsRead, "$note: the column opened a document")
        }
        return actual
    }

    @Test
    fun `a decimal column answers ranges without opening a document`(@TempDir root: Path) {
        val directory = scratch(root, "column")
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, indexStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                loaded(directory, 400, catalog, store)
                val handle = catalog.createIndex(store, IndexDefinition.column("$.price"))
                assertEquals(app.oreshkov.rabosh.catalog.IndexKind.SHREDDED_COLUMN, handle.kind)

                store.snapshot().use { snapshot ->
                    catalog.readColumn(store, handle, snapshot).use { reader ->
                        assertTrue(reader.isAuthoritative, "everything is flushed, compacted and covered")
                        // A decimal column, not a residual one: this is the case that would have been
                        // entirely unshredded had DOUBLE been the numeric column type.
                        assertTrue(
                            reader.columnTypes().all { it.startsWith("DECIMAL") },
                            "expected decimal columns, got ${reader.columnTypes()}",
                        )

                        assertMatchesScan(
                            store,
                            reader,
                            ColumnPredicate.numericRange(BigDecimal("100"), BigDecimal("200")),
                            "a bounded range",
                        )
                        assertMatchesScan(
                            store,
                            reader,
                            ColumnPredicate.numericRange(BigDecimal("350"), null),
                            "an open upper range",
                        )
                        assertMatchesScan(
                            store,
                            reader,
                            ColumnPredicate.numericRange(null, BigDecimal("10")),
                            "an open lower range",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `an integer column, a string column and a boolean column all match a scan`(@TempDir root: Path) {
        val directory = scratch(root, "column")
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, indexStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                loaded(directory, 400, catalog, store)
                val qty = catalog.createIndex(store, IndexDefinition.column("$.qty"))
                val sku = catalog.createIndex(store, IndexDefinition.column("$.sku"))
                val live = catalog.createIndex(store, IndexDefinition.column("$.live"))

                store.snapshot().use { snapshot ->
                    catalog.readColumn(store, qty, snapshot).use { reader ->
                        assertTrue(reader.columnTypes().all { it == "INT64" }, reader.columnTypes().toString())
                        assertMatchesScan(
                            store,
                            reader,
                            ColumnPredicate.numericRange(BigDecimal("10"), BigDecimal("20")),
                            "an integer range",
                        )
                        assertMatchesScan(store, reader, ColumnPredicate.numericEqualTo(42L), "integer equality")
                    }

                    catalog.readColumn(store, sku, snapshot).use { reader ->
                        assertTrue(reader.columnTypes().all { it == "STRING" }, reader.columnTypes().toString())
                        assertMatchesScan(
                            store,
                            reader,
                            ColumnPredicate.textRange("sku-00100", "sku-00200"),
                            "a string range",
                        )
                        assertMatchesScan(
                            store,
                            reader,
                            ColumnPredicate.textEqualTo("sku-00007"),
                            "string equality",
                        )
                    }

                    catalog.readColumn(store, live, snapshot).use { reader ->
                        assertTrue(reader.columnTypes().all { it == "BOOLEAN" }, reader.columnTypes().toString())
                        assertMatchesScan(store, reader, ColumnPredicate.booleanEqualTo(true), "boolean equality")
                        assertMatchesScan(store, reader, ColumnPredicate.booleanEqualTo(false), "boolean equality")
                    }
                }
            }
        }
    }

    @Test
    fun `bounds prune blocks and whole segments`(@TempDir root: Path) {
        val directory = scratch(root, "column")
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, indexStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                // Disjoint ranges per flush, so a predicate can miss whole segments on their bounds.
                // Arranged deliberately: asserting a skip on data that happens to be sorted proves
                // nothing about the mechanism.
                var written = 0
                for (band in 0 until 5) {
                    repeat(150) {
                        store.put(keyFor(written++), jsonDocument("""{"v":${band * 10_000 + it}}"""))
                    }
                    store.flush()
                }
                val handle = catalog.createIndex(store, IndexDefinition.column("$.v"))

                store.snapshot().use { snapshot ->
                    catalog.readColumn(store, handle, snapshot).use { reader ->
                        // A range inside band 0 only. Everything above must be ruled out.
                        val selective = assertMatchesScan(
                            store,
                            reader,
                            ColumnPredicate.numericRange(BigDecimal("10"), BigDecimal("20")),
                            "a selective range",
                        )
                        assertTrue(
                            selective.segmentsSkipped > 0 || selective.blocksSkipped > 0,
                            "a range matching one band must skip something: $selective",
                        )

                        // A range matching nothing at all: everything is ruled out and no value is
                        // examined. Distinguishing this from "empty because the data was empty" is
                        // why the selective case above asserts a non-empty answer.
                        val impossible = ColumnQuery.keysMatching(
                            store,
                            reader,
                            ColumnPredicate.numericRange(BigDecimal("900000"), BigDecimal("900001")),
                        )
                        assertTrue(impossible.keys.isEmpty())
                        assertEquals(0, impossible.documentsRead)
                        assertEquals(0, impossible.blocksScanned, "nothing should have been examined: $impossible")
                        assertTrue(impossible.segmentsSkipped > 0, "every segment should be ruled out: $impossible")
                    }
                }
            }
        }
    }

    @Test
    fun `absent, null, empty and present are four distinguishable states`(@TempDir root: Path) {
        val directory = scratch(root, "column")
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, indexStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                store.put(Key.of("k:absent"), jsonDocument("""{"other":1}"""))
                store.put(Key.of("k:null"), jsonDocument("""{"a":null,"b":[]}"""))
                store.put(Key.of("k:emptyArray"), jsonDocument("""{"a":[],"b":[]}"""))
                store.put(Key.of("k:nullInArray"), jsonDocument("""{"b":[null]}"""))
                store.put(Key.of("k:scalar"), jsonDocument("""{"a":7,"b":[]}"""))
                store.put(Key.of("k:inArray"), jsonDocument("""{"b":[1,2,3]}"""))
                store.flush()
                store.compact()

                // Two paths, because `$.a` and `$.b[*]` are genuinely different things: the first
                // names a value, the second names every element of an array. A document whose `a` is
                // an array has no *scalar* at `$.a` and contributes nothing to that column — the same
                // rule the catalog counts by, and the reason `CatalogPath.parse` rejects `$.a[0]`.
                val scalar = catalog.createIndex(store, IndexDefinition.column("$.a"))
                val repeated = catalog.createIndex(store, IndexDefinition.column("$.b[*]"))

                store.snapshot().use { snapshot ->
                    catalog.readColumn(store, scalar, snapshot).use { reader ->
                        // Absent, empty-array and array-valued all have no scalar at `$.a`.
                        assertEquals(
                            listOf(Key.of("k:null"), Key.of("k:scalar")),
                            assertMatchesScan(store, reader, ColumnPredicate.exists(), "exists").keys,
                        )
                        assertEquals(
                            listOf(Key.of("k:null")),
                            assertMatchesScan(store, reader, ColumnPredicate.isNull(), "is null").keys,
                        )
                    }

                    catalog.readColumn(store, repeated, snapshot).use { reader ->
                        // An empty array contributes no element, so it is *absent* at `$.b[*]`.
                        assertEquals(
                            listOf(Key.of("k:inArray"), Key.of("k:nullInArray")),
                            assertMatchesScan(store, reader, ColumnPredicate.exists(), "exists").keys,
                        )
                        assertEquals(
                            listOf(Key.of("k:nullInArray")),
                            assertMatchesScan(store, reader, ColumnPredicate.isNull(), "is null").keys,
                        )
                        // A repeated path matches if *any* of its values does.
                        assertEquals(
                            listOf(Key.of("k:inArray")),
                            assertMatchesScan(store, reader, ColumnPredicate.numericEqualTo(2L), "any element").keys,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `a path of mixed types keeps the outliers as residual and still matches a scan`(@TempDir root: Path) {
        val directory = scratch(root, "column")
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, indexStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                // Mostly numbers, with strings and nulls scattered through. The strings cannot be
                // shredded into a numeric column, so their ordinals are residual — and a query for
                // them must still be right, which is what the differential asserts.
                for (index in 0 until 300) {
                    val value = when {
                        index % 17 == 0 -> "\"text-$index\""
                        index % 23 == 0 -> "null"
                        index % 5 == 0 -> "$index.25"
                        else -> "$index"
                    }
                    store.put(keyFor(index), jsonDocument("""{"m":$value}"""))
                }
                store.flush()
                store.compact()
                val handle = catalog.createIndex(store, IndexDefinition.column("$.m"))

                store.snapshot().use { snapshot ->
                    catalog.readColumn(store, handle, snapshot).use { reader ->
                        // Residual ordinals cost document reads, honestly reported. The answer is
                        // still identical to a scan, which is the invariant that matters.
                        val range = ColumnQuery.keysMatching(
                            store,
                            reader,
                            ColumnPredicate.numericRange(BigDecimal("100"), BigDecimal("150")),
                        )
                        val truth = ColumnQuery.scanKeys(
                            store,
                            reader,
                            ColumnPredicate.numericRange(BigDecimal("100"), BigDecimal("150")),
                        )
                        assertEquals(truth.keys, range.keys)
                        assertTrue(range.keys.isNotEmpty())
                        assertTrue(range.documentsRead > 0, "the strings are residual and must be read")

                        // Type bracketing: a numeric predicate does not match a string, and a text
                        // predicate does not match a number.
                        val text = ColumnQuery.keysMatching(store, reader, ColumnPredicate.textEqualTo("text-17"))
                        assertEquals(
                            ColumnQuery.scanKeys(store, reader, ColumnPredicate.textEqualTo("text-17")).keys,
                            text.keys,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `an unflushed document makes the reader non-authoritative and the answer stays right`(
        @TempDir root: Path,
    ) {
        val directory = scratch(root, "column")
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, indexStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                loaded(directory, 200, catalog, store)
                val handle = catalog.createIndex(store, IndexDefinition.column("$.qty"))

                store.put(keyFor(9999), jsonDocument("""{"qty":15}"""))
                store.snapshot().use { snapshot ->
                    catalog.readColumn(store, handle, snapshot).use { reader ->
                        assertFalse(reader.isAuthoritative, "a memtable has no column and never will")
                        val predicate = ColumnPredicate.numericRange(BigDecimal("10"), BigDecimal("20"))
                        val actual = ColumnQuery.keysMatching(store, reader, predicate)
                        assertEquals(ColumnQuery.scanKeys(store, reader, predicate).keys, actual.keys)
                        assertFalse(actual.usedColumn, "it must have fallen back rather than guessed")
                        assertTrue(keyFor(9999) in actual.keys, "the unflushed document must still be found")
                    }
                }
            }
        }
    }

    @Test
    fun `answers match a scan after every step of a write, flush and compact script`(@TempDir root: Path) {
        val directory = scratch(root, "column")
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, indexStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                val handle = catalog.createIndex(store, IndexDefinition.column("$.qty"))
                val predicate = ColumnPredicate.numericRange(BigDecimal("10"), BigDecimal("30"))

                var written = 0
                repeat(12) { step ->
                    repeat(40) { store.put(keyFor(written++), document(written)) }
                    when (step % 3) {
                        0 -> store.flush()
                        1 -> store.compact()
                        else -> Unit // documents left in the memtable, where no column covers them
                    }
                    if (step % 5 == 4) store.delete(keyFor(step))

                    store.snapshot().use { snapshot ->
                        catalog.readColumn(store, handle, snapshot).use { reader ->
                            assertEquals(
                                ColumnQuery.scanKeys(store, reader, predicate).keys,
                                ColumnQuery.keysMatching(store, reader, predicate).keys,
                                "after step $step",
                            )
                        }
                    }
                }
            }
        }
    }
}
