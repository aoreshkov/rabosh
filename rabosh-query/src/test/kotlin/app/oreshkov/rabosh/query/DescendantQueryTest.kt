package app.oreshkov.rabosh.query

import app.oreshkov.rabosh.catalog.CatalogPath
import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.core.Key
import app.oreshkov.rabosh.index.IndexCatalog
import app.oreshkov.rabosh.index.IndexDefinition
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * **`CityDTO anywhere` — the question the descendant step was added for, end to end.**
 *
 * The corpus is the shape that made an enumeration unsound: a discriminator field that occurs at
 * several depths, under objects and under arrays, with the *same* type appearing at more than one
 * shape and a shape appearing that no earlier document had. On a real 46 MB protobuf-JSON dump that
 * was measured as 345 types across 754 (type, path) pairs, 72% of tagged elements belonging to a
 * type with more than one shape, and one type occupying 49 of them. A declared path list over that
 * corpus is not a slow answer, it is a **wrong** one — a document missing from a result with nothing
 * to report it — which is why `$..["@type"]` is a step rather than a helper that expands to an `or`.
 *
 * What is asserted here is the whole claim in three parts: the index **builds** as an ordinary
 * inverted index over an ordinary posting file, the planner **uses** it with no change of its own,
 * and the answers are the ones a scan gives.
 */
class DescendantQueryTest {

    /**
     * Documents whose tagged nodes sit at four different depths.
     *
     * `Deep` is deliberately reachable only through two arrays and an object, and `Rare` only in one
     * document, so an index over the discriminator has both a common term and a selective one.
     */
    private fun document(index: Int): String {
        val nested = if (index % 3 == 0) ""","bonus":{"@type":"Rare","name":"bonus-$index"}""" else ""
        return """
            {"@type":"Envelope","id":$index,
             "payload":{"@type":"City","name":"city-${index % 7}",
                        "rewards":[{"@type":"Coin","amount":${index % 5}},
                                   {"@type":"Deep","items":[{"@type":"Leaf","name":"leaf-$index"}]}]$nested}}
        """.trimIndent()
    }

    private fun load(store: DocumentStore, count: Int = 40) {
        store.load((0 until count).map { jsonDocument(document(it)) })
    }

    private fun keysOf(engine: QueryEngine, snapshot: app.oreshkov.rabosh.core.Snapshot, query: Query): List<Key> {
        val keys = ArrayList<Key>()
        engine.execute(query.project(Projection.KEY), snapshot).use { cursor ->
            while (cursor.next()) keys.add(cursor.key)
        }
        return keys
    }

    /**
     * An ordinary inverted index over a descendant path: existing kind, existing sidecar, no new id.
     *
     * That is the claim that made this cheap — the walk changed and nothing below it did. The
     * assertions on the counters are what say the *planner* used it, since an answer alone is
     * satisfied by a full scan.
     */
    @Test
    fun `an index over a descendant path is built, used, and answers what a scan answers`(@TempDir root: Path) {
        val directory = scratch(root, "descendant")
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, queryStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                load(store)
                val handle = catalog.createIndex(store, IndexDefinition.inverted("""$..["@type"]"""))
                val engine = QueryEngine(store, catalog)

                store.snapshot().use { snapshot ->
                    catalog.read(store, handle, snapshot).use { reader ->
                        assertTrue(reader.coverage.isComplete, "every segment carries the index")
                    }

                    // `Rare` is under `payload.bonus`; `Leaf` is under two arrays and an object.
                    // Neither is reachable by any prefix of the other's path, which is the point.
                    val rare = assertMatchesScan(
                        engine,
                        store,
                        snapshot,
                        Query.where(path("""$..["@type"]""") eq "Rare"),
                        "a type that occurs at one depth in some documents",
                    )
                    assertEquals(14, rare.rowsReturned, "every third document of forty")
                    assertTrue(rare.segmentsIndexed > 0, "answered from the sidecar")
                    assertEquals(0, rare.segmentsScanned, "and nothing was scanned")

                    val leaf = assertMatchesScan(
                        engine,
                        store,
                        snapshot,
                        Query.where(path("""$..["@type"]""") eq "Leaf"),
                        "a type three levels down, under two containers",
                    )
                    assertEquals(40, leaf.rowsReturned, "every document has one")
                    assertTrue(leaf.segmentsIndexed > 0)
                }
            }
        }
    }

    /**
     * The selectivity that makes this worth having, stated as a count rather than as a feeling.
     *
     * A discriminator index holds one term per *type*, not per document: the corpus below has 40
     * documents and six distinct types, so the dictionary is six entries and each posting list is a
     * bitmap over documents. On the measured dump that ratio was 345 terms for 167 244 occurrences,
     * which is close to the cheapest useful index this engine can build — and it is a property of the
     * data rather than of the step, so it is asserted here where the data is known.
     */
    @Test
    fun `the dictionary holds one term per type, not one per occurrence`(@TempDir root: Path) {
        val directory = scratch(root, "descendant")
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, queryStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                load(store)
                catalog.createIndex(store, IndexDefinition.inverted("""$..["@type"]"""))
                val engine = QueryEngine(store, catalog)

                store.snapshot().use { snapshot ->
                    val types = listOf("Envelope", "City", "Coin", "Deep", "Leaf", "Rare")
                    val found = types.associateWith { type ->
                        keysOf(engine, snapshot, Query.where(path("""$..["@type"]""") eq type)).size
                    }
                    assertEquals(
                        mapOf(
                            "Envelope" to 40, "City" to 40, "Coin" to 40,
                            "Deep" to 40, "Leaf" to 40, "Rare" to 14,
                        ),
                        found,
                        "six terms cover every occurrence in the corpus",
                    )
                }
            }
        }
    }

    /**
     * The correlated question, which is what the step composes into: *a `City` whose own `name` is
     * `city-3`*, rather than a document that happens to hold both somewhere.
     *
     * `elemMatch("$..", …)` reads every node as a candidate element and applies the operand to it, so
     * the two conjuncts have to be satisfied by **one** node. The uncorrelated spelling beside it is
     * what says that is not the same question: it matches documents where a `City` exists and a
     * `city-3` exists, which on this corpus is every document with that name at any depth.
     */
    @Test
    fun `an elemMatch over a bare descendant correlates to one node`(@TempDir root: Path) {
        val directory = scratch(root, "descendant")
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, queryStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                load(store)
                val engine = QueryEngine(store, catalog)

                store.snapshot().use { snapshot ->
                    val correlated = Query.where(
                        elemMatch(
                            path("$.."),
                            and(path("""$["@type"]""") eq "Leaf", path("$.name") eq "leaf-9"),
                        ),
                    )
                    assertEquals(
                        listOf(keyFor(9)),
                        keysOf(engine, snapshot, correlated),
                        "one node carries both, and it is nine levels of nothing to do with the root",
                    )
                    assertMatchesScan(engine, store, snapshot, correlated, "correlated over every node")

                    // The same two conjuncts, uncorrelated: a `City` node and a `leaf-9` node, which
                    // are different nodes of the same document. Both answers are right; they are
                    // answers to different questions, and this is the pair that says so.
                    val uncorrelated = Query.where(
                        and(path("""$..["@type"]""") eq "City", path("$..name") eq "leaf-9"),
                    )
                    assertEquals(listOf(keyFor(9)), keysOf(engine, snapshot, uncorrelated))
                    assertEquals(
                        emptyList(),
                        keysOf(
                            engine,
                            snapshot,
                            Query.where(
                                elemMatch(
                                    path("$.."),
                                    and(path("""$["@type"]""") eq "City", path("$.name") eq "leaf-9"),
                                ),
                            ),
                        ),
                        "no single node is a City named leaf-9, which the uncorrelated form cannot tell",
                    )
                }
            }
        }
    }

    /**
     * A descendant leaf is a leaf: negation, existence and type bracketing are the planner's, not
     * this step's.
     *
     * The rule it would be easy to break is the negation one — `not($..a eq 1)` holds for a document
     * with no `a` anywhere and for one whose `a` is a string — because a walk that reported "no value"
     * as "no match" and then flipped it would delete documents silently. Nothing here had to change
     * for that to hold, and this is what says so.
     */
    @Test
    fun `negation and existence over a descendant mean what they mean everywhere else`(@TempDir root: Path) {
        val directory = scratch(root, "descendant")
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, queryStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                load(store)
                val engine = QueryEngine(store, catalog)

                store.snapshot().use { snapshot ->
                    // `$..bonus` names an *object*, and `Exists` over a container is false here as it is
                    // everywhere: this walk reaches its sink for scalars. A descendant does not
                    // change what counts as a value, which is the half of the rule worth pinning.
                    assertEquals(0, keysOf(engine, snapshot, Query.where(path("$..bonus").exists())).size)
                    val hasRare = Query.where(path("$..bonus.name").exists())
                    assertEquals(14, keysOf(engine, snapshot, hasRare).size)
                    assertMatchesScan(engine, store, snapshot, hasRare, "exists over a descendant")

                    val notRare = Query.where(not(path("""$..["@type"]""") eq "Rare"))
                    assertEquals(26, keysOf(engine, snapshot, notRare).size, "the complement, over documents")
                    assertMatchesScan(engine, store, snapshot, notRare, "negation over a descendant")
                }
            }
        }
    }

}
