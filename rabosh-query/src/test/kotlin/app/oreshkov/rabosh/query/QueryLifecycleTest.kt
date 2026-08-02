package app.oreshkov.rabosh.query

import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.index.IndexCatalog
import app.oreshkov.rabosh.index.IndexDefinition
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * A cursor holds files open, and lets go of them when it closes.
 *
 * Asserted through the **filesystem** rather than through a memory threshold, which is the rule the
 * rest of the engine follows: on Windows a mapped file cannot be deleted at all, so a leaked mapping
 * fails here immediately and deterministically instead of drifting past a bound somebody had to pick.
 *
 * Both directions matter and the second is the one that is easy to lose. A cursor that let its files
 * be deleted would be reading freed pages; a cursor that never let go would make a store grow
 * forever.
 */
class QueryLifecycleTest {

    @Test
    fun `an open cursor keeps the sidecars it is reading, and closing releases them`(@TempDir root: Path) {
        val directory = scratch(root, "lifecycle")
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, queryStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                val engine = QueryEngine(store, catalog)
                for (round in 0 until 4) {
                    store.load(
                        (round * 80 until round * 80 + 80).map {
                            jsonDocument("""{"team":"team-${it % 5}","score":${it % 40}}""")
                        },
                        round * 80,
                    )
                }
                catalog.createIndex(store, IndexDefinition.inverted("$.team"))
                catalog.createIndex(store, IndexDefinition.column("$.score"))

                val before = sidecars(directory)
                assertTrue(before.isNotEmpty(), "the fixture should have written sidecars")

                store.snapshot().use { snapshot ->
                    val cursor = engine.execute(Query.where(path("$.team") eq "team-2"), snapshot)
                    assertTrue(cursor.next())

                    // A compaction retires every input segment while a reader is inside its sidecars.
                    store.compact()
                    catalog.attach(store)
                    assertTrue(
                        sidecars(directory).containsAll(before),
                        "a live cursor must keep the files it is reading; missing " +
                            "${before - sidecars(directory).toSet()}",
                    )

                    // The answer is still right, from files nothing else can see any more.
                    val keys = buildList {
                        add(cursor.key)
                        while (cursor.next()) add(cursor.key)
                    }
                    assertEquals(scanKeys(store, snapshot, path("$.team") eq "team-2"), keys)
                    cursor.close()
                }

                // Nothing is holding them now, so the next compaction reclaims them.
                store.compact()
                catalog.attach(store)
                assertEquals(
                    segments(directory).size * 3,
                    sidecars(directory).size,
                    "one base and two index sidecars per live segment, exactly",
                )
            }
        }
    }

    /** Closing a cursor twice is fine; closing the engine is not a thing, because it owns nothing. */
    @Test
    fun `a cursor closes once and idempotently`(@TempDir root: Path) {
        val directory = scratch(root, "lifecycle")
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, queryStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                store.load((0 until 40).map { jsonDocument("""{"team":"team-${it % 5}"}""") })
                catalog.createIndex(store, IndexDefinition.inverted("$.team"))
                val engine = QueryEngine(store, catalog)

                store.snapshot().use { snapshot ->
                    val cursor = engine.execute(Query.all(), snapshot)
                    cursor.close()
                    cursor.close()
                }
                // And the store still compacts, which it could not if a mapping had leaked.
                store.compact()
                catalog.attach(store)
                assertEquals(segments(directory).size * 2, sidecars(directory).size)
            }
        }
    }

    private fun names(directory: Path): List<String> =
        Files.list(directory).use { paths -> paths.map { it.fileName.toString() }.toList() }

    private fun segments(directory: Path): List<String> = names(directory).filter { it.endsWith(".seg") }

    private fun sidecars(directory: Path): List<String> =
        names(directory).filter { it.endsWith(".idx") || it.endsWith(".pst") || it.endsWith(".col") }
}
