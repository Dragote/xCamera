package com.dragote.xcamera.shared.diagnostics.domain.model

/**
 * Presence of this type (as opposed to `null`) already implies
 * [android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR] is
 * supported for the lens in question. Plain Kotlin ranges (not Camera2's `Range<Int>`/`Range<Long>`)
 * so this can flow into the presentation layer without it importing `android.hardware.camera2.*`.
 */
data class ManualIsoCapability(
    val isoRange: IntRange,
    val exposureTimeRange: LongRange,
)
