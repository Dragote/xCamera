package com.dragote.xcamera.feature.camera.domain.model

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
