package app.oreshkov.rabosh.variant

import java.lang.foreign.MemorySegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VariantMetadataTest {

    @Test
    fun `empty dictionary round-trips`() {
        val metadata = VariantDictionaryBuilder().build()
        assertEquals(0, metadata.size)
        assertTrue(metadata.sortedAndUnique, "an empty dictionary is vacuously sorted and unique")
        assertContentEqualsHex(VariantMetadata.EMPTY.toByteArray(), metadata.toByteArray())
    }

    @Test
    fun `interning is idempotent and ids are handed out in order`() {
        val dictionary = VariantDictionaryBuilder()
        assertEquals(0, dictionary.intern("b"))
        assertEquals(1, dictionary.intern("a"))
        assertEquals(0, dictionary.intern("b"))
        assertEquals(2, dictionary.size)

        val metadata = dictionary.build()
        assertEquals("b", metadata.name(0))
        assertEquals("a", metadata.name(1))
        assertFalse(metadata.sortedAndUnique, "b then a is not lexicographic order")
    }

    @Test
    fun `the sorted flag is set only when insertion order happened to be sorted`() {
        val sorted = VariantDictionaryBuilder().apply {
            intern("alpha")
            intern("beta")
            intern("gamma")
        }.build()
        assertTrue(sorted.sortedAndUnique)
        // The flag is what makes the lookup a binary search rather than a scan.
        assertEquals(1, sorted.indexOf("beta"))
        assertEquals(-1, sorted.indexOf("delta"))

        val unsorted = VariantDictionaryBuilder().apply {
            intern("gamma")
            intern("alpha")
        }.build()
        assertFalse(unsorted.sortedAndUnique)
        assertEquals(1, unsorted.indexOf("alpha"))
        assertEquals(-1, unsorted.indexOf("beta"))
    }

    @Test
    fun `offset width follows the size of the string region`() {
        fun widthOf(metadata: VariantMetadata): Int = ((metadata.toByteArray()[0].toInt() and 0xFF) ushr 6) + 1

        val small = VariantDictionaryBuilder().apply { intern("a") }.build()
        assertEquals(1, widthOf(small))

        val medium = VariantDictionaryBuilder().apply { repeat(40) { intern("name$it".padEnd(10, 'x')) } }.build()
        assertEquals(2, widthOf(medium), "400 bytes of names no longer fit one-byte offsets")

        val large = VariantDictionaryBuilder().apply { repeat(100) { intern("n$it".padEnd(1000, 'x')) } }.build()
        assertEquals(3, widthOf(large), "100 KiB of names needs three-byte offsets")
        assertEquals("n99".padEnd(1000, 'x'), large.name(99))
    }

    @Test
    fun `names survive the round trip whatever they contain`() {
        val awkward = listOf("", "a", "Z", "with space", "with\"quote", "with\\backslash", "日本語", "😀", "é".repeat(50))
        val dictionary = VariantDictionaryBuilder()
        awkward.forEach { dictionary.intern(it) }
        val metadata = dictionary.build()
        awkward.forEachIndexed { id, name -> assertEquals(name, metadata.name(id)) }
    }

    @Test
    fun `a field name with an unpaired surrogate is rejected rather than mangled`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            VariantDictionaryBuilder().intern("broken\uD800")
        }
        assertTrue("surrogate" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `EMPTY is shared and readable`() {
        assertSame(VariantMetadata.EMPTY, VariantMetadata.EMPTY)
        assertEquals(0, VariantMetadata.EMPTY.size)
        assertEquals(3, VariantMetadata.EMPTY.byteSize)
        // A document of scalars needs no names, so its dictionary is empty too.
        assertEquals(0, Variant.fromJson("[1,2,3]").metadata.size)
    }

    // --- malformed metadata ----------------------------------------------------------------

    @Test
    fun `a future version is refused rather than guessed at`() {
        val failure = assertFailsWith<VariantFormatException> {
            VariantMetadata.of(byteArrayOf(0x02, 0x00, 0x00))
        }
        assertTrue("version 2" in failure.message.orEmpty(), failure.message.orEmpty())
        assertEquals(0, failure.offset)
    }

    @Test
    fun `the reserved bit is ignored, as the specification requires`() {
        // Bit 5 set, everything else as in EMPTY.
        val metadata = VariantMetadata.of(byteArrayOf(0x31, 0x00, 0x00))
        assertEquals(0, metadata.size)
    }

    @Test
    fun `truncated metadata is refused`() {
        assertFailsWith<VariantFormatException> { VariantMetadata.of(byteArrayOf(0x11)) }
        // dictionary_size 1 but no offsets at all
        assertFailsWith<VariantFormatException> { VariantMetadata.of(byteArrayOf(0x11, 0x01)) }
        // offsets present but the string region is short of the length they declare
        assertFailsWith<VariantFormatException> {
            VariantMetadata.of(byteArrayOf(0x11, 0x01, 0x00, 0x05, 0x61))
        }
    }

    @Test
    fun `offsets that run backwards are refused`() {
        val failure = assertFailsWith<VariantFormatException> {
            VariantMetadata.of(byteArrayOf(0x11, 0x02, 0x00, 0x02, 0x01, 0x61, 0x62))
        }
        assertTrue("backwards" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `a non-zero first offset is refused`() {
        val failure = assertFailsWith<VariantFormatException> {
            VariantMetadata.of(byteArrayOf(0x11, 0x01, 0x01, 0x02, 0x61, 0x62))
        }
        assertTrue("expected 0" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `metadata can be read at an offset inside a larger segment`() {
        // The layout a segment footer produces: metadata is not at position zero of the file.
        val padding = ByteArray(7) { 0x7F }
        val encoded = VariantDictionaryBuilder().apply { intern("id") }.toByteArray()
        val segment = MemorySegment.ofArray(padding + encoded + padding)

        val metadata = VariantMetadata.read(segment, padding.size.toLong())
        assertEquals(1, metadata.size)
        assertEquals("id", metadata.name(0))
        assertEquals(encoded.size.toLong(), metadata.byteSize)
        assertContentEqualsHex(encoded, metadata.toByteArray())
    }
}

internal fun assertContentEqualsHex(expected: ByteArray, actual: ByteArray) {
    assertEquals(expected.toHex(), actual.toHex())
}
