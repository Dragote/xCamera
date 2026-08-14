package com.dragote.xcamera.feature.camera.presentation

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dragote.xcamera.feature.camera.domain.model.AeCompensationCapability
import com.dragote.xcamera.feature.camera.domain.model.AfConvergenceState
import com.dragote.xcamera.feature.camera.domain.model.CameraLens
import com.dragote.xcamera.feature.camera.domain.model.CameraPermissionStatus
import com.dragote.xcamera.feature.camera.domain.model.FlashMode
import com.dragote.xcamera.feature.camera.domain.model.HistogramData
import com.dragote.xcamera.feature.camera.domain.model.ManualFocusCapability
import com.dragote.xcamera.feature.camera.domain.model.ManualIsoCapability
import com.dragote.xcamera.feature.camera.domain.model.RawCaptureCapability
import com.dragote.xcamera.feature.camera.domain.model.ZebraMask
import com.dragote.xcamera.feature.camera.domain.model.aeCompensationSteps
import com.dragote.xcamera.feature.camera.domain.model.isoStopsInRange
import com.dragote.xcamera.feature.camera.domain.model.nearestIsoStopIndex
import com.dragote.xcamera.feature.camera.domain.model.nearestShutterStopIndex
import com.dragote.xcamera.feature.camera.domain.model.shutterSpeedStopsInRange
import com.dragote.xcamera.feature.camera.domain.repository.CameraRepository
import com.dragote.xcamera.shared.common.domain.model.CameraSettings
import com.dragote.xcamera.shared.common.domain.model.LutPreset
import com.dragote.xcamera.shared.common.domain.repository.CameraSettingsRepository
import com.dragote.xcamera.shared.common.domain.repository.LutRepository
import com.dragote.xcamera.shared.common.domain.repository.LutResolutionRepository
import com.dragote.xcamera.shared.common.domain.result.DataError
import com.dragote.xcamera.shared.common.domain.result.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.abs
import javax.inject.Inject

/**
 * `bindCamera` isn't wrapped here — it needs a Compose `LifecycleOwner` + `Preview.SurfaceProvider`,
 * which this ViewModel must never import (see `CameraRepository`'s doc); `ui/CameraScreen` calls it
 * directly against a Hilt-injected [CameraRepository] instead. Every other camera operation is
 * routed through this ViewModel so the UI only ever calls ViewModel methods and renders [uiState].
 */
@HiltViewModel
class CameraViewModel @Inject constructor(
    private val cameraRepository: CameraRepository,
    private val cameraSettingsRepository: CameraSettingsRepository,
    private val lutResolutionRepository: LutResolutionRepository,
    private val lutRepository: LutRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    /**
     * Deliberately its own [StateFlow], not a [CameraUiState] field — this can emit at up to ~15fps
     * while a dial is being dragged (see `CameraController.zebraMask`'s own doc), and folding it into
     * the single [uiState] object would force every composable reading [uiState] to recompose on every
     * one of those emissions, not just whatever actually renders the zebra overlay.
     */
    val zebraMask: StateFlow<ZebraMask?> =
        cameraRepository.observeZebraMask().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Deliberately its own [StateFlow], not a [CameraUiState] field, for the same reason [zebraMask]
     * is — unlike [zebraMask] this has no enable/disable gate at all (see
     * `CameraRepository.observeHistogramData`'s own doc): it streams for the entire lifetime of the
     * preview, always-on per issue #29.
     */
    val histogramData: StateFlow<HistogramData?> =
        cameraRepository.observeHistogramData().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Deliberately its own [StateFlow], not a [CameraUiState] field, for the same reason [zebraMask]
     * is — `CONTROL_AF_STATE` can update on essentially every capture result. Drives the tap-to-focus
     * indicator's own appear/hold/fade lifecycle in `ui/CameraScreen` (issue #21 follow-up).
     */
    val afConvergenceState: StateFlow<AfConvergenceState?> =
        cameraRepository.observeAfConvergenceState().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Deliberately its own [StateFlow], not a [CameraUiState] field, for the same reason [zebraMask]
     * is — it comes from a wholly separate repository ([CameraSettingsRepository], owned by
     * `feature:settings`) rather than [cameraRepository]'s own capture-result stream, so it has no
     * business sharing a single combined state object with fields that do.
     */
    val cameraSettings: StateFlow<CameraSettings> =
        cameraSettingsRepository.observeSettings().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CameraSettings())

    /**
     * Deliberately its own [StateFlow], not a [CameraUiState] field, for the same reason [cameraSettings]
     * is — it comes from [LutResolutionRepository] (injected directly, not through [cameraRepository],
     * since it doesn't need the hardware-facing interface at all — Hilt already binds
     * `CameraRepositoryImpl` to both), a wholly separate repository from `feature:camera`'s own
     * capture-result stream. Mirrors `feature:settings`' `SettingsViewModel` consuming the same
     * interface for its per-chip spinner (issue #43): this is the viewfinder's own indicator for the
     * identical resolving window, shown for whenever the user has already navigated back to the camera
     * screen — the common case, since that's where a LUT's actual effect is visible — while a LUT
     * selection is still being read + parsed off disk.
     */
    val isLutResolving: StateFlow<Boolean> = lutResolutionRepository.observeResolvingLutId()
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Deliberately its own [StateFlow], not a [CameraUiState] field, for the same reason [cameraSettings]
     * is — it comes from [LutRepository] (`feature:settings`' catalog of imported LUTs, injected
     * directly the same way `data.repository.CameraRepositoryImpl` already consumes it to resolve
     * selections), a wholly separate repository from [cameraRepository]'s own capture-result stream.
     * Exists purely so `ui/CameraScreen`'s `LutDial` (the quick-access toolbar dial, issue #43 follow-up)
     * can gate its own visibility on "at least one LUT imported" the same way `ui/SettingsScreen`'s own
     * edit-mode toggle does — `CameraViewModel` otherwise only ever sees [CameraSettings.selectedLutId],
     * never the underlying catalog.
     */
    val luts: StateFlow<List<LutPreset>> =
        lutRepository.observeLuts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // Gated on manualExposurePinned, not manualModeEnabled — the latter flips the instant ModeLever
        // is tapped (so the ISO/SHUTTER dials appear right away), but the camera itself keeps running
        // genuine auto-exposure for a short grace period after that (see
        // ui/CameraScreen's LaunchedEffect + onManualExposurePinningReady) so these collectors can keep
        // tracking the sensor's *actual* converged ISO — including whatever EV compensation bias was
        // just dialed in — instead of freezing on a reading from before 3A had time to settle onto it.
        // Never fights a user's manual drag either way, since manualExposurePinned is long since true
        // by the time a user could physically grab a dial that only became visible on the mode switch.
        viewModelScope.launch {
            cameraRepository.observeAutoIso().collect { iso ->
                val current = _uiState.value
                if (iso == null || current.manualExposurePinned || current.isoStops.isEmpty()) return@collect
                _uiState.value = current.copy(
                    selectedIsoIndex = current.isoStops.nearestIsoStopIndex(iso),
                    // Exact raw value, not the nearest-stop index above — see CameraUiState.liveAutoIso's
                    // own doc for why pinning needs this instead of the rounded ladder entry.
                    liveAutoIso = iso,
                )
            }
        }

        // Mirrors the ISO collector above for the shutter-speed dial — see
        // CameraRepository.observeAutoExposureTime's own doc.
        viewModelScope.launch {
            cameraRepository.observeAutoExposureTime().collect { exposureTimeNs ->
                val current = _uiState.value
                if (exposureTimeNs == null || current.manualExposurePinned || current.shutterStops.isEmpty()) {
                    return@collect
                }
                _uiState.value = current.copy(
                    selectedShutterIndex = current.shutterStops.nearestShutterStopIndex(exposureTimeNs),
                    liveAutoExposureTimeNs = exposureTimeNs,
                )
            }
        }

        // Tracks continuous-AF's live converged focus distance the whole time manual focus isn't
        // locked — see CameraUiState.liveFocusDistanceDiopters's own doc. No pinning/grace-period dance
        // needed here (unlike the ISO/shutter collectors above) since manual focus lock is driven by an
        // explicit hold gesture, not a mode toggle with a settle period.
        viewModelScope.launch {
            cameraRepository.observeFocusDistance().collect { distance ->
                _uiState.value = _uiState.value.copy(liveFocusDistanceDiopters = distance)
            }
        }

        // Issue #43 follow-up — this used to be `ui/CameraScreen`'s own `LaunchedEffect(cameraSettings
        // .selectedLutId, cameraSettings.lutIntensityPercent)`, which only ran while CameraScreen itself
        // was composed. Compose-destinations navigation removes CameraScreen from composition the moment
        // the user navigates to SettingsScreen — but this ViewModel (back-stack-scoped) stays alive the
        // whole time, and Settings is the *only* place a LUT selection/import can happen. That meant the
        // entire resolve step (file read + .cube parse, including the resolve-failure-driven file-type
        // validation) never even started while the user was sitting on Settings — it only fired once they
        // navigated back to the camera screen, which is exactly why resolving looked invisible/instant on
        // "import" and slow on "navigate back". Living here instead runs for this ViewModel's whole
        // lifetime regardless of which screen is composed, so resolution now typically completes in the
        // background while the user is still on Settings. distinctUntilChanged mirrors what the two
        // LaunchedEffect keys gave for free — an unrelated settings change (e.g. showGrid) must not
        // re-trigger a resolve.
        viewModelScope.launch {
            cameraSettingsRepository.observeSettings()
                .map { it.selectedLutId to it.lutIntensityPercent }
                .distinctUntilChanged()
                .collect { (lutId, intensityPercent) -> setLut(lutId, intensityPercent) }
        }
    }

    fun setFlashMode(flashMode: FlashMode) = cameraRepository.setFlashMode(flashMode)

    fun manualIsoCapability(lens: CameraLens?): ManualIsoCapability? =
        cameraRepository.manualIsoCapability(lens)

    fun aeCompensationCapability(lens: CameraLens?): AeCompensationCapability? =
        cameraRepository.aeCompensationCapability(lens)

    fun setManualExposure(iso: Int?, shutterTimeNs: Long?) = cameraRepository.setManualExposure(iso, shutterTimeNs)

    fun setExposureCompensation(value: Int) = cameraRepository.setExposureCompensation(value)

    fun setZebraAnalysisEnabled(enabled: Boolean) = cameraRepository.setZebraAnalysisEnabled(enabled)

    fun manualFocusCapability(lens: CameraLens?): ManualFocusCapability? =
        cameraRepository.manualFocusCapability(lens)

    fun rawCaptureCapability(lens: CameraLens?): RawCaptureCapability? =
        cameraRepository.rawCaptureCapability(lens)

    fun triggerAutoFocus(displayXFraction: Float, displayYFraction: Float) =
        cameraRepository.triggerAutoFocus(displayXFraction, displayYFraction)

    fun setManualFocusDistance(distanceDiopters: Float?) =
        cameraRepository.setManualFocusDistance(distanceDiopters)

    /** Resolves and caches [CameraUiState]-adjacent LUT selection (issue #43) — see
     *  `CameraRepository.setLut`'s own doc. The `init` block's `cameraSettings`-driven collector is now
     *  the *only* caller (see its own comment for why that moved off `ui/CameraScreen`'s `LaunchedEffect`)
     *  — private since there's no longer any legitimate reason for the UI layer to trigger this directly. */
    private suspend fun setLut(lutId: String?, intensityPercent: Int) = cameraRepository.setLut(lutId, intensityPercent)

    /**
     * `ui/CameraScreen`'s `LutDial` (issue #43 follow-up) calls this on every discrete click — `null`
     * selects "OFF". Mirrors `feature:settings`' `SettingsViewModel.onLutSelected` exactly: both just
     * persist the selection via [CameraSettingsRepository.setSelectedLutId], never touch
     * [CameraSettings.lutIntensityPercent] (intensity stays a Settings-screen-only fine-tune). The
     * `init` block's `cameraSettings`-driven collector above is what actually reacts to the change and
     * triggers [setLut]'s resolve — this function doesn't duplicate any of that.
     */
    fun onLutSelected(id: String?) {
        viewModelScope.launch { cameraSettingsRepository.setSelectedLutId(id) }
    }

    suspend fun takePhoto(includeRaw: Boolean = false): Result<Uri, DataError.Local> =
        cameraRepository.takePhoto(includeRaw)

    fun listBackLenses(): List<CameraLens> = cameraRepository.listBackLenses()

    suspend fun latestGalleryPhotoUri(): Uri? = cameraRepository.latestGalleryPhotoUri()

    fun stopOrientationListener() = cameraRepository.stopOrientationListener()

    fun onPermissionResult(granted: Boolean) {
        _uiState.value = _uiState.value.copy(
            permissionStatus = if (granted) CameraPermissionStatus.Granted else CameraPermissionStatus.Denied,
        )
    }

    fun onCaptureStarted() {
        _uiState.value = _uiState.value.copy(isCapturing = true, captureError = null)
    }

    fun onPhotoSaved(uri: Uri) {
        _uiState.value = _uiState.value.copy(isCapturing = false, lastSavedUri = uri)
    }

    fun onCaptureError(message: String?) {
        _uiState.value = _uiState.value.copy(isCapturing = false, captureError = message)
    }

    fun onFlashModeToggled() {
        _uiState.value = _uiState.value.copy(flashMode = _uiState.value.flashMode.toggled())
    }

    fun onLensesLoaded(lenses: List<CameraLens>) {
        val defaultLens = lenses.minByOrNull { abs(it.zoomRatio - 1f) }
        _uiState.value = _uiState.value.copy(availableLenses = lenses, selectedLens = defaultLens)
    }

    fun onLensSelected(lens: CameraLens) {
        _uiState.value = _uiState.value.copy(selectedLens = lens)
    }

    /**
     * Called whenever [CameraLens.physicalCameraId]/[CameraLens.logicalCameraId] capability is
     * (re-)queried for [CameraUiState.selectedLens] — most notably right after a lens switch, since
     * `MANUAL_SENSOR`/`SENSOR_INFO_SENSITIVITY_RANGE`/`SENSOR_INFO_EXPOSURE_TIME_RANGE` are
     * per-physical-lens, not per-device (an ultra-wide can lack them even when the main lens has
     * them). If manual mode was active and the new lens supports *neither* ISO nor shutter stops at
     * all, this falls back to auto rather than leaving a manual UI with nothing to actually drive —
     * but if only one of the two lists goes empty (e.g. a narrow exposure-time range that doesn't
     * line up with the standard ladder), manual mode stays on for whichever parameter still has
     * stops, matching `IsoDial`/`ShutterSpeedDial`'s own "--" placeholder fallback for an empty stop
     * list. Either way, both [CameraUiState.selectedIsoIndex] and [CameraUiState.selectedShutterIndex] are re-clamped
     * against their (possibly narrower, possibly empty) new stop lists so neither dangles past the
     * end of its list.
     */
    fun onManualIsoCapabilityChanged(capability: ManualIsoCapability?) {
        val stops = capability?.let { isoStopsInRange(it.isoRange) }.orEmpty()
        val shutterStops = capability?.let { shutterSpeedStopsInRange(it.exposureTimeRange) }.orEmpty()
        val current = _uiState.value
        val stillSupported = stops.isNotEmpty() || shutterStops.isNotEmpty()
        _uiState.value = current.copy(
            manualIsoSupported = stops.isNotEmpty(),
            isoStops = stops,
            shutterStops = shutterStops,
            manualModeEnabled = current.manualModeEnabled && stillSupported,
            manualExposurePinned = current.manualExposurePinned && stillSupported,
            selectedIsoIndex = current.selectedIsoIndex.coerceIn(0, (stops.size - 1).coerceAtLeast(0)),
            selectedShutterIndex = current.selectedShutterIndex.coerceIn(0, (shutterStops.size - 1).coerceAtLeast(0)),
        )
    }

    /**
     * Called whenever [CameraLens.physicalCameraId]/[CameraLens.logicalCameraId] AE-compensation
     * capability is (re-)queried for [CameraUiState.selectedLens] — mirrors
     * [onManualIsoCapabilityChanged]'s own per-lens-characteristics reasoning
     * (`CONTROL_AE_COMPENSATION_RANGE`/`CONTROL_AE_COMPENSATION_STEP` are per-physical-lens too).
     * Unlike ISO/shutter, the previously-selected index is *not* re-clamped onto the new stop list —
     * a step index means a different actual EV value once the range changes per lens, so re-clamping
     * would silently change what EV is applied. Resets to whichever entry represents `0` EV instead.
     */
    fun onAeCompensationCapabilityChanged(capability: AeCompensationCapability?) {
        val stops = capability?.let { aeCompensationSteps(it.range) }.orEmpty()
        _uiState.value = _uiState.value.copy(
            aeCompensationStops = stops,
            aeCompensationStepEv = capability?.stepEv ?: 0f,
            selectedAeCompensationIndex = stops.indexOf(0).coerceAtLeast(0),
        )
    }

    /**
     * Called whenever [CameraLens.physicalCameraId]/[CameraLens.logicalCameraId]'s manual-focus
     * capability is (re-)queried for [CameraUiState.selectedLens] — most notably right after a lens
     * switch, mirroring [onManualIsoCapabilityChanged]'s own per-physical-lens reasoning
     * (`LENS_INFO_MINIMUM_FOCUS_DISTANCE` can differ, or be `0` for a fixed-focus lens, even when the
     * main lens supports full manual focus).
     */
    fun onManualFocusCapabilityChanged(capability: ManualFocusCapability?) {
        _uiState.value = _uiState.value.copy(
            manualFocusSupported = capability != null,
            maxFocusDistanceDiopters = capability?.maxFocusDistanceDiopters ?: 0f,
        )
    }

    /**
     * Called whenever [CameraLens.physicalCameraId]/[CameraLens.logicalCameraId]'s RAW capability is
     * (re-)queried for [CameraUiState.selectedLens] (issue #45), mirroring
     * [onManualFocusCapabilityChanged]'s own per-physical-lens re-evaluation-on-lens-switch pattern.
     * This is purely a live hardware-support signal now — `CameraSettings.captureRawByDefault` (set on
     * `ui/SettingsScreen`) is the actual user-facing "capture RAW" preference; `ui/CameraScreen` ANDs
     * the two together right before calling [takePhoto], there's no per-shot toggle state left here to
     * reconcile against a lens switch.
     */
    fun onRawCaptureCapabilityChanged(capability: RawCaptureCapability?) {
        _uiState.value = _uiState.value.copy(rawCaptureSupported = capability != null)
    }

    fun onAeCompensationIndexChanged(index: Int) {
        val current = _uiState.value
        if (current.aeCompensationStops.isEmpty()) return
        _uiState.value = current.copy(
            selectedAeCompensationIndex = index.coerceIn(0, current.aeCompensationStops.lastIndex),
        )
    }

    fun onIsoIndexChanged(index: Int) {
        val current = _uiState.value
        if (current.isoStops.isEmpty()) return
        _uiState.value = current.copy(
            selectedIsoIndex = index.coerceIn(0, current.isoStops.lastIndex),
            // The user is now driving ISO directly — stop preferring the frozen exact auto reading
            // from whenever manual mode was entered (see CameraUiState.liveAutoIso's own doc).
            liveAutoIso = null,
        )
    }

    fun onShutterIndexChanged(index: Int) {
        val current = _uiState.value
        if (current.shutterStops.isEmpty()) return
        _uiState.value = current.copy(
            selectedShutterIndex = index.coerceIn(0, current.shutterStops.lastIndex),
            liveAutoExposureTimeNs = null,
        )
    }

    /**
     * `ModeLever` now enters *and* exits manual mode with the same tap (see its own doc comment) —
     * entering is a no-op if neither ISO nor shutter has anything to offer for [CameraUiState.selectedLens]
     * (mirrors [onManualIsoCapabilityChanged]'s own `stillSupported` guard).
     */
    fun onManualModeToggled() {
        val current = _uiState.value
        if (current.manualModeEnabled) {
            // Leaving manual is instant — no reason to delay handing exposure back to auto.
            _uiState.value = current.copy(manualModeEnabled = false, manualExposurePinned = false)
        } else {
            if (current.isoStops.isEmpty() && current.shutterStops.isEmpty()) return
            _uiState.value = current.copy(manualModeEnabled = true, manualExposurePinned = false)
        }
    }

    /**
     * Called from `ui/CameraScreen`'s own `LaunchedEffect` a short grace period after
     * [CameraUiState.manualModeEnabled] turns true — not immediately, so the `init` block's live
     * auto-exposure collectors keep tracking the sensor's *actual* converged ISO/shutter (including
     * whatever EV compensation bias was just dialed in via `ExposureDial`) for a beat longer than the
     * mode toggle itself, instead of freezing [CameraUiState.selectedIsoIndex]/
     * [CameraUiState.selectedShutterIndex] on a reading from before 3A had time to settle onto the new
     * brightness — that staleness is what made the auto→manual switch visibly jump. A no-op if manual
     * mode was already exited again before this fires (e.g. a fast double-tap of `ModeLever`).
     */
    fun onManualExposurePinningReady() {
        val current = _uiState.value
        if (!current.manualModeEnabled) return
        _uiState.value = current.copy(manualExposurePinned = true)
    }
}
