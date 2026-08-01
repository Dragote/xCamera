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
)
