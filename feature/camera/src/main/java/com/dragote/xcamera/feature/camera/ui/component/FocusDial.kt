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
import androidx.compose.ui.graphics.Color
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
 * Dedicated manual-focus control — a continuous rotation-drag jog, not [DialWheel]'s click-ratchet
 * (focus distance has no discrete stop ladder to click through). Reuses [DialWheel]'s own `internal`
 * flat-line-art drawing primitives ([drawWell]/[drawBarrel]/[drawCenterCarets]) rather than duplicating
 * them.
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
            drawWell()
            drawBarrel(accent, drum, stepPx, valueRange = null)
            drawCenterCarets(accent)
        }

        DialLabelText("FOCUS")
    }
}

private const val VisualStepDp = 64f
private const val DragPxPerFullTurn = 220f

/* ── Previews ────────────────────────────────────────────────────────────── */

@Preview(widthDp = 160, heightDp = 200, backgroundColor = 0xFFFAF6EC, showBackground = true)
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

@Preview(widthDp = 160, heightDp = 200, backgroundColor = 0xFFFAF6EC, showBackground = true)
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
