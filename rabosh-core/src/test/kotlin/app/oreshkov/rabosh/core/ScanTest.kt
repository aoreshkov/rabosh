package app.oreshkov.rabosh.core

import app.oreshkov.rabosh.variant.Variant
import app.oreshkov.rabosh.variant.toJsonString
import java.nio.file.Path
import java.util.TreeMap
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * The range cursor, against a `TreeMap` under the same bounds.
 *
 * A scan is where the tree's shape shows: it merges the active memtable, every sealed one, and every
 * segment at every level, then collapses the versions of each key and drops the deleted ones. A
 * point lookup can be right while all of that is wrong, because a point lookup asks one source at a
 * time and stops.
 */
class ScanTest {

    @TempDir
    lateinit var root: Path

    private val options = StoreOptions(
        durability = Durability.BUFFERED,
        segmentMaxBytes = 2 * 1024,
        blockSize = 256,
        l0CompactionTrigger = 2,
        baseLevelBytes = 4 * 1024,
        backgroundMaintenance = false,
    )

    /**
     * Every bound, against the model, over a store whose data is spread across all three kinds of
     * source at once — segments in several levels, a sealed memtable, and the active one.
     */
    @Test
    fun `bounded scans match a TreeMap`() {
        withMixedStore { store, model ->
            val keys = model.keys.toList()
            val probes = listOf<Pair<Key?, Key?>>(
                null to null,
                keys.first() to keys.last(),
                keys.first() to keys.first(),
                keys[keys.size / 3] to keys[2 * keys.size / 3],
                Key.of("key:000000") to Key.of("key:000050"),
                Key.of("aaa") to Key.of("bbb"),
                Key.of("zzz") to null,
                null to Key.of("key:000010"),
                Key.of("key:000010") to null,
                // An inverted range is empty, not an error and not the whole store.
                keys.last() to keys.first(),
            )
            for ((from, to) in probes) {
                assertContentEquals(
                    model.entries.filter { (from == null || it.key >= from) && (to == null || it.key <= to) }
                        .map { it.key to it.value },
                    scan(store, from, to),
                    "scan from $from to $to",
                )
            }
        }
    }

    @Test
    fun `a scan emits each key once, however many versions it has`() {
        withStore { store ->
            repeat(8) { round ->
                for (index in 0 until 30) store.put(keyFor(index), Variant.fromJson("""{"r":$round}"""))
                if (round % 2 == 0) store.flush()
            }
            val entries = scan(store, null, null)
            assertEquals(30, entries.size)
            assertEquals(entries.map { it.first }.distinct().size, entries.size)
            assertTrue(entries.all { it.second == """{"r":7}""" }, "every key must show its newest version")
        }
    }

    @Test
    fun `a scan skips deleted keys wherever the tombstone lives`() {
        withStore { store ->
            for (index in 0 until 60) store.put(keyFor(index), documentFor(index))
            store.flush()
            // A deletion in a segment, one in a sealed memtable, one in the active memtable.
            store.delete(keyFor(0))
            store.flush()
            store.delete(keyFor(1))
            store.rotate()
            store.delete(keyFor(2))

            val keys = scan(store, null, null).map { it.first }
            assertEquals(57, keys.size)
            assertTrue(keys.none { it == keyFor(0) || it == keyFor(1) || it == keyFor(2) })
        }
    }

    @Test
    fun `a cursor is closed once and refuses to be used afterwards`() {
        withStore { store ->
            store.put(keyFor(1), documentFor(1))
            val cursor = store.scan()
            assertTrue(cursor.next())
            cursor.close()
            cursor.close()
            assertFailsWith<IllegalStateException> { cursor.next() }
        }
    }

    @Test
    fun `a cursor on no entry has neither a key nor a document`() {
        withStore { store ->
            store.scan().use { cursor ->
                assertFailsWith<IllegalStateException> { cursor.key }
                assertFailsWith<IllegalStateException> { cursor.document }
                assertTrue(!cursor.next())
                assertFailsWith<IllegalStateException> { cursor.key }
            }
        }
    }

    /** An empty store scans to nothing rather than failing to scan. */
    @Test
    fun `an empty store scans to nothing`() {
        withStore { store ->
            assertContentEquals(emptyList(), scan(store, null, null))
        }
    }

    private fun scan(store: DocumentStore, from: Key?, to: Key?): List<Pair<Key, String>> =
        store.scan(from, to).use { cursor ->
            val entries = ArrayList<Pair<Key, String>>()
            while (cursor.next()) entries += cursor.key to cursor.document.toJsonString()
            entries
        }

    /**
     * A store whose data sits in segments across several levels, in a sealed memtable, and in the
     * active one — with overwrites and deletions spread over all three.
     */
    private fun withMixedStore(body: (DocumentStore, TreeMap<Key, String>) -> Unit) {
        withStore { store ->
            val model = TreeMap<Key, String>()
            fun put(index: Int, json: String) {
                store.put(keyFor(index), Variant.fromJson(json))
                model[keyFor(index)] = Variant.fromJson(json).toJsonString()
            }

            for (index in 0 until 240) put(index, """{"v":$index}""")
            store.flush()
            store.compact()
            for (index in 0 until 240 step 4) put(index, """{"v":${index + 10000}}""")
            store.flush()
            for (index in 0 until 240 step 7) {
                store.delete(keyFor(index))
                model.remove(keyFor(index))
            }
            store.rotate()
            for (index in 240 until 300) put(index, """{"v":$index}""")

            assertTrue(store.stats.segmentCount > 1, "the fixture should span several segments")
            assertTrue(store.stats.sealedMemtables > 0, "the fixture should have a sealed memtable")
            body(store, model)
        }
    }

    private fun withStore(body: (DocumentStore) -> Unit) {
        DocumentStore.open(scratch(root, "scan"), options).use(body)
    }
}
