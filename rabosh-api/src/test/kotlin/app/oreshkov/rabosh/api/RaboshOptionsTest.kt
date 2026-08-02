package app.oreshkov.rabosh.api

import app.oreshkov.rabosh.index.IndexDefinition
import app.oreshkov.rabosh.query.Query
import app.oreshkov.rabosh.query.path
import app.oreshkov.rabosh.variant.toJsonString
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * A layer switched off must cost nothing, and must say so rather than answering emptily.
 *
 * Both halves matter and they fail differently. "Costs nothing" is a claim about files: no observer
 * means no sidecar on any flush, which is what makes `schema = false` worth having instead of a
 * catalog nobody reads. "Says so" is a claim about answers: a model that was never collected and a
 * model of nothing are different facts, and the engine's rule everywhere else is that the second must
 * never be reported as the first.
 */
class RaboshOptionsTest {

    @Test
    fun `schema false writes no sketch and refuses to invent a model`(@TempDir root: Path) {
        val directory = scratch(root)
        Rabosh.open(
            directory,
            RaboshOptions(store = apiStoreOptions(), schema = false),
        ).use { db ->
            db.load(0, 200)
            assertNull(db.catalog, "no schema catalog should have been constructed")
            assertTrue(namesEndingIn(directory, ".cat").isEmpty(), "no sketch sidecar should exist")

            // Queries still work: statistics are an optimisation, not a requirement.
            assertEquals((0 until 200).filter { it % 7 == 5 }.map(::keyFor), db.keys(Query.where(path("$.team") eq "team-5")))

            val failure = assertFailsWith<IllegalStateException> { db.schema() }
            assertTrue("schema = false" in failure.message.orEmpty(), "the message should name the option")
            assertFailsWith<IllegalStateException> { db.indexCandidates() }
        }
    }

    @Test
    fun `indexes false writes no sidecar and points at scan`(@TempDir root: Path) {
        val directory = scratch(root)
        Rabosh.open(
            directory,
            RaboshOptions(store = apiStoreOptions(), indexes = false),
        ).use { db ->
            db.load(0, 200)
            assertNull(db.indexCatalog, "no index catalog should have been constructed")
            assertTrue(namesEndingIn(directory, ".idx").isEmpty(), "no base sidecar should exist")
            assertTrue(namesEndingIn(directory, ".pst").isEmpty(), "no posting file should exist")

            // The unfiltered read path needs no catalog and still works.
            var seen = 0
            db.scan().use { cursor -> while (cursor.next()) seen++ }
            assertEquals(200, seen)

            val failure = assertFailsWith<IllegalStateException> { db.query(Query.all()) }
            assertTrue("indexes = false" in failure.message.orEmpty(), "the message should name the option")
            assertTrue("scan()" in failure.message.orEmpty(), "the message should name what to use instead")
            assertFailsWith<IllegalStateException> { db.createIndex(IndexDefinition.inverted("$.team")) }
            assertFailsWith<IllegalStateException> { db.indexes() }
        }
    }

    @Test
    fun `neither layer costs nothing at all`(@TempDir root: Path) {
        val directory = scratch(root)
        Rabosh.open(
            directory,
            RaboshOptions(store = apiStoreOptions(), schema = false, indexes = false),
        ).use { db ->
            db.load(0, 200)
            // Field-by-field rather than by rendered JSON: `Variant.fieldName` is name-ordered, so a
            // document round-trips with its fields sorted and comparing the text would be testing the
            // encoder's ordering rather than the facade.
            val document = assertNotNull(db.get(keyFor(7)))
            assertEquals("team-0", document.select("$.team")?.stringValue())
            assertEquals(7L, document.select("$.score")?.longValue())
            assertTrue(namesEndingIn(directory, ".cat").isEmpty())
            assertTrue(namesEndingIn(directory, ".idx").isEmpty())
        }
    }

    @Test
    fun `a caller's observer in the store options is rejected rather than discarded`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            RaboshOptions(store = apiStoreOptions().withSegmentObserver(CountingObserver()))
        }
        assertTrue(
            "RaboshOptions.segmentObserver" in failure.message.orEmpty(),
            "the message should say where the observer goes instead",
        )
    }

    @Test
    fun `a caller's own observer is composed alongside the catalogs`(@TempDir root: Path) {
        val spy = CountingObserver()
        Rabosh.open(
            scratch(root),
            RaboshOptions(store = apiStoreOptions(), segmentObserver = spy),
        ).use { db ->
            db.load(0, 200)
            assertTrue(spy.segmentsBegun.get() > 0, "a flush should have reported its segments")
            assertTrue(spy.documentsObserved.get() > 0, "and its documents")
        }
    }
}
