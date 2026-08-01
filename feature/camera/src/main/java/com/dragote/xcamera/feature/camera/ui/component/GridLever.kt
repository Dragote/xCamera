package com.dragote.xcamera.feature.camera.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dragote.xcamera.shared.designsystem.theme.XCameraTheme

/**
 * Toggles the real rule-of-thirds overlay drawn on the viewfinder ([ViewfinderGridOverlay]) — the
 * checked state is hoisted to the caller so the same boolean drives both this switch and the
 * overlay.
 */
@Composable
fun GridLever(checked: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    CameraLever(
        checked = checked,
        onToggle = onToggle,
        label = "GRID",
        glyph = LeverGlyph.Grid,
        modifier = modifier,
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF201F1D)
@Composable
private fun GridLeverPreview() {
    XCameraTheme {
        Row(modifier = Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            GridLever(checked = false, onToggle = {})
            GridLever(checked = true, onToggle = {})
        }
    }
}
