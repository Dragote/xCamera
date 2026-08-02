package com.dragote.xcamera.feature.camera.domain.model

import kotlin.math.abs

/** The standard 1-stop ISO ladder photographers actually dial through, independent of any device. */
private val StandardIsoStops = listOf(50, 100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600)

/**
 * Filters [StandardIsoStops] down to the values the device's actual
 * [android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE] (or the
 * per-physical-lens equivalent) supports. Pure by design — [range] is a plain [IntRange] rather
 * than the Camera2 `Range<Int>` type so this stays unit-testable without any Android/Camera2
 * dependency.
 */
fun isoStopsInRange(range: IntRange): List<Int> = StandardIsoStops.filter { it in range }

/**
 * Index of whichever [this] entry is nearest [targetIso] — mirrors
 * [com.dragote.xcamera.feature.camera.domain.model.nearestShutterStopIndex]'s own "nearest by
 * absolute difference" contract, used to keep the ISO dial's displayed index reflecting whatever
 * auto-exposure's live ISO actually is (see `CameraViewModel`'s continuous collection) rather than
 * just resolving once. Returns 0 for an empty list; callers are expected to already guard on
 * emptiness the same way [isoStopsInRange] callers do, this is just a safe fallback.
 */
fun List<Int>.nearestIsoStopIndex(targetIso: Int): Int {
    if (isEmpty()) return 0
    return indices.minByOrNull { i -> abs(this[i] - targetIso) } ?: 0
}
