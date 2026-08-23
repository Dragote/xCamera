package com.dragote.xcamera.feature.camera.ui.component.control

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dragote.xcamera.feature.camera.ui.theme.CameraChrome
import com.dragote.xcamera.shared.designsystem.theme.XCameraTheme

/**
 * Shutter release button — 128dp well, 102dp button. The well is a thin-stroke outline, the button a
 * flat filled circle with no gradient/gloss/shadow. A press reads as a distinct, visible state change
 * from the fill itself darkening a step (`lerp` toward a mid warm-gray) plus a shift/scale, since
 * there's no shadow to collapse for that feedback instead.
 */
@Composable
fun ShutterButton(
    modifier: Modifier = Modifier,
    onHalfPress: () -> Unit = {},
    onCapture: () -> Unit = {},
    enabled: Boolean,
) {
    var pressed by remember { mutableStateOf(false) }

    val press = remember { Animatable(0f) }

    LaunchedEffect(pressed) {
        if (pressed) {
            press.animateTo(1f, tween(80, easing = LinearOutSlowInEasing))
        } else {
            press.animateTo(0f, spring(dampingRatio = 0.52f, stiffness = Spring.StiffnessMediumLow))
        }
    }

    val p = press.value

    Box(
        modifier = modifier.size(WELL_SIZE),
        contentAlignment = Alignment.Center,
    ) {
        // Well — a plain stroked circle, no recess gradient.
        Canvas(Modifier.size(WELL_SIZE)) {
            val r = size.minDimension / 2f
            drawCircle(
                color = CameraChrome.StrokeColor,
                radius = r - CameraChrome.StrokeWidth.toPx() / 2f,
                center = center,
                style = Stroke(width = CameraChrome.StrokeWidth.toPx()),
            )
        }

        Canvas(
            Modifier
                .size(BUTTON_SIZE)
                .semantics {
                    role = Role.Button
                    contentDescription = "Shutter release"
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        pressed = enabled
                        onHalfPress()
                        val up = waitForUpOrCancellation()
                        pressed = false
                        if (up != null) onCapture()
                    }
                },
        ) {
            val dy = 3.dp.toPx() * p
            val s = 1f - 0.03f * p

            translate(top = dy) {
                scale(s, pivot = center) {
                    val r = size.minDimension / 2f
                    val fill = lerp(CameraChrome.Ink, PressedFill, p)
                    drawCircle(color = fill, radius = r, center = center)
                    // Inner ring, inset 9dp — a flat contrast line separating the face from its own
                    // rim, not a gloss/gradient.
                    drawCircle(
                        color = CameraChrome.Background,
                        radius = r - 9.dp.toPx(),
                        center = center,
                        style = Stroke(width = CameraChrome.StrokeWidth.toPx()),
                    )
                }
            }
        }
    }
}

/** Flat mid warm-gray the fill darkens toward on press — a plain solid color swap (not a shadow), just
 *  enough of a step to read as a distinct pressed state alongside the shift/scale. */
private val PressedFill = Color(0xFF4A463D)

private val WELL_SIZE: Dp = 128.dp
private val BUTTON_SIZE: Dp = 102.dp

@Preview(showBackground = true, backgroundColor = 0xFFFAF6EC)
@Composable
private fun ShutterButtonPreview() {
    XCameraTheme {
        Box(modifier = Modifier.padding(24.dp)) {
            ShutterButton(enabled = true, onCapture = {})
        }
    }
}
