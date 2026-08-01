package com.dragote.xcamera.feature.camera.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dragote.xcamera.feature.camera.ui.theme.CameraChrome
import com.dragote.xcamera.shared.designsystem.theme.XCameraTheme

/**
 * Real rule-of-thirds grid drawn over the viewfinder — two vertical and two horizontal lines at
 * the 1/3 and 2/3 marks, matching the design's `rgba(255,255,255,.28)` hairlines. Fades in/out
 * with [visible] on the same `.25s ease` timing as the GRID lever's own glyph.
 */
@Composable
fun ViewfinderGridOverlay(visible: Boolean, modifier: Modifier = Modifier) {
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 250, easing = CameraChrome.EaseStandard),
        label = "viewfinderGridAlpha",
    )

    Canvas(modifier = modifier.fillMaxSize().alpha(alpha)) {
        val color = Color.White.copy(alpha = 0.28f)
        val stroke = 1.dp.toPx()
        val xThird = size.width / 3f
        val yThird = size.height / 3f
        drawLine(color, Offset(xThird, 0f), Offset(xThird, size.height), stroke)
        drawLine(color, Offset(xThird * 2, 0f), Offset(xThird * 2, size.height), stroke)
        drawLine(color, Offset(0f, yThird), Offset(size.width, yThird), stroke)
        drawLine(color, Offset(0f, yThird * 2), Offset(size.width, yThird * 2), stroke)
    }
}

@Preview(showBackground = true, widthDp = 220, heightDp = 320, backgroundColor = 0xFF0D1210)
@Composable
private fun ViewfinderGridOverlayPreview() {
    XCameraTheme {
        ViewfinderGridOverlay(
            visible = true,
            modifier = Modifier.fillMaxSize().background(Color(0xFF0D1210)),
        )
    }
}
