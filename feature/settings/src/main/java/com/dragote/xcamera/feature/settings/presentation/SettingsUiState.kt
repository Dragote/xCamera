package com.dragote.xcamera.feature.settings.presentation

import com.dragote.xcamera.shared.common.domain.model.FocusPeakingSensitivity

data class SettingsUiState(
    val showGrid: Boolean = false,
    val showHistogram: Boolean = true,
    val showHorizonLine: Boolean = true,
    val focusPeakingSensitivity: FocusPeakingSensitivity = FocusPeakingSensitivity.MEDIUM,
)
