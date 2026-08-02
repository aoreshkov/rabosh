package app.oreshkov.rabosh.core

import app.oreshkov.rabosh.testkit.property.Gen
import app.oreshkov.rabosh.testkit.property.forAll
import app.oreshkov.rabosh.testkit.property.list
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The bloom filter, whose only real obligation is asymmetric.
 *
 * A `false` skips a segment without opening it, so a false negative loses a document. A `true`
 * costs an index lookup that finds nothing. The first is a correctness bug and the second is a
 * performance one, and every test here is written with that asymmetry in mind: no-false-negatives
 * is asserted exhaustively over generated data, while the false-positive rate is only checked
 * against a generous bound.
 */
class BloomFilterTest {

    /** The property the read path depends on. Asserted, not derived from the arithmetic. */
    @Test
    fun `a key that was added is never rejected`() {
        forAll(Gen.list(CoreGens.key, sizes = 0..200)) { keys ->
            val filter = filterOf(keys)
            for (key in keys) {
                assertTrue(
                    BloomFilter.mayContain(filter.first, filter.second, key),
                    "$key was added and then rejected",
                )
            }
        }
    }

    /**
     * Ten bits per key buys roughly a 1% false-positive rate. The bound here is five times that,
     * because the test must fail on a broken hash, not on an unlucky seed.
     */
    @Test
    fun `the false-positive rate is near what the bit budget promises`() {
        val present = (0 until 5_000).map { Key.of("present:%06d".format(it)) }
        val absent = (0 until 5_000).map { Key.of("absent:%06d".format(it)) }
        val (bytes, handle) = filterOf(present)

        val positives = absent.count { BloomFilter.mayContain(bytes, handle, it) }
        val rate = positives.toDouble() / absent.size
        assertTrue(rate < 0.05, "false-positive rate was $rate over ${absent.size} absent keys")
        // And it must not be zero either, which would mean the probe is not reading the bits at all.
        assertTrue(present.all { BloomFilter.mayContain(bytes, handle, it) })
    }

    /**
     * An empty filter rejects everything.
     *
     * A segment with no documents is not something the writer produces, but a filter that answered
     * "maybe" for every key in that case would be a filter that answers "maybe" whenever its key
     * count field is misread — which is the failure mode worth pinning down.
     */
    @Test
    fun `an empty filter rejects every key`() {
        val (bytes, handle) = filterOf(emptyList())
        assertFalse(BloomFilter.mayContain(bytes, handle, Key.of("anything")))
        assertFalse(BloomFilter.mayContain(bytes, handle, Key.of(ByteArray(0))))
    }

    /**
     * Repeated versions of one key cost one entry.
     *
     * A segment holding a hundred versions of six keys is the ordinary result of a workload that
     * overwrites, and sizing its filter for the versions would spend most of the bits on nothing.
     */
    @Test
    fun `consecutive versions of one key are counted once`() {
        val builder = BloomFilter.Builder()
        repeat(50) { builder.add(Key.of("key:1")) }
        repeat(50) { builder.add(Key.of("key:2")) }
        assertEquals(2, builder.keyCount)
    }

    @Test
    fun `every added key is found across a range of bit budgets`() {
        for (bitsPerKey in listOf(1, 2, 4, 10, 16, 32, 64)) {
            val keys = (0 until 500).map { Key.of("k:$it") }
            val builder = BloomFilter.Builder(bitsPerKey)
            keys.forEach(builder::add)
            val encoded = builder.finish()
            val bytes = segmentBytesOf(encoded)
            val handle = BlockHandle(0, encoded.size)
            assertTrue(
                keys.all { BloomFilter.mayContain(bytes, handle, it) },
                "a key went missing at $bitsPerKey bits per key",
            )
            assertTrue(BloomFilter.hashCountFor(bitsPerKey) in 1..30)
        }
    }

    /** Keys that differ only in their last byte, or only in length, must not collide wholesale. */
    @Test
    fun `the hash is sensitive to every byte position`() {
        val base = ByteArray(24) { 0x41 }
        val hashes = HashSet<Long>()
        for (index in base.indices) {
            val variant = base.copyOf()
            variant[index] = 0x42
            hashes += Hash.hash64(variant)
        }
        for (length in 1..base.size) {
            hashes += Hash.hash64(base, 0, length)
        }
        assertEquals(base.size * 2, hashes.size, "distinct inputs collided in the hash")
    }

    @Test
    fun `the hash is deterministic and offset-independent`() {
        val payload = "the quick brown fox".encodeToByteArray()
        val padded = ByteArray(7) + payload + ByteArray(5)
        assertEquals(Hash.hash64(payload), Hash.hash64(padded, 7, payload.size))
        assertEquals(Hash.hash64(payload), Hash.hash64(payload.copyOf()))
    }

    private fun filterOf(keys: List<Key>): Pair<SegmentBytes, BlockHandle> {
        val builder = BloomFilter.Builder()
        // Sorted, because that is the order a segment writer adds them in and the de-duplication
        // the builder does only works on adjacent keys.
        keys.sorted().forEach(builder::add)
        val encoded = builder.finish()
        return segmentBytesOf(encoded) to BlockHandle(0, encoded.size)
    }
}
