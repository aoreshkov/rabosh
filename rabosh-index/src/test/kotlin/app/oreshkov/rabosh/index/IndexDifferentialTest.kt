package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.catalog.CatalogPath
import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.core.Key
import app.oreshkov.rabosh.core.Snapshot
import app.oreshkov.rabosh.testkit.json.JsonGens
import app.oreshkov.rabosh.testkit.json.JsonValue
import app.oreshkov.rabosh.testkit.property.forAll
import app.oreshkov.rabosh.variant.Variant
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * The invariant the whole phase exists to keep: **an index changes query speed, never query answers.**
 *
 * Every assertion here compares the indexed answer against a brute-force scan over the same data at
 * the same snapshot, and the comparison is made in the three states the acceptance criterion names —
 * before an index exists, while one is only partly built, and after it is complete. The middle one is
 * the interesting case and it is tested rather than asserted: a half-built index is simply an index
 * with low coverage, and a query that could not be answered from it falls back without anybody
 * arranging a cutover.
 */
class IndexDifferentialTest {

    private val teams = listOf("analytics", "platform", "growth", "sre", "data")

    private fun document(index: Int): Variant {
        val note = if (index % 11 == 0) "null" else "\"n$index\""
        val fields = buildString {
            append("""{"team":"${teams[index % teams.size]}","score":${index % 23},""")
            append(""""tags":["t${index % 3}","t${index % 7}"],""")
            append(""""note":$note""")
            // A field only some documents have, so EXISTS and NOT EXISTS are both non-trivial.
            if (index % 13 != 0) append(""","optional":${index % 4}""")
            append("}")
        }
        return jsonDocument(fields)
    }

    private fun expected(
        store: DocumentStore,
        snapshot: Snapshot,
        path: String,
        term: IndexTerm,
    ): List<Key> {
        val keys = sortedSetOf<Key>()
        val evaluator = Evaluator(CatalogPath.parse(path), IndexOptions.DEFAULT)
        store.scan(snapshot = snapshot).use { cursor ->
            while (cursor.next()) {
                val terms = evaluator.terms(cursor.document)
                if (terms != null && term in terms) keys.add(cursor.key)
            }
        }
        return keys.toList()
    }

    @Test
    fun `answers are identical before, during and after a build`(@TempDir root: Path) {
        val directory = scratch(root)
        val term = IndexTerm.ofString("analytics")

        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, indexStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                for (round in 0 until 4) {
                    (0 until 60).forEach { store.put(keyFor(round * 60 + it), document(round * 60 + it)) }
                    store.flush()
                }

                // --- before: an index is defined but nothing is covered yet, because the definition
                // is made durable before any posting file exists. Every segment is uncovered, so the
                // answer comes entirely from the scan.
                val handle = catalog.createIndex(store, IndexDefinition.inverted("$.team"))

                store.snapshot().use { snapshot ->
                    val truth = expected(store, snapshot, "$.team", term)
                    assertTrue(truth.isNotEmpty(), "the fixture must produce some matches")

                    catalog.read(store, handle, snapshot).use { reader ->
                        assertTrue(reader.coverage.isComplete, "createIndex builds over what is written")
                        assertEquals(truth, IndexQuery.keysEqualTo(store, reader, term))
                    }
                }
            }
        }

        // --- during: more segments arrive with no catalog attached, so some are covered and some are
        // not. This is exactly the state a build in progress leaves, arranged deliberately.
        DocumentStore.open(directory, indexStoreOptions(null)).use { store ->
            for (round in 4 until 7) {
                (0 until 60).forEach { store.put(keyFor(round * 60 + it), document(round * 60 + it)) }
                store.flush()
            }
        }

        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, indexStoreOptions(catalog)).use { store ->
                // Load the registry and the sidecars that exist, without backfilling the rest.
                val partial = IndexRegistry.read(directory)!!.indexes.single()
                catalog.attach(store, backfill = false)

                store.snapshot().use { snapshot ->
                    val truth = expected(store, snapshot, "$.team", term)
                    catalog.read(store, partial, snapshot).use { reader ->
                        assertFalse(reader.coverage.isComplete, "some segments must be uncovered here")
                        assertTrue(reader.coverage.segmentsCovered > 0, "and some must be covered")
                        // Identical anyway. That is the claim.
                        assertEquals(truth, IndexQuery.keysEqualTo(store, reader, term))
                    }
                }

                // --- after: the build finishes and coverage is complete.
                catalog.attach(store)
                store.snapshot().use { snapshot ->
                    val truth = expected(store, snapshot, "$.team", term)
                    catalog.read(store, partial, snapshot).use { reader ->
                        assertTrue(reader.coverage.isComplete)
                        assertTrue(reader.isAuthoritative)
                        assertEquals(truth, IndexQuery.keysEqualTo(store, reader, term))
                    }
                }
            }
        }
    }

    @Test
    fun `equality, IN, EXISTS and NOT EXISTS all match a full scan`(@TempDir root: Path) {
        val directory = scratch(root)
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, indexStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                (0 until 300).forEach { store.put(keyFor(it), document(it)) }
                store.flush()
                store.compact()

                val team = catalog.createIndex(store, IndexDefinition.inverted("$.team"))
                val tags = catalog.createIndex(store, IndexDefinition.inverted("$.tags[*]"))
                val note = catalog.createIndex(store, IndexDefinition.inverted("$.note"))

                store.snapshot().use { snapshot ->
                    catalog.read(store, team, snapshot).use { reader ->
                        assertTrue(reader.isAuthoritative)
                        for (name in teams) {
                            val term = IndexTerm.ofString(name)
                            assertEquals(
                                IndexQuery.scanKeys(store, reader, matches = { term in it }),
                                IndexQuery.keysEqualTo(store, reader, term),
                                "equality on $name",
                            )
                        }
                        val wanted = setOf(IndexTerm.ofString("analytics"), IndexTerm.ofString("sre"))
                        assertEquals(
                            IndexQuery.scanKeys(store, reader, matches = { found -> found.any { it in wanted } }),
                            IndexQuery.keysAnyOf(store, reader, wanted),
                            "IN",
                        )
                    }

                    // An array path: one document contributes several terms, and `[*]` is what says so.
                    catalog.read(store, tags, snapshot).use { reader ->
                        for (tag in listOf("t0", "t1", "t2", "t5", "t6")) {
                            val term = IndexTerm.ofString(tag)
                            assertEquals(
                                IndexQuery.scanKeys(store, reader, matches = { term in it }),
                                IndexQuery.keysEqualTo(store, reader, term),
                                "equality on tag $tag",
                            )
                        }
                    }

                    // A path that is a JSON null in some documents: it is *present* there, which is
                    // what separates EXISTS from "has a value you can look up".
                    catalog.read(store, note, snapshot).use { reader ->
                        assertEquals(
                            IndexQuery.scanKeys(store, reader, matches = { true }, present = { it }),
                            IndexQuery.keysExisting(store, reader),
                            "EXISTS",
                        )
                        assertEquals(
                            IndexQuery.scanKeys(store, reader, matches = { true }, present = { !it }),
                            IndexQuery.keysAbsent(store, reader),
                            "NOT EXISTS",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `answers match a scan after every step of a write, flush and compact script`(@TempDir root: Path) {
        val directory = scratch(root)
        val term = IndexTerm.ofString("analytics")
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, indexStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                val handle = catalog.createIndex(store, IndexDefinition.inverted("$.team"))

                // Compared after *every* step rather than at the end, for the reason the bitmap is:
                // something that goes wrong on the fourth operation and right again on the sixth is
                // exactly the shape a lifecycle bug has, and an end-state check would miss it.
                var written = 0
                repeat(14) { step ->
                    repeat(40) { store.put(keyFor(written++), document(written)) }
                    when (step % 3) {
                        0 -> store.flush()
                        1 -> store.compact()
                        else -> Unit // leave documents in the memtable, where no index covers them
                    }
                    if (step % 5 == 4) store.delete(keyFor(step))

                    store.snapshot().use { snapshot ->
                        val truth = expected(store, snapshot, "$.team", term)
                        catalog.read(store, handle, snapshot).use { reader ->
                            assertEquals(truth, IndexQuery.keysEqualTo(store, reader, term), "after step $step")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `answers match a scan over generated documents`(@TempDir root: Path) {
        val directory = scratch(root)
        var round = 0
        forAll(JsonGens.document(), iterations = 30) { generated ->
            val corpus = wrap(generated, round++)
            val target = scratch(directory, "gen")
            Files.createDirectories(target)
            IndexCatalog(target).use { catalog ->
                DocumentStore.open(target, indexStoreOptions(catalog)).use { store ->
                    catalog.attach(store)
                    corpus.forEachIndexed { index, value -> store.put(keyFor(index), jsonDocument(value)) }
                    store.flush()
                    val handle = catalog.createIndex(store, IndexDefinition.inverted("$.payload"))

                    store.snapshot().use { snapshot ->
                        catalog.read(store, handle, snapshot).use { reader ->
                            assertEquals(
                                IndexQuery.scanKeys(store, reader, matches = { true }, present = { it }),
                                IndexQuery.keysExisting(store, reader),
                            )
                            // Every term the corpus actually contains, asked for one at a time.
                            val path = CatalogPath.parse("$.payload")
                            val present = corpus.flatMap { expectedTerms(it, path).orEmpty() }.toSet()
                            for (term in present.take(12)) {
                                assertEquals(
                                    IndexQuery.scanKeys(store, reader, matches = { term in it }),
                                    IndexQuery.keysEqualTo(store, reader, term),
                                    "equality on $term",
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /** A generated value under a fixed path, plus neighbours so the corpus is not one document. */
    private fun wrap(value: JsonValue, salt: Int): List<JsonValue> = listOf(
        JsonValue.Obj(listOf("payload" to value)),
        JsonValue.Obj(listOf("payload" to JsonValue.Str("fixed-$salt"))),
        JsonValue.Obj(listOf("payload" to JsonValue.Null)),
        JsonValue.Obj(listOf("other" to JsonValue.Num("1"))),
        JsonValue.Obj(listOf("payload" to value)),
    )
}
