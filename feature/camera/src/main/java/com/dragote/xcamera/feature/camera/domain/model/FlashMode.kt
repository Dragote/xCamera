package com.dragote.xcamera.feature.camera.domain.model

enum class FlashMode {
    OFF,
    ON,
    ;

    fun toggled(): FlashMode = when (this) {
        OFF -> ON
        ON -> OFF
    }
}
