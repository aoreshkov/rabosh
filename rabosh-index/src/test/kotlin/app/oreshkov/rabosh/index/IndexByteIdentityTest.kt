package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.catalog.CatalogPath
import app.oreshkov.rabosh.catalog.SchemaCatalog
import app.oreshkov.rabosh.catalog.ValueSignature
import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.testkit.json.JsonGens
import app.oreshkov.rabosh.testkit.property.forAll
import app.oreshkov.rabosh.variant.Variant
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * The headline property, and the dividend phase 6 said phase 7 would collect.
 *
 * > A sidecar written by a flush and the same sidecar rebuilt by a backfill are **byte-identical**.
 *
 * Three separate things have to be true for that to hold, and none of them is incidental. Ordinals
 * are assigned by counting `observe` calls, and `SegmentWriter` and `DocumentStore.backfill` share the
 * distinct-key filter that decides what an `observe` call is. Equal sets of ordinals encode to
 * identical bytes, because every bitmap block is normalised to its smallest encoding — §9.6's
 * canonical-form rule, asserted there as equality rather than as agreement precisely so it could be
 * relied on here. And `Variant.fieldName` is name-ordered, so a document read through a memtable's
 * dictionary and the same document read through a segment's enumerate their fields identically.
 *
 * The consequence is that comparing sidecars is comparing *files*, with no need to know how either
 * was produced.
 */
class IndexByteIdentityTest {

    // `uid` is distinct per document, so every one of its terms is a singleton posting held inline in
    // the directory. Without it the densest path in the format would be the one path this property
    // never sees — and byte identity is exactly where an encoding chosen by anything other than the
    // posting list itself would show up.
    private fun document(index: Int): Variant = jsonDocument(
        """{"team":"team-${index % 7}","uid":$index,""" +
            """"score":${index % 23},"tags":["t${index % 3}","t${index % 5}"]}""",
    )

    @Test
    fun `sidecars rebuilt by a backfill are byte-identical to the ones a flush wrote`(@TempDir root: Path) {
        val directory = scratch(root)
        var written: Map<String, ByteArray> = emptyMap()

        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, indexStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                catalog.createIndex(store, IndexDefinition.inverted("$.team"))
                catalog.createIndex(store, IndexDefinition.inverted("$.tags[*]"))
                // A unique-valued index: every posting list is a singleton, so this is the sidecar
                // whose bytes the phase-11 encoding decides.
                catalog.createIndex(store, IndexDefinition.inverted("$.uid"))
                // Both sidecar kinds, because a column's byte identity has three ways to break the
                // inverted index does not: the physical type must be a pure function of the value
                // multiset, the common scale must be the maximum over it, and every bound must be
                // stripped of trailing zeros before it is encoded.
                catalog.createIndex(store, IndexDefinition.column("$.score"))
                catalog.createIndex(store, IndexDefinition.column("$.team"))
                repeat(5) { round ->
                    (0 until 60).forEach { store.put(keyFor(round * 60 + it), document(round * 60 + it)) }
                    store.flush()
                }
                store.compact()
                written = sidecarBytes(directory)
                assertTrue(written.size >= 3, "the fixture must produce several sidecars")
            }
        }

        // Delete every sidecar and rebuild them by rescanning the segments. The registry survives,
        // because an index definition is not derived data — so the ids, and therefore the filenames,
        // are the same ones.
        for (name in sidecarNames(directory)) Files.delete(directory.resolve(name))
        assertTrue(sidecarNames(directory).isEmpty())

        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, indexStoreOptions(catalog)).use { store ->
                catalog.attach(store)
            }
        }

        val rebuilt = sidecarBytes(directory)
        assertEquals(written.keys, rebuilt.keys, "the same set of sidecars")
        for ((name, bytes) in written) {
            assertContentEquals(bytes, rebuilt[name], "$name differs between flush and backfill")
        }
    }

    @Test
    fun `rebuild reproduces the same bytes`(@TempDir root: Path) {
        val directory = scratch(root)
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, indexStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                catalog.createIndex(store, IndexDefinition.inverted("$.team"))
                repeat(4) { round ->
                    (0 until 50).forEach { store.put(keyFor(round * 50 + it), document(round * 50 + it)) }
                    store.flush()
                }
                val before = sidecarBytes(directory)

                catalog.rebuild(store)

                val after = sidecarBytes(directory)
                assertEquals(before.keys, after.keys)
                for ((name, bytes) in before) assertContentEquals(bytes, after[name], name)
                // The definitions are deliberately kept: a repair that lost them would turn a corrupt
                // file into a lost instruction.
                assertEquals(1, catalog.indexes().size)
            }
        }
    }
}

/**
 * The index and the estimator that recommended it must key values the same way.
 *
 * Nearly a tautology since [ValueSignature] was promoted out of the catalog's collector, and that is
 * the point: this pins the promotion so it cannot be quietly unpicked into two copies that drift. An
 * estimator disagreeing with its own index does not crash — it recommends against indexing a column
 * whose real cardinality is small, or builds a dictionary a query can never spell.
 */
class TermAgreementTest {

    @Test
    fun `every term the index builds is a signature the catalog counted`(@TempDir root: Path) {
        val directory = scratch(root)
        val catalog = SchemaCatalog(directory)
        IndexCatalog(directory).use { indexes ->
            DocumentStore.open(
                directory,
                indexStoreOptions(CompositeSegmentObserver(catalog, indexes)),
            ).use { store ->
                catalog.attach(store)
                indexes.attach(store)

                val documents = (0 until 200).map {
                    jsonDocument("""{"team":"team-${it % 9}","score":${it % 5}}""")
                }
                documents.forEachIndexed { index, value -> store.put(keyFor(index), value) }
                store.flush()

                val handle = indexes.createIndex(store, IndexDefinition.inverted("$.team"))
                catalog.attach(store)

                // What the catalog thinks the cardinality of the path is.
                val field = catalog.inferSchema()[CatalogPath.parse("$.team")]
                assertTrue(field != null)
                assertTrue(field.distinctIsExact, "nine values is well below the sparse limit")

                // What the index actually built.
                store.snapshot().use { snapshot ->
                    indexes.read(store, handle, snapshot).use { reader ->
                        val found = (0 until 9).count { team ->
                            IndexQuery.keysEqualTo(store, reader, IndexTerm.ofString("team-$team")).isNotEmpty()
                        }
                        assertEquals(field.distinctEstimate, found.toLong(), "the estimator and the index disagree")
                    }
                }
            }
        }
    }

    @Test
    fun `numbers are one term whatever width the document wrote them at`() {
        // The canonicalisation the estimator has always applied, now shared. A query written with an
        // integer has to find a document that stored a double, or the answer would depend on how a
        // JSON writer happened to render the value.
        val one = IndexTerm.ofNumber(1L)
        assertEquals(one, IndexTerm.of(Variant.fromJson("1"))!!)
        assertEquals(one, IndexTerm.of(Variant.fromJson("1.0"))!!)
        assertEquals(one, IndexTerm.of(Variant.fromJson("1.00"))!!)
        assertEquals(one, IndexTerm.ofNumber(1.0))
        assertEquals(one, IndexTerm.ofNumber(java.math.BigDecimal("1.000")))
    }

    @Test
    fun `a term is exactly the signature the catalog hashes`() {
        forAll(JsonGens.document(), iterations = 100) { value ->
            val variant = jsonDocument(value)
            val term = IndexTerm.of(variant)
            val signature = ValueSignature.of(variant)
            assertEquals(signature == null, term == null)
            if (signature != null) assertEquals(IndexTerm.ofSignature(signature), term)
        }
    }
}
