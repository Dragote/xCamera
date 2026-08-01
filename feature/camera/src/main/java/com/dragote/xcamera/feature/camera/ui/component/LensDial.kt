package com.dragote.xcamera.feature.camera.ui.component

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dragote.xcamera.feature.camera.domain.model.CameraLens
import com.dragote.xcamera.feature.camera.ui.theme.CameraChrome.Accent
import com.dragote.xcamera.shared.designsystem.theme.XCameraTheme

/**
 * Wraps [DialWheel] with the device's real back lenses — dragging it calls [onLensSelected] the
 * same way the old LensSwitcher chips did, just re-skinned as a barrel dial. Detent count and
 * labels are dynamic since different devices expose 1-3 back lenses. [DialWheel] needs a non-empty
 * `values` list, so a single "--" placeholder stands in before lenses finish loading.
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

    DialWheel(
        label = "LENS",
        values = labels,
        index = selectedIndex,
        onIndexChange = { index -> lenses.getOrNull(index)?.let(onLensSelected) },
        modifier = modifier,
        accent = Accent,
        stepPx = 42f,
    )
}

/**
 * Named like a phone's own lens picker (UW/W/T) instead of the raw zoom ratio, which means little
 * on its own without a "1x" reference point to compare it to. The device's main lens always
 * computes to exactly 1.0 since it's the zoom-ratio baseline (see
 * CameraController.listBackLenses), so the 0.9-1.1 "W" band only needs to cover float rounding,
 * not real lens variation.
 */
private fun CameraLens.toLensLabel(): String = when {
    zoomRatio < 0.9f -> "UW"
    zoomRatio <= 1.1f -> "W"
    else -> "T"
}

@Preview(showBackground = true, backgroundColor = 0xFF201F1D)
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