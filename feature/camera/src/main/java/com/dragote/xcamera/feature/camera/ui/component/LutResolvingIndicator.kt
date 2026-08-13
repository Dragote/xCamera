package com.dragote.xcamera.feature.camera.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
 * Unlike [ViewfinderThumbnailChip]/`HistogramOverlay`, this pill's own *position* is fixed by the
 * caller (`ui/CameraScreen`'s `Modifier.align(...)`), not corner-hopping, and its pill shape is
 * deliberately wider than it is tall — rotating the whole pill (background included) 90°/270° the way
 * [ViewfinderThumbnailChip] rotates its square glyph-box would flip it into a tall sliver overflowing
 * past the corner it's pinned near. Instead only the *content* (the spinner + text `Row`) counter-rotates
 * in place, exactly per this component's own name — the pill's own background/position never move or
 * reshape, only what's legible inside it turns to stay upright to the user.
 */
@Composable
fun LutResolvingIndicator(visible: Boolean, modifier: Modifier = Modifier) {
    val quadrant by rememberDeviceOrientationQuadrant()
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 150, easing = CameraChrome.EaseStandard),
        label = "lutResolvingIndicatorAlpha",
    )

    Box(
        modifier = modifier
            .alpha(alpha)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.graphicsLayer { rotationZ = counterRotationDegrees(quadrant) },
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

@Preview(showBackground = true, backgroundColor = 0xFF0D1210)
@Composable
private fun LutResolvingIndicatorPreview() {
    XCameraTheme {
        Box(modifier = Modifier.padding(24.dp)) {
            LutResolvingIndicator(visible = true)
        }
    }
}
