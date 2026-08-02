package app.oreshkov.rabosh.core

import app.oreshkov.rabosh.testkit.property.Gen
import app.oreshkov.rabosh.testkit.property.forAll
import app.oreshkov.rabosh.testkit.property.list
import app.oreshkov.rabosh.testkit.property.long
import app.oreshkov.rabosh.testkit.property.pair
import java.util.TreeMap
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * One block: prefix-compressed entries, restart points, and a binary search over them.
 *
 * The reference is a `TreeMap` under the same comparator. A block is a sorted map with a peculiar
 * physical layout, so the useful question is not "does it round-trip" — a broken prefix
 * reconstruction can still round-trip if the reader makes the same mistake as the writer — but
 * "does it answer every seek the way an obviously-correct sorted map does".
 */
class BlockTest {

    @Test
    fun `entries round-trip in order`() {
        forAll(keySeeds()) { seeds ->
            val entries = toEntries(seeds)
            val block = build(entries)
            assertContentEquals(
                entries.map { it.first.toHex() },
                walk(block).map { it.first.toHex() },
            )
            assertContentEquals(
                entries.map { it.second.toHex() },
                walk(block).map { it.second.toHex() },
            )
        }
    }

    /**
     * The acceptance property for the block: every seek lands where a `TreeMap` says it should.
     *
     * Probes are drawn from the block's own keys *and* from keys that are not in it, because the
     * two exercise different halves of the search — an exact hit can be found by a bisection that
     * is off by one, a near miss cannot.
     */
    @Test
    fun `seek finds the first entry at or after the probe, as a TreeMap does`() {
        forAll(keySeeds(), Gen.list(Gen.pair(CoreGens.key, Gen.long(1L..60L)), sizes = 1..12)) { seeds, probes ->
            val entries = toEntries(seeds)
            val block = build(entries)
            val model = TreeMap<HexKey, HexKey>()
            entries.forEach { model[HexKey(it.first)] = HexKey(it.second) }

            val targets = entries.map { it.first } +
                probes.map { SegmentFormat.encodeKey(it.first, it.second, OperationKind.PUT) }

            val iterator = block.iterator()
            for (target in targets) {
                iterator.seek(target)
                val expected = model.ceilingEntry(HexKey(target))
                if (expected == null) {
                    assertFalse(iterator.valid(), "seek past the end of the block should exhaust it")
                } else {
                    assertTrue(iterator.valid(), "seek to ${target.toHex()} found nothing")
                    assertEquals(expected.key.hex, currentKey(iterator).toHex())
                    assertEquals(expected.value.hex, currentValue(iterator).toHex())
                }
            }
        }
    }

    /**
     * The counts either side of a restart point.
     *
     * A restart is written on entry 0 and then every sixteenth, so the interesting sizes are the
     * ones where the last block of entries is empty, one short, or exactly full — the boundaries an
     * off-by-one in the restart bookkeeping falls off.
     */
    @Test
    fun `restart points land on the interval, at every boundary`() {
        val interval = SegmentFormat.RESTART_INTERVAL
        for (count in listOf(0, 1, interval - 1, interval, interval + 1, 2 * interval, 2 * interval + 1, 100)) {
            val entries = (0 until count).map { index ->
                SegmentFormat.encodeKey(Key.of("key:%05d".format(index)), 1, OperationKind.PUT) to
                    "value-$index".encodeToByteArray()
            }
            val block = build(entries)
            val expectedRestarts = if (count == 0) 0 else (count + interval - 1) / interval
            assertEquals(expectedRestarts, block.restartPoints, "for $count entries")
            assertEquals(count, walk(block).size)
            assertEquals(count == 0, block.isEmpty())
        }
    }

    /** Prefix compression has to actually happen, or the format pays for a feature it does not get. */
    @Test
    fun `shared prefixes are stored once per restart interval`() {
        val entries = (0 until 64).map { index ->
            SegmentFormat.encodeKey(Key.of("a-very-long-shared-prefix:%04d".format(index)), 1, OperationKind.PUT) to
                ByteArray(0)
        }
        val writer = BlockWriter()
        entries.forEach { writer.add(it.first, it.first.size, it.second, 0, 0) }
        val compressed = writer.finish().size

        val uncompressed = entries.sumOf { SegmentFormat.ENTRY_HEADER_BYTES + it.first.size } + 4 * 4 + 4
        assertTrue(
            compressed < uncompressed / 2,
            "prefix compression saved nothing: $compressed bytes against $uncompressed uncompressed",
        )
    }

    @Test
    fun `an empty block is readable and holds nothing`() {
        val block = build(emptyList())
        assertTrue(block.isEmpty())
        val iterator = block.iterator()
        iterator.seekToFirst()
        assertFalse(iterator.valid())
        iterator.seek(SegmentFormat.encodeKey(Key.of("anything"), 1, OperationKind.PUT))
        assertFalse(iterator.valid())
    }

    @Test
    fun `entries must be added in ascending order`() {
        val writer = BlockWriter()
        val second = SegmentFormat.encodeKey(Key.of("b"), 1, OperationKind.PUT)
        val first = SegmentFormat.encodeKey(Key.of("a"), 1, OperationKind.PUT)
        writer.add(second, second.size, ByteArray(0), 0, 0)
        assertFailsWith<IllegalArgumentException> { writer.add(first, first.size, ByteArray(0), 0, 0) }
    }

    /**
     * Damage inside a block is reported rather than read.
     *
     * The entry header is the dangerous part: a corrupt shared length would have the reader
     * reconstruct a key out of bytes that were never written, and a corrupt value length would hand
     * back a document that runs into its neighbour. Neither is allowed to become an answer.
     */
    @Test
    fun `a corrupt entry header is reported, never decoded`() {
        val entries = (0 until 40).map { index ->
            SegmentFormat.encodeKey(Key.of("key:%03d".format(index)), 1, OperationKind.PUT) to
                "value-$index".encodeToByteArray()
        }
        val writer = BlockWriter()
        entries.forEach { writer.add(it.first, it.first.size, it.second, 0, 0) }
        val clean = writer.finish()

        var reported = 0
        for (offset in clean.indices step 3) {
            val damaged = clean.copyOf()
            // A large flip, so the field becomes an implausible length rather than a nearby one.
            damaged[offset] = (damaged[offset].toInt() xor 0x40).toByte()
            val failure = runCatching {
                val block = BlockReader(segmentBytesOf(damaged), 0, damaged.size)
                walk(block)
            }.exceptionOrNull()
            if (failure != null) {
                assertTrue(failure is CorruptSegmentException, "a flip at $offset produced $failure")
                reported++
            }
        }
        assertTrue(reported > 0, "no damage was detected at all, which cannot be right")
    }

    // --- helpers ------------------------------------------------------------------------------

    /**
     * The generator is of the *seeds*, and the sorting happens in the property body.
     *
     * `Gen.map` would drop shrinking — a sorted, de-duplicated entry list cannot be mapped back to
     * the pairs it came from — and a block failure that arrives unminimised is a block failure
     * nobody can read. Deriving inside the property keeps the shrinker working on the pairs.
     */
    private fun keySeeds(): Gen<List<Pair<Key, Long>>> =
        Gen.list(Gen.pair(CoreGens.key, Gen.long(1L..60L)), sizes = 0..70)

    /** Distinct encoded keys in ascending order, with a value each. */
    private fun toEntries(seeds: List<Pair<Key, Long>>): List<Pair<ByteArray, ByteArray>> = seeds
        .map { SegmentFormat.encodeKey(it.first, it.second, OperationKind.PUT) }
        .distinctBy { it.toHex() }
        .sortedWith(::compareEncodedKeys)
        .mapIndexed { index, key -> key to "v$index:${key.size}".encodeToByteArray() }

    private fun build(entries: List<Pair<ByteArray, ByteArray>>): BlockReader {
        val writer = BlockWriter()
        entries.forEach { writer.add(it.first, it.first.size, it.second, 0, it.second.size) }
        val contents = writer.finish()
        return BlockReader(segmentBytesOf(contents), 0, contents.size)
    }

    private fun walk(block: BlockReader): List<Pair<ByteArray, ByteArray>> {
        val result = ArrayList<Pair<ByteArray, ByteArray>>()
        val iterator = block.iterator()
        iterator.seekToFirst()
        while (iterator.valid()) {
            result += currentKey(iterator) to currentValue(iterator)
            iterator.next()
        }
        return result
    }

    private fun currentKey(iterator: BlockIterator): ByteArray =
        iterator.key.copyOfRange(0, iterator.keyLength)

    private fun currentValue(iterator: BlockIterator): ByteArray =
        copyOut(iterator.segment, iterator.valueOffset, iterator.valueLength)

    /** A byte array with content equality, so it can key a `TreeMap` under the segment order. */
    private class HexKey(val bytes: ByteArray) : Comparable<HexKey> {
        val hex: String = bytes.toHex()
        override fun compareTo(other: HexKey): Int = compareEncodedKeys(bytes, other.bytes)
        override fun equals(other: Any?): Boolean = other is HexKey && hex == other.hex
        override fun hashCode(): Int = hex.hashCode()
        override fun toString(): String = hex
    }
}
