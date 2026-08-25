package com.dragote.xcamera.shared.diagnostics.data

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import com.dragote.xcamera.shared.diagnostics.domain.model.AeCompensationCapability
import com.dragote.xcamera.shared.diagnostics.domain.model.ManualFocusCapability
import com.dragote.xcamera.shared.diagnostics.domain.model.ManualIsoCapability
import com.dragote.xcamera.shared.diagnostics.domain.model.RawCaptureCapability

/**
 * Pure `CameraCharacteristics` -> domain-capability lookups for the diagnostics screen — a port of
 * `feature:camera`'s equivalent capability checks, stateless "given this lens's characteristics, is
 * capability X available" queries with no dependency on whether a camera is actually bound.
 */

/**
 * `null` return means "manual ISO is unavailable for this lens" —
 * [CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR] and
 * [CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE] are per-*physical*-lens characteristics,
 * not per-device, so an ultra-wide/tele auxiliary lens can lack them even when the main lens has
 * them.
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
 * `null` return means "this lens has no `LENS_FOCUS_DISTANCE` control surface" —
 * [CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE] being exactly `0` is Camera2's own
 * convention for a fixed-focus lens, distinct from [manualIsoCapabilityFrom]'s `MANUAL_SENSOR` gate
 * — a lens can support one without the other. Per-physical-lens, not per-device, mirroring
 * [manualIsoCapabilityFrom]'s own reasoning (an ultra-wide/tele auxiliary lens can have a different
 * minimum focus distance, or none, even when the main lens supports full manual focus).
 */
fun manualFocusCapabilityFrom(characteristics: CameraCharacteristics): ManualFocusCapability? {
    val minimumFocusDistance = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        ?: return null
    if (minimumFocusDistance <= 0f) return null
    return ManualFocusCapability(maxFocusDistanceDiopters = minimumFocusDistance)
}

/**
 * `null` return means "this lens has no RAW capture path" — mirrors [manualIsoCapabilityFrom]'s own
 * structure: [CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW] gates it, per-*physical*-lens
 * (not per-device — an ultra-wide/tele auxiliary lens on a `LOGICAL_MULTI_CAMERA` device can lack
 * `RAW` even when the main sensor has it).
 */
fun rawCaptureCapabilityFrom(characteristics: CameraCharacteristics): RawCaptureCapability? {
    val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
    if (CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW !in capabilities) return null

    val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
    val rawSize = map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height } ?: return null

    return RawCaptureCapability(sensorWidth = rawSize.width, sensorHeight = rawSize.height)
}

/**
 * `null` return means "exposure compensation is unavailable for this lens" —
 * [CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE] being exactly `[0,0]` is Camera2's own
 * convention for "not supported", which this returns `null` for rather than a `[0,0]`-range
 * [AeCompensationCapability] a caller could mistake for "supported but with zero range". Deliberately
 * independent of [manualIsoCapabilityFrom]/`MANUAL_SENSOR` — exposure compensation biases plain
 * auto-exposure and is supported on nearly every camera, not just ones with full manual sensor control.
 */
fun aeCompensationCapabilityFrom(characteristics: CameraCharacteristics): AeCompensationCapability? {
    val range = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: return null
    if (range.lower == 0 && range.upper == 0) return null
    val step = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP) ?: return null
    return AeCompensationCapability(range = range.lower..range.upper, stepEv = step.toFloat())
}
