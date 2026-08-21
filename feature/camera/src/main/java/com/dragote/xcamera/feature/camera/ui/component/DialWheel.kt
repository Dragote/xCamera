package com.dragote.xcamera.feature.camera.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dragote.xcamera.feature.camera.ui.theme.CameraChrome
import com.dragote.xcamera.feature.camera.ui.theme.CameraChrome.Accent
import com.dragote.xcamera.shared.designsystem.haptics.hapticTick
import com.dragote.xcamera.shared.designsystem.haptics.rememberHapticTickVibrator
import com.dragote.xcamera.shared.designsystem.theme.XCameraTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Rotating barrel selector (LENS / ISO / SHUTTER) — a physical click-wheel, not a continuous slider,
 * so a drag accumulates toward discrete detents rather than mapping 1:1 to a value. The gesture model
 * (click-detent ratchet, carry-over drag accumulation, nonlinear flick gain, the mechanical
 * `closedFraction` seal/unseal transition and its own timing/easing) is independent of how the
 * well/barrel/shutters are painted — flat black-stroke/black-or-white fills (see
 * [drawWell]/[drawBarrel]/[drawShutterPanels] below).
 */
@Composable
fun DialWheel(
    label: String,
    values: List<String>,
    index: Int,
    onIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Accent,
    onDragActiveChanged: (Boolean) -> Unit = {},
    /** 0 = fully open (normal interactive dial), 1 = fully sealed shut. */
    closedFraction: Float = 0f,
    closing: Boolean = true,
    /** Unused: the deck is one flat [CameraChrome.DeckColor], so the sealed shutters just paint that
     *  same flat color regardless of *where* on the deck this dial sits — kept as parameters purely so
     *  every call site (`ExposureDial`/`IsoDial`/`ShutterSpeedDial`/`ui/CameraScreen`'s own
     *  `ExposureOrManualDials`) doesn't need its own conditional plumbing. */
    backgroundTopY: Float = 0f,
    backgroundHeight: Float = 0f,
    width: Dp = 107.dp,
    canvasHeight: Dp = 117.dp,
) {
    val vibrator = rememberHapticTickVibrator()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val maxIndex = values.lastIndex
    val liveIndex by rememberUpdatedState(index)
    val stepPx = with(density) { STEP_DP.dp.toPx() }

    var drum by remember { mutableStateOf(-index * stepPx) }
    var snapJob by remember { mutableStateOf<Job?>(null) }
    var dragging by remember { mutableStateOf(false) }

    LaunchedEffect(index) {
        if (!dragging && snapJob == null && abs(drum - (-index * stepPx)) > 0.5f) drum = -index * stepPx
    }

    fun snapTo(target: Int) {
        snapJob?.cancel()
        snapJob = scope.launch {
            Animatable(drum).animateTo(
                targetValue = -target * stepPx,
                animationSpec = spring(dampingRatio = 1f, stiffness = 1000f),
            ) { drum = value }
            snapJob = null
        }
    }

    val textAlpha = 1f - segmentProgress(closedFraction, 0f, TEXT_FADE_END)
    val sealed = closedFraction > 0f

    Column(
        modifier = modifier.width(width),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        DialValueText(values[index.coerceIn(0, maxIndex)], alpha = textAlpha)

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(canvasHeight)
                .semantics {
                    role = Role.Button
                    contentDescription = "$label: ${values[index.coerceIn(0, maxIndex)]}"
                }
                .then(
                    if (sealed) Modifier else Modifier.pointerInput(maxIndex, stepPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        onDragActiveChanged(true)
                        dragging = true

                        var committed = liveIndex
                        var accum = 0f
                        var lastT = System.currentTimeMillis()

                        var pointer = down
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointer.id } ?: break
                            if (!change.pressed) break

                            val dy = change.positionChange().y
                            val now = System.currentTimeMillis()
                            val dt = max(8L, now - lastT).toFloat()
                            val instSpeedPxPerSec = abs(dy) / dt * 1000f
                            accum += -dy * dragGain(instSpeedPxPerSec)
                            lastT = now

                            while (accum >= stepPx && committed < maxIndex) {
                                committed++
                                accum -= stepPx
                                vibrator.hapticTick()
                                onIndexChange(committed)
                                snapTo(committed)
                            }
                            while (accum <= -stepPx && committed > 0) {
                                committed--
                                accum += stepPx
                                vibrator.hapticTick()
                                onIndexChange(committed)
                                snapTo(committed)
                            }
                            if (committed == maxIndex) accum = accum.coerceAtMost(0f)
                            if (committed == 0) accum = accum.coerceAtLeast(0f)
                            change.consume()
                            pointer = change
                        }
                        onDragActiveChanged(false)
                        dragging = false
                    }
                    },
                )
        ) {
            val wellPath = Path().apply {
                addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(WellCornerRadius.toPx())))
            }
            clipPath(wellPath) {
                val rawSinkT = segmentProgress(closedFraction, 0f, SINK_END)
                val rawPanelsT = segmentProgress(closedFraction, SINK_END, 1f)

                val sinkT = if (closing) {
                    bounceArrival(rawSinkT, SINK_BOUNCE_AMOUNT)
                } else {
                    1f - bounceArrival(1f - rawSinkT, SINK_BOUNCE_AMOUNT)
                }
                translate(0f, lerp(0f, 4.dp.toPx(), sinkT)) {
                    scale(lerp(1f, 0.80f, sinkT)) {
                        drawBarrel(accent, drum, stepPx, valueRange = 0..maxIndex)
                    }
                }
                // Flat dim scrim, not a gradient/shadow — a plain semi-transparent overlay that still
                // reads as "sinking out of view."
                if (sinkT > 0f) drawRect(CameraChrome.Ink.copy(alpha = 0.35f * sinkT.coerceIn(0f, 1f)))

                val panelsT = if (closing) {
                    easeOutBack(rawPanelsT.pow(SHUTTER_EASE_IN_POWER), SHUTTER_CONTACT_BOUNCE_OVERSHOOT)
                } else {
                    1f - (1f - rawPanelsT).pow(SHUTTER_EASE_IN_POWER)
                }
                if (panelsT > 0f) {
                    // Flat fill matching CameraChrome.DeckColor — no gradient reconstruction needed
                    // (see backgroundTopY/backgroundHeight's own doc above): the deck is one flat color
                    // everywhere now, so sealed reads as "the deck, uninterrupted" regardless of this
                    // dial's own position on it.
                    drawShutterPanels(panelsT.coerceAtLeast(0f), CameraChrome.DeckColor)
                }
            }
            // Well outline drawn last, on top of whatever's sealed/open inside it, so the dial's own
            // boundary always reads as a clean unbroken hairline regardless of closedFraction.
            drawWell()
        }

        DialLabelText(label, alpha = textAlpha)
    }
}

/* ── Mechanical open/close (closedFraction) ───────────────────────────────── */

private const val SINK_END = 0.30f
private const val TEXT_FADE_END = 0.18f
private const val SINK_BOUNCE_AMOUNT = 0.16f
private const val SHUTTER_CONTACT_BOUNCE_OVERSHOOT = 0.45f
private const val SHUTTER_EASE_IN_POWER = 2.6f

private fun bounceArrival(t: Float, bounceAmount: Float, riseFraction: Float = 0.55f): Float {
    val ct = t.coerceIn(0f, 1f)
    return if (ct <= riseFraction) {
        val u = ct / riseFraction
        1f - (1f - u) * (1f - u)
    } else {
        val u = (ct - riseFraction) / (1f - riseFraction)
        1f - bounceAmount * sin(PI.toFloat() * u)
    }
}

private fun easeOutBack(t: Float, overshoot: Float): Float {
    val u = t - 1f
    return 1f + (overshoot + 1f) * u * u * u + overshoot * u * u
}

private fun segmentProgress(value: Float, start: Float, end: Float): Float =
    ((value - start) / (end - start)).coerceIn(0f, 1f)

private fun lerp(start: Float, stop: Float, fraction: Float): Float = start + (stop - start) * fraction

/** Two flat-filled panels sliding in from the left/right edges to meet at center, at [progress] (0 =
 *  fully open, 1 = fully met) — covers everything drawn before it in the same [DrawScope]. A plain
 *  [Color], not a `Brush` gradient — see [DialWheel]'s own `backgroundTopY`/`backgroundHeight` doc for
 *  why that's unnecessary under a one-flat-color deck. */
private fun DrawScope.drawShutterPanels(progress: Float, color: Color) {
    val halfWidth = size.width / 2f * progress
    drawRect(color, topLeft = Offset(0f, 0f), size = Size(halfWidth, size.height))
    drawRect(color, topLeft = Offset(size.width - halfWidth, 0f), size = Size(halfWidth, size.height))
}

private const val STEP_DP = 64f

private const val SPEED_REF_PX_S = 900f
private const val SPEED_GAIN_EXPONENT = 1.6f
private const val MAX_SPEED_GAIN = 5f

private fun dragGain(instantSpeedPxPerSec: Float): Float {
    val ratio = instantSpeedPxPerSec / SPEED_REF_PX_S
    return 1f + min(ratio.pow(SPEED_GAIN_EXPONENT), MAX_SPEED_GAIN - 1f)
}

/** Side carets pointing at the centerline — flat filled triangles, always lit (the barrel is parked on
 *  a real detent whenever a click isn't actively mid-jump). `internal` so [FocusDial] — a different
 *  gesture model over the same well/barrel visual family — can reuse it too. */
internal fun DrawScope.drawCenterCarets(accent: Color) {
    val cy = size.height / 2f
    val w = 5.dp.toPx()
    val h = 9.dp.toPx()
    val inset = 2.dp.toPx()
    drawPath(
        Path().apply {
            moveTo(inset, cy - h / 2f)
            lineTo(inset + w, cy)
            lineTo(inset, cy + h / 2f)
            close()
        },
        accent,
    )
    drawPath(
        Path().apply {
            moveTo(size.width - inset, cy - h / 2f)
            lineTo(size.width - inset - w, cy)
            lineTo(size.width - inset, cy + h / 2f)
            close()
        },
        accent,
    )
}

/* ── Well ────────────────────────────────────────────────────────────────── */

internal val WellCornerRadius = 19.dp

/** The well's own black-stroke rounded-rect boundary, no fill/gradient/inset shadow — the well and the
 *  deck it sits on are the same flat [CameraChrome.Background], so there's nothing to visually
 *  separate them but this one hairline. `internal` (not `private`) so [FocusDial] — a different
 *  gesture model over the same well/barrel visual family — can reuse it too, mirroring
 *  [drawBarrel]/[drawCenterCarets]'s own visibility. */
internal fun DrawScope.drawWell() {
    val path = Path().apply {
        addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(WellCornerRadius.toPx())))
    }
    drawPath(path, color = CameraChrome.StrokeColor, style = Stroke(width = CameraChrome.StrokeWidth.toPx()))
}

/* ── Barrel ──────────────────────────────────────────────────────────────── */

internal const val TEETH = 0.4f
internal const val DRUM_R = 50f

/**
 * A black-stroke rounded rect (no gradient fill, no cast shadow) with a row of thin flat tick lines
 * standing in for rotating "teeth," projected via [TEETH]/[DRUM_R] and a `centerI` recentering-window
 * trick that keeps the wrap seam pinned to the barrel's own hidden back. Each tooth is one flat
 * [CameraChrome.StrokeColor] line whose length/opacity falls off toward the barrel's own visual
 * "edges" (`c`), so the wheel reads as something turning in depth, drawn as line art rather than
 * shaded metal.
 */
internal fun DrawScope.drawBarrel(
    accent: Color,
    drum: Float,
    stepPx: Float,
    valueRange: IntRange? = null,
) {
    val insetX = 7.dp.toPx()
    val insetY = 5.dp.toPx()
    val rect = Rect(insetX, insetY, size.width - insetX, size.height - insetY)
    val cornerPx = 15.dp.toPx()
    val path = Path().apply { addRoundRect(RoundRect(rect, CornerRadius(cornerPx))) }

    clipPath(path) {
        val phase = drum * (TEETH / stepPx)
        val cy = rect.center.y
        val n = floor(PI / TEETH).toInt()
        val centerI = (-phase / TEETH).roundToInt()
        for (iOffset in -n..n) {
            val i = centerI + iOffset
            if (valueRange != null && i !in valueRange) continue
            val t = ((i * TEETH + phase + PI.toFloat()) % (2 * PI.toFloat()) + 2 * PI.toFloat()) %
                (2 * PI.toFloat()) - PI.toFloat()
            val c = cos(t)
            if (c <= 0.06f) continue
            val y = cy + DRUM_R * sin(t) * (size.height / 117f)
            val h = max(1f, 5f * c) * (size.height / 117f)
            val tickWidth = rect.width * 0.62f
            drawRect(
                color = accent.copy(alpha = (0.35f + 0.65f * c).coerceIn(0f, 1f)),
                topLeft = Offset(rect.center.x - tickWidth / 2f, y - h / 2f),
                size = Size(tickWidth, h.coerceAtLeast(1f)),
            )
        }
    }

    drawPath(path, color = CameraChrome.StrokeColor, style = Stroke(width = CameraChrome.StrokeWidth.toPx()))
}

/* ── Previews ────────────────────────────────────────────────────────────── */

@Preview(widthDp = 200, heightDp = 200, backgroundColor = 0xFFFAF6EC, showBackground = true)
@Composable
private fun DialWheelPreview() {
    var i by remember { mutableStateOf(1) }
    XCameraTheme {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            DialWheel("LENS", listOf("UW", "W", "T"), i, { i = it }, accent = Accent)
        }
    }
}

@Preview(widthDp = 200, heightDp = 200, backgroundColor = 0xFFFAF6EC, showBackground = true)
@Composable
private fun DialWheelSealedPreview() {
    XCameraTheme {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            DialWheel(
                "LENS", listOf("UW", "W", "T"), 1, {}, accent = Accent,
                closedFraction = 1f, closing = true,
            )
        }
    }
}
