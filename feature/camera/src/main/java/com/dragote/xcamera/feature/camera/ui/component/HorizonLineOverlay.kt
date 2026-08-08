package com.dragote.xcamera.feature.camera.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dragote.xcamera.feature.camera.ui.theme.CameraChrome
import com.dragote.xcamera.shared.designsystem.theme.XCameraTheme

/**
 * Always-on artificial-horizon indicator, drawn as a bubble-level pair rather than a single line:
 * a dim, full-width [StaticLine] that always reads as perfectly horizontal to the viewer, and a
 * bolder, shorter [DynamicLine] that continuously tracks the device's real-world roll. Aligning the
 * two — the dynamic line sitting flush over the static one — means "level," same as a physical bubble
 * level's fixed reference mark and floating bubble. Styled like [HistogramOverlay]'s marks
 * ([CameraChrome.HistogramMarkColor], round-capped) per the feature request, not like
 * [ViewfinderGridOverlay]'s plain hairlines, even though the "edge-to-edge line over the preview"
 * mechanics are closer to the grid's.
 *
 * The Activity is portrait-locked (see AndroidManifest), so — same as [HistogramOverlay] and
 * [ViewfinderThumbnailChip] — this screen's own layout never physically rotates; only the device does,
 * in the user's hand. That means *both* lines need [counterRotationDegrees] applied, not just the
 * dynamic one:
 * - [StaticLine] uses only the coarse, hysteresis-debounced [DeviceOrientationState.quadrant] term
 *   (`counterRotationDegrees(quadrant)`, exactly [HistogramOverlay]'s bars) — this is what keeps it
 *   *always* horizontal from the user's own point of view as they physically turn the phone, including
 *   through a 90° rotation into landscape. (A prior version of this overlay drew a single line using
 *   only the small residual tilt offset from the quadrant center, never this coarse term — so turning
 *   the phone 90° left the line still near-0°-rotated in fixed canvas coordinates, which reads as
 *   *vertical* to a viewer who has themselves turned 90°. That was the shipped bug this redesign fixes.)
 * - [DynamicLine] uses the *continuous* counter-rotation of [DeviceOrientationState.smoothedOrientationDegrees]
 *   (`counterRotationDegrees(smoothed)`), not a small residual offset from the quadrant center — when
 *   the device is exactly level, `smoothed ≈ quadrant`, so `counterRotationDegrees(smoothed) ≈
 *   counterRotationDegrees(quadrant)` and the two lines visually coincide; as the device tilts away
 *   from level, the two values diverge by the tilt amount and the lines visibly separate, angularly,
 *   around their shared center. [DeviceOrientationState.smoothedOrientationDegrees] is already
 *   low-pass-filtered ([smoothOrientationDegrees]) before it reaches here — see that function's own doc
 *   for why the dynamic line needed smoothing at all (the raw accelerometer-derived orientation is
 *   jittery enough on its own that drawing it directly shook visibly on small hand tremors).
 *
 * No gesture/lever gates this, same as [HistogramOverlay] — it's a passive compositional aid, not a
 * mode the user opts into.
 */
@Composable
fun HorizonLineOverlay(modifier: Modifier = Modifier) {
    val orientation by rememberDeviceOrientationState()
    HorizonLines(
        staticRotationDegrees = counterRotationDegrees(orientation.quadrant),
        dynamicRotationDegrees = counterRotationDegrees(orientation.smoothedOrientationDegrees),
        modifier = modifier,
    )
}

/** The actual draw call, factored out from [HorizonLineOverlay] so its two [Preview]s can drive it
 *  with fixed rotations instead of a live sensor reading. */
@Composable
private fun HorizonLines(
    staticRotationDegrees: Float,
    dynamicRotationDegrees: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val marginPx = LineSideMargin.toPx()
        rotate(degrees = staticRotationDegrees, pivot = center) {
            drawLine(
                color = StaticLineColor,
                start = Offset(marginPx, center.y),
                end = Offset(size.width - marginPx, center.y),
                strokeWidth = StaticLineStrokeWidth.toPx(),
                cap = StrokeCap.Round,
            )
        }
        rotate(degrees = dynamicRotationDegrees, pivot = center) {
            val halfSpan = size.width * DynamicLineWidthFraction / 2f
            drawLine(
                color = DynamicLineColor,
                start = Offset(center.x - halfSpan, center.y),
                end = Offset(center.x + halfSpan, center.y),
                strokeWidth = DynamicLineStrokeWidth.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

/** Margin off each side of the frame the static reference line stops short of — modest, not a large
 *  inset, per the feature request that it "spans the frame." */
private val LineSideMargin = 24.dp

/** Dim — this is the fixed backdrop the dynamic line is read against, not the primary "live" element,
 *  so it should sit visually behind [DynamicLine]/[DynamicLineColor] rather than compete with it. */
private val StaticLineColor = CameraChrome.HistogramMarkColor.copy(alpha = 0.4f)

/** Thinner than [DynamicLineStrokeWidth] — a plain hairline reference mark. */
private val StaticLineStrokeWidth = 1.5.dp

/** Fraction of the frame's width the dynamic tilt-readout line spans, centered on the same point as
 *  the static line. */
private const val DynamicLineWidthFraction = 0.4f

/** Higher alpha than [StaticLineColor] — this is the "live" readout, meant to read as the prominent
 *  element against the dimmer static backdrop. */
private val DynamicLineColor = CameraChrome.HistogramMarkColor.copy(alpha = 0.9f)

/** Thicker than [StaticLineStrokeWidth] and than [ViewfinderGridOverlay]'s 1.dp hairlines — this is
 *  the primary indicator the user reads at a glance. */
private val DynamicLineStrokeWidth = 3.5.dp

@Preview(showBackground = true, widthDp = 220, heightDp = 320, backgroundColor = 0xFF0D1210)
@Composable
private fun HorizonLineOverlayLevelPreview() {
    XCameraTheme {
        HorizonLines(
            staticRotationDegrees = 0f,
            dynamicRotationDegrees = 0f,
            modifier = Modifier.fillMaxSize().background(Color(0xFF0D1210)),
        )
    }
}

@Preview(showBackground = true, widthDp = 220, heightDp = 320, backgroundColor = 0xFF0D1210)
@Composable
private fun HorizonLineOverlayTiltedPreview() {
    XCameraTheme {
        HorizonLines(
            staticRotationDegrees = 0f,
            dynamicRotationDegrees = 12f,
            modifier = Modifier.fillMaxSize().background(Color(0xFF0D1210)),
        )
    }
}
