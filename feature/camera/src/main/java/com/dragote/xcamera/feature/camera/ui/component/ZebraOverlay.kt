package com.dragote.xcamera.feature.camera.ui.component

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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
 * Diagonal-stripe clipping overlay — flags [ZebraMask.cells] classified [ZebraClipping.SHADOW]
 * (crushed blacks) or [ZebraClipping.HIGHLIGHT] (blown whites) while the ISO/shutter dial is being
 * dragged (see `CameraViewModel.zebraMask`, driven by `CameraController.setZebraAnalysisEnabled`).
 * Exactly two draw calls regardless of grid size: [mask]'s shadow cells are unioned into one [Path],
 * the highlight cells into another, each `clipPath`ed and filled with one repeating diagonal
 * `Brush.linearGradient` — plain Skia geometry, no per-cell bitmap/shader work (see issue #6's own
 * perf reasoning).
 *
 * [mask] going `null` (drag ended) fades out over the same timing [ViewfinderGridOverlay] uses rather
 * than vanishing instantly — [displayedMask] keeps the last non-null mask around for the canvas to
 * keep drawing while [alpha] animates down, since (unlike the grid overlay's fixed geometry) there's
 * no stripe content left to fade once [mask] itself goes `null`.
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

    Canvas(modifier = modifier.fillMaxSize().alpha(alpha)) {
        val currentMask = displayedMask ?: return@Canvas
        if (currentMask.columns <= 0 || currentMask.rows <= 0) return@Canvas

        val cellWidth = size.width / currentMask.columns
        val cellHeight = size.height / currentMask.rows
        val shadowPath = Path()
        val highlightPath = Path()

        for (row in 0 until currentMask.rows) {
            for (col in 0 until currentMask.columns) {
                val clipping = currentMask.cells.getOrNull(row * currentMask.columns + col) ?: continue
                if (clipping == ZebraClipping.NONE) continue

                val rect = Rect(
                    left = col * cellWidth,
                    top = row * cellHeight,
                    right = (col + 1) * cellWidth,
                    bottom = (row + 1) * cellHeight,
                )
                (if (clipping == ZebraClipping.SHADOW) shadowPath else highlightPath).addRect(rect)
            }
        }

        val periodPx = StripePeriod.toPx()
        clipPath(shadowPath) { drawRect(diagonalStripeBrush(CameraChrome.ZebraShadow, periodPx)) }
        clipPath(highlightPath) { drawRect(diagonalStripeBrush(CameraChrome.ZebraHighlight, periodPx)) }
    }
}

/** Repeat distance of one stripe pair (colored band + gap) along the gradient's diagonal axis. */
private val StripePeriod = 10.dp

/** A `45°` diagonal stripe: half [color] at [StripeAlpha], half transparent, tiled via
 *  [TileMode.Repeated] along a diagonal gradient axis rather than drawing individual stripe rects. */
private fun diagonalStripeBrush(color: Color, periodPx: Float): Brush = Brush.linearGradient(
    0.00f to color.copy(alpha = StripeAlpha),
    0.50f to color.copy(alpha = StripeAlpha),
    0.50f to Color.Transparent,
    1.00f to Color.Transparent,
    start = Offset.Zero,
    end = Offset(periodPx, periodPx),
    tileMode = TileMode.Repeated,
)

private const val StripeAlpha = 0.55f

@Preview(showBackground = true, widthDp = 220, heightDp = 320, backgroundColor = 0xFF0D1210)
@Composable
private fun ZebraOverlayPreview() {
    val columns = 8
    val rows = 12
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
