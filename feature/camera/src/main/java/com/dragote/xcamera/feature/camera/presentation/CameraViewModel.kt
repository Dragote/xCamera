package com.dragote.xcamera.feature.camera.presentation

import android.net.Uri
import androidx.lifecycle.ViewModel
import com.dragote.xcamera.feature.camera.domain.model.CameraLens
import com.dragote.xcamera.feature.camera.domain.model.CameraPermissionStatus
import com.dragote.xcamera.feature.camera.domain.model.ManualIsoCapability
import com.dragote.xcamera.feature.camera.domain.model.isoStopsInRange
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import javax.inject.Inject

@HiltViewModel
class CameraViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

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
     * `MANUAL_SENSOR`/`SENSOR_INFO_SENSITIVITY_RANGE` are per-physical-lens, not per-device (an
     * ultra-wide can lack them even when the main lens has them). If manual mode was active and the
     * new lens doesn't support it at all, this falls back to auto rather than leaving a manual UI
     * with nothing to actually drive it. Either way, [CameraUiState.selectedIsoIndex] is re-clamped
     * against the (possibly narrower, possibly empty) new stop list so it never dangles past the end
     * of [CameraUiState.isoStops].
     */
    fun onManualIsoCapabilityChanged(capability: ManualIsoCapability?) {
        val stops = capability?.let { isoStopsInRange(it.isoRange) }.orEmpty()
        val current = _uiState.value
        _uiState.value = current.copy(
            manualIsoSupported = stops.isNotEmpty(),
            isoStops = stops,
            manualModeEnabled = current.manualModeEnabled && stops.isNotEmpty(),
            selectedIsoIndex = current.selectedIsoIndex.coerceIn(0, (stops.size - 1).coerceAtLeast(0)),
        )
    }

    /**
     * The dial always shows/controls ISO — there's no separate toggle to enter manual mode, so the
     * instant the user starts dragging it (see `DialWheel`'s `onDragActiveChanged`), the app enters
     * manual ISO mode. Dragging back to the same index it started at still counts as engaging manual
     * mode, since the user physically grabbed the control.
     */
    fun onIsoDialDragStarted() {
        val current = _uiState.value
        if (current.isoStops.isEmpty()) return
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

    /** `ModeLever` has no way to *enter* manual (only the ISO dial does) — only to leave it. */
    fun onManualModeExitRequested() {
        _uiState.value = _uiState.value.copy(manualModeEnabled = false)
    }
}
