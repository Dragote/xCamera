package com.dragote.xcamera.feature.camera.ui.component

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dragote.xcamera.feature.camera.domain.model.HistogramData
import com.dragote.xcamera.feature.camera.ui.theme.CameraChrome
import com.dragote.xcamera.shared.designsystem.theme.XCameraTheme
import kotlin.math.sqrt

/**
 * Always-on tonal readout of [data] (see `CameraViewModel.histogramData`'s own doc for why this is
 * always-on, unlike [ZebraOverlay]'s dial-drag gate).
 *
 * The Activity is portrait-locked (see AndroidManifest), so this screen's own layout never
 * physically rotates — but the *device* still does, in the user's hand. Two things need to react to
 * that, same as [ViewfinderThumbnailChip]'s glyph: position (pinning to a single fixed screen corner
 * would mean this visually ends up bottom-left, or upside down, from the user's own point of view
 * once they rotate the phone) and upright-ness (the bars themselves need to counter-rotate to stay
 * gravity-aligned). This hops between the four screen corners *and* counter-rotates its content as
 * [rememberDeviceOrientationQuadrant] changes — see [cornerForQuadrant] for the corner mapping and
 * [counterRotationDegrees] for the rotation.
 *
 * The rotation happens *inside* [HistogramMarks]'s own `Canvas` draw scope (`DrawScope.rotate`), not
 * as a `Modifier.rotate` wrapped around an always-96x48dp-laid-out box — a `Modifier`-level rotation
 * only transforms how the box *paints*, it doesn't change what size the layout system thinks the box
 * is, so a 96x48dp box rotated 90° in place would still be *positioned* using its unrotated 96x48dp
 * bounds while visually occupying a 48x96dp footprint, pushing part of it off-screen once anchored
 * near a corner with only [CornerInset] of margin (this was a real, shipped bug: rotating in place
 * this way clipped the readout against the screen edge in landscape). [HistogramMarks] instead
 * *measures* itself at 48x96dp for a 90°/270° rotation to begin with — the layout system reserves
 * the correct rotated footprint before any alignment happens — and draws the same always-"96x48dp
 * logical space" bar chart into that reserved area via a coordinate-space rotation around its own
 * center, so what's drawn always fits exactly inside what's laid out.
 *
 * The hop cross-fades (old corner's content fades out while the new corner's fades in, each already
 * snapped to its own upright rotation — no animated spin, unlike [ViewfinderThumbnailChip]'s smooth
 * tween) rather than sliding a position across the screen — a discrete snap would look like a glitch,
 * and animating a diagonal slide across the viewfinder would be far more distracting than the readout
 * itself is worth.
 *
 * Each bucket draws as a single round-capped vertical line from the baseline up to its scaled height
 * — at zero height the round cap alone still paints, so an empty bucket reads as a small dot sitting
 * on the baseline and a populated one reads as a dot-capped bar, both in one `drawLine` call. Bar/dot
 * height is `sqrt(count / maxBucketCount)`-scaled, not linear — a raw linear scale lets one tall spike
 * (extremely common in a real histogram, e.g. a large flat-colored sky or wall) dwarf every other
 * bucket down to invisibility, which defeats the point of a tonal-distribution readout; the
 * square-root compresses that dominance while still preserving the tallest-bucket-is-tallest ordering.
 *
 * Every mark is [CameraChrome.HistogramMarkColor] (near-white) except the very first (darkest) and
 * very last (brightest) bucket, which use [CameraChrome.ZebraShadow]/[CameraChrome.ZebraHighlight] —
 * the same two colors [ZebraOverlay] uses for shadow/highlight clipping — to flag the absolute
 * shadow/highlight ends of the tonal range rather than tinting the whole distribution.
 *
 * No background/track fill — the marks float directly over the viewfinder rather than sitting in a
 * chrome-styled box, so [BarWidthFraction] (each bar/dot fills well under half its slot) is what
 * supplies the visible spacing between bars instead of a container edge.
 *
 * [data] going `null` (no frame classified yet, e.g. right after a fresh camera bind) still draws the
 * baseline dot row at zero height rather than nothing — unlike [ZebraOverlay]'s fade-out-then-vanish,
 * there's no gesture this is tied to that would make hiding it read as intentional, and the idle dot
 * row reads as "ready, no data yet" rather than a blank gap in the chrome.
 *
 * [modifier] should size this to the full area the readout is allowed to roam across corners of
 * (e.g. `Modifier.fillMaxSize()` over the whole viewfinder), not to the readout's own small size —
 * unlike this repo's other corner chips ([ExposingIndicator]/[ViewfinderThumbnailChip]), which are
 * pre-aligned to one fixed corner by their caller, this one picks its own corner internally since
 * that corner changes at runtime.
 */
@Composable
fun HistogramOverlay(data: HistogramData?, modifier: Modifier = Modifier) {
    val quadrant by rememberDeviceOrientationQuadrant()
    Box(modifier = modifier.padding(CornerInset)) {
        Crossfade(
            targetState = quadrant,
            modifier = Modifier.fillMaxSize(),
            animationSpec = tween(durationMillis = 300, easing = CameraChrome.EaseStandard),
            label = "histogramCorner",
        ) { activeQuadrant ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = cornerForQuadrant(Alignment.TopEnd, activeQuadrant),
            ) {
                HistogramMarks(data = data, rotationDegrees = counterRotationDegrees(activeQuadrant))
            }
        }
    }
}

/**
 * [rotationDegrees] (one of `0f`/`90f`/`180f`/`270f`) rotates the *drawn* bar chart, not a laid-out
 * box — this `Canvas` measures itself at [HistogramHeight]x[HistogramWidth] (swapped) for a 90°/270°
 * rotation so the layout system reserves the actual post-rotation footprint, then draws the same
 * always-[HistogramWidth]x[HistogramHeight]-logical-space bar chart into that reserved area via
 * `DrawScope.rotate` around the canvas's own center — see [HistogramOverlay]'s own doc for why a
 * `Modifier`-level rotation instead (which doesn't affect measurement) clips against the screen edge
 * once anchored near a corner in landscape.
 */
@Composable
private fun HistogramMarks(data: HistogramData?, rotationDegrees: Float, modifier: Modifier = Modifier) {
    val quarterTurned = rotationDegrees == 90f || rotationDegrees == 270f
    val canvasWidth = if (quarterTurned) HistogramHeight else HistogramWidth
    val canvasHeight = if (quarterTurned) HistogramWidth else HistogramHeight

    Canvas(modifier = modifier.size(width = canvasWidth, height = canvasHeight)) {
        rotate(degrees = rotationDegrees, pivot = center) {
            val logicalWidth = HistogramWidth.toPx()
            val logicalHeight = HistogramHeight.toPx()
            val originX = center.x - logicalWidth / 2f
            val originY = center.y - logicalHeight / 2f

            val bucketCount = data?.buckets?.size ?: EmptyBucketCount
            if (bucketCount <= 0) return@rotate
            val maxCount = data?.maxBucketCount ?: 0

            val slotWidth = logicalWidth / bucketCount
            val strokeWidthPx = slotWidth * BarWidthFraction
            val baselineY = originY + logicalHeight - strokeWidthPx / 2

            for (index in 0 until bucketCount) {
                val count = data?.buckets?.getOrNull(index) ?: 0
                val fraction = if (maxCount > 0) sqrt(count.toFloat() / maxCount) else 0f
                val barHeight = (logicalHeight - strokeWidthPx) * fraction
                val x = originX + slotWidth * (index + 0.5f)
                val color = when (index) {
                    0 -> CameraChrome.ZebraShadow
                    bucketCount - 1 -> CameraChrome.ZebraHighlight
                    else -> CameraChrome.HistogramMarkColor
                }
                drawLine(
                    color = color,
                    start = Offset(x, baselineY),
                    end = Offset(x, baselineY - barHeight),
                    strokeWidth = strokeWidthPx,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

private val HistogramWidth = 96.dp
private val HistogramHeight = 48.dp
private val CornerInset = 12.dp

/** Fraction of each bucket's slot width a bar/dot actually fills — the rest is the gap between bars,
 *  so this alone controls bar spacing rather than a fixed dp diameter that would look cramped or
 *  disproportionate as [EmptyBucketCount]/`CameraController.HistogramBucketCount` change. */
private const val BarWidthFraction = 0.4f

/** Bucket count for the idle (no data yet) baseline row — matches `CameraController.HistogramBucketCount`
 *  so the dot spacing doesn't visibly shift once the first real frame lands; not shared as a literal
 *  constant across the data/ui layers since a one-time idle-to-live relayout is imperceptible either way. */
private const val EmptyBucketCount = 16

@Preview(showBackground = true, widthDp = 220, heightDp = 320, backgroundColor = 0xFF0D1210)
@Composable
private fun HistogramOverlayPreview() {
    // A rough distribution with a highlight-side spike, representative of a typical outdoor frame.
    val bucketCount = 16
    val buckets = List(bucketCount) { i ->
        val mid = bucketCount / 2
        val bell = (8 - kotlin.math.abs(i - mid)).coerceAtLeast(0)
        val highlightSpike = if (i == bucketCount - 3) 40 else if (i == bucketCount - 2) 15 else 0
        bell + highlightSpike
    }
    XCameraTheme {
        HistogramOverlay(data = HistogramData(buckets), modifier = Modifier.fillMaxSize())
    }
}

@Preview(showBackground = true, widthDp = 220, heightDp = 320, backgroundColor = 0xFF0D1210)
@Composable
private fun HistogramOverlayEmptyPreview() {
    XCameraTheme {
        HistogramOverlay(data = null, modifier = Modifier.fillMaxSize())
    }
}
