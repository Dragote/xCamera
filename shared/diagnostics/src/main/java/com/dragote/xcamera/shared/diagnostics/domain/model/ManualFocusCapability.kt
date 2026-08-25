package com.dragote.xcamera.shared.diagnostics.domain.model

/**
 * Presence of this type (as opposed to `null`) already implies the lens can report and adjust
 * `LENS_FOCUS_DISTANCE` — a fixed-focus lens (`CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
 * == 0`) has no `LENS_FOCUS_DISTANCE` control surface at all.
 *
 * Deliberately separate from [ManualIsoCapability] — `LENS_INFO_MINIMUM_FOCUS_DISTANCE` is its own
 * per-physical-lens characteristic, independent of `REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR`
 * (which only gates ISO/shutter).
 *
 * [maxFocusDistanceDiopters] mirrors Camera2's own `LENS_INFO_MINIMUM_FOCUS_DISTANCE` naming
 * (confusingly, the *nearest* focus distance a lens can reach, expressed in diopters — larger means
 * physically closer) — the valid range for `CaptureRequest.LENS_FOCUS_DISTANCE` on this lens is
 * always `[0, maxFocusDistanceDiopters]`, `0` meaning optical infinity, so there's no separate "min"
 * field to carry.
 */
data class ManualFocusCapability(
    val maxFocusDistanceDiopters: Float,
)
