package com.dragote.xcamera.feature.camera.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dragote.xcamera.feature.camera.domain.model.HistogramData
import com.dragote.xcamera.feature.camera.ui.theme.CameraChrome
import com.dragote.xcamera.shared.designsystem.theme.XCameraTheme
import kotlin.math.sqrt

/**
 * Always-on tonal readout of [data] (see `CameraViewModel.histogramData`'s own doc for why this is
 * always-on, unlike [ZebraOverlay]'s dial-drag gate) — a fixed-size flat rounded rectangle rather
 * than a translucent full-viewfinder overlay, matching [ExposingIndicator]/[ViewfinderThumbnailChip]'s
 * own corner-chip convention rather than [ZebraOverlay]/[ViewfinderGridOverlay]'s full-bleed one.
 *
 * Bar heights are `sqrt(count / maxBucketCount)`-scaled, not linear — a raw linear scale lets one
 * tall spike (extremely common in a real histogram, e.g. a large flat-colored sky or wall) dwarf
 * every other bucket down to invisibility, which defeats the point of a tonal-distribution readout;
 * the square-root compresses that dominance while still preserving the tallest-bucket-is-tallest
 * ordering. Bar color is a horizontal gradient from [CameraChrome.ZebraShadow] (dark/shadow end, at
 * bucket 0) to [CameraChrome.ZebraHighlight] (light/highlight end, at the last bucket) — the same two
 * colors [ZebraOverlay] already uses for shadow/highlight clipping, so a shadow-clipped or
 * highlight-clipped tail of the histogram visually matches whatever [ZebraOverlay] would be striping
 * at the same time.
 *
 * [data] going `null` (no frame classified yet, e.g. right after a fresh camera bind) draws an empty
 * box rather than hiding entirely — unlike [ZebraOverlay]'s fade-out-then-vanish (there's no gesture
 * this is tied to that would make hiding it read as intentional), the box itself is a permanent
 * fixture of the viewfinder chrome.
 */
@Composable
fun HistogramOverlay(data: HistogramData?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(width = HistogramWidth, height = HistogramHeight)
            .clip(RoundedCornerShape(8.dp))
            .background(CameraChrome.HistogramBackground),
    ) {
        Canvas(modifier = Modifier.size(width = HistogramWidth, height = HistogramHeight)) {
            val buckets = data?.buckets.orEmpty()
            if (buckets.isEmpty()) return@Canvas
            val maxCount = data?.maxBucketCount ?: 0
            if (maxCount <= 0) return@Canvas

            val barWidth = size.width / buckets.size
            for (index in buckets.indices) {
                val fraction = sqrt(buckets[index].toFloat() / maxCount)
                val barHeight = size.height * fraction
                val t = if (buckets.size > 1) index.toFloat() / (buckets.size - 1) else 0f
                val color = lerp(CameraChrome.ZebraShadow, CameraChrome.ZebraHighlight, t)
                drawRect(
                    color = color,
                    topLeft = Offset(x = index * barWidth, y = size.height - barHeight),
                    size = Size(width = barWidth, height = barHeight),
                )
            }
        }
    }
}

private val HistogramWidth = 96.dp
private val HistogramHeight = 48.dp

@Preview(showBackground = true, backgroundColor = 0xFF0D1210)
@Composable
private fun HistogramOverlayPreview() {
    // A rough bell-ish shape with a highlight-side spike, representative of a typical outdoor frame.
    val bucketCount = 64
    val buckets = List(bucketCount) { i ->
        val mid = bucketCount / 2
        val bell = (40 - kotlin.math.abs(i - mid)).coerceAtLeast(0)
        val highlightSpike = if (i > bucketCount - 6) 60 else 0
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
