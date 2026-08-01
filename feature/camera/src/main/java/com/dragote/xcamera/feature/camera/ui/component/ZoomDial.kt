package com.dragote.xcamera.feature.camera.ui.component

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dragote.xcamera.feature.camera.ui.theme.CameraChrome.Accent
import com.dragote.xcamera.shared.designsystem.theme.XCameraTheme

private val ZoomStops = listOf(0.5f, 1f, 2f, 3f, 5f)
private const val DefaultZoomIndex = 1 // 1x

/**
 * Decorative-only: no digital/optical zoom control exists yet, so this dial just spins its own
 * local index with the design's drag/coast/snap animation — it never touches the real preview or
 * capture pipeline.
 */
@Composable
fun ZoomDial(modifier: Modifier = Modifier) {
    var index by remember { mutableIntStateOf(DefaultZoomIndex) }

    DialWheel(
        label = "ZOOM",
        values = ZoomStops.map { "${it.toLabel()}×" },
        index = index,
        onIndexChange = { index = it },
        modifier = modifier,
        accent = Accent,
        stepPx = 30f,
    )
}

private fun Float.toLabel(): String = if (this == this.toInt().toFloat()) this.toInt().toString() else this.toString()

@Preview(showBackground = true, backgroundColor = 0xFF201F1D)
@Composable
private fun ZoomDialPreview() {
    XCameraTheme {
        ZoomDial(modifier = Modifier.padding(24.dp))
    }
}