package com.dragote.xcamera.shared.diagnostics.domain.model

/**
 * Presence of this type (as opposed to `null`) already implies
 * [android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW] is supported
 * for the lens in question, mirroring [ManualIsoCapability]/[ManualFocusCapability]'s own "nullable
 * capability, not a boolean flag" pattern. Per-physical-lens, not per-device — a
 * `LOGICAL_MULTI_CAMERA` lens's ultra-wide/tele sub-cameras can lack `RAW` even when the main sensor
 * has it.
 *
 * [sensorWidth]/[sensorHeight] are the largest `RAW_SENSOR`-format output size this lens's
 * `StreamConfigurationMap` reports — plain `Int`s (not Camera2's `Size`) so this stays free of
 * `android.hardware.camera2.*`/`android.util.*` types, and gives the diagnostics UI something
 * concrete to reason about (e.g. an approximate uncompressed buffer size) beyond a bare
 * "supported" bit.
 *
 * Confirming a lens *reports* `RAW` here does **not** by itself guarantee an actual 3-surface
 * (preview + JPEG + `RAW_SENSOR`) capture session is genuinely configurable on this hardware —
 * that additional `CameraDevice.isSessionConfigurationSupported` check only makes sense once a
 * camera session is actually open, which is out of scope for a characteristics-only diagnostics
 * readout.
 */
data class RawCaptureCapability(
    val sensorWidth: Int,
    val sensorHeight: Int,
)
