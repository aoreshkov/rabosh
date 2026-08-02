package app.oreshkov.rabosh.query

import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.core.Key
import app.oreshkov.rabosh.core.Snapshot
import app.oreshkov.rabosh.index.IndexCatalog
import app.oreshkov.rabosh.index.IndexDefinition
import app.oreshkov.rabosh.testkit.json.JsonValue
import app.oreshkov.rabosh.testkit.property.forAll
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

/**
 * Generated predicates against a fixed corpus, checked against both oracles.
 *
 * The corpus is fixed and the *predicate* is generated, which is the way round that matters: the
 * shapes a planner gets wrong are structural — a negation over a disjunction, a conjunction with one
 * unindexed leaf, an `IN` whose values are of mixed type — and no amount of generated data reaches
 * them. A failure reports a minimal predicate, because `QueryGens` shrinks trees rather than dropping
 * shrinking through `Gen.map`.
 */
class QueryPropertyTest {

    @Test
    fun `a generated predicate agrees with a scan and with the reference model`(@TempDir root: Path) {
        withCorpus(root) { store, engine, corpus, snapshot ->
            forAll(QueryGens.predicate()) { predicate ->
                val query = Query.where(predicate)
                assertEquals(
                    scanKeys(store, snapshot, predicate),
                    engine.keys(query, snapshot),
                    "the plan changed the answer for $predicate",
                )
                assertEquals(
                    referenceKeys(corpus, predicate),
                    engine.keys(query, snapshot),
                    "the engine and the reference model disagree about $predicate",
                )
            }
        }
    }

    /** The same predicates over a store with an uncovered segment and an unflushed memtable. */
    @Test
    fun `a generated predicate agrees over a partially covered store`(@TempDir root: Path) {
        withCorpus(root, partial = true) { store, engine, corpus, snapshot ->
            forAll(QueryGens.predicate(maxDepth = 2)) { predicate ->
                assertEquals(
                    referenceKeys(corpus, predicate),
                    engine.keys(Query.where(predicate), snapshot),
                    "partial coverage changed the answer for $predicate",
                )
            }
        }
    }

    /** A limit must return a prefix of the unlimited answer, whatever the predicate. */
    @Test
    fun `a limited query is a prefix of the unlimited one`(@TempDir root: Path) {
        withCorpus(root) { store, engine, _, snapshot ->
            forAll(QueryGens.predicate(maxDepth = 2)) { predicate ->
                val everything = engine.keys(Query.where(predicate), snapshot)
                for (limit in listOf(0, 1, 3, 25)) {
                    assertEquals(
                        everything.take(limit),
                        engine.keys(Query.where(predicate).limit(limit), snapshot),
                        "limit $limit of $predicate",
                    )
                }
            }
        }
    }

    private fun withCorpus(
        root: Path,
        partial: Boolean = false,
        body: (DocumentStore, QueryEngine, Map<Key, JsonValue>, Snapshot) -> Unit,
    ) {
        val directory = scratch(root, "property")
        val corpus = LinkedHashMap<Key, JsonValue>()
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, queryStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                for (round in 0 until 3) {
                    val from = round * 80
                    store.load((from until from + 80).map(::scriptDocument), from)
                    for (index in from until from + 80) corpus[keyFor(index)] = scriptJson(index)
                }
                catalog.createIndex(store, IndexDefinition.inverted("$.team"))
                catalog.createIndex(store, IndexDefinition.column("$.score"))
                catalog.createIndex(store, IndexDefinition.inverted("$.note"))
                catalog.createIndex(store, IndexDefinition.inverted("$.tags[*]"))

                if (partial) {
                    // A segment no index covers, and writes that are still in a memtable.
                    for (index in 240 until 300) {
                        store.put(keyFor(index), scriptDocument(index))
                        corpus[keyFor(index)] = scriptJson(index)
                    }
                }

                store.snapshot().use { snapshot ->
                    body(store, QueryEngine(store, catalog), corpus, snapshot)
                }
            }
        }
    }
}
