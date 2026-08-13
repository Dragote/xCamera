package com.dragote.xcamera.feature.camera.ui.component

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.dragote.xcamera.feature.camera.ui.theme.CameraChrome
import com.dragote.xcamera.shared.designsystem.theme.XCameraTheme

/**
 * Shown over the viewfinder while `CameraViewModel.isLutResolving` is true (issue #43 follow-up) — the
 * per-chip spinner on `feature:settings`' own `SettingsScreen` only covers the moment the user is still
 * on that screen; once they've navigated back to the camera screen (the common case, since that's where
 * a LUT's actual effect is visible) there was previously no indication at all that grading was still
 * being applied, so a resolve that took a beat just looked like nothing happened. Mirrors
 * [ExposingIndicator]'s own small-pill treatment (same background/padding/corner radius) rather than a
 * full-screen loading overlay — this is a brief, background operation, not a blocking one.
 *
 * **Corner-hops like [ViewfinderThumbnailChip], not fixed like an earlier version of this component
 * was** — this app locks `MainActivity` to portrait (see root `AndroidManifest.xml`), so the window
 * itself never physically rotates; a Compose `Alignment.TopStart` therefore always lands on the same
 * fixed *device* corner, not on whatever corner currently reads as "top-left" to a user holding the
 * phone rotated for a landscape shot. [cornerForQuadrant] (same mechanism [ViewfinderThumbnailChip]/
 * `HistogramOverlay` already use) re-anchors the whole pill to a different Compose corner as the device
 * rotates, so it stays at the viewfinder's perceived top-left.
 *
 * **The whole pill (background *and* content) rotates together as one rigid unit** via [RotatingBadge],
 * not just the inner text/spinner `Row` the way an earlier version of this component did — rotating only
 * the content left the *background* box sized/shaped for the un-rotated (wide, short) layout, so at a
 * 90°/270° quadrant the now-vertically-oriented text visibly overflowed past a background that never
 * itself reshaped to match (confirmed on-device: "the background just moves, without flipping, and the
 * text doesn't fit"). [RotatingBadge] fixes this by reserving a footprint from its parent that's
 * *transposed* (not just squared up) to match the rotation — see [RotatingBadge]'s own doc for why a
 * square reservation was tried first and rejected (it left a large empty gap around the pill at 0°/180°)
 * — then rotates the entire pill — background, clip, padding, text — as a single image within that
 * exactly-sized footprint.
 *
 * [modifier] should size this to the full area it's allowed to roam across corners of (e.g.
 * `Modifier.fillMaxSize()` over the whole viewfinder), matching [ViewfinderThumbnailChip]'s own calling
 * convention — not to the pill's own small size.
 */
@Composable
fun LutResolvingIndicator(visible: Boolean, modifier: Modifier = Modifier) {
    val quadrant by rememberDeviceOrientationQuadrant()
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 150, easing = CameraChrome.EaseStandard),
        label = "lutResolvingIndicatorAlpha",
    )

    Box(modifier = modifier.padding(CornerInset)) {
        Crossfade(
            targetState = quadrant,
            modifier = Modifier.fillMaxSize(),
            animationSpec = tween(durationMillis = 300, easing = CameraChrome.EaseStandard),
            label = "lutResolvingIndicatorCorner",
        ) { activeQuadrant ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = cornerForQuadrant(Alignment.TopStart, activeQuadrant),
            ) {
                RotatingBadge(rotationDegrees = counterRotationDegrees(activeQuadrant)) {
                    Box(
                        modifier = Modifier
                            .alpha(alpha)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 1.5.dp,
                                color = Color.White,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "APPLYING LUT…",
                                style = CameraChrome.leverLabelStyle().copy(color = Color.White),
                            )
                        }
                    }
                }
            }
        }
    }
}

private val CornerInset = 12.dp

/**
 * Measures [content] at its own natural (unconstrained) size, then reports a footprint to this
 * composable's own parent that's *transposed* (width/height swapped) whenever [rotationDegrees] is a
 * quarter turn (90°/270°) — exactly [content]'s real un-rotated size otherwise — and rotates+centers
 * [content] within that footprint. An *always-square* (side = the larger natural dimension) footprint
 * was tried first and rejected: it left a large, visibly wrong gap around the pill at 0°/180° (a wide,
 * short pill centered inside a square sized to its own width leaves tall empty margins above/below it)
 * — confirmed on-device. Transposing instead of squaring reserves exactly the pill's own bounding box at
 * every quadrant (identical to its natural size at 0°/180°, swapped at 90°/270°), so there's never slack
 * space to leave a gap, while still guaranteeing the rotated pill can't exceed its reserved footprint —
 * `rotationDegrees == 90f`/`270f` is a safe exact-float comparison here the same way
 * `HistogramOverlay`'s own `HistogramMarks.quarterTurned` already relies on it: both values only ever
 * come from [counterRotationDegrees]/[rememberDeviceOrientationQuadrant]'s discrete quadrant snapping,
 * never arithmetic that could introduce floating-point drift.
 */
@Composable
private fun RotatingBadge(rotationDegrees: Float, content: @Composable () -> Unit) {
    Layout(content = { Box(modifier = Modifier.graphicsLayer { rotationZ = rotationDegrees }) { content() } }) { measurables, _ ->
        val placeable = measurables.first().measure(Constraints())
        val quarterTurned = rotationDegrees == 90f || rotationDegrees == 270f
        val footprintWidth = if (quarterTurned) placeable.height else placeable.width
        val footprintHeight = if (quarterTurned) placeable.width else placeable.height
        layout(footprintWidth, footprintHeight) {
            placeable.place(
                x = (footprintWidth - placeable.width) / 2,
                y = (footprintHeight - placeable.height) / 2,
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1210)
@Composable
private fun LutResolvingIndicatorPreview() {
    XCameraTheme {
        Box(modifier = Modifier.padding(24.dp).size(300.dp, 200.dp)) {
            LutResolvingIndicator(visible = true, modifier = Modifier.fillMaxSize())
        }
    }
}
