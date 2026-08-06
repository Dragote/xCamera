package com.dragote.xcamera.feature.camera.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dragote.xcamera.feature.camera.domain.model.HistogramData
import com.dragote.xcamera.feature.camera.ui.theme.CameraChrome
import com.dragote.xcamera.shared.designsystem.theme.XCameraTheme
import kotlin.math.sqrt

/**
 * Always-on tonal readout of [data] (see `CameraViewModel.histogramData`'s own doc for why this is
 * always-on, unlike [ZebraOverlay]'s dial-drag gate) — a small fixed-size corner readout rather than
 * a translucent full-viewfinder overlay, matching [ExposingIndicator]/[ViewfinderThumbnailChip]'s own
 * corner-chip convention (position-wise) rather than [ZebraOverlay]/[ViewfinderGridOverlay]'s
 * full-bleed one.
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
 */
@Composable
fun HistogramOverlay(data: HistogramData?, modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(width = HistogramWidth, height = HistogramHeight)) {
        Canvas(
            modifier = Modifier
                .size(width = HistogramWidth, height = HistogramHeight)
                .padding(horizontal = HorizontalInset, vertical = VerticalInset),
        ) {
            val bucketCount = data?.buckets?.size ?: EmptyBucketCount
            if (bucketCount <= 0) return@Canvas
            val maxCount = data?.maxBucketCount ?: 0

            val slotWidth = size.width / bucketCount
            val strokeWidthPx = slotWidth * BarWidthFraction
            val baselineY = size.height - strokeWidthPx / 2

            for (index in 0 until bucketCount) {
                val count = data?.buckets?.getOrNull(index) ?: 0
                val fraction = if (maxCount > 0) sqrt(count.toFloat() / maxCount) else 0f
                val barHeight = (size.height - strokeWidthPx) * fraction
                val x = slotWidth * (index + 0.5f)
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
private val HorizontalInset = 8.dp
private val VerticalInset = 8.dp

/** Fraction of each bucket's slot width a bar/dot actually fills — the rest is the gap between bars,
 *  so this alone controls bar spacing rather than a fixed dp diameter that would look cramped or
 *  disproportionate as [EmptyBucketCount]/`CameraController.HistogramBucketCount` change. */
private const val BarWidthFraction = 0.4f

/** Bucket count for the idle (no data yet) baseline row — matches `CameraController.HistogramBucketCount`
 *  so the dot spacing doesn't visibly shift once the first real frame lands; not shared as a literal
 *  constant across the data/ui layers since a one-time idle-to-live relayout is imperceptible either way. */
private const val EmptyBucketCount = 16

@Preview(showBackground = true, backgroundColor = 0xFF0D1210)
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
        Box(modifier = Modifier.padding(24.dp)) {
            HistogramOverlay(data = HistogramData(buckets))
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1210)
@Composable
private fun HistogramOverlayEmptyPreview() {
    XCameraTheme {
        Box(modifier = Modifier.padding(24.dp)) {
            HistogramOverlay(data = null)
        }
    }
}
