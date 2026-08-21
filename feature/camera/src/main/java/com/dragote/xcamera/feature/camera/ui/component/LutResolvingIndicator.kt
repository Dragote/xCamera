package com.dragote.xcamera.feature.camera.ui.component

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.dragote.xcamera.feature.camera.ui.theme.CameraChrome
import com.dragote.xcamera.shared.designsystem.theme.XCameraTheme

/**
 * Shown over the viewfinder while `CameraViewModel.isLutResolving` is true. Hops between screen
 * corners and rotates as a rigid whole pill ([RotatingBadge], [cornerForQuadrant]) rather than a
 * `Modifier`-level rotation — see [RotatingBadge]'s own doc for why. Built on [InfoPill] (see its own
 * doc for why solid black over translucent).
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
                RotatingBadge(rotationDegrees = counterRotationDegrees(activeQuadrant)) {
                    InfoPill(alpha = alpha) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                            color = Color.White,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "APPLYING LUT…", style = CameraChrome.leverLabelStyle(color = Color.White))
                    }
                }
            }
        }
    }
}

private val CornerInset = 12.dp

/**
 * Rotates [content] as a whole via `graphicsLayer { rotationZ = ... }`, but measures its own layout
 * footprint with width/height *transposed* for a 90°/270° [rotationDegrees] rather than trusting the
 * unrotated measurement — a plain `Modifier`-level rotation only changes how content paints, not what
 * size the layout system thinks it occupies, which clips part of it off-screen once anchored near a
 * corner with limited margin (see `docs/features/camera-capture.md`, shared with `HistogramOverlay`'s
 * own `HistogramMarks`).
 */
@Composable
private fun RotatingBadge(rotationDegrees: Float, content: @Composable () -> Unit) {
    Layout(content = { Box(modifier = Modifier.graphicsLayer { rotationZ = rotationDegrees }) { content() } }) { measurables, _ ->
        val placeable = measurables.first().measure(Constraints())
        val quarterTurned = rotationDegrees == 90f || rotationDegrees == 270f
        val footprintWidth = if (quarterTurned) placeable.height else placeable.width
        val footprintHeight = if (quarterTurned) placeable.width else placeable.height
        layout(footprintWidth, footprintHeight) {
            placeable.place(
                x = (footprintWidth - placeable.width) / 2,
                y = (footprintHeight - placeable.height) / 2,
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1210)
@Composable
private fun LutResolvingIndicatorPreview() {
    XCameraTheme {
        Box(modifier = Modifier.padding(24.dp).size(300.dp, 200.dp)) {
            LutResolvingIndicator(visible = true, modifier = Modifier.fillMaxSize())
        }
    }
}
