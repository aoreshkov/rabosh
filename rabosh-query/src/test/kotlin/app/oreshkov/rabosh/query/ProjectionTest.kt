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
 * What a query returns, and what asking for it costs.
 *
 * The load-bearing distinction here is that **projection speaks `VariantPath` while filtering speaks
 * `CatalogPath`**. `$.tags[*]` is a fine thing to filter on — does *any* tag match — and has no
 * answer at all as a projection, so it is rejected rather than resolved to something arbitrary.
 */
class ProjectionTest {

    @Test
    fun `named fields are projected, and a missing one is null rather than an error`(@TempDir root: Path) {
        withStore(root) { store, catalog, engine ->
            catalog.createIndex(store, IndexDefinition.inverted("$.team"))
            store.snapshot().use { snapshot ->
                val query = Query.where(path("$.team") eq "team-1")
                    .project("$.team", "$.score", "$.nested.deep", "$.missing")

                engine.execute(query, snapshot).use { cursor ->
                    var rows = 0
                    while (cursor.next()) {
                        rows++
                        val row = cursor.row
                        assertEquals("team-1", row["$.team"]?.stringValue())
                        assertEquals("deep", row["$.nested.deep"]?.stringValue())
                        assertNull(row["$.missing"], "an absent path is absent, not a failure")
                        assertTrue(row.toJsonString().startsWith("""{"$.team":"team-1""""))
                        assertFailsWith<IllegalArgumentException> { row["$.notProjected"] }
                    }
                    assertTrue(rows > 0)
                }
            }
        }
    }

    @Test
    fun `the document projection hands back the whole document`(@TempDir root: Path) {
        withStore(root) { store, catalog, engine ->
            store.snapshot().use { snapshot ->
                val query = Query.where(path("$.team") eq "team-1").project(Projection.DOCUMENT)
                engine.execute(query, snapshot).use { cursor ->
                    assertTrue(cursor.next())
                    assertEquals("team-1", cursor.row.document().select("$.team")?.stringValue())
                    assertTrue(cursor.row.toJsonString().contains("\"score\""))
                }
            }
        }
    }

    /** The keys-only projection never opens a document, and says so if asked for one. */
    @Test
    fun `the key projection refuses to invent a document`(@TempDir root: Path) {
        withStore(root) { store, catalog, engine ->
            store.snapshot().use { snapshot ->
                engine.execute(Query.where(path("$.team") eq "team-1"), snapshot).use { cursor ->
                    assertTrue(cursor.next())
                    assertFailsWith<IllegalStateException> { cursor.row.document() }
                    assertEquals("{}", cursor.row.toJsonString())
                }
            }
        }
    }

    /** A wildcard names a set of locations, so it is not something a row can hold. */
    @Test
    fun `a wildcard is rejected as a projection`() {
        assertFailsWith<IllegalArgumentException> { Projection.of("$.tags[*]") }
        assertFailsWith<IllegalArgumentException> { Projection.of("$.not a path") }
        assertFailsWith<IllegalArgumentException> { Projection.of() }
        // But it is perfectly ordinary as a filter, which is the whole point of the two types.
        assertEquals("$.tags[*]", path("$.tags[*]").path.toString())
    }

    /** An index element is a projection and not a filter — the mirror image of the rule above. */
    @Test
    fun `an array index projects but does not filter`() {
        assertEquals(listOf("$.tags[0]"), Projection.of("$.tags[0]").names)
        assertFailsWith<IllegalArgumentException> { path("$.tags[0]") }
    }

    @Test
    fun `a cursor off a row has neither a key nor a row`(@TempDir root: Path) {
        withStore(root) { store, catalog, engine ->
            store.snapshot().use { snapshot ->
                engine.execute(Query.where(path("$.team") eq "nobody"), snapshot).use { cursor ->
                    assertFailsWith<IllegalStateException> { cursor.key }
                    assertFailsWith<IllegalStateException> { cursor.row }
                    assertTrue(!cursor.next())
                    assertFailsWith<IllegalStateException> { cursor.key }
                }
            }
        }
    }

    private fun withStore(root: Path, body: (DocumentStore, IndexCatalog, QueryEngine) -> Unit) {
        val directory = scratch(root, "projection")
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, queryStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                store.load(
                    (0 until 60).map { index ->
                        jsonDocument(
                            """{"team":"team-${index % 4}","score":$index,"tags":["a","b"],""" +
                                """"nested":{"deep":"deep"}}""",
                        )
                    },
                )
                body(store, catalog, QueryEngine(store, catalog))
            }
        }
    }
}
