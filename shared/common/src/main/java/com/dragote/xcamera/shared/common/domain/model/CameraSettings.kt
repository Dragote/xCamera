package com.dragote.xcamera.shared.common.domain.model

/** User-configurable app preferences, persisted by `feature:settings` — mostly `feature:camera`'s
 *  viewfinder overlays, plus the presentation-wide chrome and haptics switches every screen honors.
 *  See [com.dragote.xcamera.shared.common.domain.repository.CameraSettingsRepository]. */
data class CameraSettings(
    val showGrid: Boolean = false,
    val showHistogram: Boolean = true,
    val showHorizonLine: Boolean = true,
    val focusPeakingSensitivity: FocusPeakingSensitivity = FocusPeakingSensitivity.MEDIUM,
    /** [LutPreset.id] of the active 3D LUT color-grading preset, `null` meaning "off" (grading
     *  disabled entirely — preview/capture render exactly as they do with no LUT). Resolving this id
     *  to the actual parsed LUT content is `feature:camera`'s job (via
     *  [com.dragote.xcamera.shared.common.domain.repository.LutRepository]), not this model's. */
    val selectedLutId: String? = null,
    /** Blend between original and graded color, 0-100. Only meaningful while [selectedLutId] is
     *  non-null; defaults to fully graded so picking a LUT has a visible effect immediately. */
    val lutIntensityPercent: Int = 100,
    /**
     * "Capture RAW alongside JPEG whenever possible" — a *preference*, not a
     * hardware guarantee: `feature:settings` has no way to know whether the currently active lens
     * actually supports `RAW`, so `feature:camera` ANDs this with its own live per-lens
     * `rawCaptureCapability` check before actually requesting a RAW buffer. Defaults to `false` since
     * a `.dng` roughly doubles-or-more a capture's storage cost — an opt-in, not an opt-out.
     */
    val captureRawByDefault: Boolean = false,
    /**
     * Color-inverted variant of the app's "minimal chrome" flat line-art theme — `false` = white
     * background / black ink (default), `true` = black background / white ink. Purely a display
     * preference; no interaction/behavior implications.
     */
    val minimalChromeInverted: Boolean = false,
    /**
     * Master switch for every haptic effect the app fires — the dial detents, the lever/toggle
     * presses, the focus holds, the horizon-level tick. Defaults to `true`: the tactile chrome is
     * central to this app's identity, so silence is the opt-in.
     *
     * The app needs its own switch because it deliberately escapes the system one: `hapticTick`
     * tags its vibration `USAGE_HARDWARE_FEEDBACK`, a category Android's "Touch feedback" setting
     * doesn't gate (see that function's own doc), so without this a user has no way to turn the
     * app's haptics off at all.
     */
    val hapticFeedbackEnabled: Boolean = true,
)
