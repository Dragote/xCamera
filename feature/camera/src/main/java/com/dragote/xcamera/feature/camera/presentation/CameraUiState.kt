package com.dragote.xcamera.feature.camera.presentation

import android.net.Uri
import com.dragote.xcamera.feature.camera.domain.model.CameraLens
import com.dragote.xcamera.feature.camera.domain.model.CameraPermissionStatus
import com.dragote.xcamera.feature.camera.domain.model.FlashMode
import com.dragote.xcamera.feature.camera.domain.model.ManualControlTarget

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
    /** Which parameter `ManualExposureDial` currently shows/drives — the two overlay buttons flip this. */
    val manualTarget: ManualControlTarget = ManualControlTarget.ISO,
    /** The standard shutter-speed ladder filtered down to [selectedLens]'s supported exposure-time range. */
    val shutterStops: List<Long> = emptyList(),
    val selectedShutterIndex: Int = 0,
    /**
     * Whether [selectedShutterIndex] already reflects either a real user drag or the one-time
     * nearest-to-auto resolution done the first time [manualTarget] becomes [ManualControlTarget.SHUTTER_SPEED]
     * this manual session — see `CameraViewModel.onManualShutterResolutionNeeded`. Reset on exiting
     * manual mode so a fresh entry re-resolves against whatever auto exposure has since settled on,
     * rather than reusing a possibly stale value. Not read directly by the UI.
     */
    val shutterIndexInitialized: Boolean = false,
)
