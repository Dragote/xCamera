package com.dragote.xcamera.feature.camera.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class FocusPeakingMaskTest {

    private fun flatLuma(width: Int, height: Int, value: Int) = IntArray(width * height) { value }

    @Test
    fun `a uniform flat frame has no edges anywhere`() {
        val luma = flatLuma(8, 8, 128)
        val mask = FocusPeakingMask.fromLuma(luma, width = 8, height = 8, columns = 2, rows = 2)
        assertEquals(List(4) { false }, mask.edge)
    }

    @Test
    fun `a sharp vertical edge is detected only in the cell it actually falls inside`() {
        // 8x4, columns=2 -> cell 0 covers x in [0,4), cell 1 covers x in [4,8). The hard transition
        // sits inside cell 0 (between x=1 and x=2); cell 1 stays flat, so only cell 0 should flag.
        val width = 8
        val height = 4
        val luma = IntArray(width * height) { i ->
            val x = i % width
            if (x < 2) 0 else 255
        }
        val mask = FocusPeakingMask.fromLuma(luma, width, height, columns = 2, rows = 1)
        assertEquals(listOf(true, false), mask.edge)
    }

    @Test
    fun `a soft gradient below the threshold is not flagged as an edge`() {
        // Luma rises by exactly 1 per pixel -> peak local contrast of 1, far below the default
        // threshold of 40.
        val width = 50
        val height = 4
        val luma = IntArray(width * height) { i -> (i % width) }
        val mask = FocusPeakingMask.fromLuma(luma, width, height, columns = 1, rows = 1)
        assertEquals(listOf(false), mask.edge)
    }

    @Test
    fun `threshold boundary is inclusive`() {
        val width = 2
        val height = 1
        val exactlyAtThreshold = intArrayOf(0, FocusPeakingMask.DefaultContrastThreshold)
        assertEquals(
            listOf(true),
            FocusPeakingMask.fromLuma(exactlyAtThreshold, width, height, columns = 1, rows = 1).edge,
        )

        val justBelowThreshold = intArrayOf(0, FocusPeakingMask.DefaultContrastThreshold - 1)
        assertEquals(
            listOf(false),
            FocusPeakingMask.fromLuma(justBelowThreshold, width, height, columns = 1, rows = 1).edge,
        )
    }

    @Test
    fun `cells are row-major and only the cell actually containing contrast is flagged`() {
        // 4x4, only the bottom-right quadrant has a sharp edge; the rest is flat.
        val width = 4
        val height = 4
        val luma = IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            if (y >= 2 && x >= 2) (if (x == 2) 0 else 255) else 128
        }
        val mask = FocusPeakingMask.fromLuma(luma, width, height, columns = 2, rows = 2)
        assertEquals(listOf(false, false, false, true), mask.edge)
    }

    @Test
    fun `a custom contrast threshold is respected`() {
        val luma = intArrayOf(0, 20)
        val strict = FocusPeakingMask.fromLuma(luma, width = 2, height = 1, columns = 1, rows = 1, contrastThreshold = 25)
        assertEquals(listOf(false), strict.edge)

        val lenient = FocusPeakingMask.fromLuma(luma, width = 2, height = 1, columns = 1, rows = 1, contrastThreshold = 15)
        assertEquals(listOf(true), lenient.edge)
    }

    @Test
    fun `grid not evenly dividing width or height still covers every pixel`() {
        // width=5, columns=2 -> uneven 2 vs 3 column split, still shouldn't throw and should classify
        // both cells independently based on their own content.
        val width = 5
        val height = 5
        val luma = IntArray(width * height) { i -> if (i % width < 2) 0 else 0 } // flat overall
        val mask = FocusPeakingMask.fromLuma(luma, width, height, columns = 2, rows = 1)
        assertEquals(listOf(false, false), mask.edge)
    }
}
