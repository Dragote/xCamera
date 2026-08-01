package com.dragote.xcamera.feature.camera.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
import com.dragote.xcamera.feature.camera.domain.model.ManualControlTarget
import com.dragote.xcamera.feature.camera.ui.theme.CameraChrome
import com.dragote.xcamera.shared.designsystem.theme.XCameraTheme

/**
 * The two semi-transparent "ISO"/"SHUTTER" buttons that appear over the viewfinder while manual
 * exposure mode is active, letting the user pick which parameter `ManualExposureDial` currently
 * shows/drives. Bound directly to [visible] = `CameraUiState.manualModeEnabled` — that flag already
 * turns true the instant the user touches the dial and only turns false again via `ModeLever`'s
 * exit-to-auto, so these stay on screen for the whole manual session (not just while a finger is
 * down), matching [ViewfinderGridOverlay]'s own fade-in/out convention (`.25s ease`) rather than a
 * hard show/hide.
 */
@Composable
fun ManualExposureTargetSelector(
    visible: Boolean,
    target: ManualControlTarget,
    onTargetSelected: (ManualControlTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 250, easing = CameraChrome.EaseStandard),
        label = "manualExposureTargetSelectorAlpha",
    )

    Row(
        modifier = modifier.alpha(alpha),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TargetButton(
            label = "ISO",
            selected = target == ManualControlTarget.ISO,
            enabled = visible,
            onClick = { onTargetSelected(ManualControlTarget.ISO) },
        )
        TargetButton(
            label = "SHUTTER",
            selected = target == ManualControlTarget.SHUTTER_SPEED,
            enabled = visible,
            onClick = { onTargetSelected(ManualControlTarget.SHUTTER_SPEED) },
        )
    }
}

@Composable
private fun TargetButton(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val background = if (selected) CameraChrome.Accent.copy(alpha = 0.55f) else Color.Black.copy(alpha = 0.35f)
    val borderColor = Color.White.copy(alpha = if (selected) 0.4f else 0.15f)
    val textColor = if (selected) Color(0xFFFFE3D2) else CameraChrome.LabelColor

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text = label, style = CameraChrome.leverLabelStyle().copy(color = textColor))
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1210)
@Composable
private fun ManualExposureTargetSelectorPreview() {
    XCameraTheme {
        Row(modifier = Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            ManualExposureTargetSelector(visible = true, target = ManualControlTarget.ISO, onTargetSelected = {})
            ManualExposureTargetSelector(visible = true, target = ManualControlTarget.SHUTTER_SPEED, onTargetSelected = {})
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1210)
@Composable
private fun ManualExposureTargetSelectorHiddenPreview() {
    XCameraTheme {
        ManualExposureTargetSelector(
            visible = false,
            target = ManualControlTarget.ISO,
            onTargetSelected = {},
            modifier = Modifier.padding(24.dp),
        )
    }
}
