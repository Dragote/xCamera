package com.dragote.xcamera.shared.designsystem.component.control

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dragote.xcamera.shared.designsystem.haptics.hapticTick
import com.dragote.xcamera.shared.designsystem.haptics.rememberHapticTickVibrator
import com.dragote.xcamera.shared.designsystem.theme.MinimalChrome
import com.dragote.xcamera.shared.designsystem.theme.XCameraTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Which axis [SteppedToggle]'s track runs along. Not yet wired to any real screen slot — only
 *  `LensDial` uses [Horizontal] today — but requested up front so the component itself doesn't need a
 *  breaking change the first time a vertical slot shows up. */
enum class SteppedToggleOrientation { Horizontal, Vertical }

/**
 * Multi-position sibling of [Toggle] — generalizes the same capsule-track/circular-knob visual
 * language from 2 fixed states to N evenly-spaced detents along a straight track, in either
 * [SteppedToggleOrientation]. Used for controls where every position is a genuinely bounded, visible
 * stop along a line (e.g. `LensDial`'s UW/W/T), as opposed to `DialWheel`'s virtual-infinite-wheel
 * gesture model for ISO/SHUTTER: this track has real, visible ends, so it gets a direct 1:1 slider
 * drag instead of `DialWheel`'s velocity-accumulation.
 *
 * Reuses [Toggle]'s own [drawTrackOutline]/[drawKnob] verbatim (`internal` there for exactly this). The
 * knob is always solid-filled — unlike [Toggle], there is no "unchecked" position here, every index is a
 * valid selected value. No external value/label text (unlike `DialWheel`'s own `DialValueText`/
 * `DialLabelText` siblings) — the current value rides *inside* the knob instead, [MinimalChrome.Background]
 * on the always-filled [MinimalChrome.Ink] knob, the same "content rides with the knob and inverts against
 * its fill" contract [Toggle]'s own `knobContent` uses. This also keeps the whole component's cross-axis
 * footprint at exactly [TrackHeight] (matching [Toggle]'s own total height/`SettingsButton`'s circle) in
 * both orientations — an external label would need its own `fillMaxWidth()`-bounded width, well-defined
 * for [SteppedToggleOrientation.Horizontal]'s wide track but not for [SteppedToggleOrientation.Vertical]'s
 * narrow one.
 *
 * Interaction:
 * - **Drag**: the knob follows the finger 1:1 along the track's primary axis (not `DialWheel`'s gain
 *   curve), clamped to the track's own bounds. As the live drag position crosses into a different
 *   detent's nearest-neighbor zone, [onIndexChange] fires immediately (and a haptic tick), mirroring
 *   `DialWheel`'s own "report on each step crossed, not just on release" feel. A touch-slop gate (the
 *   platform's own [androidx.compose.ui.platform.ViewConfiguration.touchSlop]) means a drag that never
 *   clears the slop doesn't move the knob or fire anything — see "Tap" below for what happens instead.
 * - **Release** (after a real drag): the knob animates with a plain critically-damped [spring] onto the
 *   exact position of whichever detent was last reported, the same spring family `DialWheel`'s own
 *   `snapTo` uses.
 * - **Tap** (down+up that never clears touch slop): advances to the next index, wrapping to `0` past the
 *   last value, and fires the same haptic tick — deliberately *not* "jump to wherever was tapped," so a
 *   stray tap anywhere on the track always just steps forward one, like a physical detent switch.
 */
@Composable
fun SteppedToggle(
    label: String,
    values: List<String>,
    index: Int,
    onIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    orientation: SteppedToggleOrientation = SteppedToggleOrientation.Horizontal,
) {
    require(values.isNotEmpty()) { "SteppedToggle needs at least one value" }
    val vibrator = rememberHapticTickVibrator()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val maxIndex = values.lastIndex
    val liveIndex by rememberUpdatedState(index.coerceIn(0, maxIndex))
    val stepPx = with(density) { KnobDiameter.toPx() }
    val travelPx = stepPx * maxIndex

    var knobPrimaryPx by remember { mutableFloatStateOf(liveIndex * stepPx) }
    var snapJob by remember { mutableStateOf<Job?>(null) }
    var dragging by remember { mutableStateOf(false) }

    LaunchedEffect(index, maxIndex) {
        if (!dragging && snapJob == null) {
            val target = liveIndex * stepPx
            if (abs(knobPrimaryPx - target) > 0.5f) knobPrimaryPx = target
        }
    }

    fun snapTo(target: Int) {
        snapJob?.cancel()
        snapJob = scope.launch {
            Animatable(knobPrimaryPx).animateTo(
                targetValue = target * stepPx,
                animationSpec = spring(dampingRatio = 1f, stiffness = 1000f),
            ) { knobPrimaryPx = value }
            snapJob = null
        }
    }

    // Track length grows by one KnobDiameter step per extra value, same spacing Toggle's own 2-position
    // TrackWidth already uses between its two knob positions (see KnobTravel's own doc) — this is just
    // that same formula generalized from N=2 to N values.
    val trackLength = KnobDiameter * values.size + KnobInset * 2
    val boxSize = when (orientation) {
        SteppedToggleOrientation.Horizontal -> DpSize(trackLength, TrackHeight)
        SteppedToggleOrientation.Vertical -> DpSize(TrackHeight, trackLength)
    }

    val insetPx = with(density) { KnobInset.roundToPx() }

    Box(modifier.size(boxSize.width, boxSize.height)) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    role = Role.Button
                    contentDescription = "$label: ${values[liveIndex]}"
                }
                .pointerInput(orientation, maxIndex, stepPx) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val downPrimary = if (orientation == SteppedToggleOrientation.Horizontal) {
                            down.position.x
                        } else {
                            down.position.y
                        }
                        dragging = true
                        var committed = liveIndex
                        var livePrimary = knobPrimaryPx
                        var totalMovement = 0f
                        var engaged = false

                        var pointer = down
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointer.id } ?: break
                            if (!change.pressed) break

                            val positionDelta = change.positionChange()
                            val delta = if (orientation == SteppedToggleOrientation.Horizontal) {
                                positionDelta.x
                            } else {
                                positionDelta.y
                            }
                            totalMovement += abs(delta)
                            if (!engaged && totalMovement > slop) engaged = true

                            if (engaged) {
                                livePrimary = (livePrimary + delta).coerceIn(0f, travelPx)
                                knobPrimaryPx = livePrimary
                                val nearest = (livePrimary / stepPx).roundToInt().coerceIn(0, maxIndex)
                                if (nearest != committed) {
                                    committed = nearest
                                    vibrator.hapticTick()
                                    onIndexChange(committed)
                                }
                            }
                            change.consume()
                            pointer = change
                        }

                        dragging = false
                        if (engaged) {
                            snapTo(committed)
                        } else {
                            // Plain tap — never cleared touch slop. Jumps straight to whichever detent is
                            // nearest the tapped point (same nearest-detent math the drag loop above uses,
                            // evaluated once at the down position) rather than always stepping forward one —
                            // tapping a specific value selects that value directly.
                            val radiusPx = stepPx / 2f
                            val tapped = ((downPrimary - insetPx - radiusPx) / stepPx)
                                .roundToInt()
                                .coerceIn(0, maxIndex)
                            if (tapped != liveIndex) {
                                vibrator.hapticTick()
                                onIndexChange(tapped)
                            }
                            snapTo(tapped)
                        }
                    }
                },
        ) {
            drawTrackOutline()
            drawDetentDots(maxIndex, stepPx, orientation)
            val offset = primaryOffset(knobPrimaryPx, orientation)
            translate(left = offset.x, top = offset.y) { drawKnob(filled = true) }
        }

        // Rides with the knob exactly like Toggle's own knobContent overlay — same idea, but positioned
        // from a live pixel offset (knobPrimaryPx, driven by drag/spring) rather than Toggle's 0..1
        // animateFloatAsState fraction, since this track has N stops, not 2.
        Box(
            modifier = Modifier
                .offset { knobContentOffsetPx(knobPrimaryPx, orientation, insetPx) }
                .size(KnobDiameter),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = values[liveIndex],
                style = TextStyle(
                    fontFamily = MinimalChrome.Mono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MinimalChrome.Background,
                ),
            )
        }
    }
}

private fun knobContentOffsetPx(primaryPx: Float, orientation: SteppedToggleOrientation, insetPx: Int): IntOffset {
    val primary = insetPx + primaryPx.roundToInt()
    return if (orientation == SteppedToggleOrientation.Horizontal) {
        IntOffset(primary, insetPx)
    } else {
        IntOffset(insetPx, primary)
    }
}

/** Maps a live position along the track's primary axis to a [Translate][translate]-ready [Offset] —
 *  the one piece of geometry [SteppedToggle]'s two orientations genuinely differ on; everything else
 *  ([drawTrackOutline]/[drawKnob]/gesture math) is written in terms of a single primary-axis scalar and
 *  stays identical between [SteppedToggleOrientation.Horizontal] and [.Vertical]. */
private fun primaryOffset(primaryPx: Float, orientation: SteppedToggleOrientation): Offset =
    if (orientation == SteppedToggleOrientation.Horizontal) Offset(primaryPx, 0f) else Offset(0f, primaryPx)

/** Small filled dots marking each of the [0..maxIndex] detent positions — unlike [Toggle] (only ever 2
 *  positions, both track ends, self-evident), a track with 3+ stops needs *some* indication of how many
 *  there are and where; the knob itself covers whichever dot sits under the current/live position. */
private fun DrawScope.drawDetentDots(maxIndex: Int, stepPx: Float, orientation: SteppedToggleOrientation) {
    val dotRadius = DetentDotRadius.toPx()
    val cross = KnobInset.toPx() + KnobDiameter.toPx() / 2f
    for (i in 0..maxIndex) {
        val primary = KnobInset.toPx() + KnobDiameter.toPx() / 2f + i * stepPx
        val center = if (orientation == SteppedToggleOrientation.Horizontal) {
            Offset(primary, cross)
        } else {
            Offset(cross, primary)
        }
        drawCircle(color = MinimalChrome.Ink, radius = dotRadius, center = center)
    }
}

private val DetentDotRadius = 2.5.dp

/* ── Previews ────────────────────────────────────────────────────────────── */

@Preview(showBackground = true, backgroundColor = 0xFFFAF6EC)
@Composable
private fun SteppedTogglePreview() {
    XCameraTheme {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SteppedToggle(
                label = "LENS",
                values = listOf("UW", "W", "T"),
                index = 0,
                onIndexChange = {},
                orientation = SteppedToggleOrientation.Horizontal,
            )
            SteppedToggle(
                label = "LENS",
                values = listOf("UW", "W", "T"),
                index = 2,
                onIndexChange = {},
                orientation = SteppedToggleOrientation.Horizontal,
            )
            SteppedToggle(
                label = "LENS",
                values = listOf("UW", "W", "T"),
                index = 1,
                onIndexChange = {},
                orientation = SteppedToggleOrientation.Vertical,
            )
        }
    }
}