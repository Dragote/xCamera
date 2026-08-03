package com.dragote.xcamera.feature.camera.presentation

import android.net.Uri
import com.dragote.xcamera.feature.camera.domain.model.CameraLens
import com.dragote.xcamera.feature.camera.domain.model.CameraPermissionStatus
import com.dragote.xcamera.feature.camera.domain.model.FlashMode

data class CameraUiState(
    val permissionStatus: CameraPermissionStatus = CameraPermissionStatus.Unknown,
    val isCapturing: Boolean = false,
    val lastSavedUri: Uri? = null,
    val captureError: String? = null,
    val flashMode: FlashMode = FlashMode.OFF,
    val availableLenses: List<CameraLens> = emptyList(),
    val selectedLens: CameraLens? = null,
    /** Whether [selectedLens] reports Camera2's `MANUAL_SENSOR` capability — gates manual ISO. */
    val manualIsoSupported: Boolean = false,
    /** The standard ISO ladder filtered down to [selectedLens]'s supported sensitivity range. */
    val isoStops: List<Int> = emptyList(),
    val manualModeEnabled: Boolean = false,
    val selectedIsoIndex: Int = 0,
    /** The standard shutter-speed ladder filtered down to [selectedLens]'s supported exposure-time range. */
    val shutterStops: List<Long> = emptyList(),
    val selectedShutterIndex: Int = 0,
    /**
     * True once the camera has actually locked exposure to [selectedIsoIndex]/[selectedShutterIndex]
     * (`CONTROL_AE_MODE_OFF`) — distinct from [manualModeEnabled], which flips the instant `ModeLever`
     * is tapped and only controls which dials are *shown*. See `CameraViewModel.onManualModeToggled`.
     */
    val manualExposurePinned: Boolean = false,
    /**
     * The *exact* raw sensor ISO/shutter auto-exposure last converged on (not rounded to the nearest
     * [isoStops]/[shutterStops] entry, unlike [selectedIsoIndex]/[selectedShutterIndex]) — used to pin
     * manual exposure to precisely, not approximately, whatever auto was just showing, since rounding
     * to the nearest full stop can itself be a visible brightness jump. Cleared the moment the user
     * actually drags that dial themselves (see `onIsoIndexChanged`/`onShutterIndexChanged`), at which
     * point the normal discrete-stop ladder takes back over as expected for deliberate manual dialing.
     */
    val liveAutoIso: Int? = null,
    val liveAutoExposureTimeNs: Long? = null,
    /** One entry per Camera2 AE-compensation step [selectedLens] supports; empty when unsupported. */
    val aeCompensationStops: List<Int> = emptyList(),
    /** EV value of one compensation step — needed to format [aeCompensationStops] entries for display. */
    val aeCompensationStepEv: Float = 0f,
    val selectedAeCompensationIndex: Int = 0,
)
