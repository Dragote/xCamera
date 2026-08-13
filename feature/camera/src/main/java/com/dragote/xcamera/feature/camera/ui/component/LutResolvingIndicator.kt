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
import kotlin.math.max

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
 * text doesn't fit"). [RotatingBadge] fixes this by reserving a *square* footprint (side length = the
 * pill's own natural un-rotated width, always its longer dimension for a wide/short text pill) from its
 * parent instead of the pill's actual non-square bounds, then rotating the entire pill — background,
 * clip, padding, text — as a single image within that square; since the square's side already covers
 * the pill's longer dimension, the pill can never exceed the reserved footprint at any rotation.
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
 * Measures [content] at its own natural (unconstrained) size, then reports a *square* footprint — side
 * length = the larger of that natural width/height — to this composable's own parent, instead of
 * [content]'s real (non-square) size, and rotates+centers [content] by [rotationDegrees] within that
 * square. See [LutResolvingIndicator]'s own doc for why: a wide/short pill's background needs to rotate
 * together with its text, and a square reservation is what keeps the whole rotated pill inside its
 * allotted space at every quadrant without needing to separately re-layout the pill's own content for
 * each orientation.
 */
@Composable
private fun RotatingBadge(rotationDegrees: Float, content: @Composable () -> Unit) {
    Layout(content = { Box(modifier = Modifier.graphicsLayer { rotationZ = rotationDegrees }) { content() } }) { measurables, _ ->
        val placeable = measurables.first().measure(Constraints())
        val side = max(placeable.width, placeable.height)
        layout(side, side) {
            placeable.place(x = (side - placeable.width) / 2, y = (side - placeable.height) / 2)
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
