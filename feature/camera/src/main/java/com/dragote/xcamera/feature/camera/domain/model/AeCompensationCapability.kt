package com.dragote.xcamera.feature.camera.domain.model

/**
 * Presence of this type (as opposed to `null`) already implies
 * [android.hardware.camera2.CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE] is non-trivial —
 * `CameraController.aeCompensationCapability` returns `null` outright when that range is exactly
 * `[0,0]`, Camera2's own convention for "not supported", rather than an "unsupported" variant of this
 * class. Deliberately separate from [ManualIsoCapability]: exposure compensation biases plain
 * `CONTROL_AE_MODE_ON` auto-exposure and works on nearly every camera, it does not require
 * `MANUAL_SENSOR` the way manual ISO/shutter control does. [stepEv] is the already-converted `Float`
 * value of [android.hardware.camera2.CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP] (a Camera2
 * `Rational`) — kept as a plain `Float` here, not `android.util.Rational`, per this module's
 * data-layer-owns-hardware convention (domain never imports `android.hardware.camera2.*`).
 */
data class AeCompensationCapability(
    val range: IntRange,
    val stepEv: Float,
)
