package com.dragote.xcamera.feature.camera.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dragote.xcamera.feature.camera.domain.model.ZebraClipping
import com.dragote.xcamera.feature.camera.domain.model.ZebraMask
import com.dragote.xcamera.feature.camera.ui.theme.CameraChrome
import com.dragote.xcamera.shared.designsystem.theme.XCameraTheme

/**
 * A functional overlay over the live viewfinder feed carrying real shadow/highlight-clip information,
 * so [CameraChrome.ZebraShadow]/[CameraChrome.ZebraHighlight] deliberately stay real hues under this
 * otherwise-monochrome chrome (see this module's own design-agent brief on when color is allowed to
 * survive a flat pass).
 *
 * Diagonal-stripe clipping overlay — flags [ZebraMask.cells] classified [ZebraClipping.SHADOW]
 * (crushed blacks) or [ZebraClipping.HIGHLIGHT] (blown whites) while the ISO/shutter dial is being
 * dragged (see `CameraViewModel.zebraMask`, driven by `CameraController.setZebraAnalysisEnabled`).
 * Exactly two draw calls regardless of grid size: [mask]'s shadow cells are unioned into one [Path]
 * of *round*-rects (rather than sharp [Rect]s — softens the grid's inherently blocky cell edges
 * instead of reading as a hard mosaic), the highlight cells into another, each `clipPath`ed and
 * filled with one repeating diagonal `Brush.linearGradient` — plain Skia geometry, no per-cell
 * bitmap/shader work.
 *
 * The stripe brush's own offset advances every frame while [mask] is non-`null` ([marchPhase]),
 * giving the classic "marching" scrolling-stripe look pro monitors use rather than a static pattern
 * — [Offset.Zero]/[Offset]`(periodPx, periodPx)` both shift by the same `phase * periodPx` amount,
 * so the repeating gradient loops seamlessly at `phase = 1`.
 *
 * [mask] going `null` (drag ended) fades out over the same timing [ViewfinderGridOverlay] uses rather
 * than vanishing instantly — [displayedMask] keeps the last non-null mask around for the canvas to
 * keep drawing while [alpha] animates down, since (unlike the grid overlay's fixed geometry) there's
 * no stripe content left to fade once [mask] itself goes `null`. The march itself freezes (rather
 * than continuing through the fade) once [mask] goes `null`, since restarting/stopping the
 * [LaunchedEffect] driving it on every drag start/end is cheaper than animating it forever.
 */
@Composable
fun ZebraOverlay(mask: ZebraMask?, modifier: Modifier = Modifier) {
    var displayedMask by remember { mutableStateOf(mask) }
    LaunchedEffect(mask) { if (mask != null) displayedMask = mask }

    val alpha by animateFloatAsState(
        targetValue = if (mask != null) 1f else 0f,
        animationSpec = tween(durationMillis = 250, easing = CameraChrome.EaseStandard),
        label = "zebraOverlayAlpha",
    )

    val marchPhase = remember { Animatable(0f) }
    LaunchedEffect(mask != null) {
        if (mask != null) {
            while (true) {
                marchPhase.animateTo(1f, animationSpec = tween(MarchDurationMillis, easing = LinearEasing))
                marchPhase.snapTo(0f)
            }
        }
    }

    Canvas(modifier = modifier.fillMaxSize().alpha(alpha)) {
        val currentMask = displayedMask ?: return@Canvas
        if (currentMask.columns <= 0 || currentMask.rows <= 0) return@Canvas

        val cellWidth = size.width / currentMask.columns
        val cellHeight = size.height / currentMask.rows
        val cornerRadius = CornerRadius(
            minOf(CellCornerRadius.toPx(), cellWidth / 2f, cellHeight / 2f),
        )
        val shadowPath = Path()
        val highlightPath = Path()

        for (row in 0 until currentMask.rows) {
            for (col in 0 until currentMask.columns) {
                val clipping = currentMask.cells.getOrNull(row * currentMask.columns + col) ?: continue
                if (clipping == ZebraClipping.NONE) continue

                val roundRect = RoundRect(
                    rect = Rect(
                        left = col * cellWidth,
                        top = row * cellHeight,
                        right = (col + 1) * cellWidth,
                        bottom = (row + 1) * cellHeight,
                    ),
                    cornerRadius = cornerRadius,
                )
                (if (clipping == ZebraClipping.SHADOW) shadowPath else highlightPath).addRoundRect(roundRect)
            }
        }

        val periodPx = StripePeriod.toPx()
        val phaseOffset = Offset(marchPhase.value * periodPx, marchPhase.value * periodPx)
        clipPath(shadowPath) {
            drawRect(diagonalStripeBrush(CameraChrome.ZebraShadow, periodPx, phaseOffset))
        }
        clipPath(highlightPath) {
            drawRect(diagonalStripeBrush(CameraChrome.ZebraHighlight, periodPx, phaseOffset))
        }
    }
}

/** Repeat distance of one stripe pair (colored band + gap) along the gradient's diagonal axis. */
private val StripePeriod = 6.dp

/** Corner radius applied to each flagged grid cell — softens the grid's inherently blocky/angular
 *  edges without hiding the underlying cell shape. Clamped per-cell against half the cell's own
 *  width/height so a coarse grid (large cells) doesn't look under-rounded while a fine grid (small
 *  cells) doesn't over-round into circles/pills. */
private val CellCornerRadius = 5.dp

/** One marching-stripe loop's duration — slow enough to read as a gentle drift rather than a
 *  distracting strobe, matching the deliberately calm pace of this app's other idle animations. */
private const val MarchDurationMillis = 1200

/** A `45°` diagonal stripe: half [color] at [StripeAlpha], half transparent, tiled via
 *  [TileMode.Repeated] along a diagonal gradient axis rather than drawing individual stripe rects.
 *  [phaseOffset] shifts both gradient endpoints by the same amount, scrolling the tiled pattern
 *  without changing its shape — see [ZebraOverlay]'s own doc for why this loops seamlessly. */
private fun diagonalStripeBrush(color: Color, periodPx: Float, phaseOffset: Offset): Brush = Brush.linearGradient(
    0.00f to color.copy(alpha = StripeAlpha),
    0.50f to color.copy(alpha = StripeAlpha),
    0.50f to Color.Transparent,
    1.00f to Color.Transparent,
    start = phaseOffset,
    end = Offset(periodPx, periodPx) + phaseOffset,
    tileMode = TileMode.Repeated,
)

private const val StripeAlpha = 0.55f

@Preview(showBackground = true, widthDp = 220, heightDp = 320, backgroundColor = 0xFF0D1210)
@Composable
private fun ZebraOverlayPreview() {
    val columns = 16
    val rows = 24
    val cells = List(columns * rows) { i ->
        val col = i % columns
        val row = i / columns
        when {
            row < 2 -> ZebraClipping.HIGHLIGHT
            row >= rows - 2 -> ZebraClipping.SHADOW
            col == row % columns -> ZebraClipping.HIGHLIGHT
            else -> ZebraClipping.NONE
        }
    }
    XCameraTheme {
        ZebraOverlay(
            mask = ZebraMask(columns, rows, cells),
            modifier = Modifier.fillMaxSize().background(Color(0xFF0D1210)),
        )
    }
}

@Preview(showBackground = true, widthDp = 220, heightDp = 320, backgroundColor = 0xFF0D1210)
@Composable
private fun ZebraOverlayNoClippingPreview() {
    XCameraTheme {
        ZebraOverlay(
            mask = null,
            modifier = Modifier.fillMaxSize().background(Color(0xFF0D1210)),
        )
    }
}