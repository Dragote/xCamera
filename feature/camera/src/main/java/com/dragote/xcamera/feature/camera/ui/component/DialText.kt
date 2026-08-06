package com.dragote.xcamera.feature.camera.ui.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dragote.xcamera.shared.designsystem.theme.XCameraTheme

/**
 * The value readout above every mechanical dial's own barrel (e.g. [DialWheel]'s ISO/SHUTTER/LENS
 * value, [FocusDial]'s focus-distance readout) — extracted once this exact styling was duplicated a
 * second time (`FocusDial`, byte-for-byte identical to `DialWheel`'s own), per this project's "abstract
 * at the second occurrence" convention (see root `CLAUDE.md`). [alpha] exists purely for [DialWheel]'s
 * own mechanical seal/unseal transition (`textAlpha`, faded out as the barrel sinks) — callers with no
 * such transition (like [FocusDial]) just leave it at the default `1f`.
 */
@Composable
internal fun DialValueText(value: String, modifier: Modifier = Modifier, alpha: Float = 1f) {
    Text(
        value,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        color = Color(0xFFDED7C3).copy(alpha = alpha),
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.fillMaxWidth().padding(horizontal = 2.dp),
    )
}

/** The unit label below every mechanical dial's own barrel (e.g. "LENS"/"ISO"/"SHUTTER", "FOCUS") —
 *  see [DialValueText]'s own doc for why this was extracted and what [alpha] is for. */
@Composable
internal fun DialLabelText(label: String, modifier: Modifier = Modifier, alpha: Float = 1f) {
    Text(
        label,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        letterSpacing = 2.sp,
        color = Color(0xFF877F6C).copy(alpha = alpha),
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.fillMaxWidth().padding(horizontal = 2.dp),
    )
}

/* ── Previews ────────────────────────────────────────────────────────────── */

@Preview(showBackground = true, backgroundColor = 0xFF2A2722)
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
