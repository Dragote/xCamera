package com.dragote.xcamera.feature.camera.domain.model

import kotlin.math.abs
import kotlin.math.roundToLong

private const val NanosPerSecond = 1_000_000_000L

/**
 * Fractional (sub-second) shutter speeds as their denominator — e.g. `8000` means 1/8000s. A handful
 * of these (60/30/15) don't divide [NanosPerSecond] evenly, so their nanosecond value below is
 * rounded rather than truncated; [formatShutterSpeed] recovers the same denominator from that rounded
 * value, so the round trip (ladder value -> label) stays exact.
 */
private val SubSecondDenominators = listOf(8000, 4000, 2000, 1000, 500, 250, 125, 60, 30, 15, 8, 4, 2)

/** Whole-second shutter speeds, in seconds. */
private val WholeSecondStops = listOf(1L, 2L, 4L, 8L, 15L, 30L)

/**
 * The standard photographic shutter-speed ladder photographers actually dial through, in
 * nanoseconds — the unit [android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME]/
 * [android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE] use. Ordered
 * fastest (shortest exposure) to slowest, mirroring [IsoStops]' own ascending convention and the
 * dial's physical layout.
 */
private val StandardShutterSpeedStopsNs: List<Long> =
    SubSecondDenominators.map { denominator -> (NanosPerSecond.toDouble() / denominator).roundToLong() } +
        WholeSecondStops.map { seconds -> seconds * NanosPerSecond }

/**
 * Filters [StandardShutterSpeedStopsNs] down to the values the device's actual
 * [android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE] (or the
 * per-physical-lens equivalent) supports. Pure by design — [range] is a plain [LongRange] rather
 * than the Camera2 `Range<Long>` type, mirroring [isoStopsInRange]'s own reasoning, so this stays
 * unit-testable without any Android/Camera2 dependency.
 */
fun shutterSpeedStopsInRange(range: LongRange): List<Long> = StandardShutterSpeedStopsNs.filter { it in range }

/**
 * Standard photography convention: sub-second speeds as a fraction ("1/125"), 1 second and longer
 * as whole seconds with an "s" suffix ("2s", "30s"). Rounds to the nearest whole denominator/second
 * rather than requiring an exact match, so it stays correct for [ns] values coming from this ladder
 * (see [StandardShutterSpeedStopsNs]'s own rounding note) as well as any other nanosecond value.
 */
fun formatShutterSpeed(ns: Long): String {
    if (ns <= 0) return "--"
    return if (ns >= NanosPerSecond) {
        val seconds = (ns / NanosPerSecond.toDouble()).roundToLong()
        "${seconds}s"
    } else {
        val denominator = (NanosPerSecond.toDouble() / ns).roundToLong()
        "1/$denominator"
    }
}

/**
 * Index of whichever [this] entry is nearest [targetNs] — resolves the shutter dial's first-touch
 * position to the auto-converged exposure time instead of an arbitrary index (e.g. 0, the fastest
 * and often wildly-underexposed stop), the same anti-jump reasoning already applied when pinning
 * exposure time alongside a manually-chosen ISO. Returns 0 for an empty list; callers are expected to
 * already guard on emptiness the same way [isoStopsInRange] callers do, this is just a safe fallback.
 */
fun List<Long>.nearestShutterStopIndex(targetNs: Long): Int {
    if (isEmpty()) return 0
    return indices.minByOrNull { i -> abs(this[i] - targetNs) } ?: 0
}
