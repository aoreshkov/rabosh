package app.oreshkov.rabosh.query

import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.core.Key
import app.oreshkov.rabosh.index.IndexCatalog
import app.oreshkov.rabosh.index.IndexDefinition
import app.oreshkov.rabosh.testkit.property.RandomSource
import java.math.BigDecimal
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Every plan shape, compared against a scan **after every step** of a write/flush/compact script.
 *
 * The same rule the bitmap suite follows and for the same reason: a plan that goes wrong on the
 * fourth step and right again on the sixth is exactly the shape a coverage-transition bug has, and
 * comparing only the end state cannot see it. The states this walks through are the ones that break
 * things — a segment written before an index existed, a segment written after, an overwrite a
 * compaction has not yet collapsed, a deletion that is still a tombstone.
 */
class QueryScriptTest {

    private val shapes = listOf<Pair<String, Predicate>>(
        "equality" to (path("$.team") eq "team-2"),
        "conjunction" to and(path("$.team") eq "team-2", path("$.score") ge 20L),
        "disjunction" to or(path("$.team") eq "team-1", path("$.score") lt 5L),
        "range" to path("$.score").between(BigDecimal("10"), BigDecimal("30")),
        "strict range" to (path("$.score") gt 40L),
        "IN" to path("$.team").oneOf("team-0", "team-3"),
        "exists" to path("$.note").exists(),
        "not exists" to not(path("$.note").exists()),
        "is null" to path("$.note").isNull(),
        "negated equality" to not(path("$.team") eq "team-2"),
        "repeated path" to (path("$.tags[*]") eq "t1"),
        "unindexed leaf" to and(path("$.team") eq "team-2", path("$.other") eq true),
    )

    @Test
    fun `answers match a scan after every step of a write, flush and compact script`(@TempDir root: Path) {
        val directory = scratch(root, "script")
        val random = RandomSource(seed = 20260727)

        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, queryStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                val engine = QueryEngine(store, catalog)
                var written = 0

                repeat(24) { step ->
                    // An index arrives partway through, over data already on disk, which is the
                    // situation the whole engine is built around.
                    if (step == 6) catalog.createIndex(store, IndexDefinition.inverted("$.team"))
                    if (step == 11) catalog.createIndex(store, IndexDefinition.column("$.score"))
                    if (step == 17) catalog.createIndex(store, IndexDefinition.inverted("$.note"))

                    when (random.nextInt(100)) {
                        in 0..44 -> {
                            // New documents.
                            val count = random.nextInt(5..25)
                            repeat(count) { store.put(keyFor(written), document(written++)) }
                        }

                        in 45..64 -> {
                            // Overwrites, which are the versions a snapshot and a compaction argue over.
                            repeat(random.nextInt(1..10)) {
                                if (written > 0) {
                                    val index = random.nextInt(written)
                                    store.put(keyFor(index), document(index + 7_000))
                                }
                            }
                        }

                        in 65..74 -> {
                            repeat(random.nextInt(1..6)) {
                                if (written > 0) store.delete(keyFor(random.nextInt(written)))
                            }
                        }

                        in 75..88 -> store.flush()
                        else -> store.compact()
                    }

                    store.snapshot().use { snapshot ->
                        for ((note, predicate) in shapes) {
                            assertMatchesScan(engine, store, snapshot, Query.where(predicate), "step $step: $note")
                        }
                        // And with a key range, which is the other half of a plan.
                        val bounded = Query.where(path("$.team") eq "team-2")
                            .range(keyFor(10), keyFor(90))
                        val expected = scanKeys(store, snapshot, bounded.predicate)
                            .filter { it >= keyFor(10) && it <= keyFor(90) }
                        assertEquals(expected, engine.keys(bounded, snapshot), "step $step: bounded")
                    }
                }

                assertTrue(written > 100, "the script should have written a reasonable amount")
                assertTrue(catalog.indexes().isNotEmpty(), "and defined at least one index")
            }
        }
    }

    private fun document(index: Int) = jsonDocument(
        buildString {
            append("""{"team":"team-${index % 5}","score":${index % 60},""")
            append(""""tags":["t${index % 4}","t${index % 3}"]""")
            if (index % 3 != 1) append(""","note":${if (index % 6 == 2) "null" else "\"n$index\""}""")
            if (index % 7 == 0) append(""","other":true""")
            append("}")
        },
    )
}

