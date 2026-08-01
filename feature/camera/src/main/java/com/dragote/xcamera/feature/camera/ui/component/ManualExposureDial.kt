package com.dragote.xcamera.feature.camera.ui.component

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dragote.xcamera.feature.camera.domain.model.ManualControlTarget
import com.dragote.xcamera.feature.camera.domain.model.formatShutterSpeed
import com.dragote.xcamera.feature.camera.ui.theme.CameraChrome.Accent
import com.dragote.xcamera.shared.designsystem.theme.XCameraTheme

/**
 * Repurposed from the old decorative "ZOOM" dial — zoom doesn't exist as a feature in xCamera and
 * never will via this control, so it's been handed over entirely to manual exposure. Originally
 * ISO-only (`IsoDial`); now shows/drives whichever of [target]'s two parameters is active, since
 * Camera2's `CONTROL_AE_MODE_OFF` fixes ISO and shutter speed together — there's no "ISO manual,
 * shutter auto" mode — so a single dial with a target switch (the two overlay buttons over the
 * viewfinder) is the natural fit rather than two independent dials. There's still no separate toggle
 * to *enter* manual mode: the moment the user starts dragging (`onDragActiveChanged(true)`, wired to
 * `CameraViewModel.onManualExposureDialDragStarted`) the app enters manual mode on its own, for
 * whichever parameter [target] currently points at. `ModeLever` is what reflects that state and lets
 * you leave it again.
 *
 * When the active target's stop list is empty (`CameraViewModel.onManualIsoCapabilityChanged` found
 * no `MANUAL_SENSOR` support, or a supported-but-unaligned range, for the currently selected lens —
 * e.g. an ultra-wide auxiliary lens on a phone whose main lens does support it), this falls back to a
 * single inert "--" detent for that parameter, matching [LensDial]'s own placeholder-before-loaded
 * convention.
 */
@Composable
fun ManualExposureDial(
    target: ManualControlTarget,
    isoStops: List<Int>,
    selectedIsoIndex: Int,
    shutterStops: List<Long>,
    selectedShutterIndex: Int,
    onIsoIndexChange: (Int) -> Unit,
    onShutterIndexChange: (Int) -> Unit,
    onDragActiveChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (target) {
        ManualControlTarget.ISO -> {
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
                stepPx = 30f,
                onDragActiveChanged = if (supported) onDragActiveChanged else { _ -> },
            )
        }

        ManualControlTarget.SHUTTER_SPEED -> {
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
                stepPx = 30f,
                onDragActiveChanged = if (supported) onDragActiveChanged else { _ -> },
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF201F1D)
@Composable
private fun ManualExposureDialIsoPreview() {
    XCameraTheme {
        ManualExposureDial(
            target = ManualControlTarget.ISO,
            isoStops = listOf(100, 200, 400, 800, 1600, 3200),
            selectedIsoIndex = 1,
            shutterStops = emptyList(),
            selectedShutterIndex = 0,
            onIsoIndexChange = {},
            onShutterIndexChange = {},
            onDragActiveChanged = {},
            modifier = Modifier.padding(24.dp),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF201F1D)
@Composable
private fun ManualExposureDialShutterPreview() {
    XCameraTheme {
        ManualExposureDial(
            target = ManualControlTarget.SHUTTER_SPEED,
            isoStops = emptyList(),
            selectedIsoIndex = 0,
            shutterStops = listOf(4_000_000L, 8_000_000L, 16_666_667L, 125_000_000L, 1_000_000_000L),
            selectedShutterIndex = 2,
            onIsoIndexChange = {},
            onShutterIndexChange = {},
            onDragActiveChanged = {},
            modifier = Modifier.padding(24.dp),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF201F1D)
@Composable
private fun ManualExposureDialUnsupportedPreview() {
    XCameraTheme {
        ManualExposureDial(
            target = ManualControlTarget.ISO,
            isoStops = emptyList(),
            selectedIsoIndex = 0,
            shutterStops = emptyList(),
            selectedShutterIndex = 0,
            onIsoIndexChange = {},
            onShutterIndexChange = {},
            onDragActiveChanged = {},
            modifier = Modifier.padding(24.dp),
        )
    }
}
