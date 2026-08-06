package com.dragote.xcamera.feature.camera.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dragote.xcamera.feature.camera.domain.model.formatFocusDistance
import com.dragote.xcamera.feature.camera.ui.theme.CameraChrome.Accent
import com.dragote.xcamera.shared.designsystem.theme.XCameraTheme
import kotlin.math.PI

/**
 * Dedicated manual-focus control (issue #21 UX rework) — visually the same barrel/well family as
 * [DialWheel] (reuses its `internal` drawing primitives: [drawBarrel]/[drawWell]/[drawCenterCarets]/
 * [WellCornerRadius]), but a fundamentally different *gesture* model: [DialWheel] is a click-to-next-
 * value ratchet, this is a continuous rotation-drag jog control, since there's no discrete value ladder
 * for focus distance the way there is for ISO/shutter/lens selection. Touch-down begins tracking
 * immediately — unlike the viewfinder's own long-press-vs-tap disambiguation
 * (`ui/CameraScreen.detectFocusGestures`), there's no competing tap gesture on this control to guard
 * against.
 *
 * Dragging vertically (mirroring [DialWheel]'s own drag axis) accumulates a continuous rotation value,
 * in the same radians unit
 * [com.dragote.xcamera.feature.camera.domain.model.manualFocusDistanceForRotation] consumes — this
 * composable does no diopter math itself, it just reports [onRotate]'s raw accumulated angle and lets
 * the caller (`ui/CameraScreen`) drive both the manual focus distance and the manual focus ring's own
 * `rotationDegrees` from it, the same function the old viewfinder-rotation gesture used. [onHoldStart]/
 * [onHoldEnd] bookend one continuous press — releasing doesn't reset the underlying focus distance
 * (the caller simply stops calling `CameraViewModel.setManualFocusDistance` with a new value, per
 * `CameraController.setManualFocusDistance`'s own "commit is just not clearing the pending value"
 * doc), so whatever distance was last dialed in stays locked.
 *
 * [focusDistanceDiopters] is purely a display value (the value readout below the barrel, via
 * [formatFocusDistance]) — this composable holds no focus-distance state of its own, matching every
 * other dial in this file.
 */
@Composable
fun FocusDial(
    focusDistanceDiopters: Float,
    onHoldStart: () -> Unit,
    onRotate: (totalRotationRadians: Float) -> Unit,
    onHoldEnd: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Accent,
) {
    val density = LocalDensity.current
    val stepPx = with(density) { VisualStepDp.dp.toPx() }
    val dragPxPerFullTurn = with(density) { DragPxPerFullTurn.dp.toPx() }

    // Purely the barrel's own visual spin position — reset to 0 at the start of every press (see
    // pointerInput below) rather than persisted across releases, since (unlike DialWheel's `drum`)
    // there's no "settled detent" this needs to return to; the *actual* focus distance this gesture
    // drives lives entirely in the caller's state via onRotate/onHoldStart/onHoldEnd.
    var drum by remember { mutableFloatStateOf(0f) }

    Column(
        modifier = modifier.width(107.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        DialValueText(formatFocusDistance(focusDistanceDiopters))

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(117.dp)
                .semantics {
                    role = Role.Button
                    contentDescription = "FOCUS: ${formatFocusDistance(focusDistanceDiopters)}"
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        drum = 0f
                        onHoldStart()
                        var totalDragPx = 0f

                        var pointer = down
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointer.id } ?: break
                            if (!change.pressed) break

                            val dy = change.positionChange().y
                            // Dragging up (negative dy) increases totalDragPx, mirroring DialWheel's
                            // own "-dy" convention (up = further into the value range).
                            totalDragPx += -dy
                            drum = totalDragPx
                            val totalRotationRadians = totalDragPx * (2f * PI.toFloat()) / dragPxPerFullTurn
                            onRotate(totalRotationRadians)
                            change.consume()
                            pointer = change
                        }
                        onHoldEnd()
                    }
                },
        ) {
            val wellPath = Path().apply {
                addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(WellCornerRadius.toPx())))
            }
            clipPath(wellPath) {
                drawWell()
                drawBarrel(accent, drum, stepPx, valueRange = null)
                drawCenterCarets(accent)
            }
        }

        DialLabelText("FOCUS")
    }
}

// Purely a visual tooth-spacing scale for drawBarrel's own phase math (see DialWheel.STEP_DP) — this
// dial has no discrete step/click semantics of its own, so this doesn't need to match DialWheel's
// value.
private const val VisualStepDp = 64f

/** How much total vertical drag (dp) it takes to accumulate one full 2π rotation — chosen so the
 *  lens's whole [0, maxFocusDistanceDiopters] range (~1.5 turns, see
 *  [com.dragote.xcamera.feature.camera.domain.model.manualFocusDistanceForRotation]) sweeps across a
 *  comfortable few-finger-widths of drag, not a single screen-length swipe or an imperceptibly tiny
 *  twitch. */
private const val DragPxPerFullTurn = 220f

/* ── Previews ────────────────────────────────────────────────────────────── */

@Preview(widthDp = 160, heightDp = 200, backgroundColor = 0xFF2A2722, showBackground = true)
@Composable
private fun FocusDialInfinityPreview() {
    XCameraTheme {
        Box(Modifier.padding(24.dp)) {
            FocusDial(
                focusDistanceDiopters = 0f,
                onHoldStart = {},
                onRotate = {},
                onHoldEnd = {},
            )
        }
    }
}

@Preview(widthDp = 160, heightDp = 200, backgroundColor = 0xFF2A2722, showBackground = true)
@Composable
private fun FocusDialMidRangePreview() {
    XCameraTheme {
        Box(Modifier.padding(24.dp)) {
            FocusDial(
                focusDistanceDiopters = 0.8f,
                onHoldStart = {},
                onRotate = {},
                onHoldEnd = {},
            )
        }
    }
}

@Preview(widthDp = 160, heightDp = 200, backgroundColor = 0xFF2A2722, showBackground = true)
@Composable
private fun FocusDialMacroPreview() {
    XCameraTheme {
        Box(Modifier.padding(24.dp)) {
            FocusDial(
                focusDistanceDiopters = 8f,
                onHoldStart = {},
                onRotate = {},
                onHoldEnd = {},
            )
        }
    }
}
