package com.dragote.xcamera.feature.camera.ui.component.dial

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dragote.xcamera.feature.camera.ui.theme.CameraChrome.DialInk
import com.dragote.xcamera.shared.designsystem.theme.XCameraTheme

/**
 * One of two independent physical dials shown only while manual mode is engaged. A thin [DialWheel]
 * wrapper, no visuals of its own.
 */
@Composable
fun IsoDial(
    isoStops: List<Int>,
    selectedIsoIndex: Int,
    onIsoIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onDragActiveChanged: (Boolean) -> Unit = {},
    closedFraction: Float = 0f,
    closing: Boolean = true,
    backgroundTopY: Float = 0f,
    backgroundHeight: Float = 0f,
) {
    val labels = isoStops.map { it.toString() }.ifEmpty { listOf("--") }
    val index = selectedIsoIndex.coerceIn(0, labels.lastIndex)
    val supported = isoStops.isNotEmpty()

    DialWheel(
        label = "ISO",
        values = labels,
        index = index,
        onIndexChange = if (supported) onIsoIndexChange else { _ -> },
        modifier = modifier,
        accent = DialInk,
        onDragActiveChanged = onDragActiveChanged,
        closedFraction = closedFraction,
        closing = closing,
        backgroundTopY = backgroundTopY,
        backgroundHeight = backgroundHeight,
    )
}

@Preview(showBackground = true, backgroundColor = 0xFFFAF6EC)
@Composable
private fun IsoDialPreview() {
    XCameraTheme {
        IsoDial(
            isoStops = listOf(100, 200, 400, 800, 1600, 3200),
            selectedIsoIndex = 1,
            onIsoIndexChange = {},
            modifier = Modifier.padding(24.dp),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFAF6EC)
@Composable
private fun IsoDialUnsupportedPreview() {
    XCameraTheme {
        IsoDial(
            isoStops = emptyList(),
            selectedIsoIndex = 0,
            onIsoIndexChange = {},
            modifier = Modifier.padding(24.dp),
        )
    }
}
