package com.dragote.xcamera.feature.camera.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.dragote.xcamera.shared.designsystem.theme.MinimalChrome

/**
 * Colors/type for `feature:camera`'s screen chrome — the "minimal chrome" identity: thin black
 * hairlines and flat black-or-white fills on a warm-white body, in the spirit of an OP-1 Field
 * control-panel line-art diagram.
 *
 * The subset genuinely reusable across feature modules lives in `shared:designsystem`'s [MinimalChrome]
 * and is re-exported here; everything below that has no counterpart there is specific to this one
 * screen (the zebra-clip semantic tints, the viewfinder bezel/inset treatment) and stays local.
 *
 * Deliberately keeps a stable public shape (`DialInk`, `EaseStandard`, `leverLabelStyle()`,
 * `dialValueStyle()`, ...) even where a token is now just a flat pass-through with no gradient/hue
 * behind it — every component under `ui/component/` references `CameraChrome.<member>` by these same
 * names regardless of which visual identity is live, so a future reskin only needs to swap *values*
 * here (and, for a few members like `DeckGradient`, *kind* — a `Brush` for a flat `Color`), not rename
 * the API components/screen code already depend on.
 *
 * Every token below that derives from [MinimalChrome.Background]/[MinimalChrome.Ink] is a `get()`
 * property, not a plain `val` — [MinimalChrome.Background]/[.Ink] themselves now track
 * [MinimalChrome.current] (see that object's own doc), and a plain `val` here would snapshot whichever
 * palette was live the first time this object was touched instead of re-reading on every access.
 */
object CameraChrome {

    /** What the dials' barrels and center carets draw with — flat [Ink], no hue. Kept as a named
     *  token (rather than switching every call site to `MinimalChrome.Ink` directly) so the
     *  dial/lever components that take an `accent: Color` parameter don't couple that parameter's
     *  *meaning* to whichever identity is currently live. */
    val DialInk: Color get() = MinimalChrome.Ink

    /** Local alias for [MinimalChrome.Ink] — see [DialInk]'s own doc for why call sites keep a
     *  feature-local name rather than reaching into [MinimalChrome] directly everywhere. */
    val Ink: Color get() = MinimalChrome.Ink

    /** The user's chosen accent hue, `null` meaning "no accent" — see [MinimalChrome.accent]. Stays
     *  nullable rather than collapsing to [Ink] here because a control painting with it generally
     *  needs a different treatment for the two cases, not just a different color (see
     *  [com.dragote.xcamera.feature.camera.ui.component.control.ShutterButton]'s pressed fill). */
    val Accent: Color? get() = MinimalChrome.accent

    /** [com.dragote.xcamera.feature.camera.domain.model.ZebraClipping.SHADOW] stripe tint — kept as a
     *  real hue (not flattened to [Ink]) because it's carrying live-viewfinder semantic information
     *  (crushed blacks), not decorative chrome; see this module's own design-agent brief on when color
     *  is allowed to survive an otherwise-monochrome pass. */
    val ZebraShadow = Color(0xFF2F6FED)

    /** [com.dragote.xcamera.feature.camera.domain.model.ZebraClipping.HIGHLIGHT] stripe tint (blown
     *  whites) — same reasoning as [ZebraShadow]. */
    val ZebraHighlight = Color(0xFFE04B2A)

    /** CSS `ease` — reused, not re-derived; see [MinimalChrome]'s own doc for why this reskin doesn't
     *  touch motion. */
    val EaseStandard = MinimalChrome.EaseStandard

    /** Design's overshoot "clunk" easing for lever knob travel — same reasoning as [EaseStandard]. */
    val KnobOvershootEasing = MinimalChrome.KnobOvershootEasing

    /** Flat body/background fill — a plain [Color], not a `Brush` gradient (see `ui/CameraScreen.kt`'s
     *  own call site: `Modifier.background(Color)` accepts either). */
    val Background: Color get() = MinimalChrome.Background

    /** Flat deck fill (top toolbar row + bottom control deck) — same [Background] value as the rest of
     *  the body; the deck reads as a *bordered region* of one continuous flat body (see the `Seam`
     *  divider in `ui/CameraScreen.kt`), not a separately-lit panel implying physical depth. Named
     *  separately from [Background] only so call sites stay self-documenting about *which* region
     *  they're painting. */
    val DeckColor: Color get() = MinimalChrome.Background

    /** Viewfinder bezel — flat [Ink]; the live camera feed itself provides all the visual weight this
     *  region needs, so the bezel is just a thin frame (see `ui/CameraScreen.kt`'s viewfinder `Box`,
     *  bordered with [StrokeColor]/[StrokeWidth]). Follows [current][MinimalChrome.current], unlike
     *  [ViewfinderInsetColor] below — it's part of the flat chrome, not the simulated camera interior. */
    val ViewfinderBezelColor: Color get() = Ink

    /** Always [MinimalChrome.Palette.Inverted]'s near-black, regardless of which chrome variant is
     *  live — this is what's visible behind the live preview before its first frame arrives (or while
     *  torn down between lenses), simulating the camera's own black interior, not decorative "ink." It
     *  must never read as a blank white/paper panel under [MinimalChrome.Palette.Normal]. */
    val ViewfinderInsetColor: Color = MinimalChrome.Palette.Inverted.background

    /** The one stroke color/width this whole identity draws with — see [MinimalChrome]'s own doc. */
    val StrokeColor: Color get() = MinimalChrome.Ink
    val StrokeWidth = MinimalChrome.StrokeWidth

    val LabelColor: Color get() = MinimalChrome.Ink
    val ValueColor: Color get() = MinimalChrome.Ink

    /** Histogram baseline marks — near-white now instead of near-black [Ink], since (like
     *  [ZebraShadow]/[ZebraHighlight]) this is drawn *over the live viewfinder feed*, not over the flat
     *  body chrome; it needs to hold up against arbitrary scene content, not this identity's own
     *  background. */
    val HistogramMarkColor = Color(0xFFFAF6EC)

    val Mono = MinimalChrome.Mono

    fun leverLabelStyle(color: Color = LabelColor): TextStyle = MinimalChrome.labelStyle(color)

    fun dialValueStyle(color: Color = ValueColor): TextStyle = MinimalChrome.valueStyle(color)
}
