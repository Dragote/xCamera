package com.dragote.xcamera.feature.settings.presentation

import com.dragote.xcamera.shared.common.domain.model.AccentColor
import com.dragote.xcamera.shared.common.domain.model.FocusPeakingSensitivity
import com.dragote.xcamera.shared.common.domain.model.LutPreset

data class SettingsUiState(
    val showGrid: Boolean = false,
    val showHistogram: Boolean = true,
    val showHorizonLine: Boolean = true,
    val focusPeakingSensitivity: FocusPeakingSensitivity = FocusPeakingSensitivity.MEDIUM,
    /** Every imported LUT — empty until the user imports one, "OFF" isn't a member of
     *  this list, it's [selectedLutId] being `null`. */
    val luts: List<LutPreset> = emptyList(),
    val selectedLutId: String? = null,
    val lutIntensityPercent: Int = 100,
    /** The id of whichever LUT the camera pipeline is still resolving (file read + `.cube` parse) —
     *  `null` once resolution finishes or nothing's in flight. Drives the spinner on that specific
     *  chip in `ui/SettingsScreen`'s `LutSelector`, not a generic global spinner. */
    val resolvingLutId: String? = null,
    /** "Capture RAW alongside JPEG whenever possible" preference — see
     *  `CameraSettings.captureRawByDefault`'s own doc for why this is a preference, not a hardware
     *  guarantee. */
    val captureRawByDefault: Boolean = false,
    /** Color-inverted variant of `feature:camera`'s minimal-chrome theme — see
     *  `CameraSettings.minimalChromeInverted`'s own doc. */
    val minimalChromeInverted: Boolean = false,
    /** Master switch for every haptic the app fires — see `CameraSettings.hapticFeedbackEnabled`'s
     *  own doc. */
    val hapticFeedbackEnabled: Boolean = true,
    /** The hue the shutter release is painted with — see `CameraSettings.accentColor`'s own doc. */
    val accentColor: AccentColor = AccentColor.OFF,
)
