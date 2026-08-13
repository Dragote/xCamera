package com.dragote.xcamera.shared.common.domain.model

/** User-configurable viewfinder overlays, persisted by `feature:settings` and consumed by
 *  `feature:camera` — see [com.dragote.xcamera.shared.common.domain.repository.CameraSettingsRepository]. */
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
)
