package app.oreshkov.rabosh.core

import app.oreshkov.rabosh.testkit.property.Gen
import app.oreshkov.rabosh.testkit.property.PropertyConfig
import app.oreshkov.rabosh.testkit.property.forAll
import app.oreshkov.rabosh.testkit.property.list
import app.oreshkov.rabosh.variant.Variant
import app.oreshkov.rabosh.variant.toJsonString
import java.nio.file.Path
import java.util.TreeMap
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

/**
 * The store against a reference model. This is phase 4's acceptance criterion.
 *
 * The model is a `TreeMap`: obviously correct, and wrong in none of the ways an LSM-tree can be
 * wrong. Every operation is applied to both and the entire key space is compared afterwards, so a
 * divergence is caught at the operation that caused it rather than at the end of the run.
 *
 * Three things in the operation mix make this test worth its runtime:
 *
 * - **`Reopen`** is what makes it a recovery test as well as a correctness test. Roughly one
 *   operation in ten closes the store and opens it again, so the histories under test are "many
 *   writes, interleaved with crashes that happen to be clean ones".
 * - **`Flush` and `Compact`** move data out of memory and then merge it downwards, which is where
 *   an LSM loses documents: a version dropped one merge too early, a tombstone dropped above the
 *   level that still holds what it hides, a segment whose key range routes a lookup past it.
 * - **The full ordered `scan`** is compared as well as the point lookups. A point lookup can be
 *   right while iteration is wrong — a segment skipped by the index, a merge that loses a run — and
 *   comparing the whole key space in order is what catches that.
 */
class DocumentStoreModelTest {

    @TempDir
    lateinit var root: Path

    private val keySpace = 6

    /**
     * Fewer iterations than the harness default, because each one is a real store on a real
     * filesystem rather than a value in memory.
     *
     * The suite-wide dial still reaches it — `./gradlew test -Drabosh.property.iterations=500` — so
     * a longer run can be asked for without editing this file. Hard-coding the count would have made
     * that impossible, which is the trap in cheapening an expensive property.
     */
    private val iterations: Int =
        System.getProperty(PropertyConfig.ITERATIONS_PROPERTY)?.toIntOrNull() ?: 25

    @Test
    fun `matches a TreeMap under random operation histories`() {
        // Buffered, because the property under test is the ordering of writes and not the fsync;
        // durability has its own tests, and a forced write per operation would put this one out of
        // reach of a normal test run. `close` forces, so every reopen is still a real one.
        //
        // Small segments and blocks so that a forty-operation history produces a tree with several
        // levels rather than one file: the defaults are sized for real data and would make every
        // compaction in this test a no-op.
        val options = StoreOptions(
            durability = Durability.BUFFERED,
            segmentMaxBytes = 2 * 1024,
            blockSize = 256,
            l0CompactionTrigger = 2,
            baseLevelBytes = 4 * 1024,
            backgroundMaintenance = false,
        )

        forAll(Gen.list(CoreGens.storeOp(keySpace), sizes = 0..40), iterations = iterations) { operations ->
            val directory = scratch(root, "model")
            val model = TreeMap<Key, String>()
            var store = DocumentStore.open(directory, options)
            try {
                for (operation in operations) {
                    when (operation) {
                        is CoreGens.StoreOp.Put -> {
                            val document = Variant.fromJson(operation.json)
                            store.put(operation.key, document)
                            model[operation.key] = document.toJsonString()
                        }

                        is CoreGens.StoreOp.Delete -> {
                            store.delete(operation.key)
                            model.remove(operation.key)
                        }

                        CoreGens.StoreOp.Reopen -> {
                            store.close()
                            store = DocumentStore.open(directory, options)
                        }

                        CoreGens.StoreOp.Rotate -> store.rotate()
                        CoreGens.StoreOp.Sync -> store.sync()
                        CoreGens.StoreOp.Flush -> store.flush()
                        CoreGens.StoreOp.Compact -> store.compact()
                    }

                    for (index in 0 until keySpace) {
                        val key = Key.of("key:$index")
                        assertEquals(
                            model[key],
                            store.jsonAt(key),
                            "diverged at $operation over $key",
                        )
                    }

                    assertContentEquals(
                        model.entries.map { it.key to it.value },
                        scanAll(store),
                        "the ordered scan diverged at $operation",
                    )
                }
            } finally {
                store.close()
            }
        }
    }

    /**
     * The whole store in key order, as the cursor reports it.
     *
     * Deliberately unbounded rather than a range: the bounds have their own test, and what is wanted
     * here is that the merge over memtables and every level of segments produces the model's entire
     * contents, once each, in order.
     */
    private fun scanAll(store: DocumentStore): List<Pair<Key, String>> =
        store.scan().use { cursor ->
            val entries = ArrayList<Pair<Key, String>>()
            while (cursor.next()) entries += cursor.key to cursor.document.toJsonString()
            entries
        }
}
