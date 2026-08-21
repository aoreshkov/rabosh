package app.oreshkov.rabosh.query

import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.core.Key
import app.oreshkov.rabosh.index.IndexCatalog
import app.oreshkov.rabosh.index.IndexDefinition
import app.oreshkov.rabosh.index.IndexOptions
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * A walk budget costs coverage, and never an answer.
 *
 * `IndexOptions.maxChildren` used to be the one budget in the engine that could delete a document
 * from a result without saying anything. A container wider than it was walked to it, so the terms
 * recorded for a path under it were a *prefix* of the values the document held there — and the
 * segment still read as covered, so the documents whose value sat past the bound were never
 * candidates and were never opened. **The whole differential suite agreed with the shortfall**,
 * because the recheck and the fallback scan ran the identical bounded walk: two oracles built from
 * the same truncation cannot see it.
 *
 * That is why the assertions here name the expected keys **by construction** rather than comparing
 * the plan against `scanKeys`. `assertMatchesScan` is the right instrument for a planning question
 * and the wrong one for this: it compares the engine with the engine. The fixture is built so that
 * the answer is known before anything is executed — element `WIDE - 1` of each array is unique to
 * its document — and what is asserted is that the engine finds it.
 *
 * Two mechanisms, and both are load-bearing:
 *
 * - a segment whose build hit the bound is left **not covered** by that index, the escape
 *   `maxTermsPerSegment` already took, so the question falls to a scan;
 * - the scan walks the document **whole** — `TermExtractor.reading` carries no budget — because a
 *   fallback that truncated at the same element would answer exactly as short as the index it
 *   replaced, and the fix would be no fix.
 *
 * Remove either one and the tests below fail. That is deliberate: the pair is the claim.
 */
class WalkTruncationTest {

    private companion object {
        /** Elements per array. Above the bound the fixtures set, and below any real default. */
        const val WIDE = 20

        /** The bound under test. Small so that the fixture is small; the mechanism does not care. */
        const val BOUND = 8

        const val DOCUMENTS = 6
    }

    /**
     * One document whose distinguishing value sits at the **last** element of a wide array.
     *
     * `pad-*` values are shared, so an index over the path is not trivially empty and its dictionary
     * is exercised; `needle-i` is unique to document *i* and lives at element `WIDE - 1`, which is
     * past `BOUND`. `team` is a second indexed path that no wide container sits under.
     */
    private fun document(index: Int): String {
        val tags = (0 until WIDE - 1).joinToString(",") { """"pad-$it"""" }
        return """{"team":"team-${index % 3}","tags":[$tags,"needle-$index"]}"""
    }

    private fun load(store: DocumentStore) {
        store.load((0 until DOCUMENTS).map { jsonDocument(document(it)) })
    }

    @Test
    fun `a value past the bound is still found, by a scan the index stood down for`(@TempDir root: Path) {
        val directory = scratch(root, "truncation")
        IndexCatalog(directory, IndexOptions(maxChildren = BOUND)).use { catalog ->
            DocumentStore.open(directory, queryStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                load(store)
                val tags = catalog.createIndex(store, IndexDefinition.inverted("$.tags[*]"))
                val engine = QueryEngine(store, catalog)

                store.snapshot().use { snapshot ->
                    catalog.read(store, tags, snapshot).use { reader ->
                        assertEquals(
                            0,
                            reader.coverage.segmentsCovered,
                            "a build that saw $BOUND of $WIDE elements must not claim the segment",
                        )
                        assertTrue(reader.coverage.segmentsTotal > 0, "the fixture must hold a segment")
                    }

                    // The value at element 19 of a 20-element array, under a bound of 8. The index
                    // never recorded it; the answer does not depend on the index.
                    assertEquals(
                        listOf(keyFor(3)),
                        keysOf(engine, snapshot, Query.where(path("$.tags[*]") eq "needle-3")),
                        "a document whose only matching value sits past the bound",
                    )
                    // And the values inside the bound are still all of them, which is the direction a
                    // truncation that reported *everything* would break.
                    assertEquals(
                        (0 until DOCUMENTS).map(::keyFor),
                        keysOf(engine, snapshot, Query.where(path("$.tags[*]") eq "pad-0")),
                        "the first element of every array",
                    )
                }
            }
        }
    }

    /**
     * The same store, the same query, a bound the fixture does not reach: the index answers.
     *
     * The presence case for the assertion above, and not optional. *The answer is complete* is
     * satisfied by an engine that never uses an index at all, so it has to be paired with a case
     * where the index demonstrably does the work — otherwise this suite would pass for a change that
     * disabled indexing outright.
     */
    @Test
    fun `a bound the widest container does not reach leaves the index answering`(@TempDir root: Path) {
        val directory = scratch(root, "truncation")
        IndexCatalog(directory, IndexOptions(maxChildren = WIDE)).use { catalog ->
            DocumentStore.open(directory, queryStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                load(store)
                val tags = catalog.createIndex(store, IndexDefinition.inverted("$.tags[*]"))
                val engine = QueryEngine(store, catalog)

                store.snapshot().use { snapshot ->
                    catalog.read(store, tags, snapshot).use { reader ->
                        assertTrue(reader.coverage.isComplete, "exactly at the bound is not past it")
                    }
                    val stats = assertMatchesScan(
                        engine,
                        store,
                        snapshot,
                        Query.where(path("$.tags[*]") eq "needle-3"),
                        "covered",
                    )
                    assertEquals(listOf(keyFor(3)), keysOf(engine, snapshot, Query.where(path("$.tags[*]") eq "needle-3")))
                    assertTrue(stats.segmentsIndexed > 0, "and the sidecar is what answered it")
                    assertEquals(0, stats.segmentsScanned, "with nothing scanned")
                }
                assertTrue(catalog.problems.isEmpty(), "nothing fired: ${catalog.problems}")
            }
        }
    }

    /**
     * The claim the number was raised on, finally checkable: **lowering the bound costs scans and
     * not documents.**
     *
     * `DEFAULT_MAX_CHILDREN` was raised from 4096 to 65 536 as a mitigation, with the note that a
     * coverage signal *would let the bound come back down*. This is that sentence as an assertion —
     * four bounds spanning both sides of the fixture, one answer.
     */
    @Test
    fun `the answer does not depend on the bound, and the coverage does`(@TempDir root: Path) {
        val covered = ArrayList<Pair<Int, Int>>()
        for (bound in listOf(2, BOUND, WIDE - 1, WIDE)) {
            val directory = scratch(root, "truncation-$bound")
            IndexCatalog(directory, IndexOptions(maxChildren = bound)).use { catalog ->
                DocumentStore.open(directory, queryStoreOptions(catalog)).use { store ->
                    catalog.attach(store)
                    load(store)
                    val tags = catalog.createIndex(store, IndexDefinition.inverted("$.tags[*]"))
                    val engine = QueryEngine(store, catalog)

                    store.snapshot().use { snapshot ->
                        assertEquals(
                            listOf(keyFor(1)),
                            keysOf(engine, snapshot, Query.where(path("$.tags[*]") eq "needle-1")),
                            "maxChildren=$bound changed the answer, which no budget may do",
                        )
                        catalog.read(store, tags, snapshot).use { reader ->
                            covered += bound to reader.coverage.segmentsCovered
                        }
                    }
                }
            }
        }
        assertEquals(
            listOf(2 to 0, BOUND to 0, (WIDE - 1) to 0, WIDE to 1),
            covered,
            "coverage is what a narrower bound spends",
        )
    }

    /**
     * The bound fired at `$.tags`, so the index over `$.tags[*]` stands down and the index over
     * `$.team` does not.
     *
     * Uncovering is per index, not per segment, and the attribution is what makes the escape
     * affordable: a single wide array in a document would otherwise take down every index in the
     * store on the segment holding it. `TermExtractor` reports the candidates the wildcard step kept,
     * which for an array is exact.
     */
    @Test
    fun `a truncated container stands down the index over it and not the one beside it`(@TempDir root: Path) {
        val directory = scratch(root, "truncation")
        IndexCatalog(directory, IndexOptions(maxChildren = BOUND)).use { catalog ->
            DocumentStore.open(directory, queryStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                load(store)
                val tags = catalog.createIndex(store, IndexDefinition.inverted("$.tags[*]"))
                val team = catalog.createIndex(store, IndexDefinition.inverted("$.team"))
                val engine = QueryEngine(store, catalog)

                store.snapshot().use { snapshot ->
                    catalog.read(store, tags, snapshot).use { reader ->
                        assertEquals(0, reader.coverage.segmentsCovered, "the wide path")
                    }
                    catalog.read(store, team, snapshot).use { reader ->
                        assertTrue(reader.coverage.isComplete, "the path no wide container sits under")
                    }
                    val stats = assertMatchesScan(
                        engine,
                        store,
                        snapshot,
                        Query.where(path("$.team") eq "team-1"),
                        "the untouched index",
                    )
                    assertTrue(stats.segmentsIndexed > 0, "which still answers from its sidecar")
                }

                // Distinct, because the second `createIndex` backfills the segment again — an
                // uncovered segment is retried by every build, exactly as one whose dictionary
                // overflowed is, and a truncation will fail that retry every time. That is the cost
                // of the escape and it is the cost the existing one already has.
                val reported = catalog.problems.map { it.message.orEmpty() }.distinct()
                assertEquals(1, reported.size, "one index stood down, not two: $reported")
                assertTrue(reported.single().contains("$.tags[*]"), "naming the index: ${reported.single()}")
                assertTrue(reported.single().contains("maxChildren"), "and the budget: ${reported.single()}")
            }
        }
    }

    /**
     * The correlated half, which travels a different walk and would have been missed by asserting on
     * the uncorrelated one alone.
     *
     * An `elemMatch` is decided by `ElementExtractor` feeding a nested matcher, so widening one and
     * not the other would leave a correlated predicate truncating where a leaf no longer does — a
     * gap in the shape of "the fix worked everywhere the test looked".
     */
    @Test
    fun `an elemMatch past the bound is answered too`(@TempDir root: Path) {
        val directory = scratch(root, "truncation")
        IndexCatalog(directory, IndexOptions(maxChildren = BOUND)).use { catalog ->
            DocumentStore.open(directory, queryStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                val items = (0 until WIDE - 1).joinToString(",") { """{"sku":"pad-$it","qty":1}""" }
                store.load(
                    (0 until DOCUMENTS).map {
                        jsonDocument("""{"items":[$items,{"sku":"needle-$it","qty":9}]}""")
                    },
                )
                val engine = QueryEngine(store, catalog)

                store.snapshot().use { snapshot ->
                    assertEquals(
                        listOf(keyFor(2)),
                        keysOf(
                            engine,
                            snapshot,
                            Query.where(
                                elemMatch(
                                    path("$.items[*]"),
                                    and(path("$.sku") eq "needle-2", path("$.qty") eq 9),
                                ),
                            ),
                        ),
                        "an element past the bound satisfying both conjuncts",
                    )
                }
            }
        }
    }

    private fun keysOf(engine: QueryEngine, snapshot: app.oreshkov.rabosh.core.Snapshot, query: Query): List<Key> {
        val keys = ArrayList<Key>()
        engine.execute(query.project(Projection.KEY), snapshot).use { cursor ->
            while (cursor.next()) keys.add(cursor.key)
        }
        return keys
    }
}
