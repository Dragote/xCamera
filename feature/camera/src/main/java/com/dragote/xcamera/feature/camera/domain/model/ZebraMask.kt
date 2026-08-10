package com.dragote.xcamera.feature.camera.domain.model

import java.nio.ByteBuffer
import kotlin.math.max

/** One grid cell's clipping state — [SHADOW] crushed toward black, [HIGHLIGHT] blown toward white. */
enum class ZebraClipping { NONE, SHADOW, HIGHLIGHT }

/**
 * A coarse [columns]x[rows] grid over the viewfinder, each cell classified by [ZebraMask.fromLumaPlane]
 * from the live preview's own luma plane — deliberately blocky/grid-shaped rather than per-pixel,
 * matching the reference implementation's own grid-like clipping mask. [cells] is row-major (index =
 * `row * columns + col`).
 */
data class ZebraMask(
    val columns: Int,
    val rows: Int,
    val cells: List<ZebraClipping>,
) {
    companion object {

        /**
         * Buckets a `YUV_420_888` luma plane (plane 0 — no need to touch the chroma planes at all,
         * clipping is purely a luma concept) into a [columns]x[rows] grid: each cell is classified by
         * majority vote of its own *sampled* pixels against [shadowLumaThreshold]/
         * [highlightLumaThreshold], not a per-cell average — an average would wash out a genuinely
         * clipped patch sitting inside an otherwise mid-toned cell, which is exactly the case a zebra
         * overlay exists to catch.
         *
         * Each cell samples at most [SamplesPerCellAxis]x[SamplesPerCellAxis] evenly-spaced pixels
         * rather than visiting every pixel in the cell — this plane now comes straight from the live
         * preview stream (potentially full preview resolution, not a small dedicated capture), and
         * this call runs on every delivered frame (throttled — see `CameraController`'s own doc), so
         * bounding the per-cell sample count keeps the total cost independent of source resolution.
         * For a cell smaller than the sample budget (the common case in this class's own unit tests,
         * and for any grid finer than the sample budget in each dimension) every pixel still gets
         * visited — the stride only kicks in once a cell has more pixels than the budget.
         *
         * [rowStride]/[pixelStride] come straight from `Image.Plane.rowStride`/`pixelStride` (the row
         * stride in particular is very commonly larger than [width] — sensors pad rows to an alignment
         * boundary) — indexing through them rather than assuming a tightly packed buffer is required for
         * correctness on real devices, not just a defensive nicety.
         *
         * [width]/[height]/[columns]/[rows] need not divide evenly; cell boundaries are computed by
         * scaling (`col * width / columns`), the standard remainder-spreading partition so every pixel
         * lands in exactly one cell with no gaps or overlaps regardless of divisibility.
         *
         * Pure and Android/Camera2-type-free (a plain [ByteBuffer], not `android.media.Image.Plane`) so
         * this is fully unit-testable without Robolectric.
         */
        fun fromLumaPlane(
            buffer: ByteBuffer,
            rowStride: Int,
            pixelStride: Int,
            width: Int,
            height: Int,
            columns: Int,
            rows: Int,
            shadowLumaThreshold: Int = 10,
            highlightLumaThreshold: Int = 245,
        ): ZebraMask {
            val cells = ArrayList<ZebraClipping>(columns * rows)
            for (row in 0 until rows) {
                val yStart = row * height / rows
                val yEnd = (row + 1) * height / rows
                val yStep = max(1, (yEnd - yStart) / SamplesPerCellAxis)
                for (col in 0 until columns) {
                    val xStart = col * width / columns
                    val xEnd = (col + 1) * width / columns
                    val xStep = max(1, (xEnd - xStart) / SamplesPerCellAxis)

                    var shadowCount = 0
                    var highlightCount = 0
                    var total = 0
                    var y = yStart
                    while (y < yEnd) {
                        val rowOffset = y * rowStride
                        var x = xStart
                        while (x < xEnd) {
                            val luma = buffer.get(rowOffset + x * pixelStride).toInt() and 0xFF
                            when {
                                luma <= shadowLumaThreshold -> shadowCount++
                                luma >= highlightLumaThreshold -> highlightCount++
                            }
                            total++
                            x += xStep
                        }
                        y += yStep
                    }

                    cells += when {
                        total == 0 -> ZebraClipping.NONE
                        shadowCount * 2 > total -> ZebraClipping.SHADOW
                        highlightCount * 2 > total -> ZebraClipping.HIGHLIGHT
                        else -> ZebraClipping.NONE
                    }
                }
            }
            return ZebraMask(columns, rows, cells)
        }

        /** Sample budget per cell axis — see [fromLumaPlane]'s own doc. 8x8 = 64 samples/cell is
         *  comfortably enough for a stable majority vote while keeping even `CameraController`'s
         *  48x64 grid's total per-frame cost (≈197k luma reads) trivial regardless of the source
         *  frame's real resolution. */
        private const val SamplesPerCellAxis = 8
    }

    /**
     * Rotates the grid clockwise by [degrees] (must be a multiple of 90 — Camera2's own
     * `CameraCharacteristics.SENSOR_ORIENTATION` is always 0/90/180/270, the only caller of this).
     * Needed because [fromLumaPlane]'s source buffer comes from an `ImageReader` — unlike a
     * `TextureView`'s own on-screen rendering (which can get producer-side pre-rotation from the
     * window/`SurfaceTexture` compositor), an `ImageReader` surface gets no automatic rotation, so its
     * content is always in the sensor's native (physically landscape, on essentially every phone)
     * pixel layout regardless of how the device is held or how the preview is displayed.
     * `CameraController` applies this with the same `SENSOR_ORIENTATION` angle `captureStillJpeg`
     * bakes into `JPEG_ORIENTATION` for the still capture, so the mask lines up with what's actually
     * shown in the (fixed-portrait) viewfinder. A 90/270 rotation swaps [columns]/[rows]; 0/180 keep
     * them as-is.
     */
    fun rotatedBy(degrees: Int): ZebraMask = when (((degrees % 360) + 360) % 360) {
        90 -> rotate90()
        180 -> rotate180()
        270 -> rotate270()
        else -> this
    }

    private fun rotate90(): ZebraMask {
        val newColumns = rows
        val newRows = columns
        val newCells = ArrayList<ZebraClipping>(cells.size)
        for (newRow in 0 until newRows) {
            for (newCol in 0 until newColumns) {
                val oldCol = newRow
                val oldRow = rows - 1 - newCol
                newCells += cells[oldRow * columns + oldCol]
            }
        }
        return ZebraMask(newColumns, newRows, newCells)
    }

    private fun rotate180(): ZebraMask {
        val newCells = ArrayList<ZebraClipping>(cells.size)
        for (newRow in 0 until rows) {
            for (newCol in 0 until columns) {
                val oldCol = columns - 1 - newCol
                val oldRow = rows - 1 - newRow
                newCells += cells[oldRow * columns + oldCol]
            }
        }
        return ZebraMask(columns, rows, newCells)
    }

    private fun rotate270(): ZebraMask {
        val newColumns = rows
        val newRows = columns
        val newCells = ArrayList<ZebraClipping>(cells.size)
        for (newRow in 0 until newRows) {
            for (newCol in 0 until newColumns) {
                val oldRow = newCol
                val oldCol = columns - 1 - newRow
                newCells += cells[oldRow * columns + oldCol]
            }
        }
        return ZebraMask(newColumns, newRows, newCells)
    }
}
