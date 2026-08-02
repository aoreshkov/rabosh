package app.oreshkov.rabosh.query

import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.core.Key
import app.oreshkov.rabosh.index.DamagedIndexPolicy
import app.oreshkov.rabosh.index.IndexCatalog
import app.oreshkov.rabosh.index.IndexDefinition
import app.oreshkov.rabosh.index.IndexException
import app.oreshkov.rabosh.index.IndexOptions
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * A damaged sidecar changes what a query *costs* or whether it runs — never what it returns.
 *
 * The two-level checksum decides *when* damage is found, and a query is the first caller that sees
 * both levels. A flip in a **directory** is caught when the file is opened, which is where a policy
 * can act on it: `REPORT` surfaces it, `REBUILD` rebuilds from the segment, which is possible exactly
 * because an index is derived data. A flip inside a **section** — a posting list, a column's
 * statistics — is caught when that section is first read, which is a query, and there the only sound
 * outcome is to fail loudly. Silently returning fewer documents is the one thing that must not
 * happen, and phase 7b's *readable and wrong* case is why the statistics carry a checksum at all.
 */
class QueryCorruptionTest {

    private fun document(index: Int) =
        jsonDocument("""{"team":"team-${index % 5}","score":${index % 40}}""")

    private val queries = listOf(
        Query.where(path("$.team") eq "team-2"),
        Query.where(path("$.score") lt 10L),
        Query.where(and(path("$.team") eq "team-1", path("$.score") ge 20L)),
    )

    @Test
    fun `a damaged directory is reported rather than worked around`(@TempDir root: Path) {
        val directory = build(root)
        flip(directory, ".pst", ::directoryOffset)

        IndexCatalog(directory, IndexOptions(damagedSidecars = DamagedIndexPolicy.REPORT)).use { catalog ->
            DocumentStore.open(directory, queryStoreOptions(catalog)).use { store ->
                val failure = runCatching { catalog.attach(store) }.exceptionOrNull()
                assertTrue(
                    failure is IndexException,
                    "damage must be reported: ${failure?.let { it::class.simpleName }} ${failure?.message}",
                )
            }
        }
    }

    @Test
    fun `a damaged directory is rebuilt, and the answers are unchanged`(@TempDir root: Path) {
        val directory = build(root)
        val expected = answers(directory)
        flip(directory, ".pst", ::directoryOffset)

        IndexCatalog(directory, IndexOptions(damagedSidecars = DamagedIndexPolicy.REBUILD)).use { catalog ->
            DocumentStore.open(directory, queryStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                val engine = QueryEngine(store, catalog)
                store.snapshot().use { snapshot ->
                    for ((query, keys) in expected) {
                        assertEquals(keys, engine.keys(query, snapshot), "after a rebuild: $query")
                        assertMatchesScan(engine, store, snapshot, query, "after a rebuild")
                    }
                }
            }
        }
    }

    /** The same for a column, whose directory damage is found at the same moment. */
    @Test
    fun `a damaged column directory is rebuilt, and ranges still answer`(@TempDir root: Path) {
        val directory = build(root)
        val expected = answers(directory)
        flip(directory, ".col", ::directoryOffset)

        IndexCatalog(directory, IndexOptions(damagedSidecars = DamagedIndexPolicy.REBUILD)).use { catalog ->
            DocumentStore.open(directory, queryStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                val engine = QueryEngine(store, catalog)
                store.snapshot().use { snapshot ->
                    for ((query, keys) in expected) {
                        assertEquals(keys, engine.keys(query, snapshot), "after a rebuild: $query")
                    }
                }
            }
        }
    }

    /**
     * Damage inside a section is found when a query reads it, and the query **fails**.
     *
     * Not "returns what it can": a posting list that will not decode is a set of documents nobody can
     * enumerate, and answering around it would be an index changing an answer. The failure names the
     * file, which is what makes the repair — rebuild that sidecar — an obvious next step rather than
     * an investigation.
     */
    @Test
    fun `damage inside a section makes the query fail rather than lose documents`(@TempDir root: Path) {
        val directory = build(root)
        flip(directory, ".pst") { size -> size * 3 / 4 }

        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, queryStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                val engine = QueryEngine(store, catalog)
                store.snapshot().use { snapshot ->
                    val failures = queries.map { runCatching { engine.keys(it, snapshot) }.exceptionOrNull() }
                    assertTrue(
                        failures.any { it is IndexException },
                        "a section that will not decode must be reported: $failures",
                    )
                    assertTrue(
                        failures.filterNotNull().all { it is IndexException },
                        "and reported as an index failure, not as something three frames down: $failures",
                    )
                }
            }
        }
    }

    /** A missing sidecar is not damage: it reads as *not covered*, and the segment is scanned. */
    @Test
    fun `a deleted sidecar is scanned rather than reported`(@TempDir root: Path) {
        val directory = build(root)
        val expected = answers(directory)
        val victim = Files.list(directory).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".pst") }.sorted().toList().last()
        }
        Files.delete(victim)

        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, queryStoreOptions(catalog)).use { store ->
                catalog.attach(store, backfill = false)
                val engine = QueryEngine(store, catalog)
                store.snapshot().use { snapshot ->
                    for ((query, keys) in expected) {
                        assertEquals(keys, engine.keys(query, snapshot), "with a sidecar missing: $query")
                    }
                    val stats = assertMatchesScan(
                        engine,
                        store,
                        snapshot,
                        Query.where(path("$.team") eq "team-2"),
                        "with a sidecar missing",
                    )
                    assertTrue(stats.segmentsScanned > 0, "the uncovered segment must be scanned")
                }
            }
        }
    }

    /** A store with both index kinds over several segments. */
    private fun build(root: Path): Path {
        val directory = scratch(root, "corrupt")
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, queryStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                for (round in 0 until 3) {
                    store.load((round * 80 until round * 80 + 80).map(::document), round * 80)
                }
                catalog.createIndex(store, IndexDefinition.inverted("$.team"))
                catalog.createIndex(store, IndexDefinition.column("$.score"))
            }
        }
        return directory
    }

    /** What the queries answer while everything is intact. */
    private fun answers(directory: Path): Map<Query, List<Key>> {
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, queryStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                val engine = QueryEngine(store, catalog)
                store.snapshot().use { snapshot ->
                    return queries.associateWith { engine.keys(it, snapshot) }
                }
            }
        }
    }

    /** Well inside the header and directory, which every one of these files begins with. */
    private fun directoryOffset(size: Long): Long = 24L

    /** Flips one byte of the newest sidecar of a kind, at the offset [at] chooses. */
    private fun flip(directory: Path, suffix: String, at: (Long) -> Long) {
        val victim = Files.list(directory).use { paths ->
            paths.filter { it.fileName.toString().endsWith(suffix) }.sorted().toList().last()
        }
        Files.newByteChannel(victim, StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
            val offset = at(channel.size())
            val buffer = ByteBuffer.allocate(1)
            channel.position(offset).read(buffer)
            val flipped = ByteBuffer.wrap(byteArrayOf((buffer.get(0).toInt() xor 0x5A).toByte()))
            channel.position(offset).write(flipped)
        }
    }
}
