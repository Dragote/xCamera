package com.dragote.xcamera.feature.camera.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dragote.xcamera.shared.designsystem.theme.XCameraTheme

/**
 * Shutter release button — neo-skeuomorphism.
 *
 * Geometry 1:1 with the design:
 *   128dp well, 102dp button, inner ring inset by 9dp.
 *
 * Behavior:
 *   press   — 80ms ease-out: shifts down 3dp + scales to 0.97, shadow collapses inward
 *   release — spring with overshoot (analogous to cubic-bezier(.3,1.25,.5,1), ~380ms)
 *   onHalfPress fires on press (AF-lock), onCapture fires on a valid release.
 */
@Composable
fun ShutterButton(
    modifier: Modifier = Modifier,
    accent: Color = Color(0xFFE8632A),
    onHalfPress: () -> Unit = {},
    onCapture: () -> Unit = {},
    enabled: Boolean,
) {
    var pressed by remember { mutableStateOf(false) }

    // 0f = at rest, 1f = pressed. One value drives the shift, scale, and shadow mix.
    val press = remember { Animatable(0f) }

    LaunchedEffect(pressed) {
        if (pressed) {
            press.animateTo(1f, tween(80, easing = LinearOutSlowInEasing))
        } else {
            // dampingRatio < 1 gives the same slight overshoot as cubic-bezier(.3,1.25,.5,1)
            press.animateTo(
                0f,
                spring(dampingRatio = 0.52f, stiffness = Spring.StiffnessMediumLow)
            )
        }
    }

    val p = press.value

    Box(
        modifier = modifier.size(WELL_SIZE),
        contentAlignment = Alignment.Center
    ) {
        // ── Well (recess) ────────────────────────────────────────────────
        Canvas(Modifier.size(WELL_SIZE)) {
            val r = size.minDimension / 2f
            val c = Offset(r, r)

            // outer highlight along the bottom edge: 0 1px 0 rgba(255,255,255,.1)
            drawCircle(Color.White.copy(alpha = 0.10f), r, c.copy(y = c.y + 1.dp.toPx()))

            drawCircle(
                brush = Brush.verticalGradient(
                    0f to Color(0xFF171511),
                    0.45f to Color(0xFF242118),
                    1f to Color(0xFF3B372E),
                ),
                radius = r,
                center = c,
            )
            // 0 4px 10px rgba(0,0,0,.85) inset — shadow cast inward from the top
            insetShadow(
                center = c, radius = r,
                color = Color.Black.copy(alpha = 0.85f),
                offsetY = 4.dp.toPx(), blur = 10.dp.toPx(),
            )
            // 0 -1px 0 rgba(255,255,255,.1) inset — highlight along the inner bottom edge
            insetShadow(
                center = c, radius = r,
                color = Color.White.copy(alpha = 0.10f),
                offsetY = -1.dp.toPx(), blur = 1.5.dp.toPx(),
            )
        }

        // ── Button ──────────────────────────────────────────────────────────
        Canvas(
            Modifier
                .size(BUTTON_SIZE)
                .semantics {
                    role = Role.Button
                    contentDescription = "Shutter release"
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        pressed = enabled
                        onHalfPress()
                        val up = waitForUpOrCancellation()
                        pressed = false
                        if (up != null) onCapture()
                    }
                }
        ) {
            val dy = 3.dp.toPx() * p          // translateY(3px)
            val s = 1f - 0.03f * p            // scale(.97)

            translate(top = dy) {
                scale(s, pivot = center) {
                    val r = size.minDimension / 2f
                    val c = center

                    drawShutterFace(c, r, accent, p)
                }
            }
        }
    }
}

/** Button body: shadows, gradient, gloss, noise, and inner ring. */
private fun DrawScope.drawShutterFace(c: Offset, r: Float, accent: Color, p: Float) {
    // ── Outer drop shadow: 0 9px 18px -4px rgba(0,0,0,.8) → 0 2px 5px rgba(0,0,0,.7) when pressed
    val dropY = lerpF(9.dp.toPx(), 2.dp.toPx(), p)
    val dropBlur = lerpF(18.dp.toPx(), 5.dp.toPx(), p)
    val dropSpread = lerpF(-4.dp.toPx(), 0f, p)
    dropShadow(
        center = c.copy(y = c.y + dropY),
        radius = r + dropSpread,
        blur = dropBlur,
        color = Color.Black.copy(alpha = lerpF(0.80f, 0.70f, p)),
    )

    // ── Fill: linear-gradient(180deg, accent+16% white, accent 48%, accent+20% black)
    drawCircle(
        brush = Brush.verticalGradient(
            0f to lerp(accent, Color.White, 0.16f),
            0.48f to accent,
            1f to lerp(accent, Color.Black, 0.20f),
        ),
        radius = r,
        center = c,
    )

    // ── Gloss: white .13 → .03 (40%) → black .14
    clipCircle(c, r) {
        drawCircle(
            brush = Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.13f),
                0.40f to Color.White.copy(alpha = 0.03f),
                1f to Color.Black.copy(alpha = 0.14f),
            ),
            radius = r,
            center = c,
        )
    }

    // ── Inner shadows for the resting state
    // 0 1px 0 rgba(255,255,255,.22) inset — top bevel
    insetShadow(c, r, Color.White.copy(alpha = 0.22f * (1f - p)), offsetY = 1.dp.toPx(), blur = 1.5.dp.toPx())
    // 0 -3px 7px accent+40% transparent inset — glow from below, inside
    insetShadow(c, r, accent.copy(alpha = 0.40f * (1f - p)), offsetY = -3.dp.toPx(), blur = 7.dp.toPx())
    // 0 3px 8px accent+45% black inset — pressed state
    insetShadow(c, r, lerp(accent, Color.Black, 0.55f).copy(alpha = p), offsetY = 3.dp.toPx(), blur = 8.dp.toPx())

    // ── Inner ring: inset 9px, 1px rgba(0,0,0,.28)
    val ringR = r - 9.dp.toPx()
    drawCircle(
        color = Color.White.copy(alpha = 0.14f),
        radius = ringR,
        center = c.copy(y = c.y + 1.dp.toPx()),
        style = Stroke(1.dp.toPx()),
    )
    drawCircle(
        color = Color.Black.copy(alpha = 0.28f),
        radius = ringR,
        center = c,
        style = Stroke(1.dp.toPx()),
    )
}

/* ── Shadow utilities ────────────────────────────────────────────────────────
   Compose has no box-shadow equivalent, so we draw it by hand:
   – inset  = a radial gradient ring inside the circle, offset from center
   – drop   = a blurred circle via the native Paint.setShadowLayer            */

private fun DrawScope.insetShadow(
    center: Offset,
    radius: Float,
    color: Color,
    offsetY: Float,
    blur: Float,
) {
    if (color.alpha <= 0.001f) return
    clipCircle(center, radius) {
        val src = center.copy(y = center.y - offsetY)
        val inner = ((radius - blur) / radius).coerceIn(0f, 0.999f)
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0f to Color.Transparent,
                    inner to Color.Transparent,
                    1f to color,
                ),
                center = src,
                radius = radius,
            ),
            radius = radius,
            center = center,
        )
    }
}

private fun DrawScope.dropShadow(center: Offset, radius: Float, blur: Float, color: Color) {
    drawIntoCanvas { canvas ->
        val paint = Paint().asFrameworkPaint().apply {
            isAntiAlias = true
            this.color = android.graphics.Color.TRANSPARENT
            setShadowLayer(blur, 0f, 0f, color.toArgb())
        }
        canvas.nativeCanvas.drawCircle(center.x, center.y, radius, paint)
        paint.reset()
    }
}

private inline fun DrawScope.clipCircle(center: Offset, radius: Float, block: DrawScope.() -> Unit) {
    val path = Path().apply {
        addOval(
            androidx.compose.ui.geometry.Rect(
                offset = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
            )
        )
    }
    clipPath(path) { block() }
}

private fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t

private val WELL_SIZE: Dp = 128.dp
private val BUTTON_SIZE: Dp = 102.dp


@Preview(showBackground = true, backgroundColor = 0xFF201F1D)
@Composable
private fun ShutterButtonPreview() {
    XCameraTheme {
        Box(modifier = Modifier.padding(24.dp)) {
            ShutterButton(enabled = true, onCapture = {})
        }
    }
}
