package com.dragote.xcamera.feature.camera.data.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import com.dragote.xcamera.feature.camera.domain.model.AeCompensationCapability
import com.dragote.xcamera.feature.camera.domain.model.ManualFocusCapability
import com.dragote.xcamera.feature.camera.domain.model.ManualIsoCapability
import com.dragote.xcamera.feature.camera.domain.model.RawCaptureCapability

/**
 * Pure `CameraCharacteristics` -> domain-capability lookups, extracted out of [CameraController] since
 * every one of these is a stateless "given this lens's characteristics, is capability X available"
 * query with no dependency on whether a camera is actually bound yet — [CameraController] and
 * [PreviewRequestController] each resolve a lens to its [CameraCharacteristics] independently (via
 * [CameraController.characteristicsFor]) and call straight into these rather than going through each
 * other. Being plain functions over a directly-constructible Android type (unlike most of this
 * class's split-out collaborators, which still need a live [android.hardware.camera2.CameraDevice]/
 * [android.hardware.camera2.CameraCaptureSession]) makes these the one piece of the old monolith that's
 * genuinely unit-testable with a mocked [CameraCharacteristics] rather than only device-verifiable.
 */

/**
 * `null` return means "hide/disable manual ISO for this lens" —
 * [CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR] and
 * [CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE] are per-*physical*-lens characteristics,
 * not per-device, so an ultra-wide/tele auxiliary lens can lack them even when the main lens has
 * them (see [CameraController.listBackLenses]'s own physical-vs-logical characteristics lookup for the
 * same reasoning).
 */
fun manualIsoCapabilityFrom(characteristics: CameraCharacteristics): ManualIsoCapability? {
    val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
    if (CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR !in capabilities) return null

    val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: return null
    val exposureTimeRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: return null

    return ManualIsoCapability(
        isoRange = isoRange.lower..isoRange.upper,
        exposureTimeRange = exposureTimeRange.lower..exposureTimeRange.upper,
    )
}

/**
 * Deliberately independent of [manualIsoCapabilityFrom]/`MANUAL_SENSOR` — exposure compensation
 * biases plain auto-exposure and is supported on nearly every camera, not just ones with full
 * manual sensor control. [CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE] being exactly
 * `[0,0]` is Camera2's own convention for "not supported", which this returns `null` for rather
 * than a `[0,0]`-range [AeCompensationCapability] a caller could mistake for "supported but with
 * zero range". [CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP] is a `Rational`, converted to
 * a plain `Float` here so the domain-facing [AeCompensationCapability] stays Camera2-type-free.
 */
fun aeCompensationCapabilityFrom(characteristics: CameraCharacteristics): AeCompensationCapability? {
    val range = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: return null
    if (range.lower == 0 && range.upper == 0) return null

    val step = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP) ?: return null

    return AeCompensationCapability(
        range = range.lower..range.upper,
        stepEv = step.toFloat(),
    )
}

/**
 * `null` return means "hide/no-op both tap-to-focus and the manual focus ring for this lens"
 * (issue #21) — [CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE] being exactly `0` is
 * Camera2's own convention for a fixed-focus lens with no `LENS_FOCUS_DISTANCE` control surface at
 * all, distinct from [manualIsoCapabilityFrom]'s `MANUAL_SENSOR` gate — a lens can support one without
 * the other. Per-physical-lens, not per-device, mirroring [manualIsoCapabilityFrom]'s own reasoning
 * (an ultra-wide/tele auxiliary lens can have a different minimum focus distance, or none, even
 * when the main lens supports full manual focus).
 */
fun manualFocusCapabilityFrom(characteristics: CameraCharacteristics): ManualFocusCapability? {
    val minimumFocusDistance = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        ?: return null
    if (minimumFocusDistance <= 0f) return null
    return ManualFocusCapability(maxFocusDistanceDiopters = minimumFocusDistance)
}

/**
 * `null` return means "hide/never offer the with-RAW capture choice for this lens" (issue #45) —
 * mirrors [manualIsoCapabilityFrom]'s own structure exactly: [CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW]
 * gates it, per-*physical*-lens (not per-device — an ultra-wide/tele auxiliary lens on a
 * `LOGICAL_MULTI_CAMERA` device can lack `RAW` even when the main sensor has it, via
 * [CameraController.characteristicsFor]'s own physical-camera-id resolution). This alone does **not**
 * guarantee a "with RAW" capture will actually succeed for this lens — see [RawCaptureCapability]'s own
 * doc for the additional session-level verification [CameraController] performs once a camera is
 * actually bound.
 */
fun rawCaptureCapabilityFrom(characteristics: CameraCharacteristics): RawCaptureCapability? {
    val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
    if (CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW !in capabilities) return null

    val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
    val rawSize = map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height } ?: return null

    return RawCaptureCapability(sensorWidth = rawSize.width, sensorHeight = rawSize.height)
}
