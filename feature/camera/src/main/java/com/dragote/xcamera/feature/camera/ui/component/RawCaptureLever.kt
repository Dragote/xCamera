package com.dragote.xcamera.feature.camera.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dragote.xcamera.shared.designsystem.component.LeverSwitch
import com.dragote.xcamera.shared.designsystem.theme.XCameraTheme

/**
 * Per-shot (not persistent-mode) "with RAW" choice, shown next to `ShutterButton` only while
 * `CameraUiState.rawCaptureSupported` is true (issue #45) — the current lens/session actually
 * reporting `RAW` support is what gates this row entry existing at all, mirroring `FlashLever`'s own
 * wrap-[LeverSwitch]-with-real-state shape. The label itself carries the "large extra file" warning
 * this issue requires (rather than a separate dialog/toast) — switching to "RAW +25MB" once engaged
 * puts the warning right where the choice is being made, not off to the side.
 */
@Composable
fun RawCaptureLever(
    includeRaw: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LeverSwitch(
        checked = includeRaw,
        onToggle = onToggle,
        label = if (includeRaw) "RAW +25MB" else "RAW",
        modifier = modifier,
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF201F1D)
@Composable
private fun RawCaptureLeverPreview() {
    XCameraTheme {
        Row(modifier = Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            RawCaptureLever(includeRaw = false, onToggle = {})
            RawCaptureLever(includeRaw = true, onToggle = {})
        }
    }
}
