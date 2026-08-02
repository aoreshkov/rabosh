package app.oreshkov.rabosh.variant

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VariantTypeTest {
    @Test
    fun `basic type is decoded from the low two bits`() {
        assertEquals(VariantBasicType.PRIMITIVE, VariantBasicType.ofHeader(0b0000_0000))
        assertEquals(VariantBasicType.SHORT_STRING, VariantBasicType.ofHeader(0b0000_0001))
        assertEquals(VariantBasicType.OBJECT, VariantBasicType.ofHeader(0b0000_0010))
        assertEquals(VariantBasicType.ARRAY, VariantBasicType.ofHeader(0b0000_0011))
    }

    @Test
    fun `basic type ignores the upper six bits`() {
        assertEquals(VariantBasicType.OBJECT, VariantBasicType.ofHeader(0b1111_1110.toByte()))
    }

    @Test
    fun `primitive type is decoded from the upper six bits`() {
        // id 16 (STRING) shifted into the header's upper six bits.
        assertEquals(VariantPrimitiveType.STRING, VariantPrimitiveType.ofHeader((16 shl 2).toByte()))
        assertEquals(VariantPrimitiveType.NULL, VariantPrimitiveType.ofHeader(0))
        assertEquals(VariantPrimitiveType.UUID, VariantPrimitiveType.ofHeader((20 shl 2).toByte()))
    }

    @Test
    fun `unknown primitive ids decode to null rather than throwing`() {
        assertNull(VariantPrimitiveType.ofId(VariantPrimitiveType.MAX_ID + 1))
        assertNull(VariantPrimitiveType.ofId(-1))
        assertNull(VariantPrimitiveType.ofHeader((21 shl 2).toByte()))
    }

    @Test
    fun `primitive ids are unique and within the specified range`() {
        val ids = VariantPrimitiveType.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "primitive type ids must be unique")
        assertEquals(0..VariantPrimitiveType.MAX_ID, ids.min()..ids.max())
    }
}
