package com.dragote.xcamera.shared.designsystem.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Cross-feature subset of xCamera's sole visual identity — "minimal chrome", a deliberately
 * un-skeuomorphic, flat line-art language. Spirit reference: an OP-1 Field synth control-panel
 * line-art diagram — white/cream background, thin black strokes, flat plain-black-or-white fills, no
 * gradients/shadows/blur/grain.
 *
 * This identity has two switchable sub-variants (`INVERT CHROME` in Settings) — [Palette.Normal]
 * (paper-white body / near-black ink) and [Palette.Inverted] (near-black body / warm-white ink, same
 * "confident hairline on paper" character with the two roles swapped, not a literal RGB invert).
 * [current] holds which one is live; see its own doc for the mechanism. [accent] sits deliberately
 * outside that switch — it's the one color in here the user picks outright.
 *
 * Only the handful of tokens genuinely reusable across feature modules live here (currently
 * `feature:camera` and `feature:settings`); screen-specific tokens (zebra-clip tints, dial
 * well/barrel geometry, viewfinder bezel treatment) stay local to `feature:camera`'s own
 * `ui/theme/CameraChrome.kt`, which delegates to this object for the shared subset.
 */
object MinimalChrome {

    /** The two body/ink pairings this identity can render with. Each field name matches what
     *  [Background]/[Ink] mean for that variant, so `Palette.Normal.background`/`.ink` reads the same
     *  way [Background]/[Ink] themselves do below. */
    enum class Palette(val background: Color, val ink: Color) {
        /** Warm near-white "paper" body with near-black ink. */
        Normal(background = Color(0xFFFAF6EC), ink = Color(0xFF17140F)),

        /** Near-black "paper" body with warm-white ink. Not a literal RGB invert of [Normal]: the body
         *  is a soft near-black (`0xFF17140F` would be too close to true black and read as a dead pixel
         *  on OLED panels) and the ink is the same warm-white as [Normal]'s background rather than a
         *  cooler pure white, so it keeps the same slightly-warm "paper" character as the non-inverted
         *  variant instead of feeling like a different material. */
        Inverted(background = Color(0xFF14120D), ink = Color(0xFFFAF6EC)),
    }

    /**
     * Which [Palette] is live right now, backing [Background]/[Ink] below. Deliberately a single
     * object-level [mutableStateOf] rather than a `CompositionLocal` (can't be read from inside a
     * `DrawScope` draw lambda, which is where nearly every camera-chrome component actually reads
     * [Background]/[Ink]) or a per-component `inverted: Boolean` parameter (too invasive across the
     * ~24 components that already reference [Background]/[Ink] by name). `Canvas`'s draw lambda still
     * participates in Compose's snapshot-state observer system for redraw invalidation even though the
     * lambda itself isn't `@Composable`, so reading this `State` from inside one correctly triggers a
     * repaint on flip — see `feature:camera`'s `CameraScreen.kt` call site, which sets this once per
     * recomposition from `CameraSettings.minimalChromeInverted`.
     *
     * Global/object-level mutable state is a real scaling limitation if this design language is ever
     * driven by more than one screen at once (e.g. two camera screens open in a multi-window split each
     * wanting a different palette) — acceptable today since `feature:camera` is the only consumer, not
     * engineered further until that's an actual requirement.
     */
    var current: Palette by mutableStateOf(Palette.Normal)

    /** "Paper" body/background — the surface every flat glyph sits on. Tracks [current]. */
    val Background: Color get() = current.background

    /** The one ink color every stroke, flat fill, and label in this identity is drawn with (or
     *  [Background]) — everything except whatever [accent] is allowed to claim. Tracks [current]. */
    val Ink: Color get() = current.ink

    /**
     * The user's chosen accent hue, or `null` for "no accent" — the default, and what every control
     * that hasn't opted into the accent keeps using regardless. Only the shutter release reads it
     * today; a control that wants the accent falls back to [Ink] when this is `null`, so `null` is a
     * fully monochrome chrome, not a missing value to guard against.
     *
     * Held here (rather than as a [Palette] field) precisely because it must *not* move with
     * [current]: the whole point of picking a color is that it stays that color under either body.
     * Written by whichever screen observes the persisted preference, once per recomposition, and read
     * from `DrawScope` lambdas — same object-level snapshot-state mechanism as [current], see its doc.
     */
    var accent: Color? by mutableStateOf(null)

    /** 1.5dp reads as a confident hairline at phone-screen density without looking like a hand-drawn
     *  sketch — thinner (1dp) started disappearing on lower-density test renders. */
    val StrokeWidth = 1.5.dp

    /** CSS `ease` — used for simple opacity/color cross-fades (glyphs, track tint). */
    val EaseStandard = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

    /** Design's `cubic-bezier(.34,1.25,.55,1)` — lever knob travel, with its overshoot "clunk". */
    val KnobOvershootEasing = CubicBezierEasing(0.34f, 1.25f, 0.55f, 1f)

    /** IBM Plex Mono isn't bundled as a font resource here — platform-monospace fallback. */
    val Mono = FontFamily.Monospace

    fun labelStyle(color: Color = Ink): TextStyle = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        letterSpacing = 0.2f.em,
        color = color,
    )

    fun valueStyle(color: Color = Ink): TextStyle = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        color = color,
    )
}
