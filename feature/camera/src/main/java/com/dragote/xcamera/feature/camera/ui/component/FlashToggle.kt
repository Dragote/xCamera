package com.dragote.xcamera.feature.camera.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dragote.xcamera.shared.designsystem.component.Toggle
import com.dragote.xcamera.shared.designsystem.theme.XCameraTheme

/**
 * FLASH toggle — built on [Toggle], a circular knob carrying the bolt icon with it.
 */
@Composable
fun FlashToggle(flashOn: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Toggle(
        checked = flashOn,
        onToggle = onToggle,
        icon = Icons.Default.FlashOn,
        modifier = modifier,
    )
}

@Preview(showBackground = true, backgroundColor = 0xFFFAF6EC)
@Composable
private fun FlashTogglePreview() {
    XCameraTheme {
        Row(modifier = Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            FlashToggle(flashOn = false, onToggle = {})
            FlashToggle(flashOn = true, onToggle = {})
        }
    }
}
