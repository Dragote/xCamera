package com.dragote.xcamera.feature.camera.ui.component

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dragote.xcamera.feature.camera.domain.model.formatShutterSpeed
import com.dragote.xcamera.feature.camera.ui.theme.CameraChrome.Accent
import com.dragote.xcamera.shared.designsystem.theme.XCameraTheme

/**
 * One of two independent physical dials shown only while manual mode is engaged. A thin [DialWheel]
 * wrapper, no visuals of its own.
 */
@Composable
fun ShutterSpeedDial(
    shutterStops: List<Long>,
    selectedShutterIndex: Int,
    onShutterIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onDragActiveChanged: (Boolean) -> Unit = {},
    closedFraction: Float = 0f,
    closing: Boolean = true,
    backgroundTopY: Float = 0f,
    backgroundHeight: Float = 0f,
) {
    val labels = shutterStops.map { formatShutterSpeed(it) }.ifEmpty { listOf("--") }
    val index = selectedShutterIndex.coerceIn(0, labels.lastIndex)
    val supported = shutterStops.isNotEmpty()

    DialWheel(
        label = "SHUTTER",
        values = labels,
        index = index,
        onIndexChange = if (supported) onShutterIndexChange else { _ -> },
        modifier = modifier,
        accent = Accent,
        onDragActiveChanged = onDragActiveChanged,
        closedFraction = closedFraction,
        closing = closing,
        backgroundTopY = backgroundTopY,
        backgroundHeight = backgroundHeight,
    )
}

@Preview(showBackground = true, backgroundColor = 0xFFFAF6EC)
@Composable
private fun ShutterSpeedDialPreview() {
    XCameraTheme {
        ShutterSpeedDial(
            shutterStops = listOf(4_000_000L, 8_000_000L, 16_666_667L, 125_000_000L, 1_000_000_000L),
            selectedShutterIndex = 2,
            onShutterIndexChange = {},
            modifier = Modifier.padding(24.dp),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFAF6EC)
@Composable
private fun ShutterSpeedDialUnsupportedPreview() {
    XCameraTheme {
        ShutterSpeedDial(
            shutterStops = emptyList(),
            selectedShutterIndex = 0,
            onShutterIndexChange = {},
            modifier = Modifier.padding(24.dp),
        )
    }
}
