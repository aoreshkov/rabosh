package app.oreshkov.rabosh.api

import app.oreshkov.rabosh.catalog.IndexKind
import app.oreshkov.rabosh.index.IndexDefinition
import app.oreshkov.rabosh.query.Query
import app.oreshkov.rabosh.query.path
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * The cached planner statistics, and the two things that must not go stale in different ways.
 *
 * `QueryEngine` takes the index catalog as a **live object** and the schema as a **value**. So an
 * index created a moment ago is used by the very next query with no refresh anywhere, and the only
 * thing that can go stale is the fold of the sketches — which can only change when the set of live
 * segments does. That asymmetry is the whole caching policy, and both halves of it are asserted here.
 */
class RaboshSchemaTest {

    @Test
    fun `the model follows the data across flushes and compactions`(@TempDir root: Path) {
        Rabosh.open(scratch(root), RaboshOptions(store = apiStoreOptions())).use { db ->
            db.load(0, 100)
            assertEquals(100, db.schema().documentCount, "the model should count what was flushed")
            assertTrue(db.schema().coverage.isComplete)

            db.load(100, 200)
            assertEquals(300, db.schema().documentCount, "a later flush should be folded in")

            db.compact()
            assertEquals(300, db.schema().documentCount, "a compaction replaces sketches, it does not lose them")
            assertTrue(db.schema().coverage.isComplete, "every live segment should still be covered")

            assertNotNull(db.schema()["$.team"], "a path that exists should be in the model")
            val candidates = db.indexCandidates()
            assertTrue(
                candidates.any { it.path.toString() == "$.team" && it.kind == IndexKind.INVERTED },
                "a low-cardinality string path should be recommended for an inverted index",
            )
        }
    }

    @Test
    fun `an index created now is used by the next query, with no refresh`(@TempDir root: Path) {
        Rabosh.open(scratch(root), RaboshOptions(store = apiStoreOptions())).use { db ->
            db.load(0, 300)
            val query = Query.where(path("$.team") eq "team-4")
            val expected = (0 until 300).filter { it % 7 == 4 }.map(::keyFor)

            // Warm the cache before the index exists, so a stale engine would be the failure mode.
            assertEquals(expected, db.keys(query))
            assertEquals(0, db.explain(query).segmentsIndexed, "nothing is indexed yet")

            db.createIndex(IndexDefinition.inverted("$.team"))

            assertEquals(expected, db.keys(query), "an index must not change the answer")
            assertTrue(
                db.explain(query).segmentsIndexed > 0,
                "the index catalog is held live, so the next query should use the new index",
            )
        }
    }

    @Test
    fun `statistics are refolded when the segments move`(@TempDir root: Path) {
        Rabosh.open(scratch(root), RaboshOptions(store = apiStoreOptions())).use { db ->
            db.load(0, 100)
            db.createIndex(IndexDefinition.inverted("$.team"))
            val query = Query.where(path("$.team") eq "team-1")

            // Warm the cache against the segments as they are now.
            assertEquals((0 until 100).filter { it % 7 == 1 }.map(::keyFor), db.keys(query))

            db.load(100, 300)
            db.compact()

            // Both halves in the same assertion: the answer covers the new documents, and the
            // statistics behind the plan were folded from the segments that exist now.
            assertEquals((0 until 400).filter { it % 7 == 1 }.map(::keyFor), db.keys(query))
            assertEquals(400, db.schema().documentCount)
            assertEquals(
                db.store.liveSegmentNumbers.size,
                db.explain(Query.all()).segmentsIndexed + db.explain(Query.all()).segmentsScanned,
                "the plan should be partitioned over the segments that are live now",
            )
        }
    }
}
