package com.dragote.xcamera.feature.camera.ui.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dragote.xcamera.feature.camera.ui.theme.CameraChrome
import com.dragote.xcamera.shared.designsystem.theme.XCameraTheme

/**
 * The value readout above every mechanical dial's own barrel (e.g. [DialWheel]'s ISO/SHUTTER/LENS
 * value, [FocusDial]'s focus-distance readout) — extracted here so every dial shares one label/value
 * text styling instead of duplicating it (see the root `CLAUDE.md` duplication-vs-abstraction rule).
 */
@Composable
internal fun DialValueText(value: String, modifier: Modifier = Modifier, alpha: Float = 1f) {
    Text(
        value,
        style = CameraChrome.dialValueStyle(color = CameraChrome.ValueColor.copy(alpha = alpha)),
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.fillMaxWidth().padding(horizontal = 2.dp),
    )
}

/** The unit label below every mechanical dial's own barrel (e.g. "LENS"/"ISO"/"SHUTTER", "FOCUS") —
 *  see [DialValueText]'s own doc. */
@Composable
internal fun DialLabelText(label: String, modifier: Modifier = Modifier, alpha: Float = 1f) {
    Text(
        label,
        style = CameraChrome.leverLabelStyle(color = CameraChrome.LabelColor.copy(alpha = alpha)),
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.fillMaxWidth().padding(horizontal = 2.dp),
    )
}

/* ── Previews ────────────────────────────────────────────────────────────── */

@Preview(showBackground = true, backgroundColor = 0xFFFAF6EC)
@Composable
private fun DialTextPreview() {
    XCameraTheme {
        androidx.compose.foundation.layout.Column {
            DialValueText("1/125")
            DialLabelText("SHUTTER")
            DialValueText("1/125", alpha = 0.3f)
            DialLabelText("SHUTTER", alpha = 0.3f)
        }
    }
}
