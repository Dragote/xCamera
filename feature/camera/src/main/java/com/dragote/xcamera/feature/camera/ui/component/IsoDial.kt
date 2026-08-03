package com.dragote.xcamera.feature.camera.ui.component

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dragote.xcamera.feature.camera.ui.theme.CameraChrome.Accent
import com.dragote.xcamera.shared.designsystem.theme.XCameraTheme

/**
 * One of two independent physical dials shown only while manual mode is engaged (see
 * [ShutterSpeedDial] for the other; `ExposureDial` is what's shown in its place in auto mode). Manual
 * mode itself is now entered/exited only by tapping `ModeLever` (see
 * `CameraViewModel.onManualModeToggled`) — dragging this dial no longer has any manual-mode side
 * effect, since it's only ever reachable once already in manual mode. Camera2's `CONTROL_AE_MODE_OFF`
 * still fixes ISO and shutter speed together (there's no "ISO manual, shutter auto" mode), which is
 * why the two dials still share one manual-mode toggle despite being independent controls.
 *
 * When [isoStops] is empty (`CameraViewModel.onManualIsoCapabilityChanged` found no `MANUAL_SENSOR`
 * support, or a supported-but-unaligned range, for the currently selected lens — e.g. an ultra-wide
 * auxiliary lens on a phone whose main lens does support it), this falls back to a single inert "--"
 * detent, matching [LensDial]'s own placeholder-before-loaded convention.
 */
@Composable
fun IsoDial(
    isoStops: List<Int>,
    selectedIsoIndex: Int,
    onIsoIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
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
        accent = Accent,
        closedFraction = closedFraction,
        closing = closing,
        backgroundTopY = backgroundTopY,
        backgroundHeight = backgroundHeight,
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF201F1D)
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

@Preview(showBackground = true, backgroundColor = 0xFF201F1D)
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
