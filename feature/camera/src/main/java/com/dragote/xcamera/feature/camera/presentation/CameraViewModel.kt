package com.dragote.xcamera.feature.camera.presentation

import android.net.Uri
import androidx.lifecycle.ViewModel
import com.dragote.xcamera.feature.camera.domain.model.CameraLens
import com.dragote.xcamera.feature.camera.domain.model.CameraPermissionStatus
import com.dragote.xcamera.feature.camera.domain.model.FlashMode
import com.dragote.xcamera.feature.camera.domain.model.ManualControlTarget
import com.dragote.xcamera.feature.camera.domain.model.ManualIsoCapability
import com.dragote.xcamera.feature.camera.domain.model.isoStopsInRange
import com.dragote.xcamera.feature.camera.domain.model.nearestShutterStopIndex
import com.dragote.xcamera.feature.camera.domain.model.shutterSpeedStopsInRange
import com.dragote.xcamera.feature.camera.domain.repository.CameraRepository
import com.dragote.xcamera.shared.common.domain.result.DataError
import com.dragote.xcamera.shared.common.domain.result.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    fun setFlashMode(flashMode: FlashMode) = cameraRepository.setFlashMode(flashMode)

    fun manualIsoCapability(lens: CameraLens?): ManualIsoCapability? =
        cameraRepository.manualIsoCapability(lens)

    fun currentAutoExposureTimeNs(): Long? = cameraRepository.currentAutoExposureTimeNs()

    fun setManualExposure(iso: Int?, shutterTimeNs: Long?) = cameraRepository.setManualExposure(iso, shutterTimeNs)

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
     * stops, matching `ManualExposureDial`'s own per-parameter "--" placeholder fallback. Either way,
     * both [CameraUiState.selectedIsoIndex] and [CameraUiState.selectedShutterIndex] are re-clamped
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
            selectedIsoIndex = current.selectedIsoIndex.coerceIn(0, (stops.size - 1).coerceAtLeast(0)),
            selectedShutterIndex = current.selectedShutterIndex.coerceIn(0, (shutterStops.size - 1).coerceAtLeast(0)),
        )
    }

    /**
     * The dial always shows/controls whichever of [CameraUiState.manualTarget]'s two parameters is
     * active — there's no separate toggle to enter manual mode, so the instant the user starts
     * dragging it (see `DialWheel`'s `onDragActiveChanged`), the app enters manual mode. Dragging back
     * to the same index it started at still counts as engaging manual mode, since the user physically
     * grabbed the control. A no-op if the currently active target has no stops to offer (mirrors
     * [onIsoIndexChanged]/[onShutterIndexChanged]'s own emptiness guard).
     */
    fun onManualExposureDialDragStarted() {
        val current = _uiState.value
        val activeStopsEmpty = when (current.manualTarget) {
            ManualControlTarget.ISO -> current.isoStops.isEmpty()
            ManualControlTarget.SHUTTER_SPEED -> current.shutterStops.isEmpty()
        }
        if (activeStopsEmpty) return
        _uiState.value = current.copy(manualModeEnabled = true)
    }

    fun onIsoIndexChanged(index: Int) {
        val current = _uiState.value
        if (current.isoStops.isEmpty()) return
        _uiState.value = current.copy(
            selectedIsoIndex = index.coerceIn(0, current.isoStops.lastIndex),
            manualModeEnabled = true,
        )
    }

    fun onShutterIndexChanged(index: Int) {
        val current = _uiState.value
        if (current.shutterStops.isEmpty()) return
        _uiState.value = current.copy(
            selectedShutterIndex = index.coerceIn(0, current.shutterStops.lastIndex),
            manualModeEnabled = true,
            shutterIndexInitialized = true,
        )
    }

    /** Switching targets never resets the index of the parameter being switched away from. */
    fun onManualTargetSelected(target: ManualControlTarget) {
        _uiState.value = _uiState.value.copy(manualTarget = target)
    }

    /**
     * Resolves [CameraUiState.selectedShutterIndex]'s first-touch position (before the user has
     * actually dragged the shutter dial this manual session) to whichever stop is nearest
     * [autoExposureTimeNs] rather than leaving it at an arbitrary default index — the same anti-jump
     * reasoning `CameraController` already applies when pinning exposure time alongside a
     * manually-chosen ISO, just surfaced here so the dial's *displayed* index matches what's actually
     * applied. A no-op once [CameraUiState.shutterIndexInitialized] is already true (a real drag, or an
     * earlier call to this) so it never overrides a value the user (or this same resolution) already
     * settled on, and a no-op if there's no auto value yet (e.g. called before the first capture
     * result lands) or no shutter stops to resolve against.
     */
    fun onManualShutterResolutionNeeded(autoExposureTimeNs: Long?) {
        val current = _uiState.value
        if (current.shutterIndexInitialized || current.shutterStops.isEmpty() || autoExposureTimeNs == null) return
        _uiState.value = current.copy(
            selectedShutterIndex = current.shutterStops.nearestShutterStopIndex(autoExposureTimeNs),
            shutterIndexInitialized = true,
        )
    }

    /** `ModeLever` has no way to *enter* manual (only the dial does) — only to leave it. */
    fun onManualModeExitRequested() {
        _uiState.value = _uiState.value.copy(manualModeEnabled = false, shutterIndexInitialized = false)
    }
}
