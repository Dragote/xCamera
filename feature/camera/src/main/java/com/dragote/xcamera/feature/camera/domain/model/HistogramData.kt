package com.dragote.xcamera.feature.camera.domain.model

import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * A luma-only tonal histogram of the live viewfinder, bucketed into [buckets] (index `0` = darkest,
 * last index = brightest). Unlike [ZebraMask]'s clipping grid, this is always-on for as long as the
 * preview is running (see `CameraController`'s own doc) rather than gated behind any gesture — a
 * histogram is a conventional always-visible pro-camera readout, not a momentary diagnostic overlay.
 */
data class HistogramData(
    val buckets: List<Int>,
) {

    /** The tallest bucket's raw sample count — callers normalize bar heights against this rather than
     *  the (unbounded, resolution-dependent) total sample count. `0` for an all-empty histogram. */
    val maxBucketCount: Int get() = buckets.maxOrNull() ?: 0

    companion object {

        /**
         * Buckets a `YUV_420_888` luma plane (plane 0 — same as [ZebraMask.fromLumaPlane], luma is
         * the only channel a histogram or a zebra overlay needs) into [bucketCount] evenly-spaced
         * `0..255` luma ranges by sampling at most [SampleGridWidth]x[SampleGridHeight] evenly-spaced
         * pixels across the whole frame — a fixed sample budget *independent of the source frame's
         * real resolution*, the same bounded-cost technique [ZebraMask.fromLumaPlane] uses per-cell,
         * just applied once across the full frame here since a histogram has no per-region grid to
         * bound instead. This keeps the per-frame cost of `CameraController`'s always-on (see its own
         * doc — no enable/disable gate, unlike zebra's dial-drag gate) classification constant however
         * large the live preview stream's own resolution happens to be.
         *
         * [rowStride]/[pixelStride] come straight from `Image.Plane.rowStride`/`pixelStride`, exactly
         * as [ZebraMask.fromLumaPlane] documents — the row stride in particular is very commonly larger
         * than [width] (sensors pad rows to an alignment boundary), so indexing through it rather than
         * assuming a tightly packed buffer is required for correctness on real devices.
         *
         * [width]/[height] need not be evenly divisible by the sample grid — the stride is simply
         * floored to at least `1`, so a frame smaller than the sample budget in either dimension just
         * visits every pixel along that axis instead of skipping any.
         *
         * Pure and Android/Camera2-type-free (a plain [ByteBuffer], not `android.media.Image.Plane`),
         * matching [ZebraMask.fromLumaPlane]'s own reasoning for full unit-testability without
         * Robolectric.
         */
        fun fromLumaPlane(
            buffer: ByteBuffer,
            rowStride: Int,
            pixelStride: Int,
            width: Int,
            height: Int,
            bucketCount: Int = 64,
        ): HistogramData {
            val buckets = IntArray(bucketCount.coerceAtLeast(1))
            if (width <= 0 || height <= 0) return HistogramData(buckets.toList())

            val xStep = max(1, width / SampleGridWidth)
            val yStep = max(1, height / SampleGridHeight)

            var y = 0
            while (y < height) {
                val rowOffset = y * rowStride
                var x = 0
                while (x < width) {
                    val luma = buffer.get(rowOffset + x * pixelStride).toInt() and 0xFF
                    // luma is 0..255; scaling by bucketCount/256 evenly spreads it across the buckets,
                    // clamped so luma=255 (which would otherwise land exactly on `bucketCount`) falls
                    // into the last bucket rather than overflowing it.
                    val bucket = min(buckets.lastIndex, luma * buckets.size / 256)
                    buckets[bucket]++
                    x += xStep
                }
                y += yStep
            }
            return HistogramData(buckets.toList())
        }

        /** Sample grid bounds — see [fromLumaPlane]'s own doc. 128x128 = 16,384 luma reads/frame is
         *  plenty for a stable-looking distribution while staying trivial regardless of the live
         *  preview's real resolution, comparable in spirit to [ZebraMask.SamplesPerCellAxis]'s own
         *  per-cell budget. */
        private const val SampleGridWidth = 128
        private const val SampleGridHeight = 128
    }
}
