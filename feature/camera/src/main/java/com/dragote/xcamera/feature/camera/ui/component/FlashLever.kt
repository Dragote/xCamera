package com.dragote.xcamera.feature.camera.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dragote.xcamera.shared.designsystem.component.CameraLever
import com.dragote.xcamera.shared.designsystem.component.LeverGlyph
import com.dragote.xcamera.shared.designsystem.theme.XCameraTheme

/** Wraps [CameraLever] with real flash state. */
@Composable
fun FlashLever(
    flashOn: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CameraLever(
        checked = flashOn,
        onToggle = onToggle,
        label = "FLASH",
        glyph = LeverGlyph.Bolt,
        modifier = modifier,
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF201F1D)
@Composable
private fun FlashLeverPreview() {
    XCameraTheme {
        Row(modifier = Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            FlashLever(flashOn = false, onToggle = {})
            FlashLever(flashOn = true, onToggle = {})
        }
    }
}
