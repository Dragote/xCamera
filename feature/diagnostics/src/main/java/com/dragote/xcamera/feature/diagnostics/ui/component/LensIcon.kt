package com.dragote.xcamera.feature.diagnostics.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dragote.xcamera.shared.designsystem.theme.MinimalChrome
import com.dragote.xcamera.shared.designsystem.theme.XCameraTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A large flat-line-art lens glyph — two concentric barrel rings, a ring of focus/aperture-style
 * click-stop ticks, and a hexagonal iris opening, all drawn as unfilled [MinimalChrome.Ink] hairlines
 * per this design language's own no-gradient/no-shadow rule. [zoomLabel] (e.g. "3×") renders centered
 * on top like an engraving on the barrel — the lens's full category name is a separate caption outside
 * this composable (see `LensDiagnosticsPage`), so this stays a plain glyph-plus-number unit reusable
 * wherever a lens needs identifying at a glance.
 */
@Composable
fun LensIcon(zoomLabel: String, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawLensGlyph()
        }
        val labelFontSize = rememberLensIconLabelFontSize(zoomLabel = zoomLabel, iconSize = maxWidth)
        Text(text = zoomLabel, style = MinimalChrome.valueStyle().copy(fontSize = labelFontSize))
    }
}

/** The hexagonal iris [drawLensGlyph] draws is inscribed in a circle of radius `0.25 * iconSize` (see
 *  that function's `irisRadius`), which works out to a `0.433 * iconSize` wide, `0.5 * iconSize` tall
 *  bounding box. [IrisLabelWidthFraction]/[IrisLabelMaxHeightFraction] stay under those with margin so
 *  the label reliably sits inside the drawn hexagon instead of overflowing it, at any icon size or label
 *  length this screen can produce. */
private const val IrisLabelWidthFraction = 0.36f
private const val IrisLabelMaxHeightFraction = 0.34f

/**
 * Font size for [LensIcon]'s zoom-ratio label, derived from the icon's own measured [iconSize] and
 * [zoomLabel]'s actual rendered width — not a second fixed guess. A fixed size (the previous
 * implementation used a flat 30sp) overflows the hexagonal iris once the icon shrinks below whatever size
 * that guess assumed, or once the label itself is longer than a short one or two digits (e.g. "0.5×"
 * vs. "3×"). This measures [zoomLabel] once at a reference size, then scales that size down until the
 * measured width fits [IrisLabelWidthFraction] of [iconSize], capped by [IrisLabelMaxHeightFraction] so a
 * short label on a large icon doesn't grow past a comfortable vertical fit either.
 */
@Composable
private fun rememberLensIconLabelFontSize(zoomLabel: String, iconSize: Dp): TextUnit {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    return remember(zoomLabel, iconSize, density) {
        with(density) {
            val iconSizePx = iconSize.toPx()
            if (iconSizePx <= 0f) {
                30.sp
            } else {
                val referenceFontSize = 100.sp
                val measuredWidthPx = textMeasurer.measure(
                    text = zoomLabel,
                    style = TextStyle(fontFamily = MinimalChrome.Mono, fontWeight = FontWeight.Bold, fontSize = referenceFontSize),
                ).size.width.toFloat()
                val targetWidthPx = iconSizePx * IrisLabelWidthFraction
                val widthFitFontSizePx = referenceFontSize.toPx() * (targetWidthPx / measuredWidthPx)
                val maxFontSizePx = iconSizePx * IrisLabelMaxHeightFraction
                min(widthFitFontSizePx, maxFontSizePx).toSp()
            }
        }
    }
}

/** Two barrel rings, a ring of radial click-stop ticks (mirrors a lens barrel's own focus/aperture ring
 *  markings), and a hexagonal iris opening at center — all unfilled hairline strokes, no fill/gradient,
 *  per [MinimalChrome]'s flat line-art rule. */
private fun DrawScope.drawLensGlyph() {
    val radius = size.minDimension / 2f
    val center = Offset(size.width / 2f, size.height / 2f)
    val stroke = Stroke(width = MinimalChrome.StrokeWidth.toPx())

    drawCircle(color = MinimalChrome.Ink, radius = radius * 0.98f, center = center, style = stroke)
    drawCircle(color = MinimalChrome.Ink, radius = radius * 0.78f, center = center, style = stroke)

    val tickCount = 16
    repeat(tickCount) { i ->
        val angle = (2 * PI * i / tickCount).toFloat()
        val outer = Offset(center.x + cos(angle) * radius * 0.98f, center.y + sin(angle) * radius * 0.98f)
        val inner = Offset(center.x + cos(angle) * radius * 0.90f, center.y + sin(angle) * radius * 0.90f)
        drawLine(color = MinimalChrome.Ink, start = inner, end = outer, strokeWidth = MinimalChrome.StrokeWidth.toPx())
    }

    val bladeCount = 6
    val irisRadius = radius * 0.5f
    val irisPath = Path().apply {
        repeat(bladeCount) { i ->
            val angle = (2 * PI * i / bladeCount - PI / 2).toFloat()
            val point = Offset(center.x + cos(angle) * irisRadius, center.y + sin(angle) * irisRadius)
            if (i == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
        }
        close()
    }
    drawPath(irisPath, color = MinimalChrome.Ink, style = stroke)
}

@Preview(showBackground = true, backgroundColor = 0xFFFAF6EC)
@Composable
private fun LensIconPreview() {
    XCameraTheme {
        Row(modifier = Modifier.padding(24.dp)) {
            LensIcon(zoomLabel = "0.5×", modifier = Modifier.size(120.dp))
            LensIcon(zoomLabel = "1×", modifier = Modifier.size(120.dp))
            LensIcon(zoomLabel = "3×", modifier = Modifier.size(120.dp))
        }
    }
}

/** A small icon size (roughly what `LensDiagnosticsPage`'s `fillMaxWidth(0.5f)` icon shrinks to on a
 *  ~360dp-wide narrow phone screen) alongside every zoom-label shape this screen actually produces —
 *  short ("1×"), one-decimal ("0.5×"), and multi-digit ("10×") — demonstrating the label staying inside
 *  the drawn hexagon at small sizes and longer strings alike, instead of a fixed font size overflowing it. */
@Preview(showBackground = true, backgroundColor = 0xFFFAF6EC)
@Composable
private fun LensIconSmallAndLongLabelPreview() {
    XCameraTheme {
        Row(modifier = Modifier.padding(24.dp)) {
            LensIcon(zoomLabel = "0.5×", modifier = Modifier.size(56.dp))
            LensIcon(zoomLabel = "1×", modifier = Modifier.size(56.dp))
            LensIcon(zoomLabel = "10×", modifier = Modifier.size(56.dp))
        }
    }
}
