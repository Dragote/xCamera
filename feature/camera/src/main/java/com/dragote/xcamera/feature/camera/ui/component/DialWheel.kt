package com.dragote.xcamera.feature.camera.ui.component

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dragote.xcamera.feature.camera.ui.theme.CameraChrome.Accent
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
 * Rotating barrel selector (LENS / ISO / SHUTTER) — a physical click-wheel, not a continuous slider.
 * The barrel sits frozen exactly on the current value at all times; dragging only accumulates raw
 * finger travel, and the instant that accumulation crosses [STEP_DP] worth of distance it fires a
 * "click": the value changes, a haptic tick fires, and the barrel does a short, sharp, near-zero-
 * bounce animated jump straight to the next detent (never a partial/proportional one). Any leftover
 * drag distance beyond the threshold carries into the next click rather than being discarded, so one
 * fast continuous swipe can chain through several values instead of being capped at one click per
 * gesture. [dragGain] additionally amplifies fast flicks nonlinearly, on top of that carry-over, so a
 * quick swipe can cross many steps without needing a proportionally long swipe to match.
 *
 * This replaced an earlier continuous 1:1-drag version (drum tracked the finger directly, detents
 * were a rounding threshold with no dead zone) after hands-on testing found it didn't read as a
 * physical wheel and misfired easily — a value could commit the instant a drag rounded to a new
 * integer, so a shaky finger near a boundary would flicker back and forth. A ratchet-style click
 * accumulator *without* an animated per-click jump, and a spring/geared continuous-drag model, were
 * both tried in between and dropped as worse than this.
 */
@Composable
fun DialWheel(
    label: String,
    values: List<String>,
    index: Int,
    onIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Color(0xFF625D51),
    onDragActiveChanged: (Boolean) -> Unit = {},
) {
    val vibrator = rememberDialVibrator()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val maxIndex = values.lastIndex
    val liveIndex by rememberUpdatedState(index)
    // Drag distance required to fire one click, in px-space — also doubles as the px-space distance
    // the barrel jumps per click (and thus how much of a "spin" the teeth do). Decoupling those two
    // roles would just be another parameter for no real gain: the click always covers exactly one
    // tooth's worth of angle regardless of this value, since [TEETH] and this scale together.
    val stepPx = with(density) { STEP_DP.dp.toPx() }

    var drum by remember { mutableStateOf(-index * stepPx) }
    var snapJob by remember { mutableStateOf<Job?>(null) }
    var dragging by remember { mutableStateOf(false) }

    // Only meant to catch *external* index changes (state restoration, a reset button) — our own
    // commits already drive drum via the snap animation below, so this must stay out of their way.
    LaunchedEffect(index) {
        if (!dragging && snapJob == null && abs(drum - (-index * stepPx)) > 0.5f) drum = -index * stepPx
    }

    fun snapTo(target: Int) {
        snapJob?.cancel()
        snapJob = scope.launch {
            Animatable(drum).animateTo(
                targetValue = -target * stepPx,
                // dampingRatio = 1 (critically damped): arrives with no overshoot at all, reads as a
                // hard mechanical click rather than a physics-y settle — a real gear tooth doesn't
                // bounce past where it lands.
                animationSpec = spring(dampingRatio = 1f, stiffness = 1000f),
            ) { drum = value }
            snapJob = null
        }
    }

    Column(
        modifier = modifier.width(107.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        // Value above the wheel, unit label below it — value gets first billing since it's what the
        // user is actively dialing in, and centering both (rather than the old label/value-share-a-row
        // layout) means neither has to fight the other for width: each is the sole occupant of its own
        // full-width line, so a long value like "1/125" never gets squeezed down to nothing the way it
        // did sharing a row with "SHUTTER".
        Text(
            values[index.coerceIn(0, maxIndex)],
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = Color(0xFFDED7C3),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        )

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(117.dp)
                .semantics {
                    role = Role.Button
                    contentDescription = "$label: ${values[index.coerceIn(0, maxIndex)]}"
                }
                .pointerInput(maxIndex, stepPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        onDragActiveChanged(true)
                        dragging = true

                        var committed = liveIndex
                        // Raw drag px accumulated since the *last click* (not since touch-down) — reset
                        // by exactly stepPx on every commit, not clamped to 0, so a fast flick that
                        // overshoots a click's threshold carries the leftover straight into the next
                        // one instead of losing it, letting one continuous swipe chain several clicks.
                        var accum = 0f
                        // Seeded from the actual down time, not 0 — otherwise the first move event's
                        // dt gets floored to 8ms regardless of how much real time actually passed since
                        // touch-down, which made even a slow, deliberate drag's opening motion register
                        // as a huge instantaneous speed and get hit with the fast-flick gain multiplier.
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
                                tickHaptic(vibrator)
                                onIndexChange(committed)
                                snapTo(committed)
                            }
                            while (accum <= -stepPx && committed > 0) {
                                committed--
                                accum += stepPx
                                tickHaptic(vibrator)
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
                }
        ) {
            drawWell()
            drawBarrel(accent, drum, stepPx, valueRange = 0..maxIndex)
            drawCenterCarets(accent)
        }

        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            fontSize = 10.sp,
            letterSpacing = 2.sp,
            color = Color(0xFF877F6C),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        )
    }
}

private const val STEP_DP = 64f // finger travel (dp) required to fire one click

// Nonlinear drag gain: a slow, deliberate drag stays close to 1:1 (fine control over one step at a
// time); a fast flick gets amplified well past 1:1 so a single swipe can cross many steps instead of
// being capped at whatever fits in one screen-length of finger travel at the base step rate.
private const val SPEED_REF_PX_S = 900f
private const val SPEED_GAIN_EXPONENT = 1.6f
private const val MAX_SPEED_GAIN = 5f

private fun dragGain(instantSpeedPxPerSec: Float): Float {
    val ratio = instantSpeedPxPerSec / SPEED_REF_PX_S
    return 1f + min(ratio.pow(SPEED_GAIN_EXPONENT), MAX_SPEED_GAIN - 1f)
}

/**
 * Bypasses [android.view.View.performHapticFeedback] and goes straight to [Vibrator] — but a plain
 * `vibrator.vibrate(effect)` turned out NOT to be enough on its own: modern Android tags any
 * vibration that doesn't say otherwise as [VibrationAttributes.USAGE_TOUCH] by default, and the
 * platform vibrator service gates USAGE_TOUCH on the exact same "Touch feedback" system toggle that
 * gates `performHapticFeedback` — confirmed by testing with that toggle off, where this still
 * produced nothing. [VibrationAttributes.USAGE_HARDWARE_FEEDBACK] (API 33+) is a different category
 * — "feedback for a hardware component, such as a physical button" — that isn't gated by that
 * toggle, so tagging the tick with it is what actually gets it through regardless of that setting.
 * Below API 33 there's no equivalent override; the tick just falls back to whatever the system
 * setting allows. A future iteration of this dial should expose its own in-app haptics on/off (or
 * "match system") preference rather than always silently overriding what the user chose in system
 * settings — this is a deliberate, known gap, not an oversight.
 */
@Composable
private fun rememberDialVibrator(): Vibrator {
    val context = LocalContext.current
    return remember(context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }
}

private fun tickHaptic(vibrator: Vibrator) {
    if (!vibrator.hasVibrator()) return
    val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
    } else {
        VibrationEffect.createOneShot(12L, VibrationEffect.DEFAULT_AMPLITUDE)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val attributes = VibrationAttributes.Builder()
            .setUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK)
            .build()
        vibrator.vibrate(effect, attributes)
    } else {
        vibrator.vibrate(effect)
    }
}

/** Side carets pointing at the centerline — always lit, since the barrel is parked on a real detent
 *  whenever a click isn't actively mid-jump. */
private fun DrawScope.drawCenterCarets(accent: Color) {
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

internal fun DrawScope.drawWell() {
    val r = CornerRadius(19.dp.toPx())
    val rect = RoundRect(Rect(Offset.Zero, size), r)

    // 0 1px 0 rgba(255,255,255,.1) — outer bottom edge highlight
    drawPath(Path().apply { addRoundRect(RoundRect(Rect(Offset(0f, 1.dp.toPx()), size), r)) },
        Color.White.copy(alpha = 0.10f))

    val path = Path().apply { addRoundRect(rect) }
    drawPath(path, Brush.verticalGradient(listOf(Color(0xFF241F1A), Color(0xFF2A2620))))

    clipPath(path) {
        // 0 3px 7px rgba(0,0,0,.75) inset
        drawRect(Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.75f),
            (7.dp.toPx() / size.height) to Color.Transparent,
        ))
        // 0 -1px 0 rgba(255,255,255,.07) inset
        drawRect(Brush.verticalGradient(
            (1f - 2.dp.toPx() / size.height) to Color.Transparent,
            1f to Color.White.copy(alpha = 0.07f),
        ))
    }
}

/* ── Barrel ──────────────────────────────────────────────────────────────── */

internal const val TEETH = 0.4f   // angular tooth spacing, radians
internal const val DRUM_R = 50f   // cylinder radius in projection, px

internal fun DrawScope.drawBarrel(
    accent: Color,
    drum: Float,
    stepPx: Float,
    /** When set, teeth whose absolute detent index falls outside this range aren't drawn — the
     *  visual "the wheel ends here" cue at the first/last value, instead of an infinitely repeating
     *  tooth pattern that implies the barrel keeps going past both ends. */
    valueRange: IntRange? = null,
) {
    val insetX = 7.dp.toPx()
    val insetY = 5.dp.toPx()
    val rect = Rect(insetX, insetY, size.width - insetX, size.height - insetY)
    val path = Path().apply { addRoundRect(RoundRect(rect, CornerRadius(15.dp.toPx()))) }

    // barrel drop shadow: 0 3px 6px rgba(0,0,0,.6)
    translate(0f, 3.dp.toPx()) {
        drawIntoCanvas { canvas ->
            val p = Paint().asFrameworkPaint().apply {
                isAntiAlias = true
                color = android.graphics.Color.TRANSPARENT
                setShadowLayer(6.dp.toPx(), 0f, 0f, Color.Black.copy(alpha = 0.6f).toArgb())
            }
            canvas.nativeCanvas.drawRoundRect(
                rect.left, rect.top, rect.right, rect.bottom,
                15.dp.toPx(), 15.dp.toPx(), p
            )
            p.reset()
        }
    }

    clipPath(path) {
        // cylinder body
        drawRect(
            brush = Brush.verticalGradient(
                0f to lerp(accent, Color.White, 0.18f),
                0.30f to lerp(accent, Color.White, 0.04f),
                0.55f to accent,
                0.80f to lerp(accent, Color.Black, 0.28f),
                1f to lerp(accent, Color.Black, 0.45f),
            ),
            topLeft = rect.topLeft, size = rect.size,
        )

        // teeth: evenly spaced around the circumference, projected onto the visible face.
        // TEETH doesn't evenly divide a full circle (2π / TEETH ≈ 15.7), so any *fixed* window of
        // indices is either too wide (the two ends overlap and double-render) or too narrow (a gap
        // is left over) — and either way, that seam is fixed in the drum's own rotating frame, so
        // it periodically swings into view once per rotation instead of staying on the hidden back.
        // Recentering the window on the current phase keeps the seam pinned to the back at all times.
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
            val y = cy + DRUM_R * sin(t) * (size.height / 117f) // scaled to actual height
            val h = max(1f, 5f * c) * (size.height / 117f)
            val o = 0.35f + 0.65f * c
            // 1px highlight + darkening below, band centered on y so a fixed indicator (the carets)
            // pointing at the same y lines up with what actually reads as the tooth's center.
            val bandTop = y - h / 2f
            drawRect(Color.White.copy(alpha = 0.30f * o), Offset(rect.left, bandTop), Size(rect.width, 1f))
            drawRect(Color.Black.copy(alpha = 0.42f * o), Offset(rect.left, bandTop + 1f), Size(rect.width, h - 1f))
        }

        // vertical cylindrical shading
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.55f),
                0.08f to Color.Black.copy(alpha = 0.18f),
                0.26f to Color.White.copy(alpha = 0.10f),
                0.44f to Color.White.copy(alpha = 0.02f),
                0.66f to Color.Black.copy(alpha = 0.20f),
                0.88f to Color.Black.copy(alpha = 0.55f),
                1f to Color.Black.copy(alpha = 0.72f),
                startY = rect.top, endY = rect.bottom,
            ),
            topLeft = rect.topLeft, size = rect.size,
        )
        // side light from the left
        drawRect(
            brush = Brush.horizontalGradient(
                0f to Color.White.copy(alpha = 0.08f),
                0.12f to Color.White.copy(alpha = 0.02f),
                0.34f to Color.Transparent,
                0.72f to Color.Black.copy(alpha = 0.16f),
                1f to Color.Black.copy(alpha = 0.38f),
                startX = rect.left, endX = rect.right,
            ),
            topLeft = rect.topLeft, size = rect.size,
        )
        // inner edges: 0 1px 0 white .28 / 0 -2px 3px black .5
        drawRect(Color.White.copy(alpha = 0.28f), Offset(rect.left, rect.top), Size(rect.width, 1f))
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.5f),
                startY = rect.bottom - 3.dp.toPx(), endY = rect.bottom,
            ),
            topLeft = Offset(rect.left, rect.bottom - 3.dp.toPx()),
            size = Size(rect.width, 3.dp.toPx()),
        )
    }
}

/* ── Previews ────────────────────────────────────────────────────────────── */

@Preview(widthDp = 200, heightDp = 200, backgroundColor = 0xFF2A2722, showBackground = true)
@Composable
private fun DialWheelPreview() {
    var i by remember { mutableStateOf(1) }
    XCameraTheme {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            DialWheel("LENS", listOf("UW", "W", "T"), i, { i = it }, accent = Accent)
        }
    }
}
