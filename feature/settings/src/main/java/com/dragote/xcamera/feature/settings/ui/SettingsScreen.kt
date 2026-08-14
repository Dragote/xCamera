package com.dragote.xcamera.feature.settings.ui

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.graphicsLayer
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
    val isImportingLut by viewModel.isImportingLut.collectAsStateWithLifecycle()
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
                    isImportingLut = isImportingLut,
                    onShowGridToggled = viewModel::onShowGridToggled,
                    onShowHistogramToggled = viewModel::onShowHistogramToggled,
                    onShowHorizonLineToggled = viewModel::onShowHorizonLineToggled,
                    onFocusPeakingSensitivityChanged = viewModel::onFocusPeakingSensitivityChanged,
                    onCaptureRawByDefaultToggled = viewModel::onCaptureRawByDefaultToggled,
                    onLutSelected = viewModel::onLutSelected,
                    onLutDeleteRequested = viewModel::onLutDeleteRequested,
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
    isImportingLut: Boolean,
    onShowGridToggled: (Boolean) -> Unit,
    onShowHistogramToggled: (Boolean) -> Unit,
    onShowHorizonLineToggled: (Boolean) -> Unit,
    onFocusPeakingSensitivityChanged: (FocusPeakingSensitivity) -> Unit,
    onCaptureRawByDefaultToggled: (Boolean) -> Unit,
    onLutSelected: (String?) -> Unit,
    onLutDeleteRequested: (String) -> Unit,
    onLutIntensityChanged: (Int) -> Unit,
    onImportLutRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            // A wrapping FlowRow shows every imported LUT chip at once (no horizontal scroll) — with
            // enough imported LUTs that row alone can exceed the screen's height, which without this
            // verticalScroll would strand the intensity slider and anything below it permanently
            // off-screen with no way to reach it.
            .verticalScroll(rememberScrollState())
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
        CaptureRawByDefaultSetting(
            enabled = uiState.captureRawByDefault,
            onToggle = onCaptureRawByDefaultToggled,
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
            isImportingLut = isImportingLut,
            onLutSelected = onLutSelected,
            onLutDeleteRequested = onLutDeleteRequested,
            onIntensityChanged = onLutIntensityChanged,
            onImportRequested = onImportLutRequested,
        )
    }
}

/**
 * "Capture RAW alongside JPEG whenever possible" (issue #45 follow-up) — moved here from a per-shot
 * toggle next to the shutter button on `ui/CameraScreen` per user feedback; this is now a persisted
 * preference like GRID/HISTOGRAM/HORIZON above, not a per-capture choice. Uses the same unwrapped
 * [LeverSwitch] look as those three, plus a small caption underneath carrying the "large extra file"
 * warning issue #45 originally required on the per-shot toggle's own label — still required here since
 * the setting can silently make every future capture noticeably larger.
 *
 * `feature:settings` has no way to know whether the *currently active* lens actually supports `RAW`
 * (that's a live per-lens hardware check, `CameraViewModel.rawCaptureCapability`) — this toggle only
 * ever expresses the user's *preference*; `ui/CameraScreen` ANDs it with its own live capability check
 * before actually requesting a RAW buffer, so turning this on while on a non-RAW lens is a harmless
 * no-op until the user switches to one that supports it.
 */
@Composable
private fun CaptureRawByDefaultSetting(enabled: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        LeverSwitch(
            checked = enabled,
            onToggle = { onToggle(!enabled) },
            label = "RAW CAPTURE",
            glyph = LeverGlyph.None,
        )
        Text(
            text = "Saves an additional .dng file (~25-50MB) alongside the JPEG on lenses that support it.",
            style = AppChrome.labelStyle(),
        )
    }
}

/**
 * Color-grading LUT picker (issue #43) — a wrapping [FlowRow] of pills (an unbounded, user-grown list,
 * unlike [PeakingSensitivitySelector]'s fixed three options; wraps onto further lines rather than
 * scrolling horizontally so every imported LUT stays visible/reachable without a scroll gesture) with
 * "OFF" always first, then each imported [LutPreset], then a trailing "+ IMPORT" pill. Reuses the same
 * filled-pill-vs-outlined-text selection language [PeakingSensitivitySelector] established rather than
 * inventing a second one. The intensity [Slider] only appears once a LUT is actually selected — it's
 * meaningless while grading is off. [resolvingLutId] (from `LutResolutionRepository`, `feature:camera`'s
 * side of `setLut`'s file-read/parse work) shows a small spinner on whichever chip's id matches it —
 * "OFF" can never match, see that interface's own doc. [isImportingLut] shows the same spinner treatment
 * on the "+ IMPORT" pill itself, for the separate file-copy step that precedes resolution, and disables
 * re-tapping import while one's already in flight.
 *
 * Deletion is a classic iOS-style "jiggle mode" (replacing an earlier long-press-to-delete interaction,
 * which risked conflicting with this row's drag gestures) — the pencil/check toggle next to the "COLOR
 * LUT" label flips [isEditMode] (pure transient UI state, local to this composable, not worth threading
 * through the ViewModel); while active every imported [LutPreset] chip shakes and tints red (see
 * [LutPill]'s own doc) and tapping one deletes it via [onLutDeleteRequested] instead of selecting it.
 * "OFF"/"+ IMPORT" aren't deletable, so they're just disabled for the duration instead of becoming
 * delete targets — and deleting the very last LUT while in edit mode auto-exits it (see the
 * `LaunchedEffect` below), since the toggle that would otherwise turn edit mode back off only renders
 * for a non-empty list. [initialEditMode] exists solely so a `@Preview` can render the edit-mode-active
 * state without reaching into this composable's private `remember` — production call sites never pass it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LutSelector(
    luts: List<LutPreset>,
    selectedLutId: String?,
    intensityPercent: Int,
    resolvingLutId: String?,
    isImportingLut: Boolean,
    onLutSelected: (String?) -> Unit,
    onLutDeleteRequested: (String) -> Unit,
    onIntensityChanged: (Int) -> Unit,
    onImportRequested: () -> Unit,
    modifier: Modifier = Modifier,
    initialEditMode: Boolean = false,
) {
    val haptic = LocalHapticFeedback.current
    var isEditMode by remember { mutableStateOf(initialEditMode) }
    // Deleting the last remaining LUT while in edit mode would otherwise strand isEditMode at `true`
    // forever — the toggle button below is only shown for a non-empty list (see its own comment), so
    // once luts empties out there'd be no way left to flip isEditMode back off, permanently disabling
    // "OFF"/"+ IMPORT" via LutPill's own `enabled = !isEditMode`.
    LaunchedEffect(luts.isEmpty()) {
        if (luts.isEmpty()) isEditMode = false
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LutPill(label = "OFF", isSelected = selectedLutId == null, enabled = !isEditMode) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onLutSelected(null)
            }
            luts.forEachIndexed { index, lut ->
                LutPill(
                    label = lut.displayName.uppercase(),
                    isSelected = lut.id == selectedLutId,
                    isResolving = lut.id == resolvingLutId,
                    isEditMode = isEditMode,
                    jigglePhaseIndex = index,
                ) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (isEditMode) onLutDeleteRequested(lut.id) else onLutSelected(lut.id)
                }
            }
            LutPill(
                label = "+ IMPORT",
                isSelected = false,
                isBusy = isImportingLut,
                enabled = !isEditMode,
                onClick = onImportRequested,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "COLOR LUT", style = AppChrome.labelStyle())
            // No point offering edit mode over an empty list — mirrors the old onLongClick gating,
            // which only ever existed on an actual LutPreset chip in the first place.
            if (luts.isNotEmpty()) {
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        isEditMode = !isEditMode
                    },
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        imageVector = if (isEditMode) Icons.Filled.Check else Icons.Filled.Edit,
                        contentDescription = if (isEditMode) "Done editing LUTs" else "Edit LUTs",
                        tint = if (isEditMode) MaterialTheme.colorScheme.error else AppChrome.LabelColor,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        if (selectedLutId != null) {
            LutIntensitySlider(percent = intensityPercent, onIntensityChanged = onIntensityChanged)
        }
    }
}

/**
 * [isBusy] drives the same spinner [isResolving] does, but also disables re-tapping ([onClick]) while
 * `true` — distinct flags rather than one shared boolean since a resolving chip (a genuine selection
 * already made) is still meant to read as selected/interactive-looking, while a busy "+ IMPORT" pill is
 * deliberately non-interactive until the copy finishes.
 *
 * [isEditMode] (only ever passed `true` for an actual [LutPreset] chip — "OFF"/"+ IMPORT" never jiggle)
 * drives the classic iOS "jiggle to delete" look: a small [rememberInfiniteTransition]-driven
 * `rotationZ` wobble between ±[JiggleAmplitudeDegrees] over [JiggleDurationMs] each way, plus a red tint
 * (reusing [MaterialTheme]'s own `colorScheme.error` rather than inventing a new design-system token
 * for this one spot) on the fill/border/label. [jigglePhaseIndex] offsets each chip's animation start by
 * a few milliseconds so a row of chips reads as a loosely shaking pile rather than moving in perfect
 * lockstep — deliberately simple (index-based, not random) per this component's own scope.
 */
@Composable
private fun LutPill(
    label: String,
    isSelected: Boolean,
    isResolving: Boolean = false,
    isBusy: Boolean = false,
    isEditMode: Boolean = false,
    jigglePhaseIndex: Int = 0,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val rotationDegrees = if (isEditMode) {
        val infiniteTransition = rememberInfiniteTransition(label = "lutPillJiggle")
        val angle by infiniteTransition.animateFloat(
            initialValue = -JiggleAmplitudeDegrees,
            targetValue = JiggleAmplitudeDegrees,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = JiggleDurationMs, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
                initialStartOffset = StartOffset((jigglePhaseIndex * JiggleStaggerMs) % JiggleDurationMs),
            ),
            label = "lutPillJiggleAngle",
        )
        angle
    } else {
        0f
    }
    val destructiveColor = MaterialTheme.colorScheme.error

    Box(
        modifier = Modifier
            .graphicsLayer { rotationZ = rotationDegrees }
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    isEditMode -> destructiveColor.copy(alpha = 0.22f)
                    isSelected -> AppChrome.Accent
                    else -> Color.Transparent
                },
            )
            .border(
                width = 1.dp,
                color = when {
                    isEditMode -> destructiveColor.copy(alpha = 0.7f)
                    isSelected -> Color.Transparent
                    else -> AppChrome.LabelColor.copy(alpha = 0.4f)
                },
                shape = RoundedCornerShape(8.dp),
            )
            .semantics { selected = isSelected }
            .clickable(
                enabled = enabled && !isBusy,
                role = Role.RadioButton,
                // In edit mode a tap always fires (that's the delete gesture, even on the already-
                // selected chip); otherwise mirrors the old no-op-on-reselect behavior.
                onClick = { if (isEditMode || !isSelected) onClick() },
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = AppChrome.valueStyle(
                    when {
                        isEditMode -> destructiveColor
                        isSelected -> Color.White
                        else -> AppChrome.ValueColor
                    },
                ),
            )
            if (isResolving || isBusy) {
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

/** Jiggle rotation amplitude (each way from center) — classic "shaking icon" feel, small enough to stay
 *  legible. */
private const val JiggleAmplitudeDegrees = 2.5f

/** One leg (center-to-extreme) of the jiggle wobble — a full back-and-forth cycle is roughly double
 *  this, landing in the "quick, jittery" range rather than a slow sway. */
private const val JiggleDurationMs = 170

/** Per-chip animation start offset (multiplied by [LutSelector]'s own `forEachIndexed` index) so a row
 *  of jiggling chips doesn't move in perfect lockstep. */
private const val JiggleStaggerMs = 45

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
private fun CaptureRawByDefaultSettingPreview() {
    XCameraTheme {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            CaptureRawByDefaultSetting(enabled = false, onToggle = {})
            CaptureRawByDefaultSetting(enabled = true, onToggle = {})
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
                isImportingLut = false,
                onLutSelected = {},
                onLutDeleteRequested = {},
                onIntensityChanged = {},
                onImportRequested = {},
            )
            // A LUT selected — intensity slider visible.
            LutSelector(
                luts = luts,
                selectedLutId = "1",
                intensityPercent = 70,
                resolvingLutId = null,
                isImportingLut = false,
                onLutSelected = {},
                onLutDeleteRequested = {},
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
                isImportingLut = false,
                onLutSelected = {},
                onLutDeleteRequested = {},
                onIntensityChanged = {},
                onImportRequested = {},
            )
            // A large file mid-copy (before it's even resolvable) — spinner on the "+ IMPORT" pill
            // itself, distinct from the resolvingLutId spinner above.
            LutSelector(
                luts = luts,
                selectedLutId = null,
                intensityPercent = 100,
                resolvingLutId = null,
                isImportingLut = true,
                onLutSelected = {},
                onLutDeleteRequested = {},
                onIntensityChanged = {},
                onImportRequested = {},
            )
            // Jiggle-to-delete edit mode active (initialEditMode is preview-only, see LutSelector's own
            // doc) — both imported chips jiggle/tint red, "OFF"/"+ IMPORT" stay put and disabled, and
            // the toggle icon has flipped from pencil to checkmark.
            LutSelector(
                luts = luts,
                selectedLutId = "1",
                intensityPercent = 70,
                resolvingLutId = null,
                isImportingLut = false,
                onLutSelected = {},
                onLutDeleteRequested = {},
                onIntensityChanged = {},
                onImportRequested = {},
                initialEditMode = true,
            )
        }
    }
}
