package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.testkit.crash.ChildJvm
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * A JVM killed while it is defining indexes.
 *
 * This is the test for the phase's one durability *inversion*. Everything else here is derived data
 * and is allowed to be lost: a `.idx` or a `.pst` that does not survive a power cut costs a rescan.
 * An index **definition** is not derived — it is an instruction somebody gave, written down nowhere
 * else — so `INDEXES` is forced and moved into place atomically, and `createIndex` makes the
 * definition durable *before* it builds a single posting file.
 *
 * The claim that buys is exactly this: after a kill, an index is either **defined** or **not
 * defined**, never half-registered, and one the child reported as created is still there. A crash
 * between the registry and the postings leaves an index that is defined and uncovered — a state every
 * query already handles by scanning — rather than posting files nothing knows about, which nothing
 * would ever read and nothing would ever delete.
 *
 * `SIGKILL` on POSIX, `TerminateProcess` on Windows. Neither is catchable and neither runs a shutdown
 * hook, which is what separates this from an in-process "crash" that still runs `finally` blocks.
 *
 * **Both tests run twice, and the second run is the sharper one.** `BLOCKING` kills a child inside
 * `createIndex`; `BACKGROUND` kills one inside `createIndexInBackground`, where the parent has already
 * been told the index exists and the build is provably still going on a thread of the catalog's own.
 * Every assertion below is the same in both. That is the claim phase 15 has to earn: making a build
 * non-blocking, cancellable and resumable must not cost a single durability guarantee, and the way to
 * show it is to change nothing here except where the kill lands.
 */
class IndexCrashTest {

    private fun runAndKill(directory: Path, linesToRead: Int, mode: String): Pair<Set<Int>, Set<Int>> {
        val created = LinkedHashSet<Int>()
        val dropped = LinkedHashSet<Int>()
        ChildJvm.launch(
            "app.oreshkov.rabosh.index.CrashIndexerMain",
            listOf(directory.toString(), "400", mode),
        ).use { child ->
            assertEquals("READY", child.nextLine(), "child stderr:\n${child.standardError}")
            var read = 0
            while (read < linesToRead) {
                val line = child.nextLine() ?: break
                val parts = line.split(' ')
                when (parts[0]) {
                    "INDEX" -> created.add(parts[1].toInt())
                    "DROP" -> dropped.add(parts[1].toInt())
                }
                read++
            }
            // A line the parent has read is a fact the child had established before it died.
            child.killForcibly()
        }
        assertTrue(created.isNotEmpty(), "the child reported no index at all")
        return created to dropped
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["BLOCKING", "BACKGROUND"])
    fun `an index the child acknowledged survives the kill`(mode: String, @TempDir root: Path) {
        val directory = scratch(root, "crash")
        val (created, dropped) = runAndKill(directory, linesToRead = 5, mode = mode)

        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, indexStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                val defined = catalog.indexes().mapTo(HashSet()) { it.id }

                // The durability claim, both directions.
                for (id in created - dropped) {
                    assertTrue(id in defined, "index #$id was acknowledged as created and is gone")
                }
                for (id in dropped) {
                    assertTrue(id !in defined, "index #$id was acknowledged as dropped and came back")
                }

                // And nothing is half-registered: every definition that survived is usable, and after
                // attach every live segment carries a posting file for it.
                val segments = segmentNumbers(directory)
                assertEquals(segments, baseSidecarNumbers(directory))
                for (handle in catalog.indexes()) {
                    val built = when (handle.kind) {
                        // A composite index's sidecar is a posting file, so the residue rules that
                        // apply to one apply to it unchanged — which is the point of reusing the file
                        // rather than inventing a third.
                        app.oreshkov.rabosh.catalog.IndexKind.INVERTED,
                        app.oreshkov.rabosh.catalog.IndexKind.COMPOSITE_TERM,
                        -> postingFiles(directory)

                        app.oreshkov.rabosh.catalog.IndexKind.SHREDDED_COLUMN -> columnFiles(directory)
                    }
                    assertEquals(
                        segments.map { it to handle.id }.toSet(),
                        built.filter { it.second == handle.id }.toSet(),
                        "index #${handle.id} (${handle.kind}) does not cover every segment after a rebuild",
                    )
                }
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["BLOCKING", "BACKGROUND"])
    fun `sidecars left behind by the kill are never mistaken for live ones`(mode: String, @TempDir root: Path) {
        val directory = scratch(root, "crash")
        runAndKill(directory, linesToRead = 7, mode = mode)

        // A kill can leave a `.pst` for a dropped index, a sidecar for a segment the manifest never
        // named, and a half-written temporary file. None may be read as live, and all are reclaimed.
        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, indexStoreOptions(catalog)).use { store ->
                catalog.attach(store)
                val defined = catalog.indexes().mapTo(HashSet()) { it.id }
                val segments = segmentNumbers(directory)

                for ((segment, id) in postingFiles(directory)) {
                    assertTrue(id in defined, "a posting file survived for undefined index #$id")
                    assertTrue(segment in segments, "a posting file survived for departed segment $segment")
                }
                // The same for columns. `sweep` parses only the names it knows, so a `.col` left by a
                // dropped index or an orphaned segment would be invisible to it and leak silently.
                for ((segment, id) in columnFiles(directory)) {
                    assertTrue(id in defined, "a column survived for undefined index #$id")
                    assertTrue(segment in segments, "a column survived for departed segment $segment")
                }
                assertEquals(segments, baseSidecarNumbers(directory))
                assertTrue(
                    Files.newDirectoryStream(directory).use { entries ->
                        entries.none { it.fileName.toString().endsWith(".tmp") }
                    },
                    "a temporary file survived the sweep",
                )

                // The answers are right, which is the only thing any of this is for.
                val handle = catalog.indexes().firstOrNull()
                if (handle != null) {
                    store.snapshot().use { snapshot ->
                        catalog.read(store, handle, snapshot).use { reader ->
                            val term = IndexTerm.ofString("a3")
                            if (reader.answers(term)) {
                                assertEquals(
                                    IndexQuery.scanKeys(store, reader, matches = { term in it }),
                                    IndexQuery.keysEqualTo(store, reader, term),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
