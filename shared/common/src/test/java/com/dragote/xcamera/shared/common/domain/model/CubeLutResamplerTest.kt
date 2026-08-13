package com.dragote.xcamera.shared.common.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CubeLutResamplerTest {

    /**
     * A hand-designed 2x2x2 LUT where each output channel isolates a single corner, so the trilinear
     * weight at any fractional coordinate is directly hand-computable:
     * - red is `1.0` only at grid point (r=0, g=0, b=1), `0.0` everywhere else.
     * - green is a constant `0.5` at every grid point (any interpolation of it must stay `0.5`).
     * - blue is `1.0` only at grid point (r=1, g=1, b=1), `0.0` everywhere else.
     *
     * Row order matches the `.cube`/[CubeLut] convention: index `r*4 + g*2 + b`.
     */
    private fun cornerIsolatingLut(): CubeLut {
        val values = FloatArray(8 * 3)
        fun setRow(r: Int, g: Int, b: Int, red: Float, green: Float, blue: Float) {
            val base = (r * 4 + g * 2 + b) * 3
            values[base] = red
            values[base + 1] = green
            values[base + 2] = blue
        }
        for (r in 0..1) {
            for (g in 0..1) {
                for (b in 0..1) {
                    val red = if (r == 0 && g == 0 && b == 1) 1f else 0f
                    val blue = if (r == 1 && g == 1 && b == 1) 1f else 0f
                    setRow(r, g, b, red, 0.5f, blue)
                }
            }
        }
        return CubeLut(2, values)
    }

    private fun channelsAt(lut: CubeLut, r: Int, g: Int, b: Int): Triple<Float, Float, Float> {
        val base = (r * lut.size * lut.size + g * lut.size + b) * 3
        return Triple(lut.values[base], lut.values[base + 1], lut.values[base + 2])
    }

    @Test
    fun `resampling to the same size is a no-op through the interpolation path`() {
        val source = cornerIsolatingLut()

        val resampled = resampleCubeLut(source, 2)

        assertEquals(source, resampled)
    }

    @Test
    fun `output corners land exactly on the corresponding source corners`() {
        val source = cornerIsolatingLut()

        val resampled = resampleCubeLut(source, 3)

        // Output (0,0,0) maps exactly onto source (0,0,0): red=0, green=0.5, blue=0.
        assertEquals(Triple(0f, 0.5f, 0f), channelsAt(resampled, 0, 0, 0))
        // Output (2,2,2) (the far corner of a 3-size grid) maps exactly onto source (1,1,1):
        // red=0, green=0.5, blue=1.
        assertEquals(Triple(0f, 0.5f, 1f), channelsAt(resampled, 2, 2, 2))
        // Output (0,0,2) maps exactly onto source (0,0,1): red=1, green=0.5, blue=0.
        assertEquals(Triple(1f, 0.5f, 0f), channelsAt(resampled, 0, 0, 2))
    }

    @Test
    fun `midpoint output grid point is the trilinear average of all 8 source corners`() {
        val source = cornerIsolatingLut()

        val resampled = resampleCubeLut(source, 3)

        // Output (1,1,1) on a 3-size grid maps to source coordinate (0.5, 0.5, 0.5) — trilinear
        // weight for any single isolated corner at the exact midpoint is 0.5*0.5*0.5 = 0.125.
        val (red, green, blue) = channelsAt(resampled, 1, 1, 1)
        assertEquals(0.125f, red, 1e-6f)
        assertEquals(0.5f, green, 1e-6f) // constant channel must stay constant under interpolation
        assertEquals(0.125f, blue, 1e-6f)
    }

    @Test
    fun `downsampling to a single grid point samples exactly the source's own origin corner`() {
        val source = cornerIsolatingLut()

        val resampled = resampleCubeLut(source, 1)

        assertEquals(1, resampled.size)
        assertEquals(Triple(0f, 0.5f, 0f), channelsAt(resampled, 0, 0, 0))
    }

    @Test
    fun `a linear gradient LUT resamples exactly onto the same gradient at any target size`() {
        // A 3x3x3 LUT whose channels are exactly the normalized grid coordinates — trilinear
        // interpolation of a linear function is exact, so every resampled point should equal its own
        // normalized coordinate in the new grid, regardless of target size.
        val sourceSize = 3
        val values = FloatArray(sourceSize * sourceSize * sourceSize * 3)
        var index = 0
        for (r in 0 until sourceSize) {
            for (g in 0 until sourceSize) {
                for (b in 0 until sourceSize) {
                    values[index] = r / (sourceSize - 1).toFloat()
                    values[index + 1] = g / (sourceSize - 1).toFloat()
                    values[index + 2] = b / (sourceSize - 1).toFloat()
                    index += 3
                }
            }
        }
        val source = CubeLut(sourceSize, values)

        val targetSize = 4
        val resampled = resampleCubeLut(source, targetSize)

        var writeIndex = 0
        for (r in 0 until targetSize) {
            for (g in 0 until targetSize) {
                for (b in 0 until targetSize) {
                    assertEquals(r / (targetSize - 1).toFloat(), resampled.values[writeIndex], 1e-5f)
                    assertEquals(g / (targetSize - 1).toFloat(), resampled.values[writeIndex + 1], 1e-5f)
                    assertEquals(b / (targetSize - 1).toFloat(), resampled.values[writeIndex + 2], 1e-5f)
                    writeIndex += 3
                }
            }
        }
    }
}
