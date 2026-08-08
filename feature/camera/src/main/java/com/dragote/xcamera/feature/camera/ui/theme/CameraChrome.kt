package com.dragote.xcamera.feature.camera.ui.theme

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import com.dragote.xcamera.shared.designsystem.theme.AppChrome
import kotlin.random.Random

/**
 * Colors/gradients/type for the skeuomorphic camera-body chrome ported from the "Camera App UI
 * v3" design (top/bottom decks, viewfinder bezel, levers, dials). The subset reused by other
 * feature modules (currently `feature:settings`, and `shared:designsystem`'s own `LeverSwitch`)
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

    /** Design's `cubic-bezier(.34,1.25,.55,1)` — lever knob travel, with its overshoot "clunk". */
    val KnobOvershootEasing = AppChrome.KnobOvershootEasing

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

    val LabelColor = AppChrome.LabelColor
    val ValueColor = AppChrome.ValueColor

    /** Near-white for the histogram's baseline dots/bars themselves — distinct from [ValueColor]'s
     *  warmer off-white so the marks read clearly against the live viewfinder image behind them (the
     *  histogram has no background fill of its own). Only the first (darkest) and last (brightest)
     *  bucket use [ZebraShadow]/[ZebraHighlight] instead, marking the shadow/highlight ends of the
     *  tonal range with the same color language [ZebraOverlay] uses. */
    val HistogramMarkColor = Color(0xFFF7F4EF)

    fun trackOnGradient(accent: Color = Accent): Brush = AppChrome.trackOnGradient(accent)

    val Mono = AppChrome.Mono

    fun leverLabelStyle(color: Color = LabelColor): TextStyle = AppChrome.labelStyle(color)

    fun dialValueStyle(color: Color = ValueColor): TextStyle = AppChrome.valueStyle(color)
}

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
