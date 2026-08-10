package app.oreshkov.rabosh.query

import app.oreshkov.rabosh.catalog.SchemaCatalog
import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.index.CompositeSegmentObserver
import app.oreshkov.rabosh.index.IndexCatalog
import app.oreshkov.rabosh.variant.Variant
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * `explain` says when a predicate cannot match the data's types.
 *
 * **A diagnostic, never a coercion**, and the two assertions that matter here are on opposite sides
 * of that line: the note appears, *and* the answer is unchanged. A numeric predicate still matches
 * numeric values only — that is type bracketing, it is part of the query contract, and skipping a
 * column whose numeric bound misses depends on it. Anything here that made `$.status eq 500` match
 * `"500"` would be a second definition of `ColumnPredicate.matches` and would break skipping.
 *
 * The fixture is the case the item exists for: a vendor sends a field as a number in most documents
 * and as a string in the rest, so a query returns fewer rows than expected with nothing in the
 * result to say why.
 */
class ExplainTypeNoteTest {

    /** One document in five sends `status` as a string, the way a real second producer does. */
    private fun mixed(index: Int): Variant = jsonDocument(
        if (index % 5 == 0) {
            """{"status":"${200 + index % 3}","team":"team-${index % 7}"}"""
        } else {
            """{"status":${200 + index % 3},"team":"team-${index % 7}"}"""
        },
    )

    /** Every document agrees. Nothing here may produce a note. */
    private fun uniform(index: Int): Variant = jsonDocument(
        """{"status":${200 + index % 3},"team":"team-${index % 7}"}""",
    )

    @Test
    fun `a numeric predicate over a partly string path is reported`(@TempDir root: Path) {
        withStore(root, ::mixed) { store, engine ->
            store.snapshot().use { snapshot ->
                val query = Query.where(path("$.status") eq 200L)
                val explain = engine.explain(query, snapshot)

                val note = explain.typeNotes.singleOrNull()
                assertTrue(note != null, "the mismatch must be reported:\n${explain.render()}")
                assertEquals("$.status", note.path)
                assertEquals("numeric", note.family)
                assertTrue(note.mismatchedFraction > 0.15, "about a fifth of the values are strings: $note")
                assertTrue(note.mismatchedFraction < 0.25, note.toString())
                assertTrue(note.mismatchedTypes.single().startsWith("string"), note.mismatchedTypes.toString())

                // It is rendered, because a property nobody prints is a property nobody reads.
                assertTrue(explain.render().contains("notes:"), explain.render())
                assertTrue(explain.render().contains("not matched"), explain.render())
            }
        }
    }

    /**
     * The other side of the line: the note changes no answer.
     *
     * Asserted against a full scan through the engine's own matcher, so "unchanged" means the same
     * documents rather than the same count — and the string-valued documents are still *not* matched,
     * which is the contract the note describes rather than a defect it announces.
     */
    @Test
    fun `the note changes nothing about the answer`(@TempDir root: Path) {
        withStore(root, ::mixed) { store, engine ->
            store.snapshot().use { snapshot ->
                val query = Query.where(path("$.status") eq 200L)
                assertMatchesScan(engine, store, snapshot, query, "a reported mismatch")

                val keys = engine.keys(query, snapshot)
                assertTrue(keys.isNotEmpty(), "the numeric documents still match")
                // Counted from the corpus's own rule rather than from a formula: the documents that
                // match are those sending a *number* that happens to be 200. The string-valued ones
                // spell "200" and are not among them, which is the whole point.
                val expected = (0 until 400).count { it % 5 != 0 && (200 + it % 3) == 200 }
                assertEquals(expected, keys.size, "no string-valued document was coerced in")
                assertTrue(
                    (0 until 400).any { it % 5 == 0 && "${200 + it % 3}" == "200" },
                    "the fixture must actually contain a string \"200\", or the assertion above is empty",
                )
            }
        }
    }

    /** A path whose values all agree produces no note, or the notes would be noise a reader learns to skip. */
    @Test
    fun `a path whose types agree is not reported`(@TempDir root: Path) {
        withStore(root, ::uniform) { store, engine ->
            store.snapshot().use { snapshot ->
                val explain = engine.explain(Query.where(path("$.status") eq 200L), snapshot)
                assertTrue(explain.typeNotes.isEmpty(), explain.render())
                assertTrue(!explain.render().contains("notes:"), explain.render())
            }
        }
    }

    /**
     * A leaf that brackets to nothing cannot mismatch, and must not claim to.
     *
     * `EXISTS` matches a value of any type, and a mixed `IN` matches two families at once. Reporting
     * a disagreement for either would be a diagnostic that lied.
     */
    @Test
    fun `a leaf with no family is never reported`(@TempDir root: Path) {
        withStore(root, ::mixed) { store, engine ->
            store.snapshot().use { snapshot ->
                assertTrue(engine.explain(Query.where(path("$.status").exists()), snapshot).typeNotes.isEmpty())
                assertTrue(
                    engine.explain(Query.where(path("$.status").oneOf(200L, "200")), snapshot).typeNotes.isEmpty(),
                    "a mixed IN brackets to nothing, so nothing disagrees with it",
                )
            }
        }
    }

    /**
     * Without a schema there is no note, and that is the honest answer rather than a gap.
     *
     * No statistics is not a statistic saying no. An engine built with no `InferredSchema` has
     * nothing to compare a family against, and a note asserting agreement it never checked would be
     * worse than silence.
     */
    @Test
    fun `no schema means no notes`(@TempDir root: Path) {
        val directory = scratch(root, "noschema")
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, queryStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                store.load((0 until 100).map(::mixed))
                val engine = QueryEngine(store, catalog, schema = null)
                store.snapshot().use { snapshot ->
                    val explain = engine.explain(Query.where(path("$.status") eq 200L), snapshot)
                    assertTrue(explain.typeNotes.isEmpty(), explain.render())
                }
            }
        }
    }

    /**
     * A leaf with no index still gets a note, which is the case a caller has no other signal for.
     *
     * Everything above runs without an index over `$.status` too, so this states it as its own claim
     * rather than leaving it implied: the plan is a full scan, the result is quietly short, and the
     * note is the only thing that says why.
     */
    @Test
    fun `a leaf with no index is reported too`(@TempDir root: Path) {
        withStore(root, ::mixed) { store, engine ->
            store.snapshot().use { snapshot ->
                val explain = engine.explain(Query.where(path("$.status") eq 200L), snapshot)
                assertTrue(explain.sources.isEmpty(), "no index over \$.status: ${explain.render()}")
                assertEquals(1, explain.typeNotes.size, explain.render())
            }
        }
    }

    private fun withStore(
        root: Path,
        document: (Int) -> Variant,
        body: (DocumentStore, QueryEngine) -> Unit,
    ) {
        val directory = scratch(root, "typenote")
        val schema = SchemaCatalog(directory)
        IndexCatalog(directory).use { indexes ->
            val observer = CompositeSegmentObserver(listOf(schema, indexes))
            DocumentStore.open(directory, queryStoreOptions(indexes).withSegmentObserver(observer)).use { store ->
                schema.attach(store)
                indexes.attach(store)
                for (round in 0 until 4) {
                    store.load((round * 100 until round * 100 + 100).map(document), round * 100)
                }
                body(store, QueryEngine(store, indexes, schema.inferSchema()))
            }
        }
    }
}
