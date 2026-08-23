package com.dragote.xcamera.feature.camera.ui.component.overlay

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
 * The hold-and-rotate manual focus ring — gesture ownership stays in `ui/CameraScreen`, this only owns
 * the drawing. A plain black stroked circle with thin white radial tick marks — flat line art standing
 * in for a "mechanical ring being turned" cue, at [rotationDegrees]-driven positions. Since this sits
 * directly over the loupe's own magnified live-preview crop (which can be any brightness), the ring
 * itself stays black — legible against [drawLoupe]'s own idle placeholder fill and, in practice, the
 * loupe crop rarely fills the *entire* ring's thin band — while the fixed index caret gets a dual-tone
 * (white-on-black) treatment for the same over-arbitrary-content legibility reasoning
 * [FocusTapIndicator] uses.
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

    val peakingImage = remember(peakingMask) {
        peakingMask?.takeIf { it.columns > 0 && it.rows > 0 }?.toHighlightImage(FocusPeakingColor)
    }

    val scale = diameter / RingDiameter

    Canvas(modifier = modifier.fillMaxSize().alpha(alpha)) {
        val ringCenter = displayedCenter ?: return@Canvas
        val outerRadiusPx = diameter.toPx() / 2f
        val ringThicknessPx = (RingThickness * scale).toPx()
        val loupeRadiusPx = outerRadiusPx - ringThicknessPx - (RingGap * scale).toPx()

        drawLoupe(ringCenter, loupeRadiusPx, loupeImage, peakingImage, scale)
        drawFlatRing(ringCenter, outerRadiusPx, ringThicknessPx, rotationDegrees, scale)
    }
}

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
            drawCircle(color = CameraChrome.Ink, radius = radiusPx, center = center)
        }

        if (peakingImage != null) {
            val dstSize = (radiusPx * 2f).toInt()
            drawImage(
                image = peakingImage,
                dstOffset = IntOffset((center.x - radiusPx).toInt(), (center.y - radiusPx).toInt()),
                dstSize = IntSize(dstSize, dstSize),
            )
        }
    }
    // Loupe rim — a thin white line separating the magnified content from the ring around it.
    drawCircle(color = Color.White.copy(alpha = 0.7f), radius = radiusPx, center = center, style = Stroke(width = (1.dp * scale).toPx()))
}

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

/** A plain black stroked circle plus thin radial tick marks that spin with [rotationDegrees]. [scale]
 *  keeps ticks/index-mark/rim proportionate at whatever [outerRadiusPx] the caller draws — see
 *  [FocusRing]'s own doc. */
private fun DrawScope.drawFlatRing(
    center: Offset,
    outerRadiusPx: Float,
    thicknessPx: Float,
    rotationDegrees: Float,
    scale: Float,
) {
    val midRadius = outerRadiusPx - thicknessPx / 2f
    drawCircle(color = CameraChrome.Ink, radius = midRadius, center = center, style = Stroke(width = thicknessPx))

    val innerRadius = outerRadiusPx - thicknessPx
    val toothOuterRadius = outerRadiusPx + (2.dp * scale).toPx()
    val toothStroke = (1.5.dp * scale).toPx()
    rotate(degrees = rotationDegrees, pivot = center) {
        for (tooth in 0 until GearTeeth) {
            val angle = (2.0 * PI * tooth / GearTeeth).toFloat()
            val cosA = cos(angle)
            val sinA = sin(angle)
            drawLine(
                color = Color.White,
                start = Offset(center.x + innerRadius * cosA, center.y + innerRadius * sinA),
                end = Offset(center.x + toothOuterRadius * cosA, center.y + toothOuterRadius * sinA),
                strokeWidth = toothStroke,
            )
        }
    }

    // Fixed (non-rotating) index mark — dual-tone (white core, black outline) for legibility against
    // whatever the loupe/live scene behind it happens to be.
    val markLength = (6.dp * scale).toPx()
    val outerStroke = (3.dp * scale).toPx()
    val innerStroke = (1.5.dp * scale).toPx()
    val markStart = Offset(center.x, center.y - outerRadiusPx - markLength)
    val markEnd = Offset(center.x, center.y - outerRadiusPx + (2.dp * scale).toPx())
    drawLine(color = Color.Black, start = markStart, end = markEnd, strokeWidth = outerStroke)
    drawLine(color = Color.White, start = markStart, end = markEnd, strokeWidth = innerStroke)
}

private val RingDiameter = 156.dp
private val RingThickness = 2.dp
private val RingGap = 4.dp
private const val GearTeeth = 40
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
