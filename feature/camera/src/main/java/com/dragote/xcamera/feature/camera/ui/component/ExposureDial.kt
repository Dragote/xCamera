package com.dragote.xcamera.feature.camera.ui.component

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
 * exposure compensation (`CONTROL_AE_EXPOSURE_COMPENSATION`), the standard +/- EV brightness bias on
 * top of full auto-metering. Auto-exposure still freely picks ISO/shutter itself; this only biases
 * what it converges toward. Unlike manual ISO/shutter, this works independently of `MANUAL_SENSOR`
 * support (see `CameraViewModel.onAeCompensationCapabilityChanged`), so it can be available even on
 * lenses where [IsoDial]/[ShutterSpeedDial] would show their own unsupported "--" placeholder.
 *
 * When [aeCompensationStops] is empty (`CONTROL_AE_COMPENSATION_RANGE` was exactly `[0,0]` — Camera2's
 * own "not supported" convention, or no capability data yet for the currently selected lens), this
 * falls back to a single inert "--" detent, matching [LensDial]/[IsoDial]'s own placeholder
 * convention.
 */
@Composable
fun ExposureDial(
    aeCompensationStops: List<Int>,
    aeCompensationStepEv: Float,
    selectedIndex: Int,
    onIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
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
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF201F1D)
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

@Preview(showBackground = true, backgroundColor = 0xFF201F1D)
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
