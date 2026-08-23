package com.dragote.xcamera.feature.camera.ui.component.indicator

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dragote.xcamera.feature.camera.ui.theme.CameraChrome
import com.dragote.xcamera.shared.designsystem.theme.XCameraTheme

/**
 * Shown over the viewfinder while `CameraUiState.isCapturing` is true — built on [InfoPill] (see its
 * own doc for why this pill is solid black rather than translucent).
 */
@Composable
fun ExposingIndicator(visible: Boolean, durationLabel: String? = null, modifier: Modifier = Modifier) {
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 150, easing = CameraChrome.EaseStandard),
        label = "exposingIndicatorAlpha",
    )

    InfoPill(alpha = alpha, modifier = modifier) {
        val label = if (durationLabel != null) "EXPOSING · $durationLabel" else "EXPOSING…"
        Text(text = label, style = CameraChrome.leverLabelStyle(color = Color.White))
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
