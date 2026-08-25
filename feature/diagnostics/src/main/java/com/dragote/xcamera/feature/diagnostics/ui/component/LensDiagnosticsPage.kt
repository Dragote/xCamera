package com.dragote.xcamera.feature.diagnostics.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dragote.xcamera.shared.designsystem.theme.MinimalChrome
import com.dragote.xcamera.shared.designsystem.theme.XCameraTheme
import com.dragote.xcamera.shared.diagnostics.domain.model.FeatureSupport
import com.dragote.xcamera.shared.diagnostics.domain.model.LensDiagnostics
import com.dragote.xcamera.shared.diagnostics.domain.model.LensSnapshot
import java.util.Locale
import kotlin.math.roundToInt

/**
 * One physical back lens's full diagnostics report, sized to fill a single [androidx.compose.foundation.pager.HorizontalPager]
 * page — `ui/DiagnosticsScreen.kt` swipes through one of these per lens rather than stacking every
 * lens's card in one scrolling list. Information hierarchy, top to bottom: a large engraved [LensIcon]
 * plus category caption identifies *which* lens this is; aperture/resolution render as the hero numbers
 * (the two specs that most define what a lens is actually good for); sensor size/focal length render
 * quieter as secondary characteristics; the three RAW/manual-ISO/manual-focus capabilities get their own
 * clearly separated pill section below a hairline divider. Minimum focus distance is shown exactly once,
 * as [FeatureSupport.Supported.detail] next to the MANUAL FOCUS pill — not repeated as a characteristic,
 * since "how close can this lens focus" and "does it support manual focus at all" are the same fact.
 */
@Composable
fun LensDiagnosticsPage(lens: LensDiagnostics, modifier: Modifier = Modifier) {
    val snapshot: LensSnapshot = lens.snapshot
    // "0.5× ULTRA-WIDE" -> "0.5×" / "ULTRA-WIDE" — displayLabel is always "<ratio>× <CATEGORY>" with
    // CATEGORY itself a single hyphenated token (see LensCandidateMapper.toDisplayLabel), so splitting
    // on the first space reliably separates the two without re-deriving either from snapshot.zoomRatio.
    val zoomLabel = lens.displayLabel.substringBefore(' ')
    val category = lens.displayLabel.substringAfter(' ', missingDelimiterValue = "")
    val megapixels = snapshot.pixelArrayWidth.toLong() * snapshot.pixelArrayHeight / 1_000_000.0
    val apertureLabel = snapshot.apertureFNumber?.let { "ƒ/${formatOneDecimal(it)}" } ?: "—"
    val resolutionLabel = "${formatOneDecimal(megapixels.toFloat())}MP"

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp)
            .border(width = MinimalChrome.StrokeWidth, color = MinimalChrome.Ink, shape = RoundedCornerShape(16.dp))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LensIcon(zoomLabel = zoomLabel, modifier = Modifier.fillMaxWidth(0.5f))
        Text(text = category, style = MinimalChrome.labelStyle().copy(fontSize = 14.sp))

        Spacer(modifier = Modifier.height(32.dp))

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val heroFontSize = rememberHeroStatFontSize(
                values = listOf(apertureLabel, resolutionLabel),
                availableWidth = maxWidth,
                minGap = HeroStatMinGap,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(HeroStatMinGap, Alignment.CenterHorizontally),
            ) {
                HeroStat(label = "APERTURE", value = apertureLabel, fontSize = heroFontSize)
                HeroStat(label = "RESOLUTION", value = resolutionLabel, fontSize = heroFontSize)
            }
        }

        // Slightly more than the 20dp used elsewhere in this Column's own spacedBy(6.dp)-plus-explicit-Spacer
        // rhythm — the hero row's height varies with rememberHeroStatFontSize's shrink-to-fit result, so this
        // needs to stay comfortable rather than exactly matching a neighbor that's always a fixed size.
        Spacer(modifier = Modifier.height(24.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            SecondaryStat(
                label = "SENSOR",
                value = "${formatOneDecimal(snapshot.sensorWidthMm)} × ${formatOneDecimal(snapshot.sensorHeightMm)} mm",
            )
            SecondaryStat(
                label = "FOCAL LENGTH",
                value = "${formatOneDecimal(snapshot.focalLengthMm)} mm",
                // Raw physical focal length is genuinely a few mm on a phone (tiny sensor vs.
                // full-frame/APS-C — see LensSnapshot's own doc) and isn't what a user familiar with
                // "real" cameras recognizes; the 35mm-equivalent is the number that actually maps to a
                // familiar field of view, so it rides along as a quieter caption rather than replacing
                // the honest raw reading above it.
                caption = "≈${snapshot.equivalentFocalLengthMm.roundToInt()}mm equiv.",
            )
        }

        Spacer(modifier = Modifier.height(28.dp))
        HairlineDivider()
        Spacer(modifier = Modifier.height(20.dp))

        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FeatureSupportRow(label = "RAW/DNG", support = lens.rawCapture)
            FeatureSupportRow(label = "MANUAL ISO/SHUTTER", support = lens.manualIsoAndShutter)
            FeatureSupportRow(label = "MANUAL FOCUS", support = lens.manualFocus)
        }
    }
}

/** The one or two numbers that most define what makes *this* lens distinct — larger, bolder type than
 *  [SecondaryStat] to read as the hero fact on a page otherwise full of smaller specs. [fontSize] is
 *  shared across both [HeroStat]s in a row (see [rememberHeroStatFontSize]) so APERTURE and RESOLUTION
 *  always render at the same scale as each other, never independently sized. */
@Composable
private fun HeroStat(label: String, value: String, fontSize: TextUnit, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = value, style = MinimalChrome.valueStyle().copy(fontSize = fontSize))
        Text(text = label, style = MinimalChrome.labelStyle())
    }
}

/** Guaranteed horizontal space between the two [HeroStat]s regardless of content width — paired with
 *  `Arrangement.spacedBy(HeroStatMinGap, Alignment.CenterHorizontally)` at the call site, which fixes
 *  this as an exact gap (not leftover space, unlike the `SpaceEvenly` this replaced) so the two stats
 *  can never end up flush against each other on a narrow screen. */
private val HeroStatMinGap = 20.dp

/** Upper/lower bounds for [rememberHeroStatFontSize]'s result — [HeroStatMaxFontSize] is this page's
 *  original fixed size (kept as the ceiling for normal/wide screens), [HeroStatMinFontSize] stays well
 *  above [MinimalChrome.valueStyle]'s own 13sp so a hero stat never shrinks down to [SecondaryStat]'s
 *  scale and loses the hero-vs-secondary hierarchy this page is built around. */
private val HeroStatMaxFontSize = 32.sp
private val HeroStatMinFontSize = 20.sp

/**
 * Font size shared by both [HeroStat]s in the hero row, derived from [availableWidth] (the row's own
 * measured width) and [values]' actual rendered widths — not a fixed guess. A fixed 32sp let the two
 * stats' own intrinsic content width consume nearly the full row on a narrow screen, leaving little or
 * no room for [minGap] despite the row's arrangement asking for even spacing (see this file's own
 * `HeroStatMinGap` doc). This measures each value once at a reference size, then scales that size down
 * until the two values' combined width plus [minGap] fits [availableWidth], capped to
 * [HeroStatMaxFontSize] so normal/wide screens keep the original hero scale and floored to
 * [HeroStatMinFontSize] so it never collapses to [SecondaryStat]'s scale. Same measure-then-scale
 * pattern as `LensIcon.kt`'s `rememberLensIconLabelFontSize`.
 */
@Composable
private fun rememberHeroStatFontSize(values: List<String>, availableWidth: Dp, minGap: Dp): TextUnit {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    return remember(values, availableWidth, minGap, density) {
        with(density) {
            val availableWidthPx = availableWidth.toPx()
            val referenceFontSize = 100.sp
            val referenceWidthsPx = values.map { value ->
                textMeasurer.measure(
                    text = value,
                    style = MinimalChrome.valueStyle().copy(fontSize = referenceFontSize),
                ).size.width.toFloat()
            }
            val totalReferenceWidthPx = referenceWidthsPx.sum()
            if (availableWidthPx <= 0f || totalReferenceWidthPx <= 0f) {
                HeroStatMaxFontSize
            } else {
                val targetTotalWidthPx = (availableWidthPx - minGap.toPx()).coerceAtLeast(0f)
                val widthFitFontSizePx = referenceFontSize.toPx() * (targetTotalWidthPx / totalReferenceWidthPx)
                widthFitFontSizePx.coerceIn(HeroStatMinFontSize.toPx(), HeroStatMaxFontSize.toPx()).toSp()
            }
        }
    }
}

/** Sensor/focal-length characteristics — real, but not what someone reaches for a lens for, so rendered
 *  at [MinimalChrome]'s own scale with a dimmed ink rather than [HeroStat]'s enlarged one, to read as
 *  quieter without introducing a second color into this single-ink design language. [caption], when
 *  given (currently only FOCAL LENGTH's 35mm-equivalent conversion), renders even quieter still below
 *  [label] — a second-order annotation on the stat, not a peer to [value]/[label] themselves. */
@Composable
private fun SecondaryStat(label: String, value: String, caption: String? = null, modifier: Modifier = Modifier) {
    val quietInk = MinimalChrome.Ink.copy(alpha = 0.55f)
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = value, style = MinimalChrome.valueStyle(quietInk))
        Text(text = label, style = MinimalChrome.labelStyle(quietInk))
        if (caption != null) {
            Text(text = caption, style = MinimalChrome.labelStyle(quietInk.copy(alpha = quietInk.alpha * 0.7f)))
        }
    }
}

/** A single dimmed-ink hairline, not [MinimalChrome.StrokeWidth]'s full-opacity ink used everywhere
 *  else — a full-strength line here would compete with the card's own border for attention; this is
 *  purely a section separator. */
@Composable
private fun HairlineDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(MinimalChrome.StrokeWidth)
            .background(MinimalChrome.Ink.copy(alpha = 0.25f)),
    )
}

/** The pill only ever carries the binary SUPPORTED/UNSUPPORTED status now — [FeatureSupport.Supported.detail],
 *  when present, renders as its own quieter caption line below the label+pill row, free to wrap across
 *  the row's full width without the pill's own size depending on the detail string's length (that
 *  coupling used to make long detail strings — e.g. "ISO 50–3200" — wrap the pill onto two lines and
 *  stretch the row). */
@Composable
private fun FeatureSupportRow(label: String, support: FeatureSupport, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, style = MinimalChrome.labelStyle())
            FeatureSupportPill(isSupported = support is FeatureSupport.Supported)
        }
        if (support is FeatureSupport.Supported) {
            val quietInk = MinimalChrome.Ink.copy(alpha = 0.55f)
            Text(text = support.detail.uppercase(), style = MinimalChrome.labelStyle(quietInk))
        }
    }
}

/** Same filled-ink-background(selected)-vs-outlined-ink-border(unselected) language `SettingsScreen`'s
 *  `LutPill`/`PeakingSensitivitySelector` establish, applied to a SUPPORTED/UNSUPPORTED binary instead
 *  of a multi-way selection. Carries only the fixed status word — never the variable-length detail
 *  string — so its size stays constant regardless of what [FeatureSupport.Supported.detail] says. */
@Composable
private fun FeatureSupportPill(isSupported: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSupported) MinimalChrome.Ink else Color.Transparent)
            .border(
                width = MinimalChrome.StrokeWidth,
                color = if (isSupported) Color.Transparent else MinimalChrome.Ink,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        val textColor = if (isSupported) MinimalChrome.Background else MinimalChrome.Ink
        Text(text = if (isSupported) "SUPPORTED" else "UNSUPPORTED", style = MinimalChrome.valueStyle(textColor))
    }
}

private fun formatOneDecimal(value: Float): String = String.format(Locale.US, "%.1f", value)

@Preview(showBackground = true, backgroundColor = 0xFFFAF6EC, widthDp = 360, heightDp = 780)
@Composable
private fun LensDiagnosticsPagePreview() {
    XCameraTheme {
        LensDiagnosticsPage(lens = FullyCapableMainLens)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFAF6EC, widthDp = 360, heightDp = 780)
@Composable
private fun LensDiagnosticsPageStrippedDownPreview() {
    XCameraTheme {
        LensDiagnosticsPage(lens = StrippedDownUltraWideLens)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFAF6EC, widthDp = 360, heightDp = 780)
@Composable
private fun LensDiagnosticsPageTelephotoPreview() {
    XCameraTheme {
        LensDiagnosticsPage(lens = TelephotoLens)
    }
}

/** Narrowest realistic target (360dp) plus a deliberately long [FeatureSupport.Supported.detail] on
 *  every row — demonstrates the pill staying a fixed-size SUPPORTED/UNSUPPORTED badge while the detail
 *  wraps freely as its own caption line underneath, instead of stretching/breaking the pill itself. */
@Preview(showBackground = true, backgroundColor = 0xFFFAF6EC, widthDp = 360, heightDp = 780)
@Composable
private fun LensDiagnosticsPageLongDetailNarrowPreview() {
    XCameraTheme {
        LensDiagnosticsPage(lens = VerboseDetailLens)
    }
}

/** Narrower than every other preview in this file (320dp, below the 360dp "narrowest realistic target"
 *  other previews use) paired with [HighResolutionLens]'s wide "108.0MP" value — the two widest hero
 *  values this page can plausibly show, at the tightest width. Demonstrates
 *  [rememberHeroStatFontSize]'s shrink-to-fit keeping APERTURE/RESOLUTION legible and clearly separated
 *  by [HeroStatMinGap] rather than touching, and confirms "108.0MP" (no space before "MP"). */
@Preview(showBackground = true, backgroundColor = 0xFFFAF6EC, widthDp = 320, heightDp = 780)
@Composable
private fun LensDiagnosticsPageNarrowHeroStatPreview() {
    XCameraTheme {
        LensDiagnosticsPage(lens = HighResolutionLens)
    }
}

/** Sample fixture data shared across this file's previews and `ui/DiagnosticsScreen.kt`'s own
 *  `DiagnosticsContentSinglePagePreview` — one fully-capable lens, one stripped-down lens with every
 *  capability unsupported, and one lens with a mix of both, matching the three actual back-lens shapes
 *  this screen sees on a typical multi-camera phone. */
internal val FullyCapableMainLens = LensDiagnostics(
    displayLabel = "1× MAIN",
    snapshot = LensSnapshot(
        logicalCameraId = "0",
        physicalCameraId = null,
        zoomRatio = 1f,
        focalLengthMm = 6.86f,
        equivalentFocalLengthMm = 24.3f,
        sensorWidthMm = 9.8f,
        sensorHeightMm = 7.3f,
        pixelArrayWidth = 4032,
        pixelArrayHeight = 3024,
        apertureFNumber = 1.8f,
    ),
    rawCapture = FeatureSupport.Supported("12 MP RAW"),
    manualIsoAndShutter = FeatureSupport.Supported("ISO 50–3200"),
    manualFocus = FeatureSupport.Supported("down to 10 cm"),
)

internal val StrippedDownUltraWideLens = LensDiagnostics(
    displayLabel = "0.5× ULTRA-WIDE",
    snapshot = LensSnapshot(
        logicalCameraId = "0",
        physicalCameraId = "2",
        zoomRatio = 0.5f,
        focalLengthMm = 2.2f,
        equivalentFocalLengthMm = 13.3f,
        sensorWidthMm = 5.7f,
        sensorHeightMm = 4.3f,
        pixelArrayWidth = 4000,
        pixelArrayHeight = 3000,
        apertureFNumber = 2.2f,
    ),
    rawCapture = FeatureSupport.Unsupported,
    manualIsoAndShutter = FeatureSupport.Unsupported,
    manualFocus = FeatureSupport.Unsupported,
)

internal val TelephotoLens = LensDiagnostics(
    displayLabel = "3× TELEPHOTO",
    snapshot = LensSnapshot(
        logicalCameraId = "0",
        physicalCameraId = "3",
        zoomRatio = 3f,
        focalLengthMm = 9.0f,
        equivalentFocalLengthMm = 77.9f,
        sensorWidthMm = 4.0f,
        sensorHeightMm = 3.0f,
        pixelArrayWidth = 3000,
        pixelArrayHeight = 2250,
        apertureFNumber = null,
    ),
    rawCapture = FeatureSupport.Unsupported,
    manualIsoAndShutter = FeatureSupport.Supported("ISO 64–1600"),
    manualFocus = FeatureSupport.Unsupported,
)

/** Same shape as [TelephotoLens] but with deliberately long [FeatureSupport.Supported.detail] strings on
 *  every row — none of this file's other fixtures has a detail long enough to demonstrate Bug 1/3's pill
 *  wrapping fix (`LensDiagnosticsPageLongDetailNarrowPreview`), so this one exists purely for that. */
internal val VerboseDetailLens = LensDiagnostics(
    displayLabel = "1× MAIN",
    snapshot = TelephotoLens.snapshot,
    rawCapture = FeatureSupport.Supported("48 MP RAW with extended dynamic range compositing"),
    manualIsoAndShutter = FeatureSupport.Supported("ISO 50–3200, extended low-light sensitivity boost mode"),
    manualFocus = FeatureSupport.Supported("down to 2 cm in dedicated macro focus mode"),
)

/** A deliberately unrealistic 108MP pixel array — none of this file's other fixtures produces a
 *  RESOLUTION value wide enough to stress-test [rememberHeroStatFontSize]'s shrink-to-fit against
 *  [LensDiagnosticsPageNarrowHeroStatPreview]'s 320dp width, so this one exists purely for that. */
internal val HighResolutionLens = LensDiagnostics(
    displayLabel = "1× MAIN",
    snapshot = LensSnapshot(
        logicalCameraId = "0",
        physicalCameraId = null,
        zoomRatio = 1f,
        focalLengthMm = 6.86f,
        equivalentFocalLengthMm = 24.3f,
        sensorWidthMm = 9.8f,
        sensorHeightMm = 7.3f,
        pixelArrayWidth = 12000,
        pixelArrayHeight = 9000,
        apertureFNumber = 1.8f,
    ),
    rawCapture = FeatureSupport.Supported("108 MP RAW"),
    manualIsoAndShutter = FeatureSupport.Supported("ISO 50–3200"),
    manualFocus = FeatureSupport.Supported("down to 10 cm"),
)
