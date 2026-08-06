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

    Canvas(modifier = modifier.fillMaxSize().alpha(alpha)) {
        val ringCenter = displayedCenter ?: return@Canvas
        val outerRadiusPx = diameter.toPx() / 2f
        val ringThicknessPx = RingThickness.toPx()
        val loupeRadiusPx = outerRadiusPx - ringThicknessPx - RingGap.toPx()

        drawLoupe(ringCenter, loupeRadiusPx, loupeImage, peakingMask)
        drawGearRing(ringCenter, outerRadiusPx, ringThicknessPx, rotationDegrees)
    }
}

/** Magnified live-preview crop, clipped to a circle, with the loupe-local focus-peaking highlight
 *  drawn on top of it (also clipped to the same circle — the whole point of scoping it to the loupe). */
private fun DrawScope.drawLoupe(
    center: Offset,
    radiusPx: Float,
    loupeImage: ImageBitmap?,
    peakingMask: FocusPeakingMask?,
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

        if (peakingMask != null && peakingMask.columns > 0 && peakingMask.rows > 0) {
            drawPeakingHighlight(peakingMask, center, radiusPx)
        }
    }
    // Loupe rim — a thin inner line separating the magnified content from the gear ring around it.
    drawCircle(color = Color.White.copy(alpha = 0.25f), radius = radiusPx, center = center, style = Stroke(width = 1.dp.toPx()))
}

/** One outlined rect per [FocusPeakingMask.edge] cell classified `true`, confined to the loupe's own
 *  circular bounds via the caller's `clipPath`. */
private fun DrawScope.drawPeakingHighlight(mask: FocusPeakingMask, center: Offset, radiusPx: Float) {
    val boxSize = radiusPx * 2f
    val cellWidth = boxSize / mask.columns
    val cellHeight = boxSize / mask.rows
    val left = center.x - radiusPx
    val top = center.y - radiusPx
    val strokeWidth = 1.5.dp.toPx()

    for (row in 0 until mask.rows) {
        for (col in 0 until mask.columns) {
            if (mask.edge.getOrNull(row * mask.columns + col) != true) continue
            drawRect(
                color = FocusPeakingColor,
                topLeft = Offset(left + col * cellWidth, top + row * cellHeight),
                size = Size(cellWidth, cellHeight),
                style = Stroke(width = strokeWidth),
            )
        }
    }
}

/** The mechanical, gear-toothed ring itself — a base stroked circle plus radial "teeth" ticks that
 *  spin with [rotationDegrees], reading as a physical focus-ring being turned. */
private fun DrawScope.drawGearRing(center: Offset, outerRadiusPx: Float, thicknessPx: Float, rotationDegrees: Float) {
    val midRadius = outerRadiusPx - thicknessPx / 2f
    drawCircle(
        color = RingBodyColor,
        radius = midRadius,
        center = center,
        style = Stroke(width = thicknessPx),
    )

    val innerRadius = outerRadiusPx - thicknessPx
    val toothOuterRadius = outerRadiusPx + 2.dp.toPx()
    val toothStroke = 2.dp.toPx()
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
    val markLength = 6.dp.toPx()
    drawLine(
        color = CameraChrome.Accent,
        start = Offset(center.x, center.y - outerRadiusPx - markLength),
        end = Offset(center.x, center.y - outerRadiusPx + 2.dp.toPx()),
        strokeWidth = 2.dp.toPx(),
    )

    drawCircle(color = Color.White.copy(alpha = 0.12f), radius = outerRadiusPx, center = center, style = Stroke(width = 1.dp.toPx()))
}

private val RingDiameter = 156.dp
private val RingThickness = 20.dp
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

private fun previewPeakingMask(): FocusPeakingMask {
    val columns = 8
    val rows = 8
    val edges = List(columns * rows) { i -> (i % columns) == (i / columns) || (i % columns) == columns - 1 - (i / columns) }
    return FocusPeakingMask(columns, rows, edges)
}

@Preview(showBackground = true, widthDp = 260, heightDp = 260, backgroundColor = 0xFF0D1210)
@Composable
private fun FocusRingWithPeakingPreview() {
    XCameraTheme {
        FocusRing(
            center = Offset(400f, 400f),
            rotationDegrees = 35f,
            loupeImage = previewLoupeBitmap(),
            peakingMask = previewPeakingMask(),
            modifier = Modifier.fillMaxSize().background(Color(0xFF0D1210)),
        )
    }
}

@Preview(showBackground = true, widthDp = 260, heightDp = 260, backgroundColor = 0xFF0D1210)
@Composable
private fun FocusRingLoadingPreview() {
    XCameraTheme {
        FocusRing(
            center = Offset(400f, 400f),
            rotationDegrees = 0f,
            loupeImage = null,
            peakingMask = null,
            modifier = Modifier.fillMaxSize().background(Color(0xFF0D1210)),
        )
    }
}

@Preview(showBackground = true, widthDp = 260, heightDp = 260, backgroundColor = 0xFF0D1210)
@Composable
private fun FocusRingHiddenPreview() {
    XCameraTheme {
        FocusRing(
            center = null,
            rotationDegrees = 0f,
            loupeImage = null,
            peakingMask = null,
            modifier = Modifier.fillMaxSize().background(Color(0xFF0D1210)),
        )
    }
}
