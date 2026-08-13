package com.dragote.xcamera.shared.common.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CubeLutBinaryFormatTest {

    private fun sampleLut() = CubeLut(
        size = 2,
        values = floatArrayOf(
            0f, 0f, 0f,
            0f, 0f, 1f,
            0f, 1f, 0f,
            0f, 1f, 1f,
            1f, 0f, 0f,
            1f, 0f, 1f,
            1f, 1f, 0f,
            1f, 1f, 1f,
        ),
    )

    @Test
    fun `round-trips size and values exactly`() {
        val lut = sampleLut()

        val roundTripped = parseCubeLutBinary(lut.toBinary())

        assertEquals(lut, roundTripped)
    }

    @Test
    fun `preserves float precision a decimal text round-trip could lose`() {
        val lut = CubeLut(size = 1, values = floatArrayOf(0.123456789f, 0.000000123f, 0.999999f))

        val roundTripped = parseCubeLutBinary(lut.toBinary())

        assertEquals(lut.values.toList(), roundTripped?.values?.toList())
    }

    @Test
    fun `returns null for content shorter than the header`() {
        assertNull(parseCubeLutBinary(ByteArray(4)))
    }

    @Test
    fun `returns null for a wrong magic number`() {
        val corrupted = sampleLut().toBinary().also { it[0] = it[0].inc() }

        assertNull(parseCubeLutBinary(corrupted))
    }

    @Test
    fun `returns null when the payload length doesn't match size cubed`() {
        val truncated = sampleLut().toBinary().copyOf(16) // header + one float, not the full payload

        assertNull(parseCubeLutBinary(truncated))
    }

    @Test
    fun `returns null for a non-positive size`() {
        val bytes = CubeLut(size = 0, values = FloatArray(0)).toBinary()

        assertNull(parseCubeLutBinary(bytes))
    }
}
