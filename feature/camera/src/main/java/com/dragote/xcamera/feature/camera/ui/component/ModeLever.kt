package com.dragote.xcamera.feature.camera.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dragote.xcamera.feature.camera.ui.theme.CameraChrome
import com.dragote.xcamera.shared.designsystem.component.LeverBody
import com.dragote.xcamera.shared.designsystem.component.LeverGlyph
import com.dragote.xcamera.shared.designsystem.component.LeverKnobHeight
import com.dragote.xcamera.shared.designsystem.component.LeverKnobInset
import com.dragote.xcamera.shared.designsystem.component.LeverKnobTravel
import com.dragote.xcamera.shared.designsystem.component.LeverKnobWidth
import com.dragote.xcamera.shared.designsystem.component.LeverTrackHeight
import com.dragote.xcamera.shared.designsystem.component.LeverTrackWidth
import com.dragote.xcamera.shared.designsystem.theme.XCameraTheme

/**
 * Reflects real manual-exposure state (see `IsoDial`/`ShutterSpeedDial`/
 * `CameraViewModel.onManualModeToggled`) rather than owning its own decorative one — a tap always
 * calls [onToggle] regardless of current state, letting the ViewModel decide whether that means
 * entering manual (a no-op if neither ISO nor shutter has anything to offer for the current lens) or
 * leaving it. Built directly on [LeverBody] (rather than [CameraLever]) since the A/M lettering needs
 * real Compose text laid on top of the knob, not a [LeverGlyph] baked into the shared Canvas draw.
 */
@Composable
fun ModeLever(manual: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current

    val t by animateFloatAsState(
        targetValue = if (manual) 1f else 0f,
        animationSpec = tween(durationMillis = 320, easing = CameraChrome.KnobOvershootEasing),
        label = "modeKnobTravel",
    )
    val on by animateFloatAsState(
        targetValue = if (manual) 1f else 0f,
        animationSpec = tween(durationMillis = 280, easing = CameraChrome.EaseStandard),
        label = "modeOnGlow",
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.size(LeverTrackWidth, LeverTrackHeight)) {
            LeverBody(t = t, on = on, glyph = LeverGlyph.AutoManual, accent = CameraChrome.Accent) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onToggle()
            }
            Box(
                modifier = Modifier
                    .offset(x = LeverKnobInset + LeverKnobTravel * t, y = LeverKnobInset)
                    .size(LeverKnobWidth, LeverKnobHeight),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = if (manual) "M" else "A",
                    style = TextStyle(
                        fontFamily = CameraChrome.Mono,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = if (manual) Color(0xFFFFE3D2) else Color(0xFFCFC8B6),
                    ),
                )
            }
        }
        Text(text = "MODE", style = CameraChrome.leverLabelStyle())
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF201F1D)
@Composable
private fun ModeLeverPreview() {
    XCameraTheme {
        Row(modifier = Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            ModeLever(manual = false, onToggle = {})
            ModeLever(manual = true, onToggle = {})
        }
    }
}