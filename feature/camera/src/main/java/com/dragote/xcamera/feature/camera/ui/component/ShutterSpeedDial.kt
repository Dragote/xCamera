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
 * One of two always-visible physical dials in the bottom control deck (see [IsoDial] for the
 * other) — split out of a single shared "ManualExposureDial" that used to flip between ISO and
 * shutter speed via an overlay target selector. Camera2's `CONTROL_AE_MODE_OFF` still fixes ISO and
 * shutter speed together (there's no "ISO manual, shutter auto" mode), but the dials themselves are
 * now independent controls: dragging either one immediately engages manual mode for both, via
 * `CameraViewModel.onManualExposureDialDragStarted`.
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
    onDragActiveChanged: (Boolean) -> Unit,
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
        onDragActiveChanged = if (supported) onDragActiveChanged else { _ -> },
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
            onDragActiveChanged = {},
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
            onDragActiveChanged = {},
            modifier = Modifier.padding(24.dp),
        )
    }
}
