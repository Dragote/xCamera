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
import androidx.compose.ui.tooling.preview.Preview
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
 * rotates, so it stays at the viewfinder's perceived top-left; [counterRotationDegrees] on top of that
 * keeps the pill's own text upright at each of those corners, the same way it always did.
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
                Box(
                    modifier = Modifier
                        .alpha(alpha)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.graphicsLayer { rotationZ = counterRotationDegrees(activeQuadrant) },
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                            color = Color.White,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "APPLYING LUT…", style = CameraChrome.leverLabelStyle().copy(color = Color.White))
                    }
                }
            }
        }
    }
}

private val CornerInset = 12.dp

@Preview(showBackground = true, backgroundColor = 0xFF0D1210)
@Composable
private fun LutResolvingIndicatorPreview() {
    XCameraTheme {
        Box(modifier = Modifier.padding(24.dp).size(300.dp, 200.dp)) {
            LutResolvingIndicator(visible = true, modifier = Modifier.fillMaxSize())
        }
    }
}
