package com.dragote.xcamera.feature.camera.domain.model

import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * Maps a tap/hold point expressed as a fraction (`0f..1f`, top-left origin) of the *displayed*,
 * already-upright viewfinder into the equivalent fraction of the sensor's own native
 * (`SENSOR_ORIENTATION`-relative, physically landscape on essentially every phone) pixel-array
 * coordinate space — the inverse of the rotation `CameraPreviewRenderer`/
 * `CameraController.analysisRotationDegrees` apply to make a raw sensor frame appear upright on
 * screen (see that field's own doc). `CameraController.triggerAutoFocus` needs this inversion because
 * Camera2's own `CONTROL_AF_REGIONS`/`MeteringRectangle` are always expressed in sensor
 * (`SENSOR_INFO_ACTIVE_ARRAY_SIZE`) coordinates, never display coordinates.
 *
 * [rotationDegrees] must be a multiple of 90 (Camera2's own `SENSOR_ORIENTATION` contract, the only
 * real source of this value) — any other value falls through to the identity mapping.
 *
 * This approximates the *whole* sensor active array as being visible in the viewfinder (a uniform
 * mapping, no center-crop offset) — a deliberate simplification: the actual preview stream is chosen
 * by `CameraController.previewOutputSize` to already closely match the viewfinder's own aspect ratio,
 * so the crop residual is minor, and `CONTROL_AF_REGIONS` is itself a coarse, sizeable rectangle
 * around the tap point (see [FocusRegionSizeFraction]), not a single-pixel target — sub-pixel accuracy
 * isn't required here. Pure and Android/Camera2-type-free so this is fully unit-testable.
 */
fun displayFractionToSensorFraction(displayX: Float, displayY: Float, rotationDegrees: Int): Pair<Float, Float> =
    when (((rotationDegrees % 360) + 360) % 360) {
        90 -> displayY to (1f - displayX)
        180 -> (1f - displayX) to (1f - displayY)
        270 -> (1f - displayY) to displayX
        else -> displayX to displayY
    }

/** Fraction of the sensor's active-array width/height a tap-to-focus `MeteringRectangle` covers —
 *  a coarse region around the tap point, not a single pixel, matching how real AF regions behave. */
const val FocusRegionSizeFraction = 0.2f

/**
 * Maps a continuous rotation gesture (radians accumulated since the manual-focus hold started, signed
 * — positive clockwise) onto a manual focus distance in diopters, starting from
 * [startDistanceDiopters] (wherever continuous AF had last converged to when the hold began, or `0f`
 * — optical infinity — if nothing has converged yet) and clamped into the lens's own supported
 * `[0, maxFocusDistanceDiopters]` range (see [ManualFocusCapability]).
 *
 * [FullRangeRotationRadians] (~1.5 full turns) of rotation sweeps the *entire* focus range end to
 * end — long enough that a small, deliberate rotation still gives fine control near critical focus,
 * short enough that racking all the way from infinity to macro doesn't require an unreasonably long
 * spin. Pure and Android-type-free so this is fully unit-testable — the actual gesture-to-radians
 * accumulation (angle-of-touch-point-around-a-fixed-center, unwrapped across the +-pi wrap boundary)
 * is `ui/component/FocusRing`'s own concern, not this function's.
 */
fun manualFocusDistanceForRotation(
    startDistanceDiopters: Float,
    rotationRadians: Float,
    maxFocusDistanceDiopters: Float,
): Float {
    if (maxFocusDistanceDiopters <= 0f) return 0f
    val delta = (rotationRadians / FullRangeRotationRadians) * maxFocusDistanceDiopters
    return (startDistanceDiopters + delta).coerceIn(0f, maxFocusDistanceDiopters)
}

private val FullRangeRotationRadians = (2.0 * PI * 1.5).toFloat()

/**
 * Formats a manual focus distance (diopters, `LENS_FOCUS_DISTANCE`'s own unit) as an approximate
 * physical distance for `FocusDial`'s value readout — `1/diopters` gives meters exactly (diopters is
 * literally the reciprocal of focus distance in meters), so this doesn't need physical calibration
 * data beyond that identity. `0f` (optical infinity — Camera2's own convention for
 * `LENS_FOCUS_DISTANCE == 0`) reads as the infinity symbol rather than a divide-by-zero. Sub-meter
 * distances switch to whole centimeters (e.g. "35cm") since "0.4m" reads less naturally than "40cm"
 * for macro-range distances; meter-plus distances show one decimal (e.g. "1.2m") — neither needs more
 * precision than that for a live dial readout, and [manualFocusDistanceForRotation] itself only
 * approximates the lens's true focus curve to begin with. Deliberately avoids locale-sensitive
 * `String.format`/`%.1f` (which can render a comma decimal separator on some locales) in favor of
 * plain integer arithmetic, matching [formatShutterSpeed]'s own approach.
 */
fun formatFocusDistance(diopters: Float): String {
    if (diopters <= 0f) return "∞"
    val meters = 1f / diopters
    return if (meters >= 1f) {
        val tenths = (meters * 10f).roundToInt()
        "${tenths / 10}.${tenths % 10}m"
    } else {
        val centimeters = (meters * 100f).roundToInt().coerceAtLeast(1)
        "${centimeters}cm"
    }
}
