package com.dragote.xcamera.feature.camera.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.dragote.xcamera.feature.camera.domain.model.FocusPeakingMask
import com.dragote.xcamera.feature.camera.ui.theme.CameraChrome
import com.dragote.xcamera.shared.designsystem.theme.XCameraTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The hold-and-rotate manual focus ring (issue #21) — a mechanical, gear-toothed ring centered on
 * wherever the long-press started ([center] is fixed for the gesture's whole duration, never
 * repositioned by rotation — see `ui/CameraScreen`'s own gesture handling, which is the sole owner of
 * [center]/[rotationDegrees]/hold lifecycle; this composable is purely a renderer of whatever it's
 * given). Inside the ring, a magnified ("loupe") crop of the live preview centered on the same point
 * ([loupeImage], already cropped+ready to draw — this composable does no cropping itself, see
 * `CameraScreen`'s `rememberFocusLoupe`) shows fine detail while adjusting; [peakingMask] (see its own
 * doc) outlines locally sharp edges within that loupe crop only — never the full viewfinder, per the
 * issue's own scoping.
 *
 * [rotationDegrees] visually spins the ring's teeth as the user rotates (a cheap, honest "this control
 * is turning" cue, independent of how far along the focus range that rotation has actually moved —
 * the ring can spin through many turns end-to-end, see
 * [com.dragote.xcamera.feature.camera.domain.model.manualFocusDistanceForRotation]'s own doc for the
 * ~1.5-turn full-range mapping).
 *
 * [center] is `null` when idle — this composable still composes (so its own fade-out animation can
 * play) but draws nothing once fully faded, mirroring [ZebraOverlay]'s own `displayedMask`-holds-the-
 * last-value-while-fading pattern.
 */
@Composable
fun FocusRing(
    center: Offset?,
    rotationDegrees: Float,
    loupeImage: ImageBitmap?,
    peakingMask: FocusPeakingMask?,
    modifier: Modifier = Modifier,
    diameter: androidx.compose.ui.unit.Dp = RingDiameter,
) {
    var displayedCenter by remember { mutableStateOf(center) }
    LaunchedEffect(center) { if (center != null) displayedCenter = center }

    val alpha by animateFloatAsState(
        targetValue = if (center != null) 1f else 0f,
        animationSpec = tween(durationMillis = 180, easing = CameraChrome.EaseStandard),
        label = "focusRingAlpha",
    )

    // Rebuilt only when the mask itself changes (throttled to ~12.5fps by CameraScreen's loupe-refresh
    // loop, not every recomposition — rotationDegrees alone changes far more often during the drag
    // gesture and must not pay this cost) — see [FocusPeakingMask.toHighlightImage]'s own doc for why
    // this is a bitmap rather than per-cell shapes.
    val peakingImage = remember(peakingMask) {
        peakingMask?.takeIf { it.columns > 0 && it.rows > 0 }?.toHighlightImage(FocusPeakingColor)
    }

    // Every absolute dp measurement below (ring thickness, tooth length/stroke, index-mark size, rim
    // stroke) scales proportionally with diameter relative to the design reference (RingDiameter) —
    // callers now routinely pass a diameter several times RingDiameter (see ui/CameraScreen, issue #21
    // follow-up: the ring fills nearly the whole viewfinder), and a fixed-dp ring band/teeth would read
    // as spindly and thin against a much bigger circle without this.
    val scale = diameter / RingDiameter

    Canvas(modifier = modifier.fillMaxSize().alpha(alpha)) {
        val ringCenter = displayedCenter ?: return@Canvas
        val outerRadiusPx = diameter.toPx() / 2f
        val ringThicknessPx = (RingThickness * scale).toPx()
        val loupeRadiusPx = outerRadiusPx - ringThicknessPx - (RingGap * scale).toPx()

        drawLoupe(ringCenter, loupeRadiusPx, loupeImage, peakingImage, scale)
        drawGearRing(ringCenter, outerRadiusPx, ringThicknessPx, rotationDegrees, scale)
    }
}

/** Magnified live-preview crop, clipped to a circle, with the loupe-local focus-peaking highlight
 *  drawn on top of it (also clipped to the same circle — the whole point of scoping it to the loupe).
 *  [scale] is the ring's own size scale relative to its design reference — see [FocusRing]'s own doc. */
private fun DrawScope.drawLoupe(
    center: Offset,
    radiusPx: Float,
    loupeImage: ImageBitmap?,
    peakingImage: ImageBitmap?,
    scale: Float,
) {
    val clip = Path().apply { addOval(Rect(center, radiusPx)) }
    clipPath(clip) {
        if (loupeImage != null) {
            val dstSize = (radiusPx * 2f).toInt()
            drawImage(
                image = loupeImage,
                dstOffset = IntOffset((center.x - radiusPx).toInt(), (center.y - radiusPx).toInt()),
                dstSize = IntSize(dstSize, dstSize),
            )
        } else {
            drawCircle(color = Color(0xFF0D1210), radius = radiusPx, center = center)
        }
        drawRect(Color.Black.copy(alpha = 0.15f), topLeft = center - Offset(radiusPx, radiusPx), size = Size(radiusPx * 2f, radiusPx * 2f))

        if (peakingImage != null) {
            val dstSize = (radiusPx * 2f).toInt()
            drawImage(
                image = peakingImage,
                dstOffset = IntOffset((center.x - radiusPx).toInt(), (center.y - radiusPx).toInt()),
                dstSize = IntSize(dstSize, dstSize),
            )
        }
    }
    // Loupe rim — a thin inner line separating the magnified content from the gear ring around it.
    drawCircle(color = Color.White.copy(alpha = 0.25f), radius = radiusPx, center = center, style = Stroke(width = (1.dp * scale).toPx()))
}

/** Renders [FocusPeakingMask.edge] (one cell per source pixel — see the mask's own doc) as a small
 *  ARGB bitmap, transparent where `false` and [color] where `true`, that [drawLoupe] then scales up in
 *  a single `drawImage` call. Bilinear upscaling from mask resolution to the loupe's much larger
 *  on-screen size is what turns the per-pixel edge classification into a smooth, thin contour tracing
 *  the sharp object's own silhouette — per-cell shapes (a `Stroke` rect, or even per-side lines) don't
 *  scale to per-pixel mask resolution: that's tens of thousands of draw calls per frame.
 *
 *  [thicknessPx] dilates the raw one-pixel-wide classification by that many mask pixels in every
 *  direction before rasterizing — a purely visual choice (this is why it lives here, in the UI layer,
 *  rather than in [FocusPeakingMask] itself, which stays a pure classification): an un-dilated single
 *  bright pixel surrounded by fully-transparent neighbors gets diluted by the bilinear upscale below,
 *  reading as a faint, thin line rather than a confident contour. */
private fun FocusPeakingMask.toHighlightImage(color: Color, thicknessPx: Int = 0): ImageBitmap {
    val highlight = color.toArgb()
    val bitmap = android.graphics.Bitmap.createBitmap(columns, rows, android.graphics.Bitmap.Config.ARGB_8888)
    val pixels = IntArray(columns * rows)
    for (row in 0 until rows) {
        for (col in 0 until columns) {
            var near = false
            for (dy in -thicknessPx..thicknessPx) {
                if (near) break
                val r = row + dy
                if (r !in 0 until rows) continue
                for (dx in -thicknessPx..thicknessPx) {
                    val c = col + dx
                    if (c in 0 until columns && edge[r * columns + c]) {
                        near = true
                        break
                    }
                }
            }
            pixels[row * columns + col] = if (near) highlight else 0
        }
    }
    bitmap.setPixels(pixels, 0, columns, 0, 0, columns, rows)
    return bitmap.asImageBitmap()
}

/** The mechanical, gear-toothed ring itself — a base stroked circle plus radial "teeth" ticks that
 *  spin with [rotationDegrees], reading as a physical focus-ring being turned. [scale] keeps the
 *  teeth/index-mark/rim proportionate at whatever [outerRadiusPx] the caller is actually drawing —
 *  see [FocusRing]'s own doc. */
private fun DrawScope.drawGearRing(
    center: Offset,
    outerRadiusPx: Float,
    thicknessPx: Float,
    rotationDegrees: Float,
    scale: Float,
) {
    val midRadius = outerRadiusPx - thicknessPx / 2f
    drawCircle(
        color = RingBodyColor,
        radius = midRadius,
        center = center,
        style = Stroke(width = thicknessPx),
    )

    val innerRadius = outerRadiusPx - thicknessPx
    val toothOuterRadius = outerRadiusPx + (2.dp * scale).toPx()
    val toothStroke = (2.dp * scale).toPx()
    rotate(degrees = rotationDegrees, pivot = center) {
        for (tooth in 0 until GearTeeth) {
            val angle = (2.0 * PI * tooth / GearTeeth).toFloat()
            val cosA = cos(angle)
            val sinA = sin(angle)
            drawLine(
                color = GearTeethColor,
                start = Offset(center.x + innerRadius * cosA, center.y + innerRadius * sinA),
                end = Offset(center.x + toothOuterRadius * cosA, center.y + toothOuterRadius * sinA),
                strokeWidth = toothStroke,
            )
        }
    }

    // Fixed (non-rotating) center indicator carets, top and bottom — an "index mark" a real focus
    // ring reads its position against, independent of how far the ring itself has spun.
    val markLength = (6.dp * scale).toPx()
    val markStroke = (2.dp * scale).toPx()
    drawLine(
        color = CameraChrome.Accent,
        start = Offset(center.x, center.y - outerRadiusPx - markLength),
        end = Offset(center.x, center.y - outerRadiusPx + (2.dp * scale).toPx()),
        strokeWidth = markStroke,
    )

    drawCircle(
        color = Color.White.copy(alpha = 0.12f),
        radius = outerRadiusPx,
        center = center,
        style = Stroke(width = (1.dp * scale).toPx()),
    )
}

private val RingDiameter = 156.dp
private val RingThickness = 2.dp
private val RingGap = 4.dp
private const val GearTeeth = 40
private val RingBodyColor = Color(0xFF201F1E).copy(alpha = 0.92f)
private val GearTeethColor = Color(0xFFDED7C3).copy(alpha = 0.85f)
private val FocusPeakingColor = Color(0xFF33E6A0)

/* ── Previews ────────────────────────────────────────────────────────────── */

private fun previewLoupeBitmap(): ImageBitmap {
    val size = 64
    val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    for (x in 0 until size) {
        for (y in 0 until size) {
            val on = ((x / 8) + (y / 8)) % 2 == 0
            val shade = if (on) 210 else 90
            bitmap.setPixel(x, y, android.graphics.Color.rgb(shade, shade, shade))
        }
    }
    return bitmap.asImageBitmap()
}

/** A ring-shaped mask at pixel-ish resolution — the honest preview shape for [toHighlightImage]'s
 *  bitmap-overlay rendering (real masks trace an arbitrary object silhouette; a ring is a simple stand-in
 *  that still exercises "thin contour, not boxed cells" the same way the diagonal-cross grid the old
 *  per-cell-rectangle renderer used no longer would). */
private fun previewPeakingMask(): FocusPeakingMask {
    val size = 96
    val center = size / 2f
    val outerRadius = size * 0.42f
    val innerRadius = outerRadius - 3f
    val edges = List(size * size) { i ->
        val x = (i % size) - center
        val y = (i / size) - center
        val dist = sqrt(x * x + y * y)
        dist in innerRadius..outerRadius
    }
    return FocusPeakingMask(size, size, edges)
}

// Matches real usage (ui/CameraScreen, issue #21 follow-up): the ring is always centered on the
// viewfinder itself, sized to ~92% of its shorter dimension — not the small, touch-point-centered
// 156dp default these previews used before that change.
private val PreviewViewfinderWidthDp = 300.dp
private val PreviewViewfinderHeightDp = 420.dp
private val PreviewRingDiameterDp = PreviewViewfinderWidthDp * 0.92f

@Preview(showBackground = true, widthDp = 300, heightDp = 420, backgroundColor = 0xFF0D1210)
@Composable
private fun FocusRingWithPeakingPreview() {
    XCameraTheme {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val center = with(density) { Offset(PreviewViewfinderWidthDp.toPx() / 2f, PreviewViewfinderHeightDp.toPx() / 2f) }
        FocusRing(
            center = center,
            rotationDegrees = 35f,
            loupeImage = previewLoupeBitmap(),
            peakingMask = previewPeakingMask(),
            diameter = PreviewRingDiameterDp,
            modifier = Modifier.fillMaxSize().background(Color(0xFF0D1210)),
        )
    }
}

@Preview(showBackground = true, widthDp = 300, heightDp = 420, backgroundColor = 0xFF0D1210)
@Composable
private fun FocusRingLoadingPreview() {
    XCameraTheme {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val center = with(density) { Offset(PreviewViewfinderWidthDp.toPx() / 2f, PreviewViewfinderHeightDp.toPx() / 2f) }
        FocusRing(
            center = center,
            rotationDegrees = 0f,
            loupeImage = null,
            peakingMask = null,
            diameter = PreviewRingDiameterDp,
            modifier = Modifier.fillMaxSize().background(Color(0xFF0D1210)),
        )
    }
}

@Preview(showBackground = true, widthDp = 300, heightDp = 420, backgroundColor = 0xFF0D1210)
@Composable
private fun FocusRingHiddenPreview() {
    XCameraTheme {
        FocusRing(
            center = null,
            rotationDegrees = 0f,
            loupeImage = null,
            peakingMask = null,
            diameter = PreviewRingDiameterDp,
            modifier = Modifier.fillMaxSize().background(Color(0xFF0D1210)),
        )
    }
}