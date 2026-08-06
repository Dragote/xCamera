package com.dragote.xcamera.feature.camera.domain.model

import kotlin.math.abs
import kotlin.math.max

/**
 * Coarse, loupe-local edge/focus-assist highlight (Pixel-style focus peaking) — computed once per
 * refreshed magnified loupe crop while a manual-focus hold gesture is in progress (issue #21), never
 * for the full viewfinder: continuous/always-on focus peaking across the whole preview is an explicit
 * non-goal (see `docs/features/camera-capture.md`). [columns]x[rows] grid, mirroring [ZebraMask]'s own
 * coarse/blocky (not per-pixel) shape and reasoning — a cell is [edge] `true` where its local luma
 * gradient magnitude clears the classification threshold, i.e. sharp, high-contrast detail. Camera2
 * exposes no per-region depth-of-field/PDAF confidence data, so this contrast-based proxy (sharp edges
 * are, almost by definition, in focus) is the same class of approximation real on-device focus-peaking
 * implementations use, not a precise optical measurement.
 */
data class FocusPeakingMask(
    val columns: Int,
    val rows: Int,
    val edge: List<Boolean>,
) {
    companion object {

        /**
         * [luma] is row-major, one `0..255` sample per pixel, `[width]x[height]` — a plain [IntArray],
         * not an `android.graphics.Bitmap`/`android.media.Image.Plane`, so this stays unit-testable
         * without Robolectric, mirroring [ZebraMask.fromLumaPlane]'s own Android-type-free reasoning.
         *
         * Each grid cell's edge score is the largest absolute luma difference between horizontally or
         * vertically adjacent pixel pairs anywhere within that cell (a cheap gradient-magnitude proxy,
         * not a full Sobel convolution — the loupe crop this runs against is already small, at most a
         * couple hundred pixels per side, so this needn't be as bandwidth-conscious as
         * [ZebraMask.fromLumaPlane]'s full-preview-frame sampling budget) — [contrastThreshold] then
         * classifies the cell as [edge] `true` once that peak local contrast clears it.
         *
         * [width]/[height]/[columns]/[rows] need not divide evenly; cell boundaries are computed by
         * scaling (`col * width / columns`), the same remainder-spreading partition
         * [ZebraMask.fromLumaPlane] uses, so every pixel lands in exactly one cell with no gaps or
         * overlaps regardless of divisibility.
         */
        fun fromLuma(
            luma: IntArray,
            width: Int,
            height: Int,
            columns: Int,
            rows: Int,
            contrastThreshold: Int = DefaultContrastThreshold,
        ): FocusPeakingMask {
            require(luma.size == width * height) {
                "luma.size (${luma.size}) must equal width*height ($width*$height)"
            }
            require(width > 0 && height > 0 && columns > 0 && rows > 0)

            fun lumaAt(x: Int, y: Int) = luma[y * width + x]

            val cells = ArrayList<Boolean>(columns * rows)
            for (row in 0 until rows) {
                val yStart = row * height / rows
                val yEnd = max(yStart + 1, (row + 1) * height / rows)
                for (col in 0 until columns) {
                    val xStart = col * width / columns
                    val xEnd = max(xStart + 1, (col + 1) * width / columns)

                    var peakContrast = 0
                    for (y in yStart until yEnd) {
                        for (x in xStart until xEnd) {
                            val center = lumaAt(x, y)
                            if (x + 1 < xEnd) {
                                peakContrast = max(peakContrast, abs(center - lumaAt(x + 1, y)))
                            }
                            if (y + 1 < yEnd) {
                                peakContrast = max(peakContrast, abs(center - lumaAt(x, y + 1)))
                            }
                        }
                    }
                    cells += peakContrast >= contrastThreshold
                }
            }
            return FocusPeakingMask(columns, rows, cells)
        }

        /** Peak local-contrast floor (0..255 luma units) a cell must clear to be classified [edge]. */
        const val DefaultContrastThreshold = 40
    }
}
