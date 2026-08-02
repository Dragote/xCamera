package com.dragote.xcamera.feature.camera.ui.component

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dragote.xcamera.feature.camera.ui.theme.CameraChrome.Accent
import com.dragote.xcamera.shared.designsystem.theme.XCameraTheme

/**
 * One of two always-visible physical dials in the bottom control deck (see [ShutterSpeedDial] for
 * the other) — split out of a single shared "ManualExposureDial" that used to flip between ISO and
 * shutter speed via an overlay target selector. Camera2's `CONTROL_AE_MODE_OFF` still fixes ISO and
 * shutter speed together (there's no "ISO manual, shutter auto" mode), but the dials themselves are
 * now independent controls: dragging either one immediately engages manual mode for both, via
 * `CameraViewModel.onManualExposureDialDragStarted`.
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
    onDragActiveChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
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
        onDragActiveChanged = if (supported) onDragActiveChanged else { _ -> },
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
            onDragActiveChanged = {},
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
            onDragActiveChanged = {},
            modifier = Modifier.padding(24.dp),
        )
    }
}
