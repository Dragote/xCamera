package com.dragote.xcamera.feature.camera.domain.model

/**
 * Which manual-exposure parameter is being engaged — `IsoDial` and `ShutterSpeedDial` are two
 * independent, always-visible physical dials, but Camera2's `CONTROL_AE_MODE_OFF` fixes ISO and
 * shutter speed simultaneously (there is no "ISO manual, shutter auto" mode), so both still get
 * pinned together once manual mode is engaged by dragging either one. This just identifies which
 * dial triggered that — see `CameraViewModel.onManualExposureDialDragStarted`.
 */
enum class ManualControlTarget {
    ISO,
    SHUTTER_SPEED,
}
