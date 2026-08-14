package com.dragote.xcamera.feature.camera.domain.model

/**
 * Presence of this type (as opposed to `null`) already implies
 * [android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW] is supported
 * for the lens in question — `CameraController.rawCaptureCapability` returns `null` outright when it
 * isn't, mirroring [ManualIsoCapability]/[ManualFocusCapability]'s own "nullable capability, not a
 * boolean flag" pattern (issue #45). Per-physical-lens, not per-device — a `LOGICAL_MULTI_CAMERA`
 * lens's ultra-wide/tele sub-cameras can lack `RAW` even when the main sensor has it, the same
 * per-physical-lens reasoning [ManualIsoCapability]'s own doc already covers.
 *
 * [sensorWidth]/[sensorHeight] are the largest `RAW_SENSOR`-format output size this lens's
 * `StreamConfigurationMap` reports — plain `Int`s (not Camera2's `Size`) so this stays free of
 * `android.hardware.camera2.*`/`android.util.*` types per this module's data-layer-owns-hardware
 * convention, and gives the UI something concrete to reason about (e.g. an approximate uncompressed
 * buffer size) beyond a bare "supported" bit — a real Camera2 characteristic, not just a marker,
 * mirroring how [ManualFocusCapability.maxFocusDistanceDiopters] carries actual data rather than
 * being an empty presence-only type.
 *
 * Confirming a lens *reports* `RAW` here does **not** by itself guarantee the actual 3-surface
 * (preview + JPEG + `RAW_SENSOR`) capture session Camera2 needs for a simultaneous JPEG+DNG capture
 * is genuinely configurable on this hardware — `CameraController` additionally verifies that via
 * `CameraDevice.isSessionConfigurationSupported` once a session is actually being opened for this
 * lens, falling back to RAW-unavailable-for-this-session if that check fails or isn't available
 * (API < 29). See `CameraController.openCamera`'s own doc.
 */
data class RawCaptureCapability(
    val sensorWidth: Int,
    val sensorHeight: Int,
)
