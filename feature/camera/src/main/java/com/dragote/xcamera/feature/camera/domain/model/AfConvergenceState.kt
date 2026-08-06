package com.dragote.xcamera.feature.camera.domain.model

/**
 * Camera2's own `CaptureResult.CONTROL_AF_STATE` collapsed into the handful of states the
 * tap-to-focus indicator (issue #21 follow-up) actually needs to drive its own lifecycle — not a 1:1
 * mirror of every `CONTROL_AF_STATE_*` constant, just "AF is actively searching" vs "AF has settled,
 * successfully or not" vs "no AF cycle is currently running at all". Domain-facing
 * (Camera2-type-free) per this module's data-layer-owns-hardware convention —
 * `CameraController`'s own mapping function does the actual `CONTROL_AF_STATE` translation.
 */
enum class AfConvergenceState {
    /** `CONTROL_AF_STATE_INACTIVE` — no scan/lock cycle in progress (the resting state before any
     *  trigger, or once manual focus is engaged and Camera2 stops reporting this meaningfully). */
    INACTIVE,

    /** `CONTROL_AF_STATE_PASSIVE_SCAN`/`ACTIVE_SCAN` — a tap-triggered or continuous-mode AF search
     *  is actively in progress; the tap indicator stays visible the whole time this is current. */
    SCANNING,

    /** `CONTROL_AF_STATE_FOCUSED_LOCKED`/`PASSIVE_FOCUSED` — AF has converged on something sharp. */
    FOCUSED,

    /** `CONTROL_AF_STATE_NOT_FOCUSED_LOCKED`/`PASSIVE_UNFOCUSED` — AF gave up without converging
     *  (e.g. a featureless scene) — still a *settled* end state, not [SCANNING]. */
    NOT_FOCUSED,
}
