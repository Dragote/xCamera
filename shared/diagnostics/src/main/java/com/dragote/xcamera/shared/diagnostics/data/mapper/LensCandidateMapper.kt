package com.dragote.xcamera.shared.diagnostics.data.mapper

import com.dragote.xcamera.shared.diagnostics.data.LensCandidate
import com.dragote.xcamera.shared.diagnostics.data.manualFocusCapabilityFrom
import com.dragote.xcamera.shared.diagnostics.data.manualIsoCapabilityFrom
import com.dragote.xcamera.shared.diagnostics.data.rawCaptureCapabilityFrom
import com.dragote.xcamera.shared.diagnostics.domain.model.FeatureSupport
import com.dragote.xcamera.shared.diagnostics.domain.model.LensDiagnostics
import kotlin.math.roundToInt

fun LensCandidate.toLensDiagnostics(): LensDiagnostics = LensDiagnostics(
    displayLabel = snapshot.zoomRatio.toDisplayLabel(),
    snapshot = snapshot,
    rawCapture = rawCaptureCapabilityFrom(characteristics)?.let {
        val megapixels = (it.sensorWidth.toLong() * it.sensorHeight / 1_000_000.0).roundToInt()
        FeatureSupport.Supported("$megapixels MP RAW")
    } ?: FeatureSupport.Unsupported,
    manualIsoAndShutter = manualIsoCapabilityFrom(characteristics)?.let {
        FeatureSupport.Supported("ISO ${it.isoRange.first}–${it.isoRange.last}")
    } ?: FeatureSupport.Unsupported,
    manualFocus = manualFocusCapabilityFrom(characteristics)?.let {
        FeatureSupport.Supported("down to ${it.maxFocusDistanceDiopters.toMinFocusDistanceCm()} cm")
    } ?: FeatureSupport.Unsupported,
)

/**
 * "0.5× ULTRA-WIDE" / "1× MAIN" / "3× TELEPHOTO" — the same zoom-ratio thresholds
 * `feature:camera`'s own (private) `LensDial.toLensLabel` uses (<0.9 ultra-wide, <=1.1 main, else
 * tele), spelled out in full rather than abbreviated to a single dial-knob letter since this screen has
 * the room for it. A separate, module-local mapping rather than a shared one — `feature:camera`'s
 * function is `private` and this is a different display context (a diagnostics list row, not a dial).
 */
private fun Float.toDisplayLabel(): String {
    val category = when {
        this < 0.9f -> "ULTRA-WIDE"
        this <= 1.1f -> "MAIN"
        else -> "TELEPHOTO"
    }
    return "${formatZoomRatio(this)}× $category"
}

/** Whole numbers render without a decimal ("1×"), fractional ones keep one ("0.5×") — matches how a
 *  zoom ratio is conventionally written on a camera's own lens barrel. */
private fun formatZoomRatio(zoomRatio: Float): String {
    val roundedToOneDecimal = (zoomRatio * 10).roundToInt() / 10f
    return if (roundedToOneDecimal == roundedToOneDecimal.toInt().toFloat()) {
        roundedToOneDecimal.toInt().toString()
    } else {
        roundedToOneDecimal.toString()
    }
}

/** `LENS_INFO_MINIMUM_FOCUS_DISTANCE` is reported in diopters (1/meters) — 100 / diopters converts to
 *  centimeters, rounded to the nearest whole cm for a readable "down to N cm" detail string. */
private fun Float.toMinFocusDistanceCm(): Int = (100 / this).roundToInt()
