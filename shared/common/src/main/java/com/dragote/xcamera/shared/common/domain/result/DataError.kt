package com.dragote.xcamera.shared.common.domain.result

sealed interface DataError {

    enum class Local : DataError {
        UNKNOWN,

        /** `android.hardware.camera2.CameraAccessException` while enumerating/reading lens
         *  characteristics — e.g. the camera service is disconnected or another process holds it. */
        CAMERA_ACCESS_UNAVAILABLE,
    }
}
