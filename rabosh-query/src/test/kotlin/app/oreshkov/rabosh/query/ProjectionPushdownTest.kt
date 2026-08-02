package app.oreshkov.rabosh.query

import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.index.IndexCatalog
import app.oreshkov.rabosh.index.IndexDefinition
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * A projected row read out of shredded columns, opening no document.
 *
 * §9.8 claimed a column means "a scan of one field never touches the documents". That was true of
 * *filtering* and false of *projection*: a plan that had decided a key outright still opened its
 * document to fill the row in, which measured at 2.9× the cost of returning keys alone. This is the
 * suite that says the claim is now whole.
 *
 * **Every work assertion here sits beside the values it was made about.** `documentsRead == 0` passes
 * trivially for a query that returned nothing, and `rowsProjectedFromColumns` proves nothing if the
 * rows are wrong — so each test asserts the projected JSON against what the document says *and* the
 * counters, in that order. That is the phase-8 rule, and it is what makes this a correctness suite
 * that happens to measure rather than a performance test that happens to check.
 */
class ProjectionPushdownTest {

    /** Text, boolean and numeric at scale 0 and scale 2 — one of each family a column can hold. */
    private fun document(index: Int): String = buildString {
        append("""{"team":"team-${index % 7}","score":${index % 50},""")
        append(""""price":${index % 90}.${"%02d".format(index % 100)},""")
        append(""""live":${index % 3 == 0}""")
        // Absent for a third of the corpus, and explicitly null for another slice: a projection has
        // to tell "no such field" from "the field is null", and both must survive the column.
        if (index % 3 != 1) append(""","note":${if (index % 6 == 2) "null" else "\"note-$index\""}""")
        append("}")
    }

    private fun build(root: Path, count: Int = 240): Path {
        val directory = scratch(root, "pushdown")
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, queryStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                for (round in 0 until 4) {
                    store.load((round * 60 until round * 60 + 60).map { jsonDocument(document(it)) }, round * 60)
                }
                store.compact()
                catalog.createIndex(store, IndexDefinition.inverted("$.team"))
                catalog.createIndex(store, IndexDefinition.column("$.score"))
                catalog.createIndex(store, IndexDefinition.column("$.price"))
                catalog.createIndex(store, IndexDefinition.column("$.live"))
                catalog.createIndex(store, IndexDefinition.column("$.note"))
            }
        }
        check(count == 240)
        return directory
    }

    private inline fun withEngine(directory: Path, body: (DocumentStore, QueryEngine) -> Unit) {
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, queryStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                body(store, QueryEngine(store, catalog))
            }
        }
    }

    /**
     * The rows a query returns, as `key -> projected JSON`, and what it cost.
     *
     * The JSON is compared rather than the `Variant`, because that is what a caller sees and because
     * the physical width a scalar happens to be re-encoded at is not part of the answer.
     */
    private fun rows(engine: QueryEngine, store: DocumentStore, query: Query): Pair<Map<String, String>, QueryStats> {
        store.snapshot().use { snapshot ->
            engine.execute(query, snapshot).use { cursor ->
                val found = LinkedHashMap<String, String>()
                while (cursor.next()) found[cursor.key.toString()] = cursor.row.toJsonString()
                return found to cursor.stats
            }
        }
    }

    /** The same projection filled from documents, which is what the answer has to equal. */
    private fun fromDocuments(store: DocumentStore, query: Query): Map<String, String> {
        val expected = LinkedHashMap<String, String>()
        store.snapshot().use { snapshot ->
            store.scan(snapshot = snapshot).use { cursor ->
                val matcher = DocumentMatcher(
                    query.predicate.normalise().lower(app.oreshkov.rabosh.index.IndexOptions.DEFAULT),
                    app.oreshkov.rabosh.index.IndexOptions.DEFAULT,
                )
                while (cursor.next()) {
                    if (!matcher.matches(cursor.document)) continue
                    expected[cursor.key.toString()] = Row(cursor.key, query.projection, cursor.document).toJsonString()
                }
            }
        }
        return expected
    }

    @Test
    fun `a projection over covered columns opens no document`(@TempDir root: Path) {
        val directory = build(root)
        withEngine(directory) { store, engine ->
            val query = Query.where(path("$.team") eq "team-3").project("$.score", "$.price", "$.live")
            val (found, stats) = rows(engine, store, query)

            // The values first: a work assertion about wrong rows is worth nothing.
            assertEquals(fromDocuments(store, query), found, "column-projected rows must equal document-projected ones")
            assertTrue(found.isNotEmpty(), "the corpus must match")

            assertEquals(0, stats.documentsRead, "every projected field had a column: no document should be opened")
            assertEquals(
                stats.rowsReturned,
                stats.rowsProjectedFromColumns,
                "and every row should say so, rather than documentsRead being zero for some other reason",
            )
        }
    }

    @Test
    fun `a null projects as null and an absent path projects as absent`(@TempDir root: Path) {
        val directory = build(root)
        withEngine(directory) { store, engine ->
            // `$.note` is absent for a third of the corpus and explicitly null for another slice. The
            // column stores a null in a value slot and stores nothing at all for an absent path, and
            // the two must not come back the same way — that is the quadrant, seen from the outside.
            val query = Query.where(path("$.team") eq "team-1").project("$.note")
            val (found, stats) = rows(engine, store, query)

            assertEquals(fromDocuments(store, query), found)
            assertEquals(0, stats.documentsRead)
            assertTrue(found.values.any { it == """{"$.note":null}""" }, "null and absent both render as null")
            assertTrue(found.values.any { it.contains("note-") }, "and a present note still comes back")
        }
    }

    @Test
    fun `a field with no column falls back to the document`(@TempDir root: Path) {
        val directory = build(root)
        withEngine(directory) { store, engine ->
            // `$.team` has an inverted index and no column, so the row cannot be filled from columns
            // at all. Not a failure — the ordinary case for a path nobody shredded.
            val query = Query.where(path("$.team") eq "team-3").project("$.team", "$.score")
            val (found, stats) = rows(engine, store, query)

            assertEquals(fromDocuments(store, query), found)
            assertEquals(0, stats.rowsProjectedFromColumns, "one unbound field must disable the whole row")
            assertTrue(stats.documentsRead > 0, "and the document must actually be read")
        }
    }

    @Test
    fun `an indexed array path is not a projection a column can serve`(@TempDir root: Path) {
        val directory = build(root)
        withEngine(directory) { store, engine ->
            // `$.score[0]` names one element; the column is over `$.score`. Different questions, and
            // the binding refuses rather than guessing which the caller meant.
            val query = Query.where(path("$.team") eq "team-3").project("$.score[0]")
            val (_, stats) = rows(engine, store, query)
            assertEquals(0, stats.rowsProjectedFromColumns)
        }
    }

    @Test
    fun `a wildcard is still refused as a projection`() {
        // The reason phase 12 needed no decision about repeated paths: a projection names one
        // location, so `$.tags[*]` never reaches a column in the first place.
        assertFailsWith<IllegalArgumentException> { Projection.of("$.tags[*]") }
    }

    @Test
    fun `a column-projected row has no document to hand back`(@TempDir root: Path) {
        val directory = build(root)
        withEngine(directory) { store, engine ->
            val query = Query.where(path("$.team") eq "team-3").project("$.score")
            store.snapshot().use { snapshot ->
                engine.execute(query, snapshot).use { cursor ->
                    assertTrue(cursor.next())
                    // No document was opened, so there is none to return. Claiming otherwise would
                    // mean a silent read behind a caller's back.
                    assertFailsWith<IllegalStateException> { cursor.row.document() }
                    // The projected field is still there: refusing the document is not refusing the row.
                    assertTrue(cursor.row["$.score"] != null)
                    assertFailsWith<IllegalArgumentException> { cursor.row["$.team"] }
                }
            }
        }
    }

    @Test
    fun `the plan says whether it will project from columns`(@TempDir root: Path) {
        val directory = build(root)
        withEngine(directory) { store, engine ->
            store.snapshot().use { snapshot ->
                val bound = engine.explain(
                    Query.where(path("$.team") eq "team-3").project("$.score", "$.price"),
                    snapshot,
                )
                assertTrue(bound.projectsFromColumns)
                assertTrue(bound.render().contains("read from shredded columns"))

                val unbound = engine.explain(
                    Query.where(path("$.team") eq "team-3").project("$.team"),
                    snapshot,
                )
                assertTrue(!unbound.projectsFromColumns)
            }
        }
    }

    /**
     * Before the columns exist, while only some segments carry them, and after.
     *
     * The middle state is the one worth arranging on purpose: an index is usable while it is still
     * building, so a projection has to be right when *some* of its ordinals can be served from a
     * column and the rest cannot. Comparing after every step is the same rule the index suites follow.
     */
    @Test
    fun `the answer is the same before, during and after the columns are built`(@TempDir root: Path) {
        val directory = scratch(root, "states")
        val query = Query.where(path("$.team") eq "team-2").project("$.score", "$.price")

        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, queryStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                store.load((0 until 60).map { jsonDocument(document(it)) }, 0)

                val engine = QueryEngine(store, catalog)
                catalog.createIndex(store, IndexDefinition.inverted("$.team"))
                val before = rows(engine, store, query)
                assertEquals(fromDocuments(store, query), before.first, "before any column exists")
                assertEquals(0, before.second.rowsProjectedFromColumns)

                // A second segment with no column over it yet: some ordinals servable, some not.
                catalog.createIndex(store, IndexDefinition.column("$.score"))
                catalog.createIndex(store, IndexDefinition.column("$.price"))
                store.load((60 until 120).map { jsonDocument(document(it)) }, 60)
                val during = rows(engine, store, query)
                assertEquals(fromDocuments(store, query), during.first, "with only some segments covered")

                store.compact()
                val after = rows(engine, store, query)
                assertEquals(fromDocuments(store, query), after.first, "with everything covered")
            }
        }
    }
}
