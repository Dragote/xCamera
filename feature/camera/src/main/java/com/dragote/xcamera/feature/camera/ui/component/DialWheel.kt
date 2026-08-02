package com.dragote.xcamera.feature.camera.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Rotating barrel selector (LENS / ZOOM).
 *
 * The notches aren't decoration — they're teeth evenly spaced AROUND THE CIRCUMFERENCE of the
 * cylinder and projected onto the visible face. That's where the bunching toward the edges and
 * the brightness falloff come from.
 *
 * Physics 1:1 with the design:
 *   drag    — 1dp of finger = 1dp of drum scroll; detent step = [stepPx]
 *   release — coast with 0.9/frame decay, then settle onto the detent (lerp 0.22)
 *   detent  — a haptic tick the moment the value changes (including during coast)
 */
@Composable
fun DialWheel(
    label: String,
    values: List<String>,
    index: Int,
    onIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Color(0xFF625D51),
    stepPx: Float = 42f,
    onDragActiveChanged: (Boolean) -> Unit = {},
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val maxIndex = values.lastIndex
    val liveIndex by rememberUpdatedState(index)

    // Drum position in dp-space. Detent i corresponds to drum = -i * stepPx.
    var drum by remember { mutableStateOf(-index * stepPx) }
    var coasting by remember { mutableStateOf<Job?>(null) }

    // External index change (buttons, state restoration) — settle the drum onto it.
    LaunchedEffect(index) {
        if (coasting == null && abs(drum - (-index * stepPx)) > 0.5f) drum = -index * stepPx
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
                        coasting?.cancel(); coasting = null

                        val startDrum = drum
                        val startIndex = index
                        var dy = 0f
                        var vel = 0f
                        var lastDy = 0f
                        var lastT = 0L

                        var pointer = down
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointer.id } ?: break
                            if (!change.pressed) break

                            dy += change.positionChange().y
                            val now = System.currentTimeMillis()
                            val dt = max(8L, now - (if (lastT == 0L) now else lastT)).toFloat()
                            vel = (dy - lastDy) / dt * 16f
                            lastDy = dy; lastT = now

                            val target = (startIndex + (-dy / stepPx).roundToInt()).coerceIn(0, maxIndex)
                            if (target != liveIndex) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onIndexChange(target)
                            }
                            drum = startDrum + dy
                            change.consume()
                            pointer = change
                        }
                        onDragActiveChanged(false)

                        // coast + settle
                        coasting = scope.launch {
                            var v = vel.coerceIn(-26f, 26f)
                            while (isActive) {
                                val targetDrum = -liveIndex * stepPx
                                if (abs(v) > 0.35f) {
                                    v *= 0.9f
                                    var next = drum + v
                                    val clamped = next.coerceIn(-maxIndex * stepPx, 0f)
                                    if (clamped != next) { next = clamped; v = 0f }
                                    val i = (-next / stepPx).roundToInt().coerceIn(0, maxIndex)
                                    if (i != liveIndex) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onIndexChange(i)
                                    }
                                    drum = next
                                } else {
                                    val next = drum + (targetDrum - drum) * 0.22f
                                    if (abs(targetDrum - next) < 0.4f) { drum = targetDrum; break }
                                    drum = next
                                }
                                withFrameNanos { }
                            }
                            coasting = null
                        }
                    }
                }
        ) {
            drawWell()
            drawBarrel(accent, drum, stepPx)
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

/* ── Well ────────────────────────────────────────────────────────────────── */

private fun DrawScope.drawWell() {
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

private const val TEETH = 0.4f   // angular tooth spacing, radians
private const val DRUM_R = 50f   // cylinder radius in projection, px

private fun DrawScope.drawBarrel(accent: Color, drum: Float, stepPx: Float) {
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
            val t = ((i * TEETH + phase + PI.toFloat()) % (2 * PI.toFloat()) + 2 * PI.toFloat()) %
                    (2 * PI.toFloat()) - PI.toFloat()
            val c = cos(t)
            if (c <= 0.06f) continue
            val y = cy + DRUM_R * sin(t) * (size.height / 117f) // scaled to actual height
            val h = max(1f, 5f * c) * (size.height / 117f)
            val o = 0.35f + 0.65f * c
            // 1px highlight on top + darkening below — matches the tooth's CSS gradient
            drawRect(Color.White.copy(alpha = 0.30f * o), Offset(rect.left, y), Size(rect.width, 1f))
            drawRect(Color.Black.copy(alpha = 0.42f * o), Offset(rect.left, y + 1f), Size(rect.width, h - 1f))
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

@Preview(widthDp = 160, heightDp = 160, backgroundColor = 0xFF2A2722, showBackground = true)
@Composable
private fun DialWheelPreview() {
    var i by remember { mutableStateOf(1) }
    XCameraTheme {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            DialWheel("LENS", listOf("UW", "W", "T"), i, { i = it }, accent = Accent)
        }
    }
}