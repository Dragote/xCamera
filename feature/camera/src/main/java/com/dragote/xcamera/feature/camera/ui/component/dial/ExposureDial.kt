package com.dragote.xcamera.feature.camera.ui.component.dial

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dragote.xcamera.feature.camera.domain.model.formatEvCompensation
import com.dragote.xcamera.feature.camera.ui.theme.CameraChrome.Accent
import com.dragote.xcamera.shared.designsystem.theme.XCameraTheme

/**
 * Shown in place of [IsoDial]/[ShutterSpeedDial] while auto mode is active — real Camera2 AE
 * exposure compensation. A thin [DialWheel] wrapper, no visuals of its own.
 */
@Composable
fun ExposureDial(
    aeCompensationStops: List<Int>,
    aeCompensationStepEv: Float,
    selectedIndex: Int,
    onIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onDragActiveChanged: (Boolean) -> Unit = {},
    closedFraction: Float = 0f,
    closing: Boolean = true,
    backgroundTopY: Float = 0f,
    backgroundHeight: Float = 0f,
) {
    val labels = aeCompensationStops.map { formatEvCompensation(it, aeCompensationStepEv) }.ifEmpty { listOf("--") }
    val index = selectedIndex.coerceIn(0, labels.lastIndex)
    val supported = aeCompensationStops.isNotEmpty()

    DialWheel(
        label = "EXPOSURE",
        values = labels,
        index = index,
        onIndexChange = if (supported) onIndexChange else { _ -> },
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
private fun ExposureDialPreview() {
    XCameraTheme {
        ExposureDial(
            aeCompensationStops = (-6..6).toList(),
            aeCompensationStepEv = 1f / 3f,
            selectedIndex = 6,
            onIndexChange = {},
            modifier = Modifier.padding(24.dp),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFAF6EC)
@Composable
private fun ExposureDialUnsupportedPreview() {
    XCameraTheme {
        ExposureDial(
            aeCompensationStops = emptyList(),
            aeCompensationStepEv = 0f,
            selectedIndex = 0,
            onIndexChange = {},
            modifier = Modifier.padding(24.dp),
        )
    }
}
