package app.oreshkov.rabosh.query

import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.index.IndexCatalog
import app.oreshkov.rabosh.index.IndexDefinition
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Queries running while flushes, compactions and index builds land underneath them.
 *
 * **The one suite here that turns `backgroundMaintenance` back on**, and the exception proves the
 * rule the rest of them follow: a test that reasons about which segments exist cannot have a thread
 * rewriting them, and a test about *racing* that thread cannot do without one.
 *
 * It asserts answers only — never which plan was chosen, never a counter. Which segments an index
 * covers at any instant is genuinely racy, and asserting on it is how a race test becomes a flake.
 * What is not racy is that every answer equals a scan at the same snapshot, and that is the claim.
 */
class QueryConcurrencyTest {

    private fun document(index: Int) =
        jsonDocument("""{"team":"team-${index % 6}","score":${index % 40},"tag":"t${index % 3}"}""")

    @Test
    fun `answers match a scan while maintenance and index builds run`(@TempDir root: Path) {
        val directory = scratch(root, "concurrent")
        val failure = AtomicReference<Throwable?>()

        IndexCatalog(directory).use { catalog ->
            DocumentStore.open(directory, queryStoreOptions(catalog, backgroundMaintenance = true)).use { store ->
                catalog.attach(store)
                val engine = QueryEngine(store, catalog)
                store.load((0 until 400).map(::document))

                val readers = Executors.newFixedThreadPool(3)
                try {
                    repeat(3) { worker ->
                        readers.execute {
                            try {
                                repeat(40) {
                                    store.snapshot().use { snapshot ->
                                        for (predicate in predicates(worker)) {
                                            assertEquals(
                                                scanKeys(store, snapshot, predicate),
                                                engine.keys(Query.where(predicate), snapshot),
                                                "worker $worker: $predicate",
                                            )
                                        }
                                    }
                                }
                            } catch (thrown: Throwable) {
                                failure.compareAndSet(null, thrown)
                            }
                        }
                    }

                    // Meanwhile: writes, flushes, compactions and two index builds over live data.
                    for (round in 1..8) {
                        store.load((round * 400 until round * 400 + 200).map(::document), round * 400)
                        if (round == 2) catalog.createIndex(store, IndexDefinition.inverted("$.team"))
                        if (round == 4) catalog.createIndex(store, IndexDefinition.column("$.score"))
                        if (round == 6) catalog.createIndex(store, IndexDefinition.inverted("$.tag"))
                        if (round % 3 == 0) store.compact()
                    }
                } finally {
                    readers.shutdown()
                    assertTrue(readers.awaitTermination(2, TimeUnit.MINUTES), "readers should finish")
                }

                assertNull(failure.get(), "a query disagreed with a scan under maintenance: ${failure.get()}")
                assertTrue(catalog.problems.isEmpty(), "no sidecar should have failed: ${catalog.problems}")
            }
        }
    }

    private fun predicates(worker: Int) = listOf(
        path("$.team") eq "team-${worker % 6}",
        and(path("$.team") eq "team-1", path("$.score") lt 20L),
        or(path("$.tag") eq "t${worker % 3}", path("$.score") ge 35L),
        not(path("$.tag").exists()),
    )
}
