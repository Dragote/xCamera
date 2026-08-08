package com.dragote.xcamera.shared.designsystem.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dragote.xcamera.shared.designsystem.theme.AppChrome
import com.dragote.xcamera.shared.designsystem.theme.XCameraTheme

val LeverTrackWidth = 88.dp
val LeverTrackHeight = 38.dp
val LeverKnobWidth = 42.dp
val LeverKnobHeight = 30.dp
val LeverKnobInset = 4.dp
val LeverKnobRadius = 15.dp
val LeverKnobTravel = LeverTrackWidth - LeverKnobWidth - LeverKnobInset * 2

/** What's drawn in the recessed part of the track, to the left of the knob. */
enum class LeverGlyph { Bolt, Grid, AutoManual, None }

/**
 * A two-position lever switch: a pill-shaped track with a knob that slides between an "off"
 * (left) and "on" (right) resting position, matching the FLASH/GRID/MODE toggles in the "Camera
 * App UI v3" design. The whole track — recess shading, on/off tint, [glyph] and knob — is drawn in
 * a single [Canvas] rather than layered Modifiers, so the knob's drop shadow and the track's inset
 * highlight can be real shadow/gradient layers instead of Modifier-based approximations.
 */
@Composable
fun LeverSwitch(
    checked: Boolean,
    onToggle: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    glyph: LeverGlyph = LeverGlyph.None,
    accent: Color = AppChrome.Accent,
) {
    val haptic = LocalHapticFeedback.current
    val t by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(durationMillis = 320, easing = AppChrome.KnobOvershootEasing),
        label = "leverKnobTravel",
    )
    val on by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(durationMillis = 280, easing = AppChrome.EaseStandard),
        label = "leverOnGlow",
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        LeverBody(t = t, on = on, glyph = glyph, accent = accent) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onToggle()
        }
        Text(text = label, style = AppChrome.labelStyle())
    }
}

/**
 * The track+knob [Canvas] on its own, without the label underneath — pulled out so `ModeLever`
 * (in `feature:camera`) can lay real Compose text for its A/M lettering on top of it instead of
 * going through a [LeverGlyph] baked into the Canvas draw.
 */
@Composable
fun LeverBody(
    t: Float,
    on: Float,
    glyph: LeverGlyph,
    accent: Color,
    onClick: () -> Unit,
) {
    Canvas(
        modifier = Modifier
            .size(LeverTrackWidth, LeverTrackHeight)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Switch,
                onClick = onClick,
            ),
    ) {
        drawTrack(accent, on)
        drawGlyph(glyph, on)
        translate(left = LeverKnobTravel.toPx() * t) { drawKnob() }
    }
}

private fun DrawScope.drawTrack(accent: Color, on: Float) {
    val corner = CornerRadius(LeverTrackHeight.toPx() / 2)
    val path = Path().apply { addRoundRect(RoundRect(Rect(Offset.Zero, size), corner)) }

    // Outer bottom edge highlight: box-shadow: 0 1px 0 rgba(255,255,255,.1)
    drawPath(
        Path().apply { addRoundRect(RoundRect(Rect(Offset(0f, 1.dp.toPx()), size), corner)) },
        Color.White.copy(alpha = 0.10f),
    )

    drawPath(path, AppChrome.TrackOffGradient)

    // Inset groove shadow: a soft blurred shadow cast along the track's own silhouette (clipped
    // back to its own bounds) wraps around the whole rim and fades gradually, unlike the old
    // hard-edged linear "cap" — which read as a flat painted band rather than a real recess.
    // Drawn before the on-tint (below) so a lit lever shows through it rather than the shadow
    // sitting on top of and muting the glow.
    clipPath(path) {
        drawIntoCanvas { canvas ->
            val shadowPaint = Paint().asFrameworkPaint().apply {
                isAntiAlias = true
                color = android.graphics.Color.TRANSPARENT
                setShadowLayer(5.dp.toPx(), 0f, 2.dp.toPx(), Color.Black.copy(alpha = 0.5f).toArgb())
            }
            val cornerPx = corner.x
            canvas.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, cornerPx, cornerPx, shadowPaint)
        }
        // Thin bright rim at the very bottom, like light catching the lower groove wall.
        drawRect(
            Brush.verticalGradient(
                (1f - 2.dp.toPx() / size.height) to Color.Transparent,
                1f to Color.White.copy(alpha = 0.12f),
            ),
        )
    }

    if (on > 0f) {
        drawPath(path, AppChrome.trackOnGradient(accent), alpha = on)
    }
}

private fun DrawScope.drawKnob() {
    val left = LeverKnobInset.toPx()
    val top = LeverKnobInset.toPx()
    val rect = Rect(left, top, left + LeverKnobWidth.toPx(), top + LeverKnobHeight.toPx())
    val radius = LeverKnobRadius.toPx()
    val path = Path().apply { addRoundRect(RoundRect(rect, CornerRadius(radius))) }

    // box-shadow: 0 3px 6px rgba(0,0,0,.6) — a real shadow layer, not an approximated gradient.
    drawIntoCanvas { canvas ->
        val shadowPaint = Paint().asFrameworkPaint().apply {
            isAntiAlias = true
            color = android.graphics.Color.TRANSPARENT
            setShadowLayer(6.dp.toPx(), 0f, 3.dp.toPx(), Color.Black.copy(alpha = 0.6f).toArgb())
        }
        canvas.nativeCanvas.drawRoundRect(rect.left, rect.top, rect.right, rect.bottom, radius, radius, shadowPaint)
    }

    drawPath(path, AppChrome.KnobGradient)

    clipPath(path) {
        drawRect(Color.White.copy(alpha = 0.28f), Offset(rect.left, rect.top), Size(rect.width, 1f))
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.5f),
                startY = rect.bottom - 3.dp.toPx(),
                endY = rect.bottom,
            ),
            topLeft = Offset(rect.left, rect.bottom - 3.dp.toPx()),
            size = Size(rect.width, 3.dp.toPx()),
        )
    }
}

private fun DrawScope.drawGlyph(glyph: LeverGlyph, on: Float) = when (glyph) {
    LeverGlyph.Bolt -> {
        val w = 7.dp.toPx()
        val h = 17.dp.toPx()
        val x = 16.dp.toPx()
        val y = size.height / 2f - h / 2f
        val points = listOf(
            0.62f to 0f, 0.10f to 0.56f, 0.44f to 0.56f,
            0.30f to 1f, 0.92f to 0.40f, 0.54f to 0.40f,
        )
        val path = Path()
        points.forEachIndexed { index, (fx, fy) ->
            val px = x + fx * w
            val py = y + fy * h
            if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        drawPath(path, Color.White, alpha = on)
    }

    LeverGlyph.Grid -> {
        val s = 14.dp.toPx()
        val x = 16.dp.toPx()
        val y = size.height / 2f - s / 2f
        val stroke = 1.5.dp.toPx()
        val color = Color.White.copy(alpha = on)
        drawRect(color, Offset(x + s * 0.33f, y), Size(stroke, s))
        drawRect(color, Offset(x + s * 0.67f, y), Size(stroke, s))
        drawRect(color, Offset(x, y + s * 0.33f), Size(s, stroke))
        drawRect(color, Offset(x, y + s * 0.67f), Size(s, stroke))
    }

    LeverGlyph.AutoManual, LeverGlyph.None -> Unit
}

@Preview(showBackground = true, backgroundColor = 0xFF201F1D)
@Composable
private fun LeverSwitchPreview() {
    XCameraTheme {
        Row(modifier = Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            LeverSwitch(checked = false, onToggle = {}, label = "FLASH", glyph = LeverGlyph.Bolt)
            LeverSwitch(checked = true, onToggle = {}, label = "FLASH", glyph = LeverGlyph.Bolt)
            LeverSwitch(checked = false, onToggle = {}, label = "GRID", glyph = LeverGlyph.Grid)
            LeverSwitch(checked = true, onToggle = {}, label = "GRID", glyph = LeverGlyph.Grid)
        }
    }
}
