package com.dragote.xcamera.feature.camera.domain.model

import com.dragote.xcamera.shared.common.domain.model.FocusPeakingSensitivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun `cells are row-major and contrast confined to one quadrant's interior only flags that cell`() {
        // 4x4, flat except a single bright pixel at (3, 3) — its only differing neighbors ((3, 2) and
        // (2, 3)) both belong to the same bottom-right cell, so this is genuinely interior contrast with
        // no cell-boundary pixel involved, unlike the boundary-straddling case covered separately below.
        val width = 4
        val height = 4
        val luma = IntArray(width * height) { 100 }.also { it[3 * width + 3] = 255 }
        val mask = FocusPeakingMask.fromLuma(luma, width, height, columns = 2, rows = 2)
        assertEquals(listOf(false, false, false, true), mask.edge)
    }

    @Test
    fun `a transition sitting exactly on a cell boundary is still flagged, by the lower-index cell`() {
        // 4x4, sharp transition between x=1 (col 0) and x=2 (col 1) — straddles the cell boundary rather
        // than falling inside either cell. Attributed to the cell containing the lower-x pixel (col 0).
        val width = 4
        val height = 4
        val luma = IntArray(width * height) { i ->
            val x = i % width
            if (x <= 1) 0 else 255
        }
        val mask = FocusPeakingMask.fromLuma(luma, width, height, columns = 2, rows = 2)
        assertEquals(listOf(true, false, true, false), mask.edge)
    }

    @Test
    fun `one cell per pixel (columns=width, rows=height) still detects a sharp edge`() {
        // ui/CameraScreen's real usage: one mask cell per loupe-crop pixel, so FocusRing can render it
        // as a bitmap contour (see FocusRing.toHighlightImage). At this resolution every cell's own
        // xEnd/yEnd equals xStart+1/yStart+1, so this only passes if neighbor comparisons are bounded by
        // the image, not the (single-pixel) cell — regression coverage for that bug.
        val width = 4
        val height = 1
        val luma = intArrayOf(0, 0, 255, 255)
        val mask = FocusPeakingMask.fromLuma(luma, width, height, columns = width, rows = height)
        assertEquals(listOf(false, true, false, false), mask.edge)
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
    fun `contrastThreshold is monotonically stricter from HIGH to LOW`() {
        val low = FocusPeakingMask.contrastThreshold(FocusPeakingSensitivity.LOW)
        val medium = FocusPeakingMask.contrastThreshold(FocusPeakingSensitivity.MEDIUM)
        val high = FocusPeakingMask.contrastThreshold(FocusPeakingSensitivity.HIGH)

        assertTrue("LOW ($low) should require more contrast than MEDIUM ($medium)", low > medium)
        assertTrue("MEDIUM ($medium) should require more contrast than HIGH ($high)", medium > high)
    }

    @Test
    fun `MEDIUM sensitivity resolves to DefaultContrastThreshold`() {
        assertEquals(
            FocusPeakingMask.DefaultContrastThreshold,
            FocusPeakingMask.contrastThreshold(FocusPeakingSensitivity.MEDIUM),
        )
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
