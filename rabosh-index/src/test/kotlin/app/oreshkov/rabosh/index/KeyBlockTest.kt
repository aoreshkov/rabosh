package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.testkit.property.Gen
import app.oreshkov.rabosh.testkit.property.RandomSource
import app.oreshkov.rabosh.testkit.property.forAll
import java.lang.foreign.MemorySegment
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The key block, checked without a store.
 *
 * The restart interval is the thing worth testing hard here, and it is tested **at** the value rather
 * than near it: 15, 16 and 17 keys are three different shapes, and a suite that only checked "well
 * below" and "well above" would not be testing the boundary at all. That rule is `CLAUDE.md`'s and it
 * came out of phase 6's container thresholds.
 *
 * Phase 18 gave the block a second layout and everything here now runs over **both**. Two things make
 * that more than duplicated coverage:
 *
 * - The two readers are compared *against each other*, on hits and on **misses**. Nothing outside this
 *   file looks at an insertion point today — `IndexCatalog` only asks whether a key is present — so a
 *   wrong one would be invisible until something needed a range, which is exactly the shape of bug a
 *   differential catches and a round trip does not.
 * - The saving is asserted as an **inequality over every generated block**, not as a number in a
 *   comment. Phase 17's front-coded term region had a crossover and found it by an assertion failing;
 *   this change should not have one, and "should not" is a claim a test makes or nobody does.
 */
class KeyBlockTest {

    private fun encode(keys: List<ByteArray>): ByteArray {
        val writer = KeyBlockWriter()
        keys.forEach(writer::add)
        return writer.build()
    }

    private fun encodeLegacy(keys: List<ByteArray>): ByteArray {
        val writer = LegacyKeyBlockWriter()
        keys.forEach(writer::add)
        return writer.build()
    }

    private fun read(encoded: ByteArray, count: Int, legacy: Boolean): KeyBlockReader {
        val bytes = IndexBytes(
            MemorySegment.ofArray(encoded),
            0,
            encoded.size,
            "keys.idx",
            ::CorruptIndexException,
        )
        return if (legacy) FixedWidthKeyBlockReader(bytes, count) else VarintKeyBlockReader(bytes, count)
    }

    /** Both layouts of the same keys, so every assertion below is made twice by construction. */
    private fun readers(keys: List<ByteArray>): List<KeyBlockReader> = listOf(
        read(encode(keys), keys.size, legacy = false),
        read(encodeLegacy(keys), keys.size, legacy = true),
    )

    private fun assertResolves(keys: List<ByteArray>) {
        for (reader in readers(keys)) {
            assertEquals(keys.size, reader.count)
            for (ordinal in keys.indices) {
                assertContentEquals(keys[ordinal], reader.keyAt(ordinal), "key at ordinal $ordinal")
            }
            for (ordinal in keys.indices) {
                assertEquals(ordinal, reader.ordinalOf(keys[ordinal]), "ordinal of key $ordinal")
            }
        }
    }

    private fun ascending(count: Int, prefix: String = "key:"): List<ByteArray> =
        (0 until count).map { "$prefix%08d".format(it).encodeToByteArray() }

    /**
     * Ascending keys whose lengths go up **and down**, over a three-letter alphabet.
     *
     * `ascending` pads to a fixed width, so every key in it is exactly as long as its predecessor and
     * the one case a shared decode buffer can get wrong never occurs. Sorting short words over a small
     * alphabet produces `"aab"` immediately before `"ab"` in quantity, which is that case.
     */
    private fun varying(count: Int): List<ByteArray> {
        val alphabet = "abc"
        val words = sortedSetOf<String>()
        var index = 0
        while (words.size < count) {
            var remaining = index++
            words += buildString {
                do {
                    append(alphabet[remaining % alphabet.length])
                    remaining /= alphabet.length
                } while (remaining > 0)
            }
        }
        return words.map { it.encodeToByteArray() }
    }

    @Test
    fun `resolves every ordinal at and around a restart boundary`() {
        // 0 and 1 are the degenerate cases; 15, 16, 17 straddle the interval; 31..33 straddle the
        // second one, because an off-by-one in the restart index only shows from the second group on.
        for (count in listOf(0, 1, 2, 15, 16, 17, 31, 32, 33, 64, 100)) {
            assertResolves(ascending(count))
        }
    }

    @Test
    fun `holds keys that share no prefix at all`() {
        // Prefix compression's worst case, and the one where the sidecar is genuinely a second copy
        // of the key space. It has to be correct there, not only cheap elsewhere.
        val keys = (0 until 40).map { byteArrayOf(it.toByte()) + "-unshared-$it".encodeToByteArray() }
        assertResolves(keys.sortedWith { a, b -> java.util.Arrays.compareUnsigned(a, b) })
    }

    @Test
    fun `orders keys by unsigned bytes`() {
        // 0x01 must sort before 0x80. A signed comparison puts them the other way round, and every
        // search over a block holding a high byte would then be quietly wrong.
        val keys = listOf(byteArrayOf(0x01), byteArrayOf(0x7F), byteArrayOf(0x80.toByte()), byteArrayOf(0xFF.toByte()))
        assertResolves(keys)
    }

    @Test
    fun `reports an absent key as an insertion point`() {
        val keys = ascending(50)
        for (reader in readers(keys)) {
            // Below everything.
            assertEquals(-1, reader.ordinalOf("aaa".encodeToByteArray()))
            // Above everything.
            assertEquals(-(keys.size + 1), reader.ordinalOf("zzz".encodeToByteArray()))
            // Between ordinal 9 and 10.
            val between = "key:000000095".encodeToByteArray()
            assertEquals(-(10 + 1), reader.ordinalOf(between))
        }
    }

    /**
     * A key shorter than the one before it must not inherit its tail.
     *
     * Phase 19 made the walk reconstruct each key **over** the last one in a buffer it keeps, which is
     * what removed sixteen allocations from resolving one ordinal — and it introduces exactly one bug:
     * a reader tracking the buffer it decoded into rather than the length it decoded would answer
     * `"abaa"` for `"ab"` written after `"aaaa"`. Nothing in this file arranged that shape before, and
     * neither generator produced it: `ascending` pads to a fixed width and the unshared-prefix case
     * only ever grows. So it is arranged deliberately, in one restart group, the way a pruning fixture
     * is.
     */
    @Test
    fun `a key shorter than the one before it leaves no tail behind`() {
        val keys = listOf("aaaa", "aaab", "ab", "b", "ba", "bbbbbbbbbbbb", "bc", "c")
            .map { it.encodeToByteArray() }
        assertResolves(keys)
        for (reader in readers(keys)) {
            assertEquals("ab", reader.keyAt(2).decodeToString())
            assertEquals("b", reader.keyAt(3).decodeToString())
            assertEquals("bc", reader.keyAt(6).decodeToString())
        }
        // The same shape crossing a restart boundary, so the reset is covered as well as the walk.
        assertResolves(varying(80))
    }

    /**
     * `ordinalOf` against a linear reference, on hits and on misses.
     *
     * The differential above compares the two *layouts*, which agree by construction if both walks are
     * wrong the same way — and phase 19 rewrote the walk they now share. This compares against a scan
     * of the list the block was built from, which is a second definition of "where would this key go"
     * rather than a second copy of the first. Probes land at the first, middle and last positions of a
     * group by construction: [probesAround] takes every key with its last byte removed.
     */
    @Test
    fun `ordinalOf agrees with a linear search over every probe`() {
        for (gen in listOf(ascendingKeys(), varyingKeys())) {
            forAll(gen) { keys ->
                for (reader in readers(keys)) {
                    for (probe in probesAround(keys)) {
                        assertEquals(
                            linearSearch(keys, probe),
                            reader.ordinalOf(probe),
                            "search for ${probe.decodeToString()} among ${keys.size} key(s)",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `rejects a key that does not ascend`() {
        val writer = KeyBlockWriter()
        writer.add("b".encodeToByteArray())
        assertFailsWithMessage("must ascend") { writer.add("a".encodeToByteArray()) }
    }

    @Test
    fun `resolves every ordinal of a generated block`() {
        forAll(ascendingKeys()) { keys -> assertResolves(keys) }
    }

    /**
     * The two layouts answer identically, including where they answer "not here".
     *
     * This is the assertion the second reader exists to be checked by. `keyAt` disagreeing would show
     * up as a wrong document; `ordinalOf` disagreeing on a *miss* would show up as nothing at all
     * today, which is why the misses are enumerated rather than sampled — one probe between every
     * adjacent pair, plus the two outside the range.
     */
    @Test
    fun `both layouts agree on every hit and every miss`() {
        forAll(ascendingKeys()) { keys ->
            val current = read(encode(keys), keys.size, legacy = false)
            val legacy = read(encodeLegacy(keys), keys.size, legacy = true)
            for (ordinal in keys.indices) {
                assertContentEquals(legacy.keyAt(ordinal), current.keyAt(ordinal), "key at $ordinal")
            }
            for (probe in probesAround(keys)) {
                assertEquals(
                    legacy.ordinalOf(probe),
                    current.ordinalOf(probe),
                    "search for ${probe.decodeToString()}",
                )
            }
        }
    }

    /**
     * A version-2 block is never larger than the version-1 block of the same keys.
     *
     * The claim phase 18 rests on, and the one phase 17 could not make about its own term region: a
     * varint pair is two bytes below 128 and four below 16 KiB against a `u32` pair's eight, so there
     * is no crossover and no unfavourable corpus. Asserted over generated blocks *and* over the two
     * shapes that would break it if anything did — keys sharing no prefix, and keys long enough to
     * need a two-byte length.
     */
    @Test
    fun `the varint block is never larger than the fixed-width one`() {
        fun assertNotLarger(keys: List<ByteArray>, note: String) {
            val current = encode(keys).size
            val legacy = encodeLegacy(keys).size
            assertTrue(current <= legacy, "$note: version 2 is $current bytes against version 1's $legacy")
        }

        forAll(ascendingKeys()) { keys -> assertNotLarger(keys, "${keys.size} generated key(s)") }

        val unshared = (0 until 40).map { "%03d-no-shared-prefix-at-all".format(it).encodeToByteArray() }
        assertNotLarger(unshared, "keys sharing nothing")

        // Long enough that both lengths need two varint bytes, which is the only regime where the
        // saving is four bytes rather than six. Still a saving, which is the point.
        val long = (0 until 40).map { ("k%03d".format(it) + "x".repeat(400)).encodeToByteArray() }
        assertNotLarger(long, "400-byte keys")
    }

    /**
     * And the saving is the six bytes per key the phase was costed at.
     *
     * Stated as an equality over a corpus whose lengths are all below 128, because "not larger" is
     * satisfied by a change that saves nothing. `CLAUDE.md`'s rule about work assertions, applied to a
     * space one: an inequality needs the number beside it or it is true of doing nothing.
     */
    @Test
    fun `short keys cost six bytes less per key`() {
        val keys = ascending(64)
        assertEquals(6 * keys.size, encodeLegacy(keys).size - encode(keys).size)
    }

    @Test
    fun `reports a truncated block rather than reading past it`() {
        for (legacy in listOf(false, true)) {
            val keys = ascending(40)
            val encoded = if (legacy) encodeLegacy(keys) else encode(keys)
            // Every offset, not a sample: the interesting failures are the ones that leave a plausible
            // restart count pointing at bytes that are not restart offsets.
            for (limit in 0 until encoded.size) {
                val truncated = encoded.copyOf(limit)
                val bytes = IndexBytes(
                    MemorySegment.ofArray(truncated),
                    0,
                    truncated.size,
                    "truncated.idx",
                    ::CorruptIndexException,
                )
                val failure = runCatching {
                    val reader = if (legacy) {
                        FixedWidthKeyBlockReader(bytes, 40)
                    } else {
                        VarintKeyBlockReader(bytes, 40)
                    }
                    reader.verifyRestarts()
                    for (ordinal in 0 until 40) reader.keyAt(ordinal)
                }.exceptionOrNull()
                assertTrue(
                    failure is CorruptIndexException,
                    "version ${if (legacy) 1 else 2} truncated to $limit byte(s) gave " +
                        "${failure?.let { it::class.simpleName }}: ${failure?.message}",
                )
            }
        }
    }
}

/**
 * Where [key] sits in [keys], by a scan: the ordinal, or `-(insertionPoint + 1)`.
 *
 * A second definition of the answer rather than a second copy of the block's own arithmetic, which is
 * the point — both readers now share one walk, so a differential between them cannot catch a walk that
 * is wrong the same way twice.
 */
private fun linearSearch(keys: List<ByteArray>, key: ByteArray): Int {
    for (ordinal in keys.indices) {
        val comparison = java.util.Arrays.compareUnsigned(keys[ordinal], key)
        if (comparison == 0) return ordinal
        if (comparison > 0) return -(ordinal + 1)
    }
    return -(keys.size + 1)
}

/** Every key, plus a probe just below each one and one above them all. */
private fun probesAround(keys: List<ByteArray>): List<ByteArray> = buildList {
    addAll(keys)
    add(ByteArray(0))
    for (key in keys) if (key.isNotEmpty()) add(key.copyOf(key.size - 1))
    add("￿".encodeToByteArray())
}

/** Ascending distinct keys, generated directly so that shrinking survives. */
private fun ascendingKeys(): Gen<List<ByteArray>> = object : Gen<List<ByteArray>> {
    override fun generate(source: RandomSource): List<ByteArray> {
        val count = source.nextInt(0..200)
        val prefix = listOf("", "key:", "a-very-long-shared-prefix-indeed:")[source.nextInt(0..2)]
        return (0 until count).map { "$prefix%09d".format(it).encodeToByteArray() }
    }

    override fun shrink(value: List<ByteArray>): Sequence<List<ByteArray>> = sequence {
        if (value.isEmpty()) return@sequence
        yield(emptyList())
        if (value.size > 1) yield(value.take(value.size / 2))
        if (value.size > 1) yield(value.dropLast(1))
    }

    override val edgeCases: List<List<ByteArray>> = listOf(
        emptyList(),
        listOf(ByteArray(0)),
        (0 until IndexFormat.KEY_RESTART_INTERVAL - 1).map { "%04d".format(it).encodeToByteArray() },
        (0 until IndexFormat.KEY_RESTART_INTERVAL).map { "%04d".format(it).encodeToByteArray() },
        (0 until IndexFormat.KEY_RESTART_INTERVAL + 1).map { "%04d".format(it).encodeToByteArray() },
    )

    override fun render(value: List<ByteArray>): String = "${value.size} key(s)"
}

/**
 * Ascending distinct keys of varying length, from a three-letter alphabet.
 *
 * The companion to [ascendingKeys], which pads to a fixed width and therefore never produces a key
 * shorter than its predecessor — the one shape a shared decode buffer can get wrong. Sorted short
 * words over a small alphabet produce it densely: `"aab"` immediately before `"ab"`, at every depth.
 */
private fun varyingKeys(): Gen<List<ByteArray>> = object : Gen<List<ByteArray>> {
    private val alphabet = "abc"

    override fun generate(source: RandomSource): List<ByteArray> {
        val words = sortedSetOf<String>()
        repeat(source.nextInt(0..150)) {
            words += buildString {
                repeat(source.nextInt(0..6)) { append(alphabet[source.nextInt(0..alphabet.lastIndex)]) }
            }
        }
        return words.map { it.encodeToByteArray() }
    }

    override fun shrink(value: List<ByteArray>): Sequence<List<ByteArray>> = sequence {
        if (value.isEmpty()) return@sequence
        yield(emptyList())
        if (value.size > 1) yield(value.take(value.size / 2))
        if (value.size > 1) yield(value.dropLast(1))
    }

    override val edgeCases: List<List<ByteArray>> = listOf(
        listOf("aaaa", "aaab", "ab").map { it.encodeToByteArray() },
        listOf("", "a", "aa", "b").map { it.encodeToByteArray() },
    )

    override fun render(value: List<ByteArray>): String =
        "${value.size} key(s): ${value.take(6).joinToString { it.decodeToString() }}"
}

internal inline fun assertFailsWithMessage(fragment: String, body: () -> Unit) {
    val failure = runCatching(body).exceptionOrNull()
    assertTrue(failure != null, "expected a failure mentioning '$fragment'")
    assertTrue(
        failure.message?.contains(fragment) == true,
        "expected a failure mentioning '$fragment', got: ${failure.message}",
    )
}
