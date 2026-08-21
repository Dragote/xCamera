package com.dragote.xcamera.feature.camera.ui.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dragote.xcamera.feature.camera.ui.theme.CameraChrome.Accent
import com.dragote.xcamera.shared.common.domain.model.LutPreset
import com.dragote.xcamera.shared.designsystem.theme.XCameraTheme

/**
 * Quick-access LUT selector for `ui/CameraScreen`'s top toolbar — a thin [DialWheel] wrapper, no
 * visuals of its own.
 */
@Composable
fun LutDial(
    luts: List<LutPreset>,
    selectedLutId: String?,
    onLutSelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 96.dp,
    canvasHeight: Dp = 64.dp,
) {
    val values = listOf("OFF") + luts.map { it.displayName.uppercase() }
    val selectedIndex = selectedLutId?.let { id -> luts.indexOfFirst { it.id == id } }
        ?.takeIf { it >= 0 }
        ?.plus(1)
        ?: 0

    DialWheel(
        label = "LUT",
        values = values,
        index = selectedIndex,
        onIndexChange = { index -> onLutSelected(if (index == 0) null else luts[index - 1].id) },
        modifier = modifier,
        accent = Accent,
        width = width,
        canvasHeight = canvasHeight,
    )
}

@Preview(showBackground = true, backgroundColor = 0xFFFAF6EC)
@Composable
private fun LutDialOffPreview() {
    XCameraTheme {
        Row(modifier = Modifier.padding(24.dp)) {
            LutDial(
                luts = listOf(
                    LutPreset(id = "1", displayName = "Portra", filePath = "/luts/1.cube"),
                    LutPreset(id = "2", displayName = "Kodachrome", filePath = "/luts/2.cube"),
                ),
                selectedLutId = null,
                onLutSelected = {},
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFAF6EC)
@Composable
private fun LutDialSelectedPreview() {
    XCameraTheme {
        Row(modifier = Modifier.padding(24.dp)) {
            LutDial(
                luts = listOf(
                    LutPreset(id = "1", displayName = "Portra", filePath = "/luts/1.cube"),
                    LutPreset(id = "2", displayName = "Kodachrome", filePath = "/luts/2.cube"),
                ),
                selectedLutId = "2",
                onLutSelected = {},
            )
        }
    }
}
