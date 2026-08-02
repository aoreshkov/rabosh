package app.oreshkov.rabosh.index

import java.lang.foreign.MemorySegment
import java.util.BitSet
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The reference implementation every property here is checked against. */
internal fun bitSetOf(ordinals: Iterable<Int>): BitSet = BitSet().also { model ->
    for (ordinal in ordinals) model.set(ordinal)
}

/** The ordinals a model holds, ascending. */
internal fun BitSet.ordinals(): List<Int> = buildList {
    var bit = nextSetBit(0)
    while (bit >= 0) {
        add(bit)
        bit = if (bit == Int.MAX_VALUE) -1 else nextSetBit(bit + 1)
    }
}

/** A bitmap holding exactly [ordinals]. */
internal fun bitmapOf(ordinals: Iterable<Int>): Bitmap = Bitmap().also { bitmap ->
    for (ordinal in ordinals) bitmap.add(ordinal)
}

/**
 * Asserts that two bitmaps hold the same ordinals, in both directions and with agreeing hash codes.
 *
 * Equality here crosses implementations — a [Bitmap] against the [BitmapView] of its own encoding — and
 * that is exactly where an asymmetric `equals` hides. Asserting it both ways round is the only way to
 * notice, and doing it in one helper keeps every call site honest.
 */
internal fun assertSameBitmap(expected: ReadableBitmap, actual: ReadableBitmap, message: String = "") {
    val where = if (message.isEmpty()) "" else " ($message)"
    assertTrue(expected == actual, "$expected is not equal to $actual$where")
    assertTrue(actual == expected, "equality is not symmetric between $actual and $expected$where")
    assertEquals(expected.hashCode(), actual.hashCode(), "hashCode disagrees with equals$where")
    assertContentEquals(expected.toIntArray(), actual.toIntArray(), "ordinals$where")
}

/**
 * Asserts that [actual] holds exactly the ordinals [model] does, and that its encoding is canonical.
 *
 * Every check the acceptance criteria name, in one place, because the model test wants all of them after
 * *every* step and the operation tests want all of them for every pairing. The last two are the ones
 * worth naming:
 *
 * - the encoding is reopened as a [BitmapView] and asked the same questions, so a mapped read and a
 *   heap read are compared against the model rather than against each other;
 * - the view is re-encoded and the bytes must be identical, which is the canonical-form property. Two
 *   bitmaps holding the same ordinals encode the same way, so a byte comparison is a content comparison
 *   and the format cannot drift into having two spellings for one answer.
 */
internal fun assertMatches(model: BitSet, actual: Bitmap, note: String = "") {
    val expected = model.ordinals()
    val where = if (note.isEmpty()) "" else " ($note)"

    assertEquals(expected.size, actual.cardinality, "cardinality$where")
    assertEquals(expected.isEmpty(), actual.isEmpty, "isEmpty$where")
    assertContentEquals(expected.toIntArray(), actual.toIntArray(), "ordinals$where")

    if (expected.isEmpty()) {
        assertFailsWithEmpty(actual)
    } else {
        assertEquals(expected.first(), actual.first(), "first$where")
        assertEquals(expected.last(), actual.last(), "last$where")
    }

    for (ordinal in expected) {
        assertTrue(actual.contains(ordinal), "$ordinal is in the model but not the bitmap$where")
    }
    // A sample of absent ordinals rather than all of them: the ones next to a present ordinal and next
    // to a block boundary are where an off-by-one lives, and a full sweep of 300 000 would dominate
    // the run time of every property in the suite.
    for (ordinal in absentProbes(expected)) {
        assertFalse(actual.contains(ordinal), "$ordinal is absent from the model but in the bitmap$where")
    }

    for (index in expected.indices) {
        assertEquals(expected[index], actual.select(index), "select($index)$where")
        assertEquals(index + 1, actual.rank(expected[index]), "rank(${expected[index]})$where")
    }

    val encoded = actual.encode()
    assertEquals(actual.encodedByteSize(), encoded.size, "encodedByteSize$where")
    val view = BitmapView.open(encoded, "test.idx")
    view.verify()
    assertEquals(expected.size, view.cardinality, "view cardinality$where")
    assertSameBitmap(actual, view, "a bitmap against the view of its own encoding$note")
    assertContentEquals(encoded, view.encode(), "re-encoding a view changed the bytes$where")
}

private fun assertFailsWithEmpty(bitmap: Bitmap) {
    val first = runCatching { bitmap.first() }.exceptionOrNull()
    assertTrue(first is NoSuchElementException, "an empty bitmap reported a first ordinal")
    val last = runCatching { bitmap.last() }.exceptionOrNull()
    assertTrue(last is NoSuchElementException, "an empty bitmap reported a last ordinal")
}

/** Absent ordinals worth probing: the neighbours of what is present, and the block boundaries. */
private fun absentProbes(present: List<Int>): List<Int> {
    val set = present.toHashSet()
    return buildList {
        for (ordinal in present.take(64)) {
            add(ordinal - 1)
            add(ordinal + 1)
        }
        for (key in 0..(IndexGens.MAX_ORDINAL ushr 16) + 1) {
            add(key * BitmapFormat.CONTAINER_VALUES)
            add(key * BitmapFormat.CONTAINER_VALUES - 1)
        }
        add(IndexGens.MAX_ORDINAL + 1)
        add(BitmapFormat.MAX_ORDINAL)
    }.filter { it >= 0 && it !in set }.distinct()
}

internal fun readU16(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

internal fun readU32(bytes: ByteArray, offset: Int): Int {
    var value = 0
    for (index in 0 until 4) value = value or ((bytes[offset + index].toInt() and 0xFF) shl (8 * index))
    return value
}

internal fun writeU16(bytes: ByteArray, offset: Int, value: Int) {
    for (index in 0 until 2) bytes[offset + index] = (value ushr (8 * index)).toByte()
}

internal fun writeU32(bytes: ByteArray, offset: Int, value: Int) {
    for (index in 0 until 4) bytes[offset + index] = (value ushr (8 * index)).toByte()
}

private fun entryOffset(index: Int): Int = BitmapFormat.HEADER_BYTES + BitmapFormat.ENTRY_BYTES * index

/**
 * The encoding each block of [bitmap] ends up in, read back out of the directory.
 *
 * The container-transition tests are about *which encoding was chosen*, and the only honest way to ask
 * is to look at the bytes: a test that asked the in-memory object would be checking the state a block
 * happens to be in mid-build rather than the one that reaches a file.
 */
internal fun encodedKinds(bitmap: ReadableBitmap): List<Int> {
    val encoded = bitmap.encode()
    return List(readU16(encoded, 2)) { encoded[entryOffset(it) + 2].toInt() }
}

/** The block keys of [bitmap], read back out of the directory. */
internal fun encodedKeys(bitmap: ReadableBitmap): List<Int> {
    val encoded = bitmap.encode()
    return List(readU16(encoded, 2)) { readU16(encoded, entryOffset(it)) }
}

/** Every other ordinal from zero, so that no two are consecutive and a run encoding never wins. */
internal fun scatteredOrdinals(count: Int, base: Int = 0): List<Int> = List(count) { base + it * 2 }

/**
 * Opens [encoded] through a [MemorySegment] at an offset chosen to be unaligned.
 *
 * Every field in the format is read through an `*_UNALIGNED` layout, and this is what proves it: a
 * bitmap embedded in a sidecar begins wherever the previous one ended, so a `u64` word of a bitset
 * block routinely starts at an odd address. An aligned layout would throw here rather than read slowly.
 */
internal fun viewAtOffset(encoded: ByteArray, offset: Int = 3, file: String = "offset.idx"): BitmapView {
    val padded = ByteArray(offset + encoded.size + 5) { 0x7F }
    encoded.copyInto(padded, offset)
    return BitmapView.open(MemorySegment.ofArray(padded), offset.toLong(), encoded.size, file)
}
