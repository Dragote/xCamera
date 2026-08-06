package com.dragote.xcamera.feature.camera.ui.component

import android.view.OrientationEventListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlin.math.abs

/**
 * Raw device-physical-orientation quadrant (`0f`/`90f`/`180f`/`270f`, clockwise from natural/portrait
 * as a fixed outside viewer would see the device rotate) — shared by [ViewfinderThumbnailChip] (which
 * smoothly animates a continuous counter-rotation on top of this so its glyph stays visually upright)
 * and `HistogramOverlay` (which snaps its own screen corner to this directly, no smoothing, since a
 * corner-hop reads as a discrete jump either way). Both need "which of the four 90° buckets is the
 * phone currently held in," just with different follow-up treatment once it changes.
 *
 * Debounced with [QUADRANT_HYSTERESIS_DEGREES] of hysteresis around the 90° boundaries — holding the
 * phone right at a boundary (45°, 135°, ...) is exactly where the raw sensor reading is noisiest, so
 * without a sticky bias toward whichever quadrant is already active, tiny jitter there flips the
 * bucket back and forth every callback.
 */
@Composable
fun rememberDeviceOrientationQuadrant(): State<Float> {
    val context = LocalContext.current
    val quadrant = remember { mutableFloatStateOf(0f) }

    DisposableEffect(context) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                quadrant.floatValue = nextQuadrant(quadrant.floatValue, orientation.toFloat())
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }

    return quadrant
}

const val QUADRANT_HYSTERESIS_DEGREES = 15f
private val QuadrantCenters = floatArrayOf(0f, 90f, 180f, 270f)

/** Shortest signed angular distance from [b] to [a], in (-180, 180]. */
fun angularDistance(a: Float, b: Float): Float {
    val d = (a - b) % 360f
    return when {
        d > 180f -> d - 360f
        d < -180f -> d + 360f
        else -> d
    }
}

/**
 * Picks which of the four 90°-quadrant centers [orientation] belongs to, biasing towards [current]
 * by [QUADRANT_HYSTERESIS_DEGREES] so the result doesn't flip back and forth when [orientation]
 * hovers near a boundary.
 */
fun nextQuadrant(current: Float, orientation: Float): Float {
    var best = current
    var bestDistance = Float.MAX_VALUE
    for (center in QuadrantCenters) {
        var distance = abs(angularDistance(orientation, center))
        if (center == current) distance -= QUADRANT_HYSTERESIS_DEGREES
        if (distance < bestDistance) {
            bestDistance = distance
            best = center
        }
    }
    return best
}
