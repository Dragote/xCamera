package com.dragote.xcamera.feature.camera.domain.model

import com.dragote.xcamera.shared.common.domain.model.FocusPeakingSensitivity
import kotlin.math.abs
import kotlin.math.max

/**
 * Loupe-local edge/focus-assist highlight (Pixel-style focus peaking) — computed once per refreshed
 * magnified loupe crop while a manual-focus hold gesture is in progress, never for the full
 * viewfinder: continuous/always-on focus peaking across the whole preview is an explicit non-goal (see
 * `.claude/docs/features/camera-capture.md`). [columns]x[rows] grid — callers (see `ui/CameraScreen`'s
 * `focusPeakingMaskFromBitmap`) size it one cell per source pixel so [FocusRing] can render it as a
 * scaled-up bitmap overlay tracing a thin contour around sharp detail, unlike [ZebraMask]'s own
 * deliberately coarse/blocky per-cell-rectangle rendering. A cell is [edge] `true` where its local luma
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
                            // Compared against the image-global neighbor, not clamped to this cell's own
                            // xEnd/yEnd — at coarse grid resolutions those are usually the same pixel, but
                            // at 1-pixel-per-cell resolution (the loupe's per-pixel contour use, see the
                            // class doc) xEnd == xStart + 1, so a cell-clamped bound would never see a
                            // neighbor at all and every cell would classify as flat. A transition is always
                            // attributed to the cell containing its lower-x/lower-y pixel, so it's still
                            // flagged exactly once even when it falls on a cell boundary.
                            if (x + 1 < width) {
                                peakContrast = max(peakContrast, abs(center - lumaAt(x + 1, y)))
                            }
                            if (y + 1 < height) {
                                peakContrast = max(peakContrast, abs(center - lumaAt(x, y + 1)))
                            }
                        }
                    }
                    cells += peakContrast >= contrastThreshold
                }
            }
            return FocusPeakingMask(columns, rows, cells)
        }

        /** Peak local-contrast floor (0..255 luma units) a cell must clear to be classified [edge] at
         *  [FocusPeakingSensitivity.MEDIUM] — also the fallback when no explicit [contrastThreshold]
         *  is passed. Raised from the original `40`: that value triggered on edges that were still
         *  visibly short of true peak focus during a manual-focus rack, misleading users into locking
         *  focus too early — see [contrastThreshold] for the full LOW/MEDIUM/HIGH sensitivity range
         *  this now lives alongside. */
        const val DefaultContrastThreshold = 50

        /** [sensitivity]'s corresponding contrast floor for [fromLuma]'s own [contrastThreshold]
         *  parameter. Sensitivity and contrast floor are inversely related — [FocusPeakingSensitivity.LOW]
         *  requires *more* contrast (a higher floor) before flagging anything as sharp, trading a later
         *  trigger during a focus rack for fewer premature "that's sharp enough" reads;
         *  [FocusPeakingSensitivity.HIGH] flags weaker edges, closer to the original pre-tuning `40`. */
        fun contrastThreshold(sensitivity: FocusPeakingSensitivity): Int = when (sensitivity) {
            FocusPeakingSensitivity.LOW -> 65
            FocusPeakingSensitivity.MEDIUM -> DefaultContrastThreshold
            FocusPeakingSensitivity.HIGH -> 35
        }
    }
}
