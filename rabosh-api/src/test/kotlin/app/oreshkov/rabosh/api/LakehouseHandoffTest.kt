package app.oreshkov.rabosh.api

import app.oreshkov.rabosh.catalog.shreddingAdvice
import app.oreshkov.rabosh.query.Projection
import app.oreshkov.rabosh.query.Query
import app.oreshkov.rabosh.query.path
import app.oreshkov.rabosh.variant.Variant
import app.oreshkov.rabosh.variant.VariantMetadata
import app.oreshkov.rabosh.variant.toJsonString
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Getting bytes out, for a caller writing them into a lakehouse.
 *
 * **The claim under test is that a detached document's `(metadata, value)` pair stands alone**, and
 * the way it is checked is what makes it worth anything: the pair is taken apart and read back
 * through an *independent decode* — `VariantMetadata` and `Variant` reconstructed from the two byte
 * arrays with nothing of the store behind them — rather than by asking the object that produced it
 * whether it is happy. A round trip through the same reader would pass for a document still pointing
 * at a segment's dictionary, which is precisely the trap.
 *
 * No Parquet writer is involved and none is a dependency. What this pins is that the bytes are
 * self-contained and that the field names survive, which is the part the engine owes a caller.
 */
class LakehouseHandoffTest {

    @TempDir
    lateinit var root: Path

    private fun options() = RaboshOptions(store = apiStoreOptions())

    /**
     * The trap, demonstrated before the fix: a document read from a segment carries **that
     * segment's** dictionary.
     *
     * Asserted as an inequality rather than described, because it is the reason `detached` exists and
     * a reader meeting only the fixed version would reasonably wonder why.
     */
    @Test
    fun `a document read from a segment carries the segment's shared dictionary`() {
        val directory = scratch(root, "shared")
        Rabosh.open(directory, options()).use { db ->
            // Each document names a field of its own, so the segment's dictionary grows with the
            // corpus while no single document's does. The main corpus cannot show this — every
            // document there carries the same six names, which is exactly the *homogeneous* shape
            // one dictionary per segment is optimised for and therefore the shape where the trap is
            // invisible.
            for (index in 0 until 100) {
                db.put(keyFor(index), """{"common":$index,"only_in_$index":true}""")
            }
            db.flush()

            // Document 50, not document 0, and the reason is the whole hazard in miniature. A
            // dictionary is name-ordered, so document 0's two names — `common`, `only_in_0` — happen
            // to take ids 0 and 1 in the *shared* dictionary as well as in its own, and pairing its
            // value with the wrong dictionary reads back perfectly. The trap is not that a mismatched
            // pair always fails; it is that it sometimes succeeds.
            val attached = db.get(keyFor(50))!!
            val detached = attached.detached()

            assertEquals(2, detached.metadata.size, "the document names two fields")
            assertTrue(
                attached.metadata.size > 50,
                "the segment's dictionary names every document's fields: ${attached.metadata.size}",
            )
            assertNotEquals(
                attached.metadata.toByteArray().size,
                detached.metadata.toByteArray().size,
                "if these matched, the fixture would not be demonstrating anything",
            )
            assertEquals(attached.toJsonString(), detached.toJsonString(), "and the value is unchanged")

            // The failure this prevents, made concrete: the value's field ids index into whichever
            // dictionary it is paired with, so pairing the detached value with the shared metadata
            // resolves the wrong names — or none at all.
            val mismatched = runCatching {
                Variant(attached.metadata, detached.toByteArray()).toJsonString()
            }.getOrNull()
            assertNotEquals(
                attached.toJsonString(),
                mismatched,
                "a value and a dictionary that do not belong together must not read as the document",
            )
        }
    }

    /**
     * The acceptance criterion: write the pair out, read it back through an independent decode.
     *
     * Nothing of the store is in scope on the way back — two byte arrays go in and a `Variant` comes
     * out — which is the situation a Parquet Variant column puts the bytes in.
     */
    @Test
    fun `a detached pair decodes on its own`() {
        withDatabase { db ->
            db.query(Query.where(path("$.team") eq "team-3").project(Projection.DOCUMENT)).use { rows ->
                var checked = 0
                while (rows.next()) {
                    val expected = rows.row.document().toJsonString()
                    val detached = rows.row.document().detached()

                    // The hand-off itself: two arrays, and nothing else crosses the boundary.
                    val metadataBytes = detached.metadata.toByteArray()
                    val valueBytes = detached.toByteArray()

                    val reread = Variant(VariantMetadata.of(metadataBytes), valueBytes)
                    assertEquals(expected, reread.toJsonString(), "the pair must stand alone")
                    assertEquals("team-3", reread.field("team")?.stringValue(), "field names survived")
                    checked++
                }
                assertTrue(checked > 0, "the query must return something, or nothing above ran")
            }
        }
    }

    /**
     * The honest alternative, which is cheaper and is what a consumer taking a shared dictionary
     * should be handed.
     *
     * Stated as a test so the pairing is on record: `detached` is not always the right answer, and a
     * caller handing over one dictionary per *segment* copies no names at all.
     */
    @Test
    fun `an undetached pair decodes when the shared metadata travels with it`() {
        withDatabase { db ->
            val document = db.get(keyFor(1))!!
            val shared = document.metadata.toByteArray()

            val reread = Variant(VariantMetadata.of(shared), document.toByteArray())
            assertEquals(document.toJsonString(), reread.toJsonString())
        }
    }

    // --- the advice ------------------------------------------------------------------------------

    /**
     * Shredding advice over what the catalog already computed.
     *
     * The corpus's `team` is a stable string carrying a real share of the bytes, so it must be
     * advised; `$.tags` is an array and cannot be one column, so it must not be. Both directions,
     * because a recommender that recommends everything is not a recommender.
     */
    @Test
    fun `shredding advice names the stable scalar paths and not the containers`() {
        withDatabase { db ->
            val advice = db.schema().shreddingAdvice()
            assertTrue(advice.isNotEmpty(), "the corpus has stable scalar paths")

            val paths = advice.map { it.path.toString() }
            assertTrue(paths.contains("$.team"), paths.toString())
            assertTrue(!paths.contains("$.tags"), "an array is not one typed column: $paths")
            assertTrue(!paths.contains("$"), "the root is not a shreddable leaf: $paths")

            val team = advice.single { it.path.toString() == "$.team" }
            assertEquals("BINARY (UTF8)", team.parquetType)
            assertTrue(team.presence > 0.99, team.toString())
            assertTrue(team.byteShare > 0.0, team.toString())
            assertTrue(team.render().contains("typed_value"), team.render())
        }
    }

    /**
     * The one decision a hand-written schema gets wrong: whether `variant_value` can be dropped.
     *
     * A path holding two types needs the untyped fallback populated, and the advice has to say so —
     * dropping it there loses every value of the minority type, silently, which is the same class of
     * failure as the type bracketing `explain` now reports.
     */
    @Test
    fun `a path with a residual type is told to keep variant_value`() {
        val directory = scratch(root, "residual")
        Rabosh.open(directory, options()).use { db ->
            for (index in 0 until 200) {
                val status = if (index % 10 == 0) """"$index"""" else "$index"
                db.put(keyFor(index), """{"status":$status,"filler":"$index-$index-$index-$index"}""")
            }
            db.flush()

            val status = db.schema().shreddingAdvice().singleOrNull { it.path.toString() == "$.status" }
            assertTrue(status != null, "a 90%-stable path is still worth shredding")
            assertTrue(status.residual, "one value in ten is a string: ${status.render()}")
            assertTrue(status.render().contains("variant_value: required"), status.render())
            assertTrue(status.reason.contains("string"), status.reason)
        }
    }

    /** A model of nothing advises nothing, rather than dividing by zero. */
    @Test
    fun `an empty model advises nothing`() {
        val directory = scratch(root, "empty")
        Rabosh.open(directory, options()).use { db ->
            assertEquals(emptyList(), db.schema().shreddingAdvice())
        }
    }

    private fun withDatabase(body: (Rabosh) -> Unit) {
        Rabosh.open(scratch(root, "handoff"), options()).use { db ->
            db.load(0, 300)
            body(db)
        }
    }
}
