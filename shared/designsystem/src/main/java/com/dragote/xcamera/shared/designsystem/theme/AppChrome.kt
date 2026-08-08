package com.dragote.xcamera.shared.designsystem.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Cross-feature subset of the skeuomorphic camera-body chrome palette/type — the parts reused by
 * more than one feature module (currently `feature:camera` and `feature:settings`, e.g. by this
 * package's own `CameraLever.kt`). Screen-specific chrome (zebra tints, dial/barrel/shutter
 * gradients, grain texture, etc.) stays local to `feature:camera`'s own `CameraChrome.kt`, which
 * delegates to this object for the shared subset.
 */
object AppChrome {

    val Accent = Color(0xFFE8632A)

    // Matte black plastic, not the design's lighter warm-graphite tone — deliberately darker and
    // less saturated per feedback that the literal design colors read as metal, not matte plastic.
    val BodyGradient = Brush.verticalGradient(
        0f to Color(0xFF2B2A29),
        0.38f to Color(0xFF201F1E),
        0.74f to Color(0xFF171615),
        1f to Color(0xFF0D0C0B),
    )

    // Exposed separately (not just baked into DeckGradient below) so anything drawn *on top of* the
    // deck — e.g. DialWheel's shutters — can rebuild this exact gradient with its own startY/endY,
    // positioned to land on precisely the colors the real deck would show through at that point.
    val DeckGradientStops: Array<Pair<Float, Color>> = arrayOf(
        0f to Color(0xFF211F1D),
        0.6f to Color(0xFF191817),
        1f to Color(0xFF100F0E),
    )

    val DeckGradient = Brush.verticalGradient(*DeckGradientStops)

    val TrackOffGradient = Brush.verticalGradient(
        0f to Color(0xFF131210),
        1f to Color(0xFF232019),
    )

    val KnobGradient = Brush.verticalGradient(
        0f to Color(0xFF625D51),
        0.55f to Color(0xFF464238),
        1f to Color(0xFF37332B),
    )

    val LabelColor = Color(0xFF877F6C)
    val ValueColor = Color(0xFFDED7C3)

    /** CSS `ease` — used for simple opacity/color cross-fades (glyphs, track tint). */
    val EaseStandard = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

    /** Design's `cubic-bezier(.34,1.25,.55,1)` — lever knob travel, with its overshoot "clunk". */
    val KnobOvershootEasing = CubicBezierEasing(0.34f, 1.25f, 0.55f, 1f)

    val Mono = FontFamily.Monospace

    fun trackOnGradient(accent: Color = Accent): Brush = Brush.verticalGradient(
        0f to lerp(accent, Color.Black, 0.3f),
        1f to accent,
    )

    fun labelStyle(): TextStyle = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        letterSpacing = 0.2f.em,
        color = LabelColor,
    )

    fun valueStyle(color: Color = ValueColor): TextStyle = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        color = color,
    )
}

/**
 * Approximates a CSS inset box-shadow pair (dark shade hugging the top inner edge, faint highlight
 * hugging the bottom inner edge) that Compose has no direct equivalent for. Must be chained after
 * `.clip(shape)` (so the gradients are cropped to the same rounded shape) and after `.background(...)`
 * — like a CSS inset shadow, it paints on top of this element's own fill but behind any children
 * drawn further down the modifier chain (e.g. a knob riding on a track, or a label inside a knob).
 */
fun Modifier.edgeShade(
    topColor: Color = Color.Transparent,
    topHeight: Dp = 0.dp,
    bottomColor: Color = Color.Transparent,
    bottomHeight: Dp = 0.dp,
): Modifier = this.drawWithContent {
    if (topHeight > 0.dp) {
        val h = topHeight.toPx().coerceAtMost(size.height)
        drawRect(
            brush = Brush.verticalGradient(listOf(topColor, Color.Transparent), endY = h),
            size = Size(size.width, h),
        )
    }
    if (bottomHeight > 0.dp) {
        val h = bottomHeight.toPx().coerceAtMost(size.height)
        drawRect(
            brush = Brush.verticalGradient(listOf(Color.Transparent, bottomColor), startY = size.height - h, endY = size.height),
            topLeft = Offset(0f, size.height - h),
            size = Size(size.width, h),
        )
    }
    drawContent()
}

/** Recessed track shadow — lever tracks & dial troughs (`box-shadow: ... inset` in the design). */
fun Modifier.recessedTrackShadow(): Modifier = edgeShade(
    topColor = Color.Black.copy(alpha = 0.75f),
    topHeight = 11.dp,
    bottomColor = Color.White.copy(alpha = 0.08f),
    bottomHeight = 3.dp,
)
