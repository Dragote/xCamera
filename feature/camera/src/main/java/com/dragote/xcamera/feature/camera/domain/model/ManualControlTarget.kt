package com.dragote.xcamera.feature.camera.domain.model

/**
 * Which manual-exposure parameter the one shared physical dial (`ManualExposureDial`) is currently
 * showing/driving. Camera2's `CONTROL_AE_MODE_OFF` fixes ISO and shutter speed simultaneously — there
 * is no "ISO manual, shutter auto" mode — so both are always pinned once manual mode is engaged;
 * this only picks which of the two the dial's rotation currently edits. Two semi-transparent buttons
 * over the viewfinder let the user pick between them; see `CameraViewModel.onManualTargetSelected`.
 */
enum class ManualControlTarget {
    ISO,
    SHUTTER_SPEED,
}
