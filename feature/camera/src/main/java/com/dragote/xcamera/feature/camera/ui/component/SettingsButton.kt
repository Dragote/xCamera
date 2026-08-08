package com.dragote.xcamera.feature.camera.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dragote.xcamera.feature.camera.ui.theme.CameraChrome
import com.dragote.xcamera.shared.designsystem.component.LeverTrackHeight
import com.dragote.xcamera.shared.designsystem.component.LeverTrackWidth
import com.dragote.xcamera.shared.designsystem.theme.XCameraTheme
import com.dragote.xcamera.shared.designsystem.theme.recessedTrackShadow

/**
 * A static navigation button matching FLASH/GRID/MODE's own chrome ([LeverTrackWidth]/
 * [LeverTrackHeight] track, [CameraChrome.leverLabelStyle] label), but deliberately not built on
 * `LeverSwitch`/`LeverBody` — those are genuine two-state toggles with a sliding knob, and this has
 * no checked state to fake. Opens `feature:settings`'s screen (see the route this is wired to in
 * `ui/CameraScreen`).
 */
@Composable
fun SettingsButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            modifier = Modifier
                .size(LeverTrackWidth, LeverTrackHeight)
                .clip(RoundedCornerShape(LeverTrackHeight / 2))
                .background(CameraChrome.TrackOffGradient)
                .recessedTrackShadow()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onClick()
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.85f),
            )
        }
        Text(text = "SETTINGS", style = CameraChrome.leverLabelStyle())
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF201F1D)
@Composable
private fun SettingsButtonPreview() {
    XCameraTheme {
        Row(modifier = Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            SettingsButton(onClick = {})
        }
    }
}
