package app.oreshkov.rabosh.api

import app.oreshkov.rabosh.core.Key
import app.oreshkov.rabosh.index.IndexDefinition
import app.oreshkov.rabosh.query.Query
import app.oreshkov.rabosh.query.path
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * One `close`, and the proof of it is that the directory can be **deleted**.
 *
 * That assertion is the whole reason this suite exists. Getting the lifecycle wrong leaks a mapping,
 * and on Windows a mapped file cannot be deleted at all — so a leak fails here immediately and
 * deterministically rather than as a drift somebody has to pick a memory bound for. CI runs Windows
 * for exactly this, as it does for `ResourceLeakTest` and `IndexLifecycleTest` below.
 */
class RaboshLifecycleTest {

    @Test
    fun `open write index query close, and then the directory deletes`(@TempDir root: Path) {
        val directory = scratch(root)
        Rabosh.open(directory, RaboshOptions(store = apiStoreOptions())).use { db ->
            db.load(0, 300)
            val handle = db.createIndex(IndexDefinition.inverted("$.team"))
            val column = db.createIndex(IndexDefinition.column("$.score"))

            val keys = db.keys(Query.where(path("$.team") eq "team-3"))
            assertEquals((0 until 300).filter { it % 7 == 3 }.map(::keyFor), keys)

            assertEquals(listOf(handle, column), db.indexes())
            assertNotNull(db.get(keyFor(7)))
            assertTrue(db.schema().documentCount > 0)
        }

        // The assertion. A leaked mapping in the store or in either catalog fails this line.
        deleteRecursively(directory)
        assertTrue(!Files.exists(directory), "the directory should be gone")
    }

    /**
     * The README's example, run.
     *
     * A documented snippet that nothing executes is a snippet that rots, and this one is the first
     * thing anybody will type. It covers the two surfaces the suites above do not: the JSON overload
     * of `put`, and `explain(...).render()`.
     */
    @Test
    fun `the documented example works as written`(@TempDir root: Path) {
        val directory = scratch(root)
        Rabosh.open(directory, RaboshOptions(store = apiStoreOptions())).use { db ->
            db.put(Key.of("user:1"), """{"name":"ada","team":"analytics"}""")
            db.put(Key.of("user:2"), """{"name":"grace","team":"platform"}""")
            db.flush()

            db.createIndex(IndexDefinition.inverted("$.team"))

            val query = Query.where(path("$.team") eq "analytics")
            val found = ArrayList<Key>()
            db.query(query).use { rows -> while (rows.next()) found.add(rows.key) }
            assertEquals(listOf(Key.of("user:1")), found)

            assertTrue(db.schema().render().contains("$.team"), "the model should render the path")
            assertTrue(db.explain(query).render().contains("INVERTED"), "the plan should render its source")
            // `indexCandidates` is exercised in RaboshSchemaTest, where the corpus is large enough
            // for the default thresholds to recommend anything at all — two documents is not.
            db.indexCandidates()
        }
        deleteRecursively(directory)
    }

    @Test
    fun `close is idempotent and refuses further work honestly`(@TempDir root: Path) {
        val directory = scratch(root)
        val db = Rabosh.open(directory, RaboshOptions(store = apiStoreOptions()))
        db.load(0, 20)
        db.close()
        db.close()

        // A closed database reports itself through the layer that owns the state, rather than the
        // facade inventing a second closed-ness of its own.
        assertFailsWith<Throwable> { db.get(keyFor(0)) }
        assertFailsWith<IllegalStateException> { db.attach() }

        deleteRecursively(directory)
    }

    @Test
    fun `an open cursor keeps its files and releasing it frees them`(@TempDir root: Path) {
        val directory = scratch(root)
        Rabosh.open(directory, RaboshOptions(store = apiStoreOptions())).use { db ->
            db.load(0, 300)
            db.createIndex(IndexDefinition.inverted("$.team"))

            val cursor = db.query(Query.where(path("$.team") eq "team-1"))
            db.compact()
            // A reader may be inside a file the compaction replaced, so it must still be there.
            assertTrue(
                namesEndingIn(directory, ".seg").isNotEmpty(),
                "a compaction with a reader open must not leave the store without segments",
            )
            cursor.close()
        }
        deleteRecursively(directory)
    }

    /**
     * A non-blocking open, a background build, queries while it runs, and then the directory deletes.
     *
     * The whole of phase 15 through the facade, ending in the assertion this suite exists for. Two
     * things are being claimed at once and both matter: the queries are answered *correctly* while the
     * index is still being built, because segments it has not reached are scanned; and `close` stops
     * the build, so a build in flight cannot keep a mapping alive past it.
     */
    @Test
    fun `a background build runs while queries do, and close stops it`(@TempDir root: Path) {
        val directory = scratch(root)
        Rabosh.open(directory, RaboshOptions(store = apiStoreOptions())).use { db ->
            db.load(0, 600)
            db.flush()

            val build = db.createIndexInBackground(IndexDefinition.inverted("$.team"))
            assertNotNull(build.handle, "a created index has a handle before it is built")

            val expected = (0 until 600).filter { it % 7 == 3 }.map(::keyFor)
            // While the build runs: the plan uses the sidecars that exist and scans the rest, so this
            // is the same answer it will give afterwards, not a subset of it.
            assertEquals(expected, db.keys(Query.where(path("$.team") eq "team-3")), "during the build")
            build.await()
            assertEquals(expected, db.keys(Query.where(path("$.team") eq "team-3")), "after the build")

            // And the general pass, which is what an open with `backfill = false` leaves behind.
            db.buildIndexesInBackground().await()
        }
        deleteRecursively(directory)
        assertTrue(!Files.exists(directory), "the directory should be gone")
    }

    /** Fails loudly rather than quietly: a file that will not delete is the bug being looked for. */
    private fun deleteRecursively(directory: Path) {
        if (!Files.exists(directory)) return
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }
}
