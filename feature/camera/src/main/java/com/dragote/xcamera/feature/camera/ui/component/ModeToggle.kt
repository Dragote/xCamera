package com.dragote.xcamera.feature.camera.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dragote.xcamera.feature.camera.ui.theme.CameraChrome
import com.dragote.xcamera.shared.designsystem.component.Toggle
import com.dragote.xcamera.shared.designsystem.theme.XCameraTheme

/**
 * Auto/Manual mode toggle — built on [Toggle], supplying its own `knobContent` (the A/M letter swap)
 * instead of the single-[androidx.compose.ui.graphics.vector.ImageVector] overload — the two states
 * need genuinely different content here, not just a repositioned icon, so knob position alone
 * wouldn't be enough to read which mode is active at a glance.
 */
@Composable
fun ModeToggle(manual: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Toggle(checked = manual, onToggle = onToggle, modifier = modifier) { checked ->
        BasicText(
            text = if (checked) "M" else "A",
            style = TextStyle(
                fontFamily = CameraChrome.Mono,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = if (checked) CameraChrome.Background else CameraChrome.Ink,
            ),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFAF6EC)
@Composable
private fun ModeTogglePreview() {
    XCameraTheme {
        Row(modifier = Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            ModeToggle(manual = false, onToggle = {})
            ModeToggle(manual = true, onToggle = {})
        }
    }
}
