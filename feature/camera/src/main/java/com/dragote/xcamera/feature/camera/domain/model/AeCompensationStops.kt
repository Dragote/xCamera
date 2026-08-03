package com.dragote.xcamera.feature.camera.domain.model

import java.util.Locale
import kotlin.math.abs

/**
 * Unlike [isoStopsInRange]/[shutterSpeedStopsInRange], there's no fixed "photographer's ladder" to
 * curate down to the device's range here — the hardware's own integer compensation-step range *is*
 * the natural ladder, one Camera2 step at a time (each step worth [AeCompensationCapability.stepEv]
 * EV). Returns every step in [range] in ascending order.
 */
fun aeCompensationSteps(range: IntRange): List<Int> = range.toList()

/**
 * Standard photographic convention: always-signed EV to one decimal place, except exactly `0`
 * ("+1.0", "-0.7", "0" — never "+0.0"/"-0.0"). Explicit [Locale.US] since this is the first formatter
 * in this module doing locale-sensitive decimal formatting — [formatShutterSpeed] avoids the issue
 * entirely via integer math, this one can't.
 */
fun formatEvCompensation(step: Int, stepEv: Float): String {
    if (step == 0) return "0"
    val ev = step * stepEv
    val sign = if (ev > 0) "+" else "-"
    return String.format(Locale.US, "%s%.1f", sign, abs(ev))
}
