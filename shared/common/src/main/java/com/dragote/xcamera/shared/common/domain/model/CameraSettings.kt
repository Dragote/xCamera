package com.dragote.xcamera.shared.common.domain.model

/** User-configurable viewfinder overlays, persisted by `feature:settings` and consumed by
 *  `feature:camera` — see [com.dragote.xcamera.shared.common.domain.repository.CameraSettingsRepository]. */
data class CameraSettings(
    val showGrid: Boolean = false,
    val showHistogram: Boolean = true,
    val showHorizonLine: Boolean = true,
    val focusPeakingSensitivity: FocusPeakingSensitivity = FocusPeakingSensitivity.MEDIUM,
)
