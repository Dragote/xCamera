package com.dragote.xcamera.feature.camera.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dragote.xcamera.feature.camera.ui.theme.CameraChrome
import com.dragote.xcamera.shared.designsystem.theme.XCameraTheme

/**
 * Tap-to-focus's own visual feedback (issue #21 follow-up) — a bracket-cornered AF box centered on
 * the tap point, matching the app's existing `CameraChrome.Accent` tint. This composable only knows
 * how to fade [visible] in/out at whatever [position] it's last been given — the same "external state
 * drives a fade, this just renders it" shape [FocusRing]/`ZebraOverlay` already use — it doesn't know
 * *why* [visible] is true or false.
 *
 * `ui/CameraScreen`'s own `LaunchedEffect` owns that lifecycle: [visible] flips `true` the instant a
 * tap fires, stays `true` for as long as `CameraViewModel.afConvergenceState` reports
 * [com.dragote.xcamera.feature.camera.domain.model.AfConvergenceState.SCANNING], then stays `true` a
 * short beat longer once AF settles (so a fast convergence still reads as a deliberate "locked" pause
 * rather than a flicker) before flipping back to `false` — driven by Camera2's own real AF state, not
 * a fixed timer guessing how long convergence takes.
 */
@Composable
fun FocusTapIndicator(position: Offset?, visible: Boolean, modifier: Modifier = Modifier) {
    var displayedPosition by remember { mutableStateOf(position) }
    LaunchedEffect(position) { if (position != null) displayedPosition = position }

    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 200, easing = CameraChrome.EaseStandard),
        label = "focusTapIndicatorAlpha",
    )

    Canvas(modifier = modifier.fillMaxSize().alpha(alpha)) {
        val center = displayedPosition ?: return@Canvas
        drawAfBracketBox(center)
    }
}

/** Four corner brackets (not a full outline — reads as a lighter, more "viewfinder AF box"-like mark)
 *  around [center], plus a small centered dot marking the exact metering point. */
private fun DrawScope.drawAfBracketBox(center: Offset) {
    val halfSize = BoxSize.toPx() / 2f
    val cornerLength = CornerLength.toPx()
    val stroke = 2.dp.toPx()
    val left = center.x - halfSize
    val top = center.y - halfSize
    val right = center.x + halfSize
    val bottom = center.y + halfSize

    val corners = listOf(
        listOf(Offset(left, top + cornerLength), Offset(left, top), Offset(left + cornerLength, top)),
        listOf(Offset(right - cornerLength, top), Offset(right, top), Offset(right, top + cornerLength)),
        listOf(Offset(right, bottom - cornerLength), Offset(right, bottom), Offset(right - cornerLength, bottom)),
        listOf(Offset(left + cornerLength, bottom), Offset(left, bottom), Offset(left, bottom - cornerLength)),
    )
    corners.forEach { points ->
        for (i in 0 until points.size - 1) {
            drawLine(color = CameraChrome.Accent, start = points[i], end = points[i + 1], strokeWidth = stroke)
        }
    }
    drawCircle(color = CameraChrome.Accent, radius = 2.dp.toPx(), center = center)
}

private val BoxSize = 72.dp
private val CornerLength = 14.dp

@Preview(showBackground = true, widthDp = 220, heightDp = 320, backgroundColor = 0xFF0D1210)
@Composable
private fun FocusTapIndicatorVisiblePreview() {
    XCameraTheme {
        FocusTapIndicator(
            position = Offset(300f, 400f),
            visible = true,
            modifier = Modifier.fillMaxSize().background(Color(0xFF0D1210)),
        )
    }
}

@Preview(showBackground = true, widthDp = 220, heightDp = 320, backgroundColor = 0xFF0D1210)
@Composable
private fun FocusTapIndicatorHiddenPreview() {
    XCameraTheme {
        FocusTapIndicator(
            position = null,
            visible = false,
            modifier = Modifier.fillMaxSize().background(Color(0xFF0D1210)),
        )
    }
}
