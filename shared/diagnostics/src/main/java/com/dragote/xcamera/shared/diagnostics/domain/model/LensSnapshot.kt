package com.dragote.xcamera.shared.diagnostics.domain.model

/**
 * A snapshot of one physical back-facing lens's identity and raw
 * `android.hardware.camera2.CameraCharacteristics` readout, for display on a diagnostics screen —
 * unlike `feature:camera`'s own `CameraLens`, this deliberately keeps the underlying sensor/pixel
 * geometry and aperture instead of collapsing them down to just a zoom ratio, since a diagnostics
 * screen's whole purpose is exposing that raw hardware data to the user.
 *
 * [physicalCameraId] is non-null when this lens is a physical sub-camera of a
 * `LOGICAL_MULTI_CAMERA` [logicalCameraId] rather than its own top-level camera ID.
 * [apertureFNumber] is `null` when `LENS_INFO_AVAILABLE_APERTURES` isn't reported.
 *
 * [focalLengthMm] is the lens's raw physical focal length — genuinely a few mm on a phone, since a
 * phone's sensor is tiny compared to a full-frame/APS-C camera's, so the same field of view needs far
 * less glass. That raw number isn't what a user familiar with "real" cameras recognizes, so
 * [equivalentFocalLengthMm] (the standard 35mm-equivalent conversion, normalized by sensor diagonal —
 * the same [logicalCameraId]/[physicalCameraId]-independent formula [LensEnumerator] already computes
 * internally to derive [zoomRatio]) is carried alongside it for display.
 */
data class LensSnapshot(
    val logicalCameraId: String,
    val physicalCameraId: String?,
    val zoomRatio: Float,
    val focalLengthMm: Float,
    val equivalentFocalLengthMm: Float,
    val sensorWidthMm: Float,
    val sensorHeightMm: Float,
    val pixelArrayWidth: Int,
    val pixelArrayHeight: Int,
    val apertureFNumber: Float?,
)
