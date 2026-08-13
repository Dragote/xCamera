package com.dragote.xcamera.feature.settings.ui

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dragote.xcamera.feature.settings.presentation.SettingsUiState
import com.dragote.xcamera.feature.settings.presentation.SettingsViewModel
import com.dragote.xcamera.shared.common.domain.model.FocusPeakingSensitivity
import com.dragote.xcamera.shared.common.domain.model.LutPreset
import com.dragote.xcamera.shared.designsystem.component.LeverSwitch
import com.dragote.xcamera.shared.designsystem.component.LeverGlyph
import com.dragote.xcamera.shared.designsystem.component.LoadingIndicator
import com.dragote.xcamera.shared.designsystem.theme.AppChrome
import com.dragote.xcamera.shared.designsystem.theme.XCameraTheme
import com.dragote.xcamera.shared.navigation.SettingsRoutes
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator

/**
 * Reached from `feature:camera`'s toolbar settings button via the plain route constant
 * [SettingsRoutes.SETTINGS_SCREEN] — feature modules never depend on each other directly, so
 * `feature:camera` navigates by route rather than by a generated `SettingsScreenDestination`.
 */
@Destination(route = SettingsRoutes.SETTINGS_SCREEN)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navigator: DestinationsNavigator,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lutImportError by viewModel.lutImportError.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // ACTION_OPEN_DOCUMENT (not GET_CONTENT) per this issue's own SAF requirement — a .cube file has
    // no registered MIME type, so this deliberately accepts "*/*" rather than trying to filter by one.
    val importLutLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.onLutImportRequested(uri, displayNameFor(context, uri))
    }

    LaunchedEffect(lutImportError) {
        val error = lutImportError
        if (error != null) {
            Toast.makeText(context, "Couldn't import LUT: $error", Toast.LENGTH_SHORT).show()
            viewModel.onLutImportErrorShown()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(AppChrome.BodyGradient)) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text("SETTINGS") },
                    navigationIcon = {
                        IconButton(onClick = navigator::navigateUp) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            },
        ) { innerPadding ->
            val loadedState = uiState
            if (loadedState == null) {
                LoadingIndicator(modifier = Modifier.padding(innerPadding))
            } else {
                SettingsContent(
                    uiState = loadedState,
                    onShowGridToggled = viewModel::onShowGridToggled,
                    onShowHistogramToggled = viewModel::onShowHistogramToggled,
                    onShowHorizonLineToggled = viewModel::onShowHorizonLineToggled,
                    onFocusPeakingSensitivityChanged = viewModel::onFocusPeakingSensitivityChanged,
                    onLutSelected = viewModel::onLutSelected,
                    onLutIntensityChanged = viewModel::onLutIntensityChanged,
                    onImportLutRequested = { importLutLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.padding(innerPadding),
                )
            }
        }
    }
}

/**
 * Places the same [LeverSwitch] toggle FLASH/GRID/MODE use directly on the screen's own
 * [AppChrome.BodyGradient] — no wrapping card/recess around each one, mirroring exactly how
 * `feature:camera`'s toolbar Row presents `FlashLever`/`ModeLever`.
 */
@Composable
private fun SettingsContent(
    uiState: SettingsUiState,
    onShowGridToggled: (Boolean) -> Unit,
    onShowHistogramToggled: (Boolean) -> Unit,
    onShowHorizonLineToggled: (Boolean) -> Unit,
    onFocusPeakingSensitivityChanged: (FocusPeakingSensitivity) -> Unit,
    onLutSelected: (String?) -> Unit,
    onLutIntensityChanged: (Int) -> Unit,
    onImportLutRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        LeverSwitch(
            checked = uiState.showGrid,
            onToggle = { onShowGridToggled(!uiState.showGrid) },
            label = "GRID",
            glyph = LeverGlyph.Grid,
        )
        LeverSwitch(
            checked = uiState.showHistogram,
            onToggle = { onShowHistogramToggled(!uiState.showHistogram) },
            label = "HISTOGRAM",
            glyph = LeverGlyph.None,
        )
        LeverSwitch(
            checked = uiState.showHorizonLine,
            onToggle = { onShowHorizonLineToggled(!uiState.showHorizonLine) },
            label = "HORIZON",
            glyph = LeverGlyph.None,
        )
        PeakingSensitivitySelector(
            selected = uiState.focusPeakingSensitivity,
            onSelected = onFocusPeakingSensitivityChanged,
        )
        LutSelector(
            luts = uiState.luts,
            selectedLutId = uiState.selectedLutId,
            intensityPercent = uiState.lutIntensityPercent,
            resolvingLutId = uiState.resolvingLutId,
            onLutSelected = onLutSelected,
            onIntensityChanged = onLutIntensityChanged,
            onImportRequested = onImportLutRequested,
        )
    }
}

/**
 * Color-grading LUT picker (issue #43) — a horizontally scrollable pill row (an unbounded, user-grown
 * list, unlike [PeakingSensitivitySelector]'s fixed three options, so `LazyRow`-style scrolling makes
 * more sense than wrapping) with "OFF" always first, then each imported [LutPreset], then a trailing
 * "+ IMPORT" pill. Reuses the same filled-pill-vs-outlined-text selection language
 * [PeakingSensitivitySelector] established rather than inventing a second one. The intensity
 * [Slider] only appears once a LUT is actually selected — it's meaningless while grading is off.
 * [resolvingLutId] (from `LutResolutionRepository`, `feature:camera`'s side of `setLut`'s file-read/
 * parse work) shows a small spinner on whichever chip's id matches it — "OFF" can never match, see
 * that interface's own doc.
 */
@Composable
private fun LutSelector(
    luts: List<LutPreset>,
    selectedLutId: String?,
    intensityPercent: Int,
    resolvingLutId: String?,
    onLutSelected: (String?) -> Unit,
    onIntensityChanged: (Int) -> Unit,
    onImportRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LutPill(label = "OFF", isSelected = selectedLutId == null) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onLutSelected(null)
            }
            luts.forEach { lut ->
                LutPill(
                    label = lut.displayName.uppercase(),
                    isSelected = lut.id == selectedLutId,
                    isResolving = lut.id == resolvingLutId,
                ) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLutSelected(lut.id)
                }
            }
            LutPill(label = "+ IMPORT", isSelected = false, onClick = onImportRequested)
        }
        Text(text = "COLOR LUT", style = AppChrome.labelStyle())

        if (selectedLutId != null) {
            LutIntensitySlider(percent = intensityPercent, onIntensityChanged = onIntensityChanged)
        }
    }
}

@Composable
private fun LutPill(label: String, isSelected: Boolean, isResolving: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) AppChrome.Accent else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (isSelected) Color.Transparent else AppChrome.LabelColor.copy(alpha = 0.4f),
                shape = RoundedCornerShape(8.dp),
            )
            .semantics { selected = isSelected }
            .clickable(enabled = !isSelected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = label, style = AppChrome.valueStyle(if (isSelected) Color.White else AppChrome.ValueColor))
            if (isResolving) {
                Spacer(modifier = Modifier.width(6.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = if (isSelected) Color.White else AppChrome.ValueColor,
                )
            }
        }
    }
}

/**
 * 0-100 blend intensity between original and graded color. Tracks its own local drag position (like
 * a normal Material [Slider]) and only calls [onIntensityChanged] — which persists to DataStore, see
 * `SettingsViewModel.onLutIntensityChanged` — on release (`onValueChangeFinished`), not on every
 * per-pixel drag delta; a continuous per-frame DataStore write while dragging would be both wasteful
 * and, via `CameraRepository.setLut` re-resolving/re-parsing the LUT file on every settings emission,
 * needlessly expensive (parsing only needs to happen when the *selected LUT itself* changes, not its
 * intensity — see that function's own doc).
 */
@Composable
private fun LutIntensitySlider(percent: Int, onIntensityChanged: (Int) -> Unit, modifier: Modifier = Modifier) {
    var localValue by remember(percent) { mutableFloatStateOf(percent.toFloat()) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Slider(
            value = localValue,
            onValueChange = { localValue = it },
            onValueChangeFinished = { onIntensityChanged(localValue.toInt()) },
            valueRange = 0f..100f,
            colors = SliderDefaults.colors(
                thumbColor = AppChrome.Accent,
                activeTrackColor = AppChrome.Accent,
                inactiveTrackColor = AppChrome.LabelColor.copy(alpha = 0.3f),
            ),
        )
        Text(text = "INTENSITY ${localValue.toInt()}%", style = AppChrome.labelStyle())
    }
}

/** Best-effort `OpenableColumns.DISPLAY_NAME` query for the SAF-picked [uri] — falls back to its own
 *  last path segment (still a reasonable display name for most `content://` document URIs) rather
 *  than failing the import outright over a missing/queryable display name. */
private fun displayNameFor(context: android.content.Context, uri: Uri): String {
    val queried = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else {
                null
            }
        }
    return queried ?: uri.lastPathSegment ?: "LUT"
}

/** LOW/MEDIUM/HIGH segmented picker for [FocusPeakingSensitivity] — the settings screen's first
 *  non-boolean control, so (per this repo's duplication convention) built local to this file rather
 *  than promoted to `shared:designsystem` until a second multi-option setting needs the same shape.
 *  Segments styled as filled pills (selected) vs. outlined text (unselected) using the same
 *  [AppChrome] mono type/accent [LeverSwitch] uses, so it reads as part of the same control family
 *  even though the interaction shape (radio group, not a two-position toggle) is different. */
@Composable
private fun PeakingSensitivitySelector(
    selected: FocusPeakingSensitivity,
    onSelected: (FocusPeakingSensitivity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FocusPeakingSensitivity.entries.forEach { option ->
                val isSelected = option == selected
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) AppChrome.Accent else Color.Transparent)
                        .border(
                            width = 1.dp,
                            color = if (isSelected) Color.Transparent else AppChrome.LabelColor.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(8.dp),
                        )
                        .semantics { this.selected = isSelected }
                        .clickable(enabled = !isSelected, role = Role.RadioButton) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSelected(option)
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = option.name,
                        style = AppChrome.valueStyle(if (isSelected) Color.White else AppChrome.ValueColor),
                    )
                }
            }
        }
        Text(text = "PEAKING SENSITIVITY", style = AppChrome.labelStyle())
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF201F1D)
@Composable
private fun SettingsContentPreview() {
    XCameraTheme {
        Row(modifier = Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            LeverSwitch(checked = false, onToggle = {}, label = "GRID", glyph = LeverGlyph.Grid)
            LeverSwitch(checked = true, onToggle = {}, label = "GRID", glyph = LeverGlyph.Grid)
            LeverSwitch(checked = false, onToggle = {}, label = "HISTOGRAM")
            LeverSwitch(checked = true, onToggle = {}, label = "HISTOGRAM")
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF201F1D)
@Composable
private fun PeakingSensitivitySelectorPreview() {
    XCameraTheme {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            PeakingSensitivitySelector(selected = FocusPeakingSensitivity.LOW, onSelected = {})
            PeakingSensitivitySelector(selected = FocusPeakingSensitivity.MEDIUM, onSelected = {})
            PeakingSensitivitySelector(selected = FocusPeakingSensitivity.HIGH, onSelected = {})
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF201F1D)
@Composable
private fun LutSelectorPreview() {
    val luts = listOf(
        LutPreset(id = "1", displayName = "Kodak Portra", filePath = ""),
        LutPreset(id = "2", displayName = "Teal & Orange", filePath = ""),
    )
    XCameraTheme {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            // OFF selected — no intensity slider.
            LutSelector(
                luts = luts,
                selectedLutId = null,
                intensityPercent = 100,
                resolvingLutId = null,
                onLutSelected = {},
                onIntensityChanged = {},
                onImportRequested = {},
            )
            // A LUT selected — intensity slider visible.
            LutSelector(
                luts = luts,
                selectedLutId = "1",
                intensityPercent = 70,
                resolvingLutId = null,
                onLutSelected = {},
                onIntensityChanged = {},
                onImportRequested = {},
            )
            // A LUT just picked, still being resolved (file read + .cube parse) — spinner on its own
            // chip. selectedLutId already reflects the pick (persisted instantly, see
            // SettingsViewModel.onLutSelected); resolvingLutId matches it until CameraRepositoryImpl's
            // setLut finishes.
            LutSelector(
                luts = luts,
                selectedLutId = "2",
                intensityPercent = 70,
                resolvingLutId = "2",
                onLutSelected = {},
                onIntensityChanged = {},
                onImportRequested = {},
            )
        }
    }
}
