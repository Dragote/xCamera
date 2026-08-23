package com.dragote.xcamera.feature.camera.ui.component.overlay

import android.view.OrientationEventListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
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

/**
 * Both the coarse, hysteresis-debounced [quadrant] ([nextQuadrant]'s own bucket, as returned by
 * [rememberDeviceOrientationQuadrant]) and [smoothedOrientationDegrees] — a continuous,
 * low-pass-filtered ([smoothOrientationDegrees]) view of the same raw [OrientationEventListener]
 * reading — for the same callback. `HorizonLineOverlay` needs both: [quadrant] for its static
 * reference line (always snapped to the nearest 90° bucket, never jittery), and
 * [smoothedOrientationDegrees] for its dynamic tilt-readout line (tracks the device's real-world roll
 * continuously). Pulling both from one state object here means one physical listener registration
 * instead of `HorizonLineOverlay` running a second [OrientationEventListener] alongside whatever
 * quadrant-only listener a sibling overlay already has running.
 */
data class DeviceOrientationState(val quadrant: Float, val smoothedOrientationDegrees: Float)

/** See [DeviceOrientationState]'s own doc for why this exists alongside [rememberDeviceOrientationQuadrant]
 *  rather than replacing it — existing quadrant-only consumers ([HistogramOverlay], [ViewfinderThumbnailChip])
 *  have no need for the continuous value, so they're left on the simpler `State<Float>` shape. */
@Composable
fun rememberDeviceOrientationState(): State<DeviceOrientationState> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(DeviceOrientationState(quadrant = 0f, smoothedOrientationDegrees = 0f)) }

    DisposableEffect(context) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val raw = orientation.toFloat()
                state.value = DeviceOrientationState(
                    quadrant = nextQuadrant(state.value.quadrant, raw),
                    smoothedOrientationDegrees = smoothOrientationDegrees(
                        current = state.value.smoothedOrientationDegrees,
                        target = raw,
                        factor = OrientationSmoothingFactor,
                    ),
                )
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }

    return state
}

/** Clockwise on-screen rotation that visually counters a physical device rotation of [quadrant]°, so
 *  content drawn with this rotation applied stays upright to a viewer whose own head hasn't moved.
 *  Shared by [ViewfinderThumbnailChip] (animates smoothly through this) and `HistogramOverlay` (snaps
 *  straight to it per corner, no animation — see [rememberDeviceOrientationQuadrant]'s own doc for why
 *  each treats a quadrant change differently). */
fun counterRotationDegrees(quadrant: Float): Float = (360f - quadrant) % 360f

/**
 * Panel-local corner that currently coincides with whichever physical corner [referenceCorner]
 * names, as the device sits rotated by [quadrant]° clockwise (as a fixed outside viewer would see
 * it) from natural/portrait — used to keep a corner-anchored readout ([HistogramOverlay],
 * [ViewfinderThumbnailChip]) visually pinned to the *same physical corner from the user's own point
 * of view* even though this screen's own layout never rotates (see AndroidManifest's portrait lock).
 *
 * [QuadrantCornerCycle] is [Alignment.TopEnd]/[Alignment.TopStart]/[Alignment.BottomStart]/
 * [Alignment.BottomEnd] — derived from first principles (rotate each panel corner by 90° at a time
 * and track which one lands at the viewer's top-right) rather than guessed. Any other reference
 * corner's own per-quadrant sequence is just this same cycle started at a different index — a corner
 * diagonally opposite [Alignment.TopEnd], e.g. [Alignment.BottomStart], lands two steps further
 * along it, since a 180° phase shift is exactly two 90°-steps around a 4-element cycle.
 */
fun cornerForQuadrant(referenceCorner: Alignment, quadrant: Float): Alignment {
    val referenceIndex = QuadrantCornerCycle.indexOf(referenceCorner)
    val steps = (quadrant / 90f).toInt()
    return QuadrantCornerCycle[(referenceIndex + steps).mod(QuadrantCornerCycle.size)]
}

private val QuadrantCornerCycle =
    listOf(Alignment.TopEnd, Alignment.TopStart, Alignment.BottomStart, Alignment.BottomEnd)

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

/** Exponential-moving-average step per [OrientationEventListener] callback — `HorizonLineOverlay`'s
 *  dynamic line reads straight off [DeviceOrientationState.smoothedOrientationDegrees] rather than the
 *  raw sensor callback, since the raw accelerometer-derived value has enough per-callback noise that
 *  drawing it directly reads as a visible shake on every small hand tremor. This is a fixed-fraction
 *  low-pass filter (no dt/frame-clock dependency, just "move [factor] of the way from [current] toward
 *  [target] on each raw callback") rather than a Compose-side animation, so it stays a plain testable
 *  function with no composition/coroutine plumbing. */
const val OrientationSmoothingFactor = 0.2f

/**
 * Moves [current] a [factor] fraction of the way toward [target], both angles in degrees, wrapping
 * correctly across the 0°/360° boundary — reuses [angularDistance]'s shortest-signed-path math so a
 * target of e.g. `2f` from a current of `358f` steps *forward* through the wrap (358 → 360/0 → 2)
 * rather than the long way around through 180°. The result is always folded back into `[0, 360)` so
 * it composes safely with a further [counterRotationDegrees] call.
 */
fun smoothOrientationDegrees(current: Float, target: Float, factor: Float): Float {
    val step = angularDistance(target, current) * factor
    return (current + step + 360f) % 360f
}
