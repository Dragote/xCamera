package com.dragote.xcamera.feature.camera.ui.component.overlay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dragote.xcamera.feature.camera.ui.theme.CameraChrome
import com.dragote.xcamera.shared.designsystem.haptics.hapticTick
import com.dragote.xcamera.shared.designsystem.haptics.rememberHapticTickVibrator
import com.dragote.xcamera.shared.designsystem.theme.XCameraTheme
import kotlin.math.abs

/**
 * Draws over the live viewfinder feed at [CameraChrome.HistogramMarkColor] (near-white, for
 * legibility against arbitrary scene content).
 *
 * Artificial-horizon indicator drawn as a single hairline broken into three thirds — a static outer
 * left/right pair that never moves, and a dynamic center third that tracks the device's real-world
 * roll. The center third is sized/positioned to sit exactly inside [ViewfinderGridOverlay]'s own
 * center column, matching regardless of whether the grid is actually toggled on
 * ([com.dragote.xcamera.shared.common.domain.model.CameraSettings.showGrid]) — [HorizonLines] computes
 * its own thirds the same way the grid computes its own dividers, it doesn't read the grid's visibility
 * at all.
 *
 * **Why the static thirds get no rotation at all.** [ViewfinderGridOverlay] applies *no* orientation
 * compensation whatsoever — its dividers are always plain `size.width/3`/`size.height/3` lines, fixed
 * to the frame's own edges (correct for a rule-of-thirds grid, which composes against the photo
 * rectangle, not gravity). Rotating this line's segments by the device's quadrant angle instead would
 * only coincidentally line up with the grid in portrait — see `docs/features/camera-capture.md` for
 * why that approach doesn't work in landscape.
 *
 * Instead, [HorizonLines] picks its drawing axis directly from [quadrant] — X (thirds of
 * `size.width`, laid out at `y = center.y`) in
 * portrait/upside-down (0°/180°, where the grid's *vertical* dividers are the relevant reference), Y
 * (thirds of `size.height`, laid out at `x = center.x`) in landscape (90°/270°, where the grid's
 * *horizontal* dividers become the ones a landscape-holding viewer perceives as columns — the frame
 * itself physically rotated 90° with the device, so whichever grid lines were canvas-horizontal now
 * read as vertical to that viewer). Both axis choices land exactly on the grid's own fixed divider
 * coordinates, in every quadrant, by construction — no transform needs to compensate for anything,
 * because nothing was ever moved off the frame-relative reference to begin with.
 *
 * What *does* still need live sensor-driven rotation is the fine "how far off level" cue, which is a
 * genuinely different, much smaller-magnitude quantity than the coarse 90°-bucket quadrant term: the
 * *residual* between [DeviceOrientationState.smoothedOrientationDegrees] and [DeviceOrientationState.quadrant]
 * ([angularDistance], computed once in [HorizonLineOverlay]). Only the dynamic center segment gets this
 * small [rotate] applied, around the same canvas [androidx.compose.ui.graphics.drawscope.DrawScope.center]
 * pivot as before; the static segments stay completely fixed. Tilting the device now visibly *breaks*
 * the line (the center segment's endpoints swing away from the static segments' shared axis) and it
 * only reads as one clean, unbroken line once the residual reaches ~zero, i.e. exactly when level. That
 * moment ([wasLevel] flipping false→true in [HorizonLineOverlay]) fires one haptic tick and briefly
 * flashes every segment's alpha up from [BaseLineAlpha] toward [LevelFlashAlpha] before fading back down
 * ([flashAlpha]) — positive confirmation beyond just the geometry lining up.
 *
 * All three segments share one [HorizonLineStrokeWidth]/[BaseLineAlpha] at rest — there's no separate
 * "thick" line, so the only things distinguishing the center third are (a) it moves and (b) the
 * level-alignment flash.
 */
@Composable
fun HorizonLineOverlay(modifier: Modifier = Modifier) {
    val orientation by rememberDeviceOrientationState()
    val vibrator = rememberHapticTickVibrator()

    // Hysteresis so hovering right at the level threshold (where the smoothed sensor reading is
    // noisiest) doesn't re-fire the tick/flash on every callback — must drift back out past
    // LevelExitThresholdDegrees before a later re-entry under LevelEnterThresholdDegrees counts as a
    // fresh "just leveled" event. Same shape as DeviceOrientationQuadrant's own QUADRANT_HYSTERESIS_DEGREES,
    // just a much tighter band since this is a precision "you're level" readout, not a 90°-bucket snap.
    var wasLevel by remember { mutableStateOf(false) }

    // Bumped (not just a Boolean) so the flash-animation LaunchedEffect below has a key that only ever
    // changes on a genuine new level event, never on every sensor callback — see that effect's own doc
    // for why sharing one key with the per-callback effect would cut its fade animation off almost
    // immediately every time.
    var levelEventToken by remember { mutableIntStateOf(0) }
    val flashAlpha = remember { Animatable(0f) }

    // Runs on every raw orientation callback (DeviceOrientationState is a fresh instance each time, see
    // rememberDeviceOrientationState's own doc) — cheap, no suspension of its own (hapticTick() isn't
    // suspend), so restarting it that often costs nothing and needs no debouncing beyond wasLevel's own
    // hysteresis.
    var residualRollDegrees by remember { mutableStateOf(0f) }
    LaunchedEffect(orientation) {
        val residual = angularDistance(orientation.smoothedOrientationDegrees, orientation.quadrant)
        // Negated to match counterRotationDegrees's own sign convention elsewhere in this file (a
        // positive raw deviation needs a negative/counter rotation to visually cancel it).
        residualRollDegrees = -residual

        val isLevel = if (wasLevel) abs(residual) < LevelExitThresholdDegrees else abs(residual) < LevelEnterThresholdDegrees
        if (isLevel && !wasLevel) {
            vibrator.hapticTick()
            levelEventToken++
        }
        wasLevel = isLevel
    }

    // Deliberately its own LaunchedEffect, keyed only on levelEventToken (not on every orientation
    // callback) — animateTo is a genuine suspend call, and a LaunchedEffect restarts (cancelling
    // whatever it was doing) every time its key changes. Sharing the key above would cancel this fade
    // partway through on the very next sensor reading, almost always milliseconds later, so the flash
    // would visually stick at whatever alpha it reached instead of ever completing.
    LaunchedEffect(levelEventToken) {
        if (levelEventToken == 0) return@LaunchedEffect
        flashAlpha.snapTo(1f)
        flashAlpha.animateTo(0f, animationSpec = tween(FlashFadeDurationMs, easing = CameraChrome.EaseStandard))
    }

    HorizonLines(
        quadrant = orientation.quadrant,
        residualRollDegrees = residualRollDegrees,
        flashProgress = flashAlpha.value,
        modifier = modifier,
    )
}

/** The actual draw call, factored out from [HorizonLineOverlay] so its [Preview]s can drive it with a
 *  fixed [quadrant]/[residualRollDegrees]/[flashProgress] instead of a live sensor reading + animation. */
@Composable
private fun HorizonLines(
    quadrant: Float,
    residualRollDegrees: Float,
    flashProgress: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val marginPx = LineSideMargin.toPx()
        val strokePx = HorizonLineStrokeWidth.toPx()
        val alpha = BaseLineAlpha + (LevelFlashAlpha - BaseLineAlpha) * flashProgress
        val color = LineColor.copy(alpha = alpha)

        // Portrait/upside-down (0°/180°): lay the line along X, thirds of size.width, at y = center.y —
        // matching ViewfinderGridOverlay's own two *vertical* dividers, the ones a portrait-holding
        // viewer perceives as columns. Landscape (90°/270°): lay it along Y, thirds of size.height, at
        // x = center.x instead — matching the grid's *horizontal* dividers, which is what a
        // landscape-holding viewer actually perceives as columns once the frame itself has physically
        // turned 90° with the device. See this file's own class doc for the full derivation.
        val isLandscape = quadrant == 90f || quadrant == 270f
        val extent = if (isLandscape) size.height else size.width
        val oneThird = extent / 3f
        val twoThirds = extent * 2f / 3f
        fun pointAt(along: Float) = if (isLandscape) Offset(center.x, along) else Offset(along, center.y)

        // Static outer thirds — fixed, no rotation, so they always land exactly on the grid's own
        // (also-unrotated) divider positions regardless of quadrant. StrokeCap.Butt, not Round — a
        // round cap extends a line by half its own stroke width past its nominal endpoint, so at each
        // seam the outer segment's cap and the center segment's cap would overlap; since these are
        // semi-transparent, that overlap alpha-composites into a visibly brighter dot exactly where the
        // segments are supposed to meet cleanly. A flat cap ends exactly at the coordinate given, so
        // adjacent segments abut without any overlap.
        drawLine(color, pointAt(marginPx), pointAt(oneThird), strokePx, cap = StrokeCap.Butt)
        drawLine(color, pointAt(twoThirds), pointAt(extent - marginPx), strokePx, cap = StrokeCap.Butt)

        // Dynamic center third — same fixed span as the statics, plus the small live-roll rotation.
        rotate(degrees = residualRollDegrees, pivot = center) {
            drawLine(color, pointAt(oneThird), pointAt(twoThirds), strokePx, cap = StrokeCap.Butt)
        }
    }
}

/** Margin off each side of the frame the outer static thirds stop short of — modest, not a large
 *  inset, per the feature request that the line "spans the frame." */
private val LineSideMargin = 24.dp

/** Shared by all three segments — there's no separate "thick" line, see this file's own class doc. */
private val HorizonLineStrokeWidth = 1.5.dp

/** Base color all three segments render at outside the momentary level flash — same tint
 *  [HistogramOverlay]'s own baseline marks use. */
private val LineColor = CameraChrome.HistogramMarkColor

/** Resting alpha. */
private const val BaseLineAlpha = 0.4f

/** Peak alpha the line flashes to for [FlashFadeDurationMs] right as the device becomes level, before
 *  fading back down to [BaseLineAlpha]. */
private const val LevelFlashAlpha = 0.9f

/** How long the level flash takes to fade back down — "a fraction of a second" per the feature request,
 *  same order of magnitude as this app's other quick confirmation fades. */
private const val FlashFadeDurationMs = 260

/** Residual-from-level (degrees) that counts as "just became level," triggering the haptic tick + flash. */
private const val LevelEnterThresholdDegrees = 0.5f

/** Residual-from-level the device must drift back out past before a later re-entry under
 *  [LevelEnterThresholdDegrees] counts as a fresh event — see [HorizonLineOverlay]'s own [wasLevel] doc. */
private const val LevelExitThresholdDegrees = 2f

@Preview(showBackground = true, widthDp = 220, heightDp = 320, backgroundColor = 0xFF0D1210)
@Composable
private fun HorizonLineOverlayLevelPreview() {
    XCameraTheme {
        HorizonLines(
            quadrant = 0f,
            residualRollDegrees = 0f,
            flashProgress = 0f,
            modifier = Modifier.fillMaxSize().background(Color(0xFF0D1210)),
        )
    }
}

@Preview(showBackground = true, widthDp = 220, heightDp = 320, backgroundColor = 0xFF0D1210)
@Composable
private fun HorizonLineOverlayTiltedPreview() {
    XCameraTheme {
        HorizonLines(
            quadrant = 0f,
            residualRollDegrees = 12f,
            flashProgress = 0f,
            modifier = Modifier.fillMaxSize().background(Color(0xFF0D1210)),
        )
    }
}

@Preview(showBackground = true, widthDp = 220, heightDp = 320, backgroundColor = 0xFF0D1210)
@Composable
private fun HorizonLineOverlayJustLeveledPreview() {
    XCameraTheme {
        HorizonLines(
            quadrant = 0f,
            residualRollDegrees = 0f,
            flashProgress = 1f,
            modifier = Modifier.fillMaxSize().background(Color(0xFF0D1210)),
        )
    }
}

/** Landscape (quadrant = 90°) — demonstrates the axis swap: the line now lays out along Y (thirds of
 *  height) instead of X, so its thirds still land on [ViewfinderGridOverlay]'s own divider positions
 *  even though the device has physically turned 90° from the [HorizonLineOverlayTiltedPreview] case. */
@Preview(showBackground = true, widthDp = 320, heightDp = 220, backgroundColor = 0xFF0D1210)
@Composable
private fun HorizonLineOverlayLandscapeTiltedPreview() {
    XCameraTheme {
        HorizonLines(
            quadrant = 90f,
            residualRollDegrees = 8f,
            flashProgress = 0f,
            modifier = Modifier.fillMaxSize().background(Color(0xFF0D1210)),
        )
    }
}
