package app.oreshkov.rabosh.api

import app.oreshkov.rabosh.catalog.CatalogPath
import app.oreshkov.rabosh.catalog.nodesIn
import app.oreshkov.rabosh.core.Key
import app.oreshkov.rabosh.index.IndexDefinition
import app.oreshkov.rabosh.query.Projection
import app.oreshkov.rabosh.query.Query
import app.oreshkov.rabosh.query.path
import app.oreshkov.rabosh.variant.Variant
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * A protobuf-JSON corpus, end to end and **across a reopen**.
 *
 * `@type` is on every message of such a corpus, and it is the field name this engine's own path
 * grammar cannot write in dot form: `$.@type` does not parse, so every path naming it takes the
 * bracket form and therefore the quoted spelling of `VariantPath.toString`. That matters more than
 * ergonomics, because a path is **persisted as its rendered text** — the index registry and the
 * sketch sidecar write `toString()` and read it back with `parse()` — so a renderer and a parser that
 * drifted apart on a non-identifier name would produce a store that does not reopen.
 *
 * This is the only test in the repository that would catch that. Everything else names paths a
 * `.field` step can spell.
 *
 * The directory is deleted at the end, which on Windows is the facade's acceptance instrument: a
 * mapping left live fails the delete immediately rather than as a drift somebody has to pick a bound
 * for.
 */
class ProtobufJsonTest {

    @Test
    fun `an at-type corpus is indexed, queried, expanded and recovered after a reopen`(@TempDir root: Path) {
        val directory = scratch(root)
        val options = RaboshOptions(store = apiStoreOptions())
        val expected = (0 until DOCUMENTS step 2).map { keyFor(it) }

        Rabosh.open(directory, options).use { db ->
            for (index in 0 until DOCUMENTS) db.put(keyFor(index), documentFor(index))
            db.flush()

            // The model finds both paths, which is already a round trip through the `.cat` sidecar's
            // rendered text on the next reopen.
            val paths = db.schema().fields.map { it.path.toString() }
            assertTrue(TYPE_PATH in paths, "the model should name $TYPE_PATH, found $paths")
            assertTrue(NESTED_TYPE_PATH in paths, "the model should name $NESTED_TYPE_PATH, found $paths")

            db.createIndex(IndexDefinition.inverted(TYPE_PATH))
            db.createIndex(IndexDefinition.inverted(NESTED_TYPE_PATH))

            assertEquals(expected, playerKeys(db), "the index must not change which documents match")
            assertEquals(0, documentsReadFor(db), "an inverted index over $TYPE_PATH should read no document")
        }

        // --- the reopen, which is the assertion worth having.
        Rabosh.open(directory, options).use { db ->
            assertEquals(
                listOf(NESTED_TYPE_PATH, TYPE_PATH),
                db.indexes().map { it.path.toString() }.sorted(),
                "both definitions should come back from the registry",
            )
            assertTrue(db.schema().coverage.isComplete, "reopening should find every segment covered")
            assertEquals(expected, playerKeys(db), "the same answers after a reopen")

            // Narrow with the index, expand within the document: the two-step the facade documents.
            val elements = CatalogPath.parse(PLAYERS)
            var expanded = 0
            db.query(Query.where(path(TYPE_PATH) eq PLAYER_TYPE).project(Projection.DOCUMENT)).use { rows ->
                while (rows.next()) {
                    val nodes = elements.nodesIn(rows.row.document())
                    assertEquals(
                        listOf("$['players'][0]", "$['players'][1]"),
                        nodes.map { it.location.toNormalizedPath() },
                        "for ${rows.key}",
                    )
                    assertEquals(
                        listOf(PLAYER_TYPE, PLAYER_TYPE),
                        nodes.map { it.value.field("@type")?.stringValue() },
                    )
                    expanded++
                }
            }
            assertEquals(expected.size, expanded, "every matching document should have been expanded")
        }

        deleteRecursively(directory)
    }

    private fun playerKeys(db: Rabosh): List<Key> = db.keys(Query.where(path(TYPE_PATH) eq PLAYER_TYPE))

    private fun documentsReadFor(db: Rabosh): Int {
        db.query(Query.where(path(TYPE_PATH) eq PLAYER_TYPE)).use { rows ->
            var seen = 0
            while (rows.next()) seen++
            // The counter never stands alone: `documentsRead == 0` is free for a query that
            // returned nothing.
            assertEquals(DOCUMENTS / 2, seen, "the query should have returned the player half")
            return rows.stats.documentsRead
        }
    }

    private fun documentFor(index: Int): Variant {
        val type = if (index % 2 == 0) PLAYER_TYPE else TEAM_TYPE
        val players = (0 until 2).joinToString(",") { """{"@type":"$PLAYER_TYPE","id":${index * 2 + it}}""" }
        return Variant.fromJson("""{"@type":"$type","name":"n$index","players":[$players]}""")
    }

    private fun deleteRecursively(directory: Path) {
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }

    private companion object {
        const val DOCUMENTS = 40

        /** `$.@type` does not parse; in Kotlin the readable spelling of the bracket form is raw. */
        const val TYPE_PATH = """$["@type"]"""
        const val NESTED_TYPE_PATH = """$.players[*]["@type"]"""
        const val PLAYERS = """$.players[*]"""

        const val PLAYER_TYPE = "type.googleapis.com/PlayerDTO"
        const val TEAM_TYPE = "type.googleapis.com/TeamDTO"
    }
}
