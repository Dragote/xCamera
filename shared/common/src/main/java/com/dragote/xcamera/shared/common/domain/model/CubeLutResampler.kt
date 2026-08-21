package com.dragote.xcamera.shared.common.domain.model

import kotlin.math.floor

/**
 * Trilinearly resamples [source]'s 3D grid onto a fresh [targetSize]³ grid — standard 3D LUT
 * resampling: each output grid coordinate is mapped into [source]'s own `[0, source.size - 1]`
 * coordinate space (a plain linear remap of the `0 until targetSize` output index range onto
 * `0..source.size - 1`), then the 8 surrounding source grid points are trilinearly interpolated.
 * Deliberately doesn't special-case `source.size == targetSize` — a same-size "resample" just
 * interpolates each output point exactly onto its corresponding source point (fractional part
 * always `0`), a harmless no-op through the same code path rather than a separate short-circuit.
 *
 * Used at LUT import time (`feature:settings`'s `LutRepositoryImpl`) to normalize every imported
 * `.cube` file onto one canonical grid size regardless of what the user originally uploaded — see
 * that class's own doc for why a uniform size matters for `feature:camera`'s GPU texture cache.
 */
fun resampleCubeLut(source: CubeLut, targetSize: Int): CubeLut {
    require(targetSize > 0) { "targetSize must be positive, was $targetSize" }

    val sourceMaxIndex = (source.size - 1).coerceAtLeast(0)
    // coerceAtLeast(1) purely to avoid a division by zero when targetSize == 1 — the explicit
    // `targetSize == 1` branches below never actually reach the division in that case, but this
    // keeps the expression itself always well-defined.
    val targetMaxIndex = (targetSize - 1).coerceAtLeast(1)

    val result = FloatArray(targetSize * targetSize * targetSize * 3)
    var writeIndex = 0
    for (r in 0 until targetSize) {
        val srcR = if (targetSize == 1) 0f else r.toFloat() / targetMaxIndex * sourceMaxIndex
        for (g in 0 until targetSize) {
            val srcG = if (targetSize == 1) 0f else g.toFloat() / targetMaxIndex * sourceMaxIndex
            for (b in 0 until targetSize) {
                val srcB = if (targetSize == 1) 0f else b.toFloat() / targetMaxIndex * sourceMaxIndex
                for (channel in 0 until 3) {
                    result[writeIndex + channel] = trilinearSample(source, srcR, srcG, srcB, channel)
                }
                writeIndex += 3
            }
        }
    }
    return CubeLut(targetSize, result)
}

/** Trilinearly interpolates [channel] (0=R, 1=G, 2=B) of [source] at the fractional grid coordinate
 *  ([r], [g], [b]) — each expected already clamped into `[0, source.size - 1]` by the caller. */
private fun trilinearSample(source: CubeLut, r: Float, g: Float, b: Float, channel: Int): Float {
    val size = source.size
    val maxIndex = size - 1
    val r0 = floor(r).toInt().coerceIn(0, maxIndex)
    val g0 = floor(g).toInt().coerceIn(0, maxIndex)
    val b0 = floor(b).toInt().coerceIn(0, maxIndex)
    val r1 = (r0 + 1).coerceAtMost(maxIndex)
    val g1 = (g0 + 1).coerceAtMost(maxIndex)
    val b1 = (b0 + 1).coerceAtMost(maxIndex)
    val fr = r - r0
    val fg = g - g0
    val fb = b - b0

    fun at(ri: Int, gi: Int, bi: Int): Float {
        val index = (ri * size * size + gi * size + bi) * 3 + channel
        return source.values[index]
    }

    val c00 = at(r0, g0, b0) * (1f - fr) + at(r1, g0, b0) * fr
    val c10 = at(r0, g1, b0) * (1f - fr) + at(r1, g1, b0) * fr
    val c01 = at(r0, g0, b1) * (1f - fr) + at(r1, g0, b1) * fr
    val c11 = at(r0, g1, b1) * (1f - fr) + at(r1, g1, b1) * fr
    val c0 = c00 * (1f - fg) + c10 * fg
    val c1 = c01 * (1f - fg) + c11 * fg
    return c0 * (1f - fb) + c1 * fb
}
