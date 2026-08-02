package app.oreshkov.rabosh.core

import app.oreshkov.rabosh.testkit.property.Gen
import app.oreshkov.rabosh.testkit.property.forAll
import app.oreshkov.rabosh.testkit.property.pair
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class KeyTest {

    @Test
    fun `orders bytes as unsigned`() {
        // The case that decides the comparator. As signed bytes 0x80 is -128 and sorts *below* 0x7F;
        // as unsigned bytes it sorts above. Only the unsigned order agrees with UTF-8, so a store
        // built on the signed one would interleave ASCII and non-ASCII keys.
        val low = Key.of(byteArrayOf(0x7F))
        val high = Key.of(byteArrayOf(0x80.toByte()))
        assertTrue(low < high, "0x7F must sort below 0x80")
    }

    @Test
    fun `orders text keys by code point`() {
        // U+FF21 (EF BC A1) against U+10000 (F0 90 80 80). In UTF-16 — which `String.compareTo`
        // compares — the astral character sorts first; in UTF-8 and by code point it sorts last.
        val bmp = Key.of("Ａ")
        val astral = Key.of("𐀀")
        assertTrue(bmp < astral, "UTF-8 order must follow code points, not UTF-16 units")
        assertTrue("Ａ" > "𐀀", "the String comparator disagrees, which is the point")
    }

    @Test
    fun `a prefix sorts before its extensions`() {
        assertTrue(Key.of("user") < Key.of("user:1"))
        assertTrue(Key.of(ByteArray(0)) < Key.of(byteArrayOf(0)))
    }

    @Test
    fun `equality is by content`() {
        assertEquals(Key.of("a"), Key.of(byteArrayOf('a'.code.toByte())))
        assertEquals(Key.of("a").hashCode(), Key.of("a").hashCode())
        assertNotEquals(Key.of("a"), Key.of("b"))
    }

    @Test
    fun `keys are immutable`() {
        val bytes = byteArrayOf(1, 2, 3)
        val key = Key.of(bytes)
        bytes[0] = 9
        key.toByteArray()[1] = 9
        assertContentEqualsBytes(byteArrayOf(1, 2, 3), key.toByteArray())
    }

    @Test
    fun `rejects text that has no UTF-8 encoding`() {
        // A lone high surrogate. `String.toByteArray` would substitute '?' and store a key the
        // caller never asked for; the failure has to happen at the boundary instead.
        val failure = assertFailsWith<IllegalArgumentException> { Key.of("\uD800") }
        assertTrue(failure.message!!.contains("surrogate"))
    }

    @Test
    fun `renders printable keys as text and others as hex`() {
        assertEquals("Key(user:1)", Key.of("user:1").toString())
        assertEquals("Key(0x00ff)", Key.of(byteArrayOf(0, 0xFF.toByte())).toString())
    }

    @Test
    fun `comparison agrees with unsigned byte order`() {
        forAll(Gen.pair(CoreGens.key, CoreGens.key)) { (left, right) ->
            val expected = referenceCompare(left.toByteArray(), right.toByteArray())
            assertEquals(
                expected.coerceIn(-1, 1),
                left.compareTo(right).coerceIn(-1, 1),
                "$left vs $right",
            )
        }
    }

    @Test
    fun `comparison is a total order`() {
        forAll(Gen.pair(CoreGens.key, CoreGens.key)) { (left, right) ->
            val forward = left.compareTo(right)
            val backward = right.compareTo(left)
            assertEquals(forward == 0, left == right, "compareTo and equals must agree: $left, $right")
            assertTrue(
                (forward == 0 && backward == 0) || (forward < 0) == (backward > 0),
                "antisymmetry broken for $left, $right",
            )
        }
    }

    /** An obvious, slow, independent comparison; the implementation delegates to the JDK instead. */
    private fun referenceCompare(left: ByteArray, right: ByteArray): Int {
        for (index in 0 until minOf(left.size, right.size)) {
            val a = left[index].toInt() and 0xFF
            val b = right[index].toInt() and 0xFF
            if (a != b) return a - b
        }
        return left.size - right.size
    }

    private fun assertContentEqualsBytes(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.toList(), actual.toList())
    }
}
