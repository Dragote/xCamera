package com.dragote.xcamera.feature.camera.ui.theme

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.dragote.xcamera.shared.designsystem.theme.AppChrome
import kotlin.random.Random

/**
 * Colors/gradients/type for the skeuomorphic camera-body chrome ported from the "Camera App UI
 * v3" design (top/bottom decks, viewfinder bezel, levers, dials). The subset reused by other
 * feature modules (currently `feature:settings`, and `shared:designsystem`'s own `CameraLever`)
 * lives in `shared:designsystem`'s `AppChrome` and is delegated to here; everything below stays
 * local to feature:camera since it's specific to this one screen, not a reusable app-wide style.
 * IBM Plex Mono isn't bundled as a font resource, so [Mono] falls back to the platform monospace
 * family with the design's letter-spacing preserved.
 */
object CameraChrome {

    val Accent = AppChrome.Accent

    /** [com.dragote.xcamera.feature.camera.domain.model.ZebraClipping.SHADOW] stripe tint — crushed
     *  blacks read as cool blue, matching the reference app's own convention. */
    val ZebraShadow = Color(0xFF3B82F6)

    /** [com.dragote.xcamera.feature.camera.domain.model.ZebraClipping.HIGHLIGHT] stripe tint — blown
     *  whites read as hot orange-red, matching the reference app's own convention. */
    val ZebraHighlight = Color(0xFFE8432A)

    /** CSS `ease` — used for simple opacity/color cross-fades (glyphs, track tint). */
    val EaseStandard = AppChrome.EaseStandard

    /** CSS `ease-out` — used for the shutter's fast press-down. */
    val EaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)

    /** Design's `cubic-bezier(.34,1.25,.55,1)` — lever knob travel, with its overshoot "clunk". */
    val KnobOvershootEasing = AppChrome.KnobOvershootEasing

    /** Design's `cubic-bezier(.3,1.25,.5,1)` — shutter release spring-back. */
    val ShutterReleaseEasing = CubicBezierEasing(0.3f, 1.25f, 0.5f, 1f)

    // Matte black plastic, not the design's lighter warm-graphite tone — deliberately darker and
    // less saturated per feedback that the literal design colors read as metal, not matte plastic.
    val BodyGradient = AppChrome.BodyGradient

    // Exposed separately (not just baked into DeckGradient below) so anything drawn *on top of* the
    // deck — e.g. DialWheel's shutters — can rebuild this exact gradient with its own startY/endY,
    // positioned to land on precisely the colors the real deck would show through at that point.
    val DeckGradientStops: Array<Pair<Float, Color>> = AppChrome.DeckGradientStops

    val DeckGradient = AppChrome.DeckGradient

    val ViewfinderBezelGradient = Brush.verticalGradient(
        0f to Color(0xFF131210),
        0.55f to Color(0xFF0B0A09),
        1f to Color(0xFF050505),
    )

    val ViewfinderInsetColor = Color(0xFF0D1210)

    val TrackOffGradient = AppChrome.TrackOffGradient

    val KnobGradient = AppChrome.KnobGradient

    val DialTroughGradient = Brush.verticalGradient(
        0f to Color(0xFF241F1A),
        1f to Color(0xFF2A2620),
    )

    val LabelColor = AppChrome.LabelColor
    val ValueColor = AppChrome.ValueColor
    val GrooveColor = Color.White

    /** Near-white for the histogram's baseline dots/bars themselves — distinct from [ValueColor]'s
     *  warmer off-white so the marks read clearly against the live viewfinder image behind them (the
     *  histogram has no background fill of its own). Only the first (darkest) and last (brightest)
     *  bucket use [ZebraShadow]/[ZebraHighlight] instead, marking the shadow/highlight ends of the
     *  tonal range with the same color language [ZebraOverlay] uses. */
    val HistogramMarkColor = Color(0xFFF7F4EF)

    fun trackOnGradient(accent: Color = Accent): Brush = AppChrome.trackOnGradient(accent)

    fun barrelGradient(accent: Color = Accent): Brush = Brush.verticalGradient(
        0f to lerp(accent, Color.White, 0.82f),
        0.3f to lerp(accent, Color.White, 0.96f),
        0.55f to accent,
        0.8f to lerp(accent, Color.Black, 0.28f),
        1f to lerp(accent, Color.Black, 0.45f),
    )

    fun shutterGradient(accent: Color = Accent): Brush = Brush.verticalGradient(
        0f to lerp(accent, Color.White, 0.84f),
        0.48f to accent,
        1f to lerp(accent, Color.Black, 0.2f),
    )

    val Mono = AppChrome.Mono

    fun leverLabelStyle(): TextStyle = AppChrome.labelStyle()

    fun dialValueStyle(color: Color = ValueColor): TextStyle = AppChrome.valueStyle(color)

    fun letterSpacing(value: Float): TextUnit = value.em
}

/**
 * Approximates a CSS inset box-shadow pair (dark shade hugging the top inner edge, faint highlight
 * hugging the bottom inner edge) that Compose has no direct equivalent for. Must be chained after
 * `.clip(shape)` (so the gradients are cropped to the same rounded shape) and after `.background(...)`
 * — like a CSS inset shadow, it paints on top of this element's own fill but behind any children
 * drawn further down the modifier chain (e.g. a knob riding on a track, or a label inside a knob).
 * Private copy of `shared:designsystem`'s `AppChrome.edgeShade` — kept local since [embossedShadow]/
 * [wellShadow] are camera-only and shouldn't pull in a cross-module dependency just for this helper.
 */
private fun Modifier.edgeShade(
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

/** Embossed shadow for raised parts sitting inside a track — lever knob & dial barrel. */
fun Modifier.embossedShadow(): Modifier = edgeShade(
    topColor = Color.White.copy(alpha = 0.28f),
    topHeight = 3.dp,
    bottomColor = Color.Black.copy(alpha = 0.55f),
    bottomHeight = 8.dp,
)

/** Deeper recessed shadow for the shutter well. */
fun Modifier.wellShadow(): Modifier = edgeShade(
    topColor = Color.Black.copy(alpha = 0.85f),
    topHeight = 16.dp,
    bottomColor = Color.White.copy(alpha = 0.1f),
    bottomHeight = 4.dp,
)

/**
 * Static, tiled film-grain texture (a fixed-seed noise bitmap repeated via [ShaderBrush]),
 * approximating the design's SVG `feTurbulence` grain on the body/barrels/shutter. Generated once
 * and cached — the pattern itself is decorative, not meant to be randomized per composition.
 */
private val grainBrush: Brush by lazy {
    val size = 48
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val random = Random(1)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bitmap.setPixel(x, y, AndroidColor.argb(random.nextInt(40), 255, 255, 255))
        }
    }
    ShaderBrush(ImageShader(bitmap.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
}

/** Applies the grain texture on top of this element's own background, behind its children. */
fun Modifier.grainTexture(alpha: Float = 1f): Modifier = this.drawWithContent {
    drawRect(brush = grainBrush, alpha = alpha)
    drawContent()
}
