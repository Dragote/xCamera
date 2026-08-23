package com.dragote.xcamera.feature.camera.ui.component.dial

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dragote.xcamera.feature.camera.domain.model.CameraLens
import com.dragote.xcamera.shared.designsystem.component.control.SteppedToggle
import com.dragote.xcamera.shared.designsystem.component.control.SteppedToggleOrientation
import com.dragote.xcamera.shared.designsystem.theme.XCameraTheme

/**
 * Wraps [SteppedToggle] with the device's real back lenses. **2026-08-19: swapped from [DialWheel]'s
 * rotating-barrel click-wheel to [SteppedToggle]'s straight N-detent track** (user request — the lens
 * count is small and fixed-position, unlike ISO/SHUTTER's much larger ranges that still suit a wheel) —
 * this wrapper's own public API/mapping is unchanged, only the control underneath it.
 */
@Composable
fun LensDial(
    lenses: List<CameraLens>,
    selectedLens: CameraLens?,
    onLensSelected: (CameraLens) -> Unit,
    modifier: Modifier = Modifier,
) {
    val labels = lenses.map { it.toLensLabel() }.ifEmpty { listOf("--") }
    val selectedIndex = lenses.indexOf(selectedLens).coerceIn(0, labels.lastIndex)

    SteppedToggle(
        label = "LENS",
        values = labels,
        index = selectedIndex,
        onIndexChange = { index -> lenses.getOrNull(index)?.let(onLensSelected) },
        modifier = modifier,
        orientation = SteppedToggleOrientation.Vertical,
    )
}

private fun CameraLens.toLensLabel(): String = when {
    zoomRatio < 0.9f -> "UW"
    zoomRatio <= 1.1f -> "W"
    else -> "T"
}

@Preview(showBackground = true, backgroundColor = 0xFFFAF6EC)
@Composable
private fun LensDialPreview() {
    val lenses = listOf(
        CameraLens(logicalCameraId = "0", physicalCameraId = "1", zoomRatio = 0.5f),
        CameraLens(logicalCameraId = "0", physicalCameraId = "2", zoomRatio = 1f),
        CameraLens(logicalCameraId = "0", physicalCameraId = "3", zoomRatio = 3f),
    )
    XCameraTheme {
        LensDial(
            lenses = lenses,
            selectedLens = lenses[1],
            onLensSelected = {},
            modifier = Modifier.padding(24.dp),
        )
    }
}