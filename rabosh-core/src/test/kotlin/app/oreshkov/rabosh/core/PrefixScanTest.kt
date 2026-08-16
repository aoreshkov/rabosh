package app.oreshkov.rabosh.core

import app.oreshkov.rabosh.testkit.property.Gen
import app.oreshkov.rabosh.testkit.property.forAll
import app.oreshkov.rabosh.testkit.property.string
import app.oreshkov.rabosh.variant.Variant
import app.oreshkov.rabosh.variant.toJsonString
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * `scanPrefix` and `deletePrefix` against a brute-force model of the same prefix.
 *
 * **The neighbour is the whole test.** A prefix range cannot be spelled with this API's inclusive
 * bounds — the prefix with its last byte raised is the *exclusive* upper bound, so handing it to
 * `to` admits exactly one key too many — and the key it admits is the one spelled exactly like that
 * raised prefix. Every fixture here therefore writes that key deliberately: `receipt/` is bounded by
 * `receipt0`, so a store holding `receipt0` is the one that tells the two spellings apart. A suite
 * whose corpus happened not to contain it would pass for either.
 */
class PrefixScanTest {

    @TempDir
    lateinit var root: Path

    private fun options() = StoreOptions(
        segmentMaxBytes = 4 * 1024,
        blockSize = 256,
        backgroundMaintenance = false,
    )

    /** `receipt/` with its last byte raised — the exclusive bound, and a legal key in its own right. */
    private val neighbour = Key.of("receipt0")

    @Test
    fun `a prefix scan excludes the key the raised-byte bound would admit`() {
        withStore { store ->
            store.put(Key.of("receipt/a"), doc(1))
            store.put(Key.of("receipt/b"), doc(2))
            store.put(neighbour, doc(3))
            store.flush()

            assertEquals(
                listOf(Key.of("receipt/a"), Key.of("receipt/b")),
                keysUnder(store, Key.of("receipt/")),
                "the neighbouring key is not under the prefix",
            )

            // The spelling this replaces, kept as an assertion rather than a comment: it is *not*
            // equivalent, and the difference is exactly this one key. If a future change makes the
            // range form correct, this fails and says so.
            val byRange = ArrayList<Key>()
            store.scan(from = Key.of("receipt/"), to = neighbour).use { cursor ->
                while (cursor.next()) byRange += cursor.key
            }
            assertEquals(
                listOf(Key.of("receipt/a"), Key.of("receipt/b"), neighbour),
                byRange,
                "the inclusive range admits the raised bound itself, which is the bug being removed",
            )
        }
    }

    @Test
    fun `a prefix delete leaves the neighbour alone`() {
        withStore { store ->
            store.put(Key.of("receipt/a"), doc(1))
            store.put(Key.of("receipt/b"), doc(2))
            store.put(neighbour, doc(3))
            store.flush()

            assertEquals(2, store.deletePrefix(Key.of("receipt/")), "only the two under the prefix")
            assertEquals(emptyList(), keysUnder(store, Key.of("receipt/")))
            assertEquals(
                doc(3).toJsonString(),
                store.get(neighbour)?.toJsonString(),
                "the neighbouring namespace is untouched",
            )
            assertEquals(0, store.deletePrefix(Key.of("receipt/")), "and it converges")
        }
    }

    @Test
    fun `a prefix delete spans batches`() {
        withStore { store ->
            // Well above the batch size forced below: a loop that restarted at the last key handled
            // rather than after it, or that ended on a full batch, passes with one batch and fails
            // with several.
            for (index in 0 until 500) store.put(Key.of("receipt/%04d".format(index)), doc(index))
            store.put(neighbour, doc(-1))
            store.flush()

            assertEquals(500, store.deletePrefix(Key.of("receipt/"), batchSize = 37))
            assertEquals(emptyList(), keysUnder(store, Key.of("receipt/")))
            assertTrue(store.get(neighbour) != null, "the neighbour survives a multi-batch delete")
        }
    }

    @Test
    fun `an empty prefix is every key`() {
        withStore { store ->
            for (index in 0 until 20) store.put(Key.of("k%02d".format(index)), doc(index))
            store.flush()

            val all = ArrayList<Key>()
            store.scan().use { cursor -> while (cursor.next()) all += cursor.key }
            assertEquals(all, keysUnder(store, Key.of(ByteArray(0))), "an empty prefix matches everything")
            assertEquals(20, store.deletePrefix(Key.of(ByteArray(0))))
            assertEquals(emptyList(), keysUnder(store, Key.of(ByteArray(0))))
        }
    }

    @Test
    fun `a prefix of every 0xFF byte has no bound to raise and still works`() {
        // The case the arithmetic cannot express at all: there is no byte to carry into, so a
        // raised-byte helper has to throw or return nothing. Naming the prefix has no such edge.
        withStore { store ->
            val ff = Key.of(byteArrayOf(0xFF.toByte()))
            store.put(ff, doc(1))
            store.put(Key.of(byteArrayOf(0xFF.toByte(), 0x01)), doc(2))
            store.put(Key.of(byteArrayOf(0xFE.toByte())), doc(3))
            store.flush()

            assertEquals(
                listOf(ff, Key.of(byteArrayOf(0xFF.toByte(), 0x01))),
                keysUnder(store, ff),
                "a key equal to the prefix is under it, and 0xFE is not",
            )
            assertEquals(2, store.deletePrefix(ff))
            assertTrue(store.get(Key.of(byteArrayOf(0xFE.toByte()))) != null)
        }
    }

    @Test
    fun `a prefix scan agrees with a filtered full scan`() {
        // The differential: whatever the prefix, `scanPrefix` must return exactly what a full scan
        // filtered by `startsWith` returns, in the same order. The corpus is arranged so that the
        // generated prefixes actually land inside it — a corpus of unrelated keys would make every
        // case an empty result agreeing with an empty result.
        withStore { store ->
            for (namespace in listOf("receipt/", "receipt0", "receipt", "recei", "session-end/", "s")) {
                for (index in 0 until 8) store.put(Key.of("$namespace$index"), doc(index))
                store.put(Key.of(namespace), doc(0))
            }
            store.flush()

            // The alphabet is the corpus's own, so a generated prefix lands inside it often enough
            // for the differential to be about agreement rather than about two empty lists.
            forAll(Gen.string(lengths = 0..9, alphabet = "receipt/0123session-nd")) { text ->
                val prefix = Key.of(text)
                val expected = ArrayList<Key>()
                store.scan().use { cursor ->
                    while (cursor.next()) if (cursor.key.startsWith(prefix)) expected += cursor.key
                }
                assertEquals(expected, keysUnder(store, prefix), "prefix '$text'")
            }

            // Fixed cases beside the generated ones, because a random string almost never lands on a
            // namespace boundary and the boundary is the whole subject.
            for (text in listOf("receipt/", "receipt0", "receipt", "recei", "s", "session-end/", "")) {
                val prefix = Key.of(text)
                val expected = ArrayList<Key>()
                store.scan().use { cursor ->
                    while (cursor.next()) if (cursor.key.startsWith(prefix)) expected += cursor.key
                }
                assertTrue(expected.isNotEmpty(), "fixture must cover prefix '$text'")
                assertEquals(expected, keysUnder(store, prefix), "prefix '$text'")
            }
        }
    }

    @Test
    fun `startsWith agrees with the ordering it relies on`() {
        // The property the cursor's `break` depends on: a key carrying the prefix is never below it,
        // so stopping at the first key that does not carry it cannot skip one that does.
        forAll(CoreGens.key, CoreGens.key) { key, prefix ->
            if (key.startsWith(prefix)) assertTrue(key >= prefix, "$key carries $prefix but sorts below it")
        }
    }

    private fun keysUnder(store: DocumentStore, prefix: Key): List<Key> {
        val keys = ArrayList<Key>()
        store.scanPrefix(prefix).use { cursor -> while (cursor.next()) keys += cursor.key }
        return keys
    }

    private fun doc(index: Int): Variant = Variant.fromJson("""{"n":$index}""")

    private fun withStore(body: (DocumentStore) -> Unit) {
        DocumentStore.open(scratch(root), options()).use(body)
    }
}
