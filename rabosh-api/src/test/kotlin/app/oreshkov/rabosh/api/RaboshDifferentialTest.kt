package app.oreshkov.rabosh.api

import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.core.Key
import app.oreshkov.rabosh.index.IndexDefinition
import app.oreshkov.rabosh.query.Predicate
import app.oreshkov.rabosh.query.Projection
import app.oreshkov.rabosh.query.Query
import app.oreshkov.rabosh.query.QueryEngine
import app.oreshkov.rabosh.query.and
import app.oreshkov.rabosh.query.not
import app.oreshkov.rabosh.query.or
import app.oreshkov.rabosh.query.path
import java.math.BigDecimal
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * **The facade is not a second definition of what a query means.**
 *
 * It holds no planner, no matcher and no evaluator; it delegates to one `QueryEngine`. So the thing
 * worth proving is that going through it changes nothing — and the way to prove it is to run the same
 * queries through the engine directly, over the *same* store and the *same* catalogs, reached through
 * the escape hatches the facade publishes for exactly this sort of purpose.
 *
 * That comparison alone would be circular if the facade somehow rewrote a query on the way in, so
 * every shape is also checked against a **third oracle**: the answer computed arithmetically from the
 * index that generated the document. `documentJson` builds `team` as `"team-${i % 7}"`, so the keys
 * matching `$.team = "team-3"` are the ones whose index is `3` modulo `7`, and that cannot agree with
 * the engine by construction. `rabosh-query`'s two oracles are `internal` to its own test source set
 * and re-implementing one here would give this module a private definition of a predicate — the exact
 * thing being ruled out.
 *
 * All of it in three coverage states, because "an index changes speed, never answers" is a claim about
 * a half-built index as much as a finished one.
 */
class RaboshDifferentialTest {

    private val definitions = listOf(
        IndexDefinition.inverted("$.team"),
        IndexDefinition.inverted("$.tags[*]"),
        IndexDefinition.inverted("$.note"),
        IndexDefinition.column("$.score"),
        IndexDefinition.column("$.price"),
    )

    /** Every shape, with the answer stated as arithmetic on the generating index. */
    private fun shapes(bound: Int): List<Triple<String, Predicate, List<Key>>> = listOf(
        shape("equality", path("$.team") eq "team-3", bound) { it % 7 == 3 },
        shape("equality on a number", path("$.score") eq 17L, bound) { it % 50 == 17 },
        shape("equality on a boolean", path("$.live") eq false, bound) { it % 3 != 0 },
        shape(
            "conjunction of equalities",
            and(path("$.team") eq "team-3", path("$.live") eq true),
            bound,
        ) { it % 7 == 3 && it % 3 == 0 },
        shape("range", path("$.score") lt 10L, bound) { it % 50 < 10 },
        shape(
            "strict and non-strict bounds",
            and(path("$.score") gt 10L, path("$.score") le 20L),
            bound,
        ) { it % 50 > 10 && it % 50 <= 20 },
        shape(
            "numeric range with a scale",
            path("$.price").between(BigDecimal("10.00"), BigDecimal("40.00")),
            bound,
        ) { priceOf(it) >= BigDecimal("10.00") && priceOf(it) <= BigDecimal("40.00") },
        shape(
            "equality and a range",
            and(path("$.team") eq "team-2", path("$.score") ge 25L),
            bound,
        ) { it % 7 == 2 && it % 50 >= 25 },
        shape("disjunction", or(path("$.team") eq "team-1", path("$.team") eq "team-5"), bound) {
            it % 7 == 1 || it % 7 == 5
        },
        shape("IN", path("$.team").oneOf("team-0", "team-4", "team-6"), bound) {
            it % 7 == 0 || it % 7 == 4 || it % 7 == 6
        },
        shape("exists", path("$.note").exists(), bound) { it % 3 != 1 },
        shape("not exists", not(path("$.note").exists()), bound) { it % 3 == 1 },
        shape("is null", path("$.note").isNull(), bound) { it % 3 != 1 && it % 6 == 2 },
        shape("negated equality", not(path("$.team") eq "team-3"), bound) { it % 7 != 3 },
        shape("repeated path", path("$.tags[*]") eq "t2", bound) { it % 5 == 2 || it % 3 == 2 },
        shape(
            "nested and or",
            or(and(path("$.team") eq "team-1", path("$.score") ge 25L), path("$.tags[*]") eq "t4"),
            bound,
        ) { (it % 7 == 1 && it % 50 >= 25) || it % 5 == 4 || it % 3 == 4 },
        shape(
            "one indexed leaf and one unindexed",
            and(path("$.team") eq "team-3", path("$.missing").exists()),
            bound,
        ) { false },
        shape("always true", Predicate.True, bound) { true },
        shape("always false", Predicate.False, bound) { false },
    )

    @Test
    fun `the facade agrees with the engine and with arithmetic, before during and after a build`(
        @TempDir root: Path,
    ) {
        val directory = scratch(root)

        Rabosh.open(directory, RaboshOptions(store = apiStoreOptions())).use { db ->
            for (round in 0 until 4) db.load(round * 100, 100)

            check(db, bound = 400, note = "before")
            db.snapshot().use { snapshot ->
                val stats = db.query(Query.all().project(Projection.KEY), snapshot).use { it.next(); it.stats }
                assertEquals(0, stats.segmentsIndexed, "nothing should be indexed yet")
            }

            for (definition in definitions) db.createIndex(definition)
            check(db, bound = 400, note = "after the build")
        }

        // More segments arrive with nothing attached, so some are covered and some are not. That is
        // the state a build in progress leaves, arranged deliberately.
        DocumentStore.open(directory, apiStoreOptions()).use { store ->
            for (index in 400 until 600) store.put(keyFor(index), documentOf(index))
            store.flush()
        }

        Rabosh.open(directory, RaboshOptions(store = apiStoreOptions(), backfill = false)).use { db ->
            val partial = check(db, bound = 600, note = "during")
            assertTrue(
                partial.any { it.segmentsIndexed > 0 && it.segmentsScanned > 0 },
                "a partially covered store must answer from indexes and scan what they do not cover",
            )

            db.attach()
            val complete = check(db, bound = 600, note = "after")
            assertTrue(
                complete.any { it.segmentsIndexed > 0 && it.segmentsScanned == 0 },
                "a fully covered store should answer some plan from sidecars alone",
            )
        }
    }

    /** Every shape, three ways, and the statistics of each so a caller can assert on the work. */
    private fun check(db: Rabosh, bound: Int, note: String): List<app.oreshkov.rabosh.query.QueryStats> {
        val engine = QueryEngine(db.store, db.indexCatalog!!, db.catalog!!.inferSchema())
        val collected = ArrayList<app.oreshkov.rabosh.query.QueryStats>()
        db.snapshot().use { snapshot ->
            for ((name, predicate, expected) in shapes(bound)) {
                val query = Query.where(predicate).project(Projection.KEY)

                val throughEngine = ArrayList<Key>()
                engine.execute(query, snapshot).use { rows -> while (rows.next()) throughEngine.add(rows.key) }

                val throughFacade = ArrayList<Key>()
                val stats = db.query(query, snapshot).use { rows ->
                    while (rows.next()) throughFacade.add(rows.key)
                    rows.stats
                }
                collected.add(stats)

                assertEquals(throughEngine, throughFacade, "$note/$name: the facade changed the answer")
                assertEquals(expected, throughFacade, "$note/$name: the answer disagrees with the corpus")
                assertEquals(throughFacade.sorted(), throughFacade, "$note/$name: rows must be in key order")
            }
        }
        return collected
    }

    private fun shape(
        name: String,
        predicate: Predicate,
        bound: Int,
        matches: (Int) -> Boolean,
    ): Triple<String, Predicate, List<Key>> =
        Triple(name, predicate, (0 until bound).filter(matches).map(::keyFor))

    private fun priceOf(index: Int): BigDecimal =
        BigDecimal("${index % 90}.${"%02d".format(index % 100)}")
}
