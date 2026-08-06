package com.dragote.xcamera.feature.camera.domain.model

/**
 * Presence of this type (as opposed to `null`) already implies the selected lens can report and
 * adjust `LENS_FOCUS_DISTANCE` — `CameraController.manualFocusCapability` returns `null` outright
 * for a fixed-focus lens (`CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE == 0`), gating both
 * tap-to-focus's AF-region trigger and the hold-and-rotate manual focus ring off entirely for that
 * lens (issue #21) — not just the manual ring on its own, since a fixed-focus lens has no
 * `LENS_FOCUS_DISTANCE` control surface of any kind for either gesture to drive.
 *
 * Deliberately separate from [ManualIsoCapability] — `LENS_INFO_MINIMUM_FOCUS_DISTANCE` is its own
 * per-physical-lens characteristic, independent of `REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR`
 * (which only gates ISO/shutter, see `ManualIsoCapability`'s own doc).
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
