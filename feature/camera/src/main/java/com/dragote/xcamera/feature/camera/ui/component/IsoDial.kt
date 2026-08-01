package com.dragote.xcamera.feature.camera.ui.component

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dragote.xcamera.feature.camera.ui.theme.CameraChrome.Accent
import com.dragote.xcamera.shared.designsystem.theme.XCameraTheme

/**
 * Repurposed from the old decorative "ZOOM" dial — zoom doesn't exist as a feature in xCamera and
 * never will via this control, so it's been handed over entirely to manual ISO. The dial always
 * displays/lets you pick an ISO stop; there's no separate toggle to *enter* manual mode, since the
 * moment the user starts dragging (`onDragActiveChanged(true)`, wired to
 * `CameraViewModel.onIsoDialDragStarted`) the app enters manual ISO mode on its own. `ModeLever` is
 * what reflects that state and lets you leave it again.
 *
 * When [isoStops] is empty (`CameraViewModel.onManualIsoCapabilityChanged` found no
 * `MANUAL_SENSOR`/`SENSOR_INFO_SENSITIVITY_RANGE` support for the currently selected lens — e.g. an
 * ultra-wide auxiliary lens on a phone whose main lens does support it), this falls back to a single
 * inert "--" detent, matching [LensDial]'s own placeholder-before-loaded convention.
 */
@Composable
fun IsoDial(
    isoStops: List<Int>,
    selectedIndex: Int,
    onIndexChange: (Int) -> Unit,
    onDragActiveChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val labels = isoStops.map { it.toString() }.ifEmpty { listOf("--") }
    val index = selectedIndex.coerceIn(0, labels.lastIndex)
    val supported = isoStops.isNotEmpty()

    DialWheel(
        label = "ISO",
        values = labels,
        index = index,
        onIndexChange = if (supported) onIndexChange else { _ -> },
        modifier = modifier,
        accent = Accent,
        stepPx = 30f,
        onDragActiveChanged = if (supported) onDragActiveChanged else { _ -> },
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF201F1D)
@Composable
private fun IsoDialPreview() {
    XCameraTheme {
        IsoDial(
            isoStops = listOf(100, 200, 400, 800, 1600, 3200),
            selectedIndex = 1,
            onIndexChange = {},
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
            selectedIndex = 0,
            onIndexChange = {},
            onDragActiveChanged = {},
            modifier = Modifier.padding(24.dp),
        )
    }
}
