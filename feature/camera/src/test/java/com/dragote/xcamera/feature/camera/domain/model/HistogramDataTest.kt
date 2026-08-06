package com.dragote.xcamera.feature.camera.domain.model

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistogramDataTest {

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
    fun `all-dark frame lands entirely in the first bucket`() {
        val buffer = lumaBuffer(4, 4) { _, _ -> 0 }
        val histogram = HistogramData.fromLumaPlane(buffer, rowStride = 4, pixelStride = 1, width = 4, height = 4, bucketCount = 4)
        assertEquals(listOf(16, 0, 0, 0), histogram.buckets)
    }

    @Test
    fun `all-light frame lands entirely in the last bucket`() {
        val buffer = lumaBuffer(4, 4) { _, _ -> 255 }
        val histogram = HistogramData.fromLumaPlane(buffer, rowStride = 4, pixelStride = 1, width = 4, height = 4, bucketCount = 4)
        assertEquals(listOf(0, 0, 0, 16), histogram.buckets)
    }

    @Test
    fun `mixed frame splits across buckets by luma value`() {
        // width=4: two pixels at luma 0 (bucket 0), two at luma 255 (bucket 3), per row; 4 rows total.
        val buffer = lumaBuffer(4, 4) { x, _ -> if (x < 2) 0 else 255 }
        val histogram = HistogramData.fromLumaPlane(buffer, rowStride = 4, pixelStride = 1, width = 4, height = 4, bucketCount = 4)
        assertEquals(listOf(8, 0, 0, 8), histogram.buckets)
    }

    @Test
    fun `rowStride padding beyond width is ignored`() {
        // width=2 but each row is padded out to 6 bytes (rowStride=6) — a common sensor alignment
        // case. Real pixels are dark; the padding bytes are set to 255, which would land in the
        // brightest bucket if the padding were mistakenly read as pixel data.
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

        val histogram = HistogramData.fromLumaPlane(buffer, rowStride = rowStride, pixelStride = 1, width = width, height = height, bucketCount = 4)
        assertEquals(listOf(4, 0, 0, 0), histogram.buckets)
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

        val histogram = HistogramData.fromLumaPlane(buffer, rowStride = width * 2, pixelStride = 2, width = width, height = 1, bucketCount = 4)
        assertEquals(listOf(3, 0, 0, 0), histogram.buckets)
    }

    @Test
    fun `bucket boundaries are evenly spaced across the 0 to 255 luma range`() {
        // With bucketCount=4, boundaries fall at luma 64/128/192 — verify each side of every boundary
        // lands in the expected bucket.
        val lumas = intArrayOf(0, 63, 64, 127, 128, 191, 192, 255)
        val buffer = lumaBuffer(lumas.size, 1) { x, _ -> lumas[x] }
        val histogram = HistogramData.fromLumaPlane(buffer, rowStride = lumas.size, pixelStride = 1, width = lumas.size, height = 1, bucketCount = 4)
        assertEquals(listOf(2, 2, 2, 2), histogram.buckets)
    }

    @Test
    fun `bucketCount of 1 puts every sample in the single bucket`() {
        val buffer = lumaBuffer(4, 4) { x, y -> (x + y) * 20 }
        val histogram = HistogramData.fromLumaPlane(buffer, rowStride = 4, pixelStride = 1, width = 4, height = 4, bucketCount = 1)
        assertEquals(listOf(16), histogram.buckets)
    }

    @Test
    fun `default bucketCount is 64`() {
        val buffer = lumaBuffer(4, 4) { _, _ -> 0 }
        val histogram = HistogramData.fromLumaPlane(buffer, rowStride = 4, pixelStride = 1, width = 4, height = 4)
        assertEquals(64, histogram.buckets.size)
    }

    @Test
    fun `maxBucketCount reflects the tallest bucket`() {
        val buffer = lumaBuffer(4, 4) { x, _ -> if (x == 0) 0 else 255 }
        val histogram = HistogramData.fromLumaPlane(buffer, rowStride = 4, pixelStride = 1, width = 4, height = 4, bucketCount = 4)
        assertEquals(12, histogram.maxBucketCount)
    }

    @Test
    fun `maxBucketCount is 0 for an all-empty histogram`() {
        assertEquals(0, HistogramData(List(4) { 0 }).maxBucketCount)
    }

    @Test
    fun `large frame still classifies correctly under the sample budget`() {
        // A frame far bigger than the 128x128 sample grid (see fromLumaPlane's own doc) — a
        // majority-dark frame with a small bright patch that a coarse stride could either catch or
        // miss depending on placement; here the bright patch is a small minority by area, so sampling
        // should still land mostly in the darkest bucket rather than being thrown off by not visiting
        // every pixel.
        val width = 400
        val height = 400
        val buffer = lumaBuffer(width, height) { x, y -> if (x < 20 && y < 20) 255 else 0 }
        val histogram = HistogramData.fromLumaPlane(buffer, rowStride = width, pixelStride = 1, width = width, height = height, bucketCount = 4)
        assertEquals(histogram.buckets[0], histogram.maxBucketCount)
        assertEquals(0, histogram.buckets[1])
        assertEquals(0, histogram.buckets[2])
        assertTrue(histogram.buckets[3] < histogram.buckets[0])
    }

    @Test
    fun `zero width or height yields an all-empty histogram without throwing`() {
        val buffer = ByteBuffer.wrap(ByteArray(0))
        val zeroWidth = HistogramData.fromLumaPlane(buffer, rowStride = 0, pixelStride = 1, width = 0, height = 4, bucketCount = 4)
        assertEquals(listOf(0, 0, 0, 0), zeroWidth.buckets)

        val zeroHeight = HistogramData.fromLumaPlane(buffer, rowStride = 0, pixelStride = 1, width = 4, height = 0, bucketCount = 4)
        assertEquals(listOf(0, 0, 0, 0), zeroHeight.buckets)
    }
}
