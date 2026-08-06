package com.dragote.xcamera.feature.camera.domain.model

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Test

class ZebraMaskTest {

    /** Builds a tightly-packed (pixelStride = 1, rowStride = width) luma buffer from [luma]. */
    private fun lumaBuffer(width: Int, height: Int, luma: (x: Int, y: Int) -> Int): ByteBuffer {
        val bytes = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                bytes[y * width + x] = luma(x, y).toByte()
            }
        }
        return ByteBuffer.wrap(bytes)
    }

    @Test
    fun `all-black frame is entirely shadow`() {
        val buffer = lumaBuffer(4, 4) { _, _ -> 0 }
        val mask = ZebraMask.fromLumaPlane(buffer, rowStride = 4, pixelStride = 1, width = 4, height = 4, columns = 2, rows = 2)
        assertEquals(List(4) { ZebraClipping.SHADOW }, mask.cells)
    }

    @Test
    fun `all-white frame is entirely highlight`() {
        val buffer = lumaBuffer(4, 4) { _, _ -> 255 }
        val mask = ZebraMask.fromLumaPlane(buffer, rowStride = 4, pixelStride = 1, width = 4, height = 4, columns = 2, rows = 2)
        assertEquals(List(4) { ZebraClipping.HIGHLIGHT }, mask.cells)
    }

    @Test
    fun `mid-gray frame clips nothing`() {
        val buffer = lumaBuffer(4, 4) { _, _ -> 128 }
        val mask = ZebraMask.fromLumaPlane(buffer, rowStride = 4, pixelStride = 1, width = 4, height = 4, columns = 2, rows = 2)
        assertEquals(List(4) { ZebraClipping.NONE }, mask.cells)
    }

    @Test
    fun `threshold boundaries are inclusive`() {
        // Default thresholds: shadow <= 10, highlight >= 245.
        val shadowEdge = lumaBuffer(2, 2) { _, _ -> 10 }
        assertEquals(
            listOf(ZebraClipping.SHADOW),
            ZebraMask.fromLumaPlane(shadowEdge, rowStride = 2, pixelStride = 1, width = 2, height = 2, columns = 1, rows = 1).cells,
        )

        val justAboveShadowEdge = lumaBuffer(2, 2) { _, _ -> 11 }
        assertEquals(
            listOf(ZebraClipping.NONE),
            ZebraMask.fromLumaPlane(justAboveShadowEdge, rowStride = 2, pixelStride = 1, width = 2, height = 2, columns = 1, rows = 1).cells,
        )

        val highlightEdge = lumaBuffer(2, 2) { _, _ -> 245 }
        assertEquals(
            listOf(ZebraClipping.HIGHLIGHT),
            ZebraMask.fromLumaPlane(highlightEdge, rowStride = 2, pixelStride = 1, width = 2, height = 2, columns = 1, rows = 1).cells,
        )

        val justBelowHighlightEdge = lumaBuffer(2, 2) { _, _ -> 244 }
        assertEquals(
            listOf(ZebraClipping.NONE),
            ZebraMask.fromLumaPlane(justBelowHighlightEdge, rowStride = 2, pixelStride = 1, width = 2, height = 2, columns = 1, rows = 1).cells,
        )
    }

    @Test
    fun `cell classification is majority vote, not average`() {
        // A 4x1 cell: 3 clipped-black pixels + 1 mid-gray pixel — average would land mid-gray-ish
        // and read as unclipped, but a genuine majority of the cell is crushed, so this must be SHADOW.
        val majorityShadow = lumaBuffer(4, 1) { x, _ -> if (x < 3) 0 else 128 }
        assertEquals(
            listOf(ZebraClipping.SHADOW),
            ZebraMask.fromLumaPlane(majorityShadow, rowStride = 4, pixelStride = 1, width = 4, height = 1, columns = 1, rows = 1).cells,
        )

        // Exactly half-and-half is not a majority either way.
        val tied = lumaBuffer(4, 1) { x, _ -> if (x < 2) 0 else 128 }
        assertEquals(
            listOf(ZebraClipping.NONE),
            ZebraMask.fromLumaPlane(tied, rowStride = 4, pixelStride = 1, width = 4, height = 1, columns = 1, rows = 1).cells,
        )
    }

    @Test
    fun `rowStride padding beyond width is ignored`() {
        // width=2 but each row is padded out to 6 bytes (rowStride=6) — a common sensor alignment
        // case. Real pixels are black; the padding bytes are set to 255, which would flip the cell to
        // HIGHLIGHT if the padding were mistakenly read as pixel data.
        val width = 2
        val height = 2
        val rowStride = 6
        val bytes = ByteArray(rowStride * height) { 255.toByte() }
        for (y in 0 until height) {
            for (x in 0 until width) {
                bytes[y * rowStride + x] = 0
            }
        }
        val buffer = ByteBuffer.wrap(bytes)

        val mask = ZebraMask.fromLumaPlane(buffer, rowStride = rowStride, pixelStride = 1, width = width, height = height, columns = 1, rows = 1)
        assertEquals(listOf(ZebraClipping.SHADOW), mask.cells)
    }

    @Test
    fun `pixelStride greater than 1 skips interleaved bytes`() {
        // Semi-planar-style layout: real luma at even offsets, garbage (255) at odd offsets.
        val width = 3
        val bytes = ByteArray(width * 2)
        for (x in 0 until width) {
            bytes[x * 2] = 0
            bytes[x * 2 + 1] = 255.toByte()
        }
        val buffer = ByteBuffer.wrap(bytes)

        val mask = ZebraMask.fromLumaPlane(buffer, rowStride = width * 2, pixelStride = 2, width = width, height = 1, columns = 1, rows = 1)
        assertEquals(listOf(ZebraClipping.SHADOW), mask.cells)
    }

    @Test
    fun `grid not evenly dividing width or height still covers every pixel exactly once`() {
        // width=5, columns=2 -> col0 covers x in [0,2), col1 covers x in [2,5) (uneven 2 vs 3 split).
        val buffer = lumaBuffer(5, 5) { x, _ -> if (x < 2) 0 else 255 }
        val mask = ZebraMask.fromLumaPlane(buffer, rowStride = 5, pixelStride = 1, width = 5, height = 5, columns = 2, rows = 1)
        assertEquals(listOf(ZebraClipping.SHADOW, ZebraClipping.HIGHLIGHT), mask.cells)
    }

    @Test
    fun `cells are row-major`() {
        // Top-left quadrant black, everything else mid-gray — only index 0 (row 0, col 0) should clip.
        val buffer = lumaBuffer(4, 4) { x, y -> if (x < 2 && y < 2) 0 else 128 }
        val mask = ZebraMask.fromLumaPlane(buffer, rowStride = 4, pixelStride = 1, width = 4, height = 4, columns = 2, rows = 2)
        assertEquals(
            listOf(ZebraClipping.SHADOW, ZebraClipping.NONE, ZebraClipping.NONE, ZebraClipping.NONE),
            mask.cells,
        )
    }

    @Test
    fun `large cells still classify correctly under the per-cell sample budget`() {
        // A single cell far bigger than the 8x8 sample budget (see fromLumaPlane's own doc) — a
        // majority-black frame with a small white patch that a coarse stride could either catch or
        // miss depending on placement; here the white patch is a small minority by area, so sampling
        // should still land on SHADOW rather than being thrown off by not visiting every pixel.
        val width = 400
        val height = 400
        val buffer = lumaBuffer(width, height) { x, y -> if (x < 20 && y < 20) 255 else 0 }
        val mask = ZebraMask.fromLumaPlane(buffer, rowStride = width, pixelStride = 1, width = width, height = height, columns = 1, rows = 1)
        assertEquals(listOf(ZebraClipping.SHADOW), mask.cells)
    }

    // An asymmetric 3x2 (3 columns, 2 rows) pattern — top-left marked SHADOW, bottom-right marked
    // HIGHLIGHT, everything else NONE — so a wrong rotation direction (e.g. CCW instead of CW) or a
    // plain reflection produces a different, distinguishable result rather than accidentally matching.
    private val N = ZebraClipping.NONE
    private val S = ZebraClipping.SHADOW
    private val H = ZebraClipping.HIGHLIGHT
    private val asymmetricMask = ZebraMask(columns = 3, rows = 2, cells = listOf(S, N, N, N, N, H))

    @Test
    fun `rotatedBy 0 is the identity`() {
        assertEquals(asymmetricMask, asymmetricMask.rotatedBy(0))
    }

    @Test
    fun `rotatedBy 90 rotates clockwise and swaps columns and rows`() {
        val rotated = asymmetricMask.rotatedBy(90)
        assertEquals(2, rotated.columns)
        assertEquals(3, rotated.rows)
        assertEquals(listOf(N, S, N, N, H, N), rotated.cells)
    }

    @Test
    fun `rotatedBy 180 reverses cell order and keeps dimensions`() {
        val rotated = asymmetricMask.rotatedBy(180)
        assertEquals(3, rotated.columns)
        assertEquals(2, rotated.rows)
        assertEquals(listOf(H, N, N, N, N, S), rotated.cells)
    }

    @Test
    fun `rotatedBy 270 rotates counter-clockwise and swaps columns and rows`() {
        val rotated = asymmetricMask.rotatedBy(270)
        assertEquals(2, rotated.columns)
        assertEquals(3, rotated.rows)
        assertEquals(listOf(N, H, N, N, S, N), rotated.cells)
    }

    @Test
    fun `rotatedBy normalizes negative and over-360 degrees`() {
        assertEquals(asymmetricMask.rotatedBy(270), asymmetricMask.rotatedBy(-90))
        assertEquals(asymmetricMask.rotatedBy(90), asymmetricMask.rotatedBy(450))
    }

    @Test
    fun `four consecutive 90-degree rotations return to the original`() {
        val fullCircle = asymmetricMask.rotatedBy(90).rotatedBy(90).rotatedBy(90).rotatedBy(90)
        assertEquals(asymmetricMask, fullCircle)
    }
}
