package app.oreshkov.rabosh.core

import app.oreshkov.rabosh.testkit.property.Gen
import app.oreshkov.rabosh.testkit.property.forAll
import app.oreshkov.rabosh.testkit.property.int
import app.oreshkov.rabosh.testkit.property.list
import app.oreshkov.rabosh.testkit.property.pair
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemtableTest {

    private val bytes = byteArrayOf(1, 2, 3)

    @Test
    fun `the newest version of a key wins`() {
        val memtable = Memtable()
        memtable.put(Key.of("a"), sequence = 1, metadata = bytes, value = byteArrayOf(10))
        memtable.put(Key.of("a"), sequence = 2, metadata = bytes, value = byteArrayOf(20))

        val found = assertIs<MemtableValue.Present>(memtable.get(Key.of("a")))
        assertEquals(20, found.value.single())
        assertEquals(2, memtable.entryCount, "both versions are retained, not overwritten")
    }

    @Test
    fun `a deletion is an answer, not an absence`() {
        val memtable = Memtable()
        memtable.put(Key.of("a"), sequence = 1, metadata = bytes, value = byteArrayOf(10))
        memtable.delete(Key.of("a"), sequence = 2)

        // The distinction the whole LSM read path rests on: this memtable does not merely fail to
        // find the key, it knows the key is gone — so no older segment may be consulted.
        assertIs<MemtableValue.Deleted>(memtable.get(Key.of("a")))
        assertNull(memtable.get(Key.of("b")), "an untouched key is absent, which is a different thing")
    }

    @Test
    fun `a key can be resurrected after a deletion`() {
        val memtable = Memtable()
        memtable.delete(Key.of("a"), sequence = 1)
        memtable.put(Key.of("a"), sequence = 2, metadata = bytes, value = byteArrayOf(7))

        assertIs<MemtableValue.Present>(memtable.get(Key.of("a")))
    }

    @Test
    fun `entries are ordered by key, then newest version first`() {
        val memtable = Memtable()
        for ((key, sequence) in listOf("b" to 1L, "a" to 2L, "b" to 3L, "a" to 4L)) {
            memtable.put(Key.of(key), sequence, bytes, bytes)
        }

        // The order a sorted segment is written in: one pass, no sorting, newest version of each key
        // reached first so superseded ones can be dropped without looking ahead.
        assertEquals(
            listOf("Key(a)@4", "Key(a)@2", "Key(b)@3", "Key(b)@1"),
            memtable.entries().map { it.key.toString() }.toList(),
        )
    }

    @Test
    fun `size accounting grows with the data written`() {
        val memtable = Memtable()
        assertEquals(0, memtable.approximateBytes)
        assertTrue(memtable.isEmpty())

        memtable.put(Key.of("a"), 1, ByteArray(100), ByteArray(200))
        val afterOne = memtable.approximateBytes
        assertTrue(afterOne >= 301, "at least the bytes written: $afterOne")

        memtable.put(Key.of("a"), 2, ByteArray(100), ByteArray(200))
        assertTrue(
            memtable.approximateBytes >= afterOne + 301,
            "a superseded version is still resident and must still be counted",
        )
    }

    @Test
    fun `lookup agrees with a reference map over random histories`() {
        // The memtable's own reference model: a plain map of key to its highest-sequence write. If
        // the skip-list ordering or the ceiling lookup is wrong in any corner, the two diverge.
        forAll(Gen.list(Gen.pair(CoreGens.key, Gen.int(0..2)), 0..40)) { operations ->
            val memtable = Memtable()
            val expected = HashMap<Key, Boolean>()

            operations.forEachIndexed { index, (key, kind) ->
                val sequence = index + 1L
                if (kind == 0) {
                    memtable.delete(key, sequence)
                    expected[key] = false
                } else {
                    memtable.put(key, sequence, bytes, byteArrayOf(index.toByte()))
                    expected[key] = true
                }
            }

            for ((key, present) in expected) {
                when (val found = memtable.get(key)) {
                    null -> throw AssertionError("$key was written but is not found")
                    is MemtableValue.Present -> assertTrue(present, "$key should be deleted")
                    MemtableValue.Deleted -> assertTrue(!present, "$key should be present")
                }
                if (present) {
                    val expectedValue = operations.indexOfLast { it.first == key }.toByte()
                    val found = assertIs<MemtableValue.Present>(memtable.get(key))
                    assertEquals(expectedValue, found.value.single(), "$key holds a stale version")
                }
            }
        }
    }
}
