package app.oreshkov.rabosh.api

import app.oreshkov.rabosh.index.IndexDefinition
import app.oreshkov.rabosh.query.Projection
import app.oreshkov.rabosh.query.Query
import app.oreshkov.rabosh.query.path
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * A checkpoint through the facade: the whole database, not just the store.
 *
 * `CheckpointTest` in `rabosh-core` asserts the documents. What can only be asserted here is that the
 * *derived data and the definitions* travel too — a copy that opened with the right documents and no
 * indexes would pass every assertion there and would still have lost the thing a caller spent a scan
 * building.
 *
 * **The sidecars must be read rather than rebuilt**, which is the same assertion `FormatCompatibilityTest`
 * makes of the golden stores and for the same reason: a copy opened with `backfill = true` would
 * regenerate whatever was missing and every one of these tests would pass over a checkpoint that had
 * carried nothing at all.
 */
class RaboshCheckpointTest {

    @TempDir
    lateinit var root: Path

    private fun options(backfill: Boolean = true) = RaboshOptions(
        store = apiStoreOptions(),
        backfill = backfill,
    )

    /**
     * The load-bearing one: open the copy with backfilling **off**, and the index still answers.
     *
     * With `backfill = false` nothing is scanned and nothing is rebuilt, so an index that answers is
     * an index whose `.idx` and `.pst` files were carried and decoded — and a registry that was
     * carried with them, because a posting file nothing knows about is an orphan.
     */
    @Test
    fun `a checkpoint's indexes are read rather than rebuilt`() {
        val directory = scratch(root)
        val target = root.resolve("checkpoint-indexed")

        val expected = Rabosh.open(directory, options()).use { db ->
            db.load(0, 400)
            db.flush()
            db.createIndex(IndexDefinition.inverted("$.team"))

            val before = db.keys(teamQuery())
            assertTrue(before.isNotEmpty(), "the fixture must match something, or nothing below is a claim")
            db.checkpoint(target)
            before
        }

        Rabosh.open(target, options(backfill = false)).use { copy ->
            assertEquals(1, copy.indexes().size, "the registry travelled: the definition is not derived data")

            val explained = copy.explain(teamQuery())
            assertTrue(explained.usesIndexes, "the copy answered from sidecars:\n${explained.render()}")
            assertEquals(0, explained.segmentsScanned, "a scan here would mean the sidecars were not read")

            assertEquals(expected, copy.keys(teamQuery()), "the same keys, from the copy's own files")
        }
    }

    /** The model travels too, and is likewise not recollected. */
    @Test
    fun `a checkpoint carries the schema catalog`() {
        val directory = scratch(root)
        val target = root.resolve("checkpoint-modelled")

        val expected = Rabosh.open(directory, options()).use { db ->
            db.load(0, 200)
            db.flush()
            val schema = db.schema()
            assertTrue(schema.fields.isNotEmpty())
            db.checkpoint(target)
            schema.fields.map { it.path.toString() }.sorted()
        }

        Rabosh.open(target, options(backfill = false)).use { copy ->
            val schema = copy.schema()
            assertEquals(expected, schema.fields.map { it.path.toString() }.sorted())
            assertTrue(
                schema.coverage.isComplete,
                "every segment carried its own `.cat`, so the model is complete without a scan: ${schema.coverage}",
            )
        }
    }

    /**
     * The copy is a database, not a snapshot of one: it opens for writing and carries on.
     *
     * The point case B needs — a checkpoint that could only be read would be an export, and the
     * thing an application wants after losing its data directory is to keep working.
     */
    @Test
    fun `a checkpoint opens as a writable database`() {
        val directory = scratch(root)
        val target = root.resolve("checkpoint-writable")

        Rabosh.open(directory, options()).use { db ->
            db.load(0, 100)
            db.checkpoint(target)
        }

        Rabosh.open(target, options()).use { copy ->
            copy.load(100, 50)
            copy.flush()
            assertEquals(documentOf(120).toString(), copy.get(keyFor(120)).toString())
            assertEquals(documentOf(0).toString(), copy.get(keyFor(0)).toString())
        }
    }

    /** A database with no index defined writes no registry, and the copy is not left with an empty one. */
    @Test
    fun `a checkpoint of an unindexed database carries no registry`() {
        val directory = scratch(root)
        val target = root.resolve("checkpoint-plain")

        Rabosh.open(directory, options()).use { db ->
            db.load(0, 50)
            db.checkpoint(target)
        }

        assertTrue(
            Files.notExists(target.resolve("INDEXES")),
            "a store that never defined an index has no registry, and a copy must not invent one",
        )
        Rabosh.open(target, options(backfill = false)).use { copy ->
            assertEquals(0, copy.indexes().size)
        }
    }

    /** `team-3` is one of the seven the corpus cycles through, so it matches a seventh of it. */
    private fun teamQuery(): Query = Query.where(path("$.team") eq "team-3").project(Projection.KEY)
}
