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
 * One of two independent physical dials shown only while manual mode is engaged (see [IsoDial] for
 * the other; `ExposureDial` is what's shown in its place in auto mode). Manual mode itself is now
 * entered/exited only by tapping `ModeLever` (see `CameraViewModel.onManualModeToggled`) — dragging
 * this dial no longer has any manual-mode side effect, since it's only ever reachable once already in
 * manual mode. Camera2's `CONTROL_AE_MODE_OFF` still fixes ISO and shutter speed together (there's no
 * "ISO manual, shutter auto" mode), which is why the two dials still share one manual-mode toggle
 * despite being independent controls.
 *
 * When [shutterStops] is empty (`CameraViewModel.onManualIsoCapabilityChanged` found no
 * `MANUAL_SENSOR` support, or a supported-but-unaligned exposure-time range, for the currently
 * selected lens), this falls back to a single inert "--" detent, matching [LensDial]'s own
 * placeholder-before-loaded convention.
 */
@Composable
fun ShutterSpeedDial(
    shutterStops: List<Long>,
    selectedShutterIndex: Int,
    onShutterIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
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
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF201F1D)
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

@Preview(showBackground = true, backgroundColor = 0xFF201F1D)
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
