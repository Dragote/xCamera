package com.dragote.xcamera.feature.camera.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dragote.xcamera.feature.camera.ui.theme.CameraChrome
import com.dragote.xcamera.shared.designsystem.theme.XCameraTheme

/**
 * Shown over the viewfinder while `CameraUiState.isCapturing` is true. Manual long exposures no
 * longer visibly freeze the preview (see `CameraController`'s preview-exposure-time cap), so
 * without this a multi-second capture would otherwise look like the shutter button press did
 * nothing at all. [durationLabel] (e.g. `formatShutterSpeed` of the selected manual shutter stop)
 * is optional — omitted for an auto-exposure capture, where there's no pinned duration to show.
 */
@Composable
fun ExposingIndicator(visible: Boolean, durationLabel: String? = null, modifier: Modifier = Modifier) {
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 150, easing = CameraChrome.EaseStandard),
        label = "exposingIndicatorAlpha",
    )

    Box(
        modifier = modifier
            .alpha(alpha)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        val label = if (durationLabel != null) "EXPOSING · $durationLabel" else "EXPOSING…"
        Text(text = label, style = CameraChrome.leverLabelStyle().copy(color = Color(0xFFFFE3D2)))
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1210)
@Composable
private fun ExposingIndicatorPreview() {
    XCameraTheme {
        Box(modifier = Modifier.padding(24.dp)) {
            ExposingIndicator(visible = true, durationLabel = "8s")
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1210)
@Composable
private fun ExposingIndicatorNoDurationPreview() {
    XCameraTheme {
        Box(modifier = Modifier.padding(24.dp)) {
            ExposingIndicator(visible = true)
        }
    }
}
