package com.dragote.xcamera.feature.settings.presentation

import com.dragote.xcamera.shared.common.domain.model.FocusPeakingSensitivity
import com.dragote.xcamera.shared.common.domain.model.LutPreset

data class SettingsUiState(
    val showGrid: Boolean = false,
    val showHistogram: Boolean = true,
    val showHorizonLine: Boolean = true,
    val focusPeakingSensitivity: FocusPeakingSensitivity = FocusPeakingSensitivity.MEDIUM,
    /** Every imported LUT (issue #43) — empty until the user imports one, "OFF" isn't a member of
     *  this list, it's [selectedLutId] being `null`. */
    val luts: List<LutPreset> = emptyList(),
    val selectedLutId: String? = null,
    val lutIntensityPercent: Int = 100,
)
