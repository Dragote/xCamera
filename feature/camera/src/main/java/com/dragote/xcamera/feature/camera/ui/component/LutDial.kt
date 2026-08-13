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
 * Quick-access LUT selector for `ui/CameraScreen`'s top toolbar (issue #43's follow-up) — cycles
 * through `[OFF, lut1, lut2, ...]` via [DialWheel]'s own discrete click-ratchet drag gesture, the same
 * interaction [IsoDial]/[ShutterSpeedDial] reuse (**not** [FocusDial]'s continuous-drag model — a
 * deliberately different gesture for a genuinely different interaction, per this project's own
 * duplication convention). A thin semantic wrapper exactly like those two, just at a smaller
 * toolbar-scale footprint ([width]/[canvasHeight] passed down to [DialWheel], which are the only two
 * things about the barrel's fixed 107.dp/117.dp default footprint that needed to become tunable to fit
 * here) — no new gesture code, the ratchet-drag logic lives in exactly one place ([DialWheel]).
 *
 * Only changes *selection*: [onLutSelected] mirrors `feature:settings`' own
 * `SettingsViewModel.onLutSelected(id: String?)` — both surfaces call the identical
 * `CameraSettingsRepository.setSelectedLutId` underneath (via `CameraViewModel`, here), so they stay
 * in sync automatically with no new state needed. Never touches intensity — that stays exclusively a
 * Settings-screen fine-tune control.
 *
 * Callers are expected to only show this once [luts] is non-empty (mirrors `ui/SettingsScreen`'s own
 * `luts.isNotEmpty()` gating for its edit-mode toggle) — a dial with nothing but "OFF" to cycle through
 * has no reason to occupy toolbar space.
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

@Preview(showBackground = true, backgroundColor = 0xFF201F1D)
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

@Preview(showBackground = true, backgroundColor = 0xFF201F1D)
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
