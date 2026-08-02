package app.oreshkov.rabosh.query

import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.core.Key
import app.oreshkov.rabosh.core.Snapshot
import app.oreshkov.rabosh.index.IndexCatalog
import app.oreshkov.rabosh.index.IndexDefinition
import app.oreshkov.rabosh.testkit.json.JsonValue
import java.math.BigDecimal
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * **An index may change query speed, never query answers**, for every shape of plan.
 *
 * The suite compares against two oracles rather than one, and the difference between them is the
 * point. `scanKeys` uses the engine's own evaluator over a full scan, so it isolates *planning* from
 * *meaning*; `referenceKeys` is a second implementation over the testkit's JSON model with type
 * bracketing written out by hand, so it checks the meaning itself. A plan that agreed with the first
 * and not the second would be executing a predicate nobody asked for.
 *
 * Every shape is checked in **three coverage states**, and the middle one is the one worth arranging
 * deliberately: *before* an index exists, *during* a build where only some segments are covered, and
 * *after*. "Usable while it is still building, with no cutover" is a claim rather than an observation,
 * and the only way to test it is to reach a partially covered store on purpose.
 */
class QueryDifferentialTest {

    private val corpus = LinkedHashMap<Key, JsonValue>()

    /** Documents with numbers, strings, booleans, nulls, absent paths and a repeated path. */
    private fun document(index: Int): JsonValue = JsonValue.Obj(
        buildList {
            add("team" to JsonValue.Str("team-${index % 7}"))
            add("score" to JsonValue.Num("${index % 50}"))
            add("price" to JsonValue.Num("${index % 90}.${"%02d".format(index % 100)}"))
            add("tags" to JsonValue.Arr(listOf(JsonValue.Str("t${index % 5}"), JsonValue.Str("t${index % 3}"))))
            add("live" to JsonValue.Bool(index % 3 == 0))
            // Absent for a third of the corpus, null for another slice: the two states a query must
            // keep apart, and the reason EXISTS and IS NULL are different questions.
            if (index % 3 != 1) add("note" to if (index % 6 == 2) JsonValue.Null else JsonValue.Str("n$index"))
        },
    )

    private fun shapes(): List<Pair<String, Predicate>> = listOf(
        "equality" to (path("$.team") eq "team-3"),
        "equality on a number" to (path("$.score") eq 17L),
        "conjunction of equalities" to and(path("$.team") eq "team-3", path("$.live") eq true),
        "equality and a range" to and(
            path("$.team") eq "team-2",
            path("$.price").between(BigDecimal("10.00"), BigDecimal("40.00")),
        ),
        "disjunction" to or(path("$.team") eq "team-1", path("$.team") eq "team-5"),
        "IN" to path("$.team").oneOf("team-0", "team-4", "team-6"),
        "range only" to (path("$.score") lt 10L),
        "strict and non-strict bounds" to and(path("$.score") gt 10L, path("$.score") le 20L),
        "two ranges over different paths" to and(
            path("$.score") ge 20L,
            path("$.price") lt BigDecimal("50"),
        ),
        "exists" to path("$.note").exists(),
        "not exists" to not(path("$.note").exists()),
        "is null" to path("$.note").isNull(),
        "negated equality" to not(path("$.team") eq "team-3"),
        "repeated path" to (path("$.tags[*]") eq "t2"),
        "nested and or" to or(
            and(path("$.team") eq "team-1", path("$.score") ge 25L),
            path("$.tags[*]") eq "t4",
        ),
        "one indexed leaf and one unindexed" to and(
            path("$.team") eq "team-3",
            path("$.missing").exists(),
        ),
        "no index at all" to (path("$.live") eq false),
        "always true" to Predicate.True,
        "always false" to Predicate.False,
    )

    @Test
    fun `every plan shape agrees with a scan before, during and after a build`(@TempDir root: Path) {
        val directory = scratch(root)
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, queryStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                val engine = QueryEngine(store, catalog)
                for (round in 0 until 4) load(store, round * 100, 100)

                // --- before: no index exists, so every plan is the degenerate one.
                store.snapshot().use { snapshot ->
                    val stats = check(engine, store, snapshot, "before")
                    assertTrue(stats.all { it.segmentsIndexed == 0 }, "nothing should be indexed yet")
                    assertTrue(stats.all { it.segmentsScanned == snapshot.segmentNumbers.size })
                }

                for (definition in definitions) catalog.createIndex(store, definition)
            }
        }

        // --- during: more segments arrive with no catalog attached, so some are covered and some are
        // not. That is exactly the state a build in progress leaves, arranged deliberately, and the
        // claim being tested — usable while it is still building, with no cutover — is only a claim
        // until a store is actually in it.
        DocumentStore.open(directory, queryStoreOptions(null)).use { store ->
            load(store, 400, 100)
            load(store, 500, 100)
        }

        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, queryStoreOptions(catalog)).use { store ->
                catalog.attach(store, backfill = false)
                val engine = QueryEngine(store, catalog)

                store.snapshot().use { snapshot ->
                    val stats = check(engine, store, snapshot, "during")
                    val indexed = stats.filter { it.segmentsIndexed > 0 }
                    assertTrue(indexed.isNotEmpty(), "the index should still be answering for something")
                    assertTrue(
                        indexed.all { it.segmentsScanned > 0 },
                        "a partially covered store must scan what the index does not cover",
                    )
                    assertTrue(
                        indexed.any { use -> use.indexes.any { !it.coverage.isComplete } },
                        "coverage should report the partial state rather than it being assumed",
                    )
                }

                // --- after: the build catches up and every segment is covered.
                catalog.attach(store)
                store.snapshot().use { snapshot ->
                    val stats = check(engine, store, snapshot, "after")
                    assertTrue(
                        stats.any { it.segmentsIndexed == snapshot.segmentNumbers.size && it.segmentsScanned == 0 },
                        "a fully covered store should answer some plan from sidecars alone",
                    )
                }
            }
        }
    }

    private val definitions = listOf(
        IndexDefinition.inverted("$.team"),
        IndexDefinition.inverted("$.tags[*]"),
        IndexDefinition.column("$.score"),
        IndexDefinition.column("$.price"),
        IndexDefinition.inverted("$.note"),
    )

    /** The same shapes over a store whose writes are still in a memtable, which no sidecar covers. */
    @Test
    fun `unflushed writes are answered, and reported`(@TempDir root: Path) {
        val directory = scratch(root)
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, queryStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                val engine = QueryEngine(store, catalog)
                load(store, 0, 200)
                catalog.createIndex(store, IndexDefinition.inverted("$.team"))
                catalog.createIndex(store, IndexDefinition.column("$.score"))

                for (index in 200 until 260) {
                    val document = document(index)
                    corpus[keyFor(index)] = document
                    store.put(keyFor(index), jsonDocument(document))
                }

                store.snapshot().use { snapshot ->
                    val stats = check(engine, store, snapshot, "unflushed")
                    assertTrue(stats.all { it.scannedUnflushed }, "a memtable must be merged, and said to be")
                }
            }
        }
    }

    /** A key range is pushed into both halves of the plan rather than filtering the answer. */
    @Test
    fun `key bounds agree with a scan over the same bounds`(@TempDir root: Path) {
        val directory = scratch(root)
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, queryStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                val engine = QueryEngine(store, catalog)
                for (round in 0 until 3) load(store, round * 100, 100)
                catalog.createIndex(store, IndexDefinition.inverted("$.team"))

                store.snapshot().use { snapshot ->
                    val bounds = listOf<Pair<Key?, Key?>>(
                        null to null,
                        keyFor(50) to keyFor(150),
                        keyFor(0) to keyFor(0),
                        keyFor(150) to null,
                        null to keyFor(20),
                        keyFor(150) to keyFor(50),
                    )
                    for ((from, to) in bounds) {
                        val predicate = path("$.team") eq "team-3"
                        val expected = scanKeys(store, snapshot, predicate)
                            .filter { (from == null || it >= from) && (to == null || it <= to) }
                        val actual = engine.keys(Query.where(predicate).range(from, to), snapshot)
                        assertEquals(expected, actual, "range $from..$to")
                    }
                }
            }
        }
    }

    /** A limit is a bound on work, so what it returns has to be a prefix of what it would have. */
    @Test
    fun `a limit returns the first rows and stops`(@TempDir root: Path) {
        val directory = scratch(root)
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, queryStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                val engine = QueryEngine(store, catalog)
                for (round in 0 until 3) load(store, round * 100, 100)
                catalog.createIndex(store, IndexDefinition.inverted("$.team"))

                store.snapshot().use { snapshot ->
                    val predicate = path("$.team") eq "team-3"
                    val everything = engine.keys(Query.where(predicate), snapshot)
                    assertTrue(everything.size > 10, "the fixture must match more than the limit")
                    for (limit in listOf(0, 1, 5, everything.size, everything.size + 10)) {
                        assertEquals(
                            everything.take(limit),
                            engine.keys(Query.where(predicate).limit(limit), snapshot),
                            "limit $limit",
                        )
                    }
                }
            }
        }
    }

    private fun check(
        engine: QueryEngine,
        store: DocumentStore,
        snapshot: Snapshot,
        state: String,
    ): List<QueryStats> = shapes().map { (note, predicate) ->
        val query = Query.where(predicate)
        val stats = assertMatchesScan(engine, store, snapshot, query, "$state: $note")
        assertEquals(
            referenceKeys(corpus, predicate),
            engine.keys(query, snapshot),
            "$state: $note disagrees with the reference model",
        )
        assertFalse(stats.documentsRead < 0)
        stats
    }

    private fun load(store: DocumentStore, from: Int, count: Int) {
        val documents = (from until from + count).map { index ->
            val document = document(index)
            corpus[keyFor(index)] = document
            jsonDocument(document)
        }
        store.load(documents, from)
    }
}
