package com.dragote.xcamera.feature.camera.domain.model

/**
 * Presence of this type (as opposed to `null`) already implies
 * [android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR] is
 * supported for the lens in question — [CameraController.manualIsoCapability] returns `null`
 * outright when it isn't, rather than an "unsupported" variant of this class. Plain Kotlin ranges
 * (not Camera2's `Range<Int>`/`Range<Long>`) so this can flow into the presentation layer without
 * it importing `android.hardware.camera2.*`, per this module's data-layer-owns-hardware convention.
 */
data class ManualIsoCapability(
    val isoRange: IntRange,
    val exposureTimeRange: LongRange,
)
