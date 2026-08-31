package com.dragote.xcamera.feature.camera.domain.model

enum class CameraPermissionStatus {
    Unknown,
    Granted,
    Denied,

    /**
     * Denied with the system dialog no longer available — re-requesting returns denied instantly
     * without showing anything, so the only way out is the app's own settings page.
     */
    PermanentlyDenied,
}
