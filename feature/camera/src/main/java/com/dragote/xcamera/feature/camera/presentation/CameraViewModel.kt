package com.dragote.xcamera.feature.camera.presentation

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dragote.xcamera.feature.camera.domain.model.AeCompensationCapability
import com.dragote.xcamera.feature.camera.domain.model.CameraLens
import com.dragote.xcamera.feature.camera.domain.model.CameraPermissionStatus
import com.dragote.xcamera.feature.camera.domain.model.FlashMode
import com.dragote.xcamera.feature.camera.domain.model.ManualIsoCapability
import com.dragote.xcamera.feature.camera.domain.model.aeCompensationSteps
import com.dragote.xcamera.feature.camera.domain.model.isoStopsInRange
import com.dragote.xcamera.feature.camera.domain.model.nearestIsoStopIndex
import com.dragote.xcamera.feature.camera.domain.model.nearestShutterStopIndex
import com.dragote.xcamera.feature.camera.domain.model.shutterSpeedStopsInRange
import com.dragote.xcamera.feature.camera.domain.repository.CameraRepository
import com.dragote.xcamera.shared.common.domain.result.DataError
import com.dragote.xcamera.shared.common.domain.result.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
) : ViewModel() {

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

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
    }

    fun setFlashMode(flashMode: FlashMode) = cameraRepository.setFlashMode(flashMode)

    fun manualIsoCapability(lens: CameraLens?): ManualIsoCapability? =
        cameraRepository.manualIsoCapability(lens)

    fun aeCompensationCapability(lens: CameraLens?): AeCompensationCapability? =
        cameraRepository.aeCompensationCapability(lens)

    fun setManualExposure(iso: Int?, shutterTimeNs: Long?) = cameraRepository.setManualExposure(iso, shutterTimeNs)

    fun setExposureCompensation(value: Int) = cameraRepository.setExposureCompensation(value)

    suspend fun takePhoto(): Result<Uri, DataError.Local> = cameraRepository.takePhoto()

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
