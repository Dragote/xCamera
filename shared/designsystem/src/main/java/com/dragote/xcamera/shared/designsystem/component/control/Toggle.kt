package com.dragote.xcamera.shared.designsystem.component.control

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dragote.xcamera.shared.designsystem.theme.MinimalChrome
import com.dragote.xcamera.shared.designsystem.theme.XCameraTheme

/**
 * Universal two-position toggle for the [MinimalChrome] identity — the shared base every
 * lever-shaped control in this identity (`feature:camera`'s `FlashToggle`/`ModeToggle`) is built on.
 * A thin black-stroke capsule track holds a plain circular knob that's *outlined* when unchecked and
 * *solid-filled* when checked — on/off is carried by knob position **and** this fill/outline switch,
 * not by color alone (this app's own accessibility baseline), even though this identity has no
 * separate "on" hue to switch to in the first place.
 *
 * [TrackWidth] is deliberately just a bit more than two knob diameters — enough for the knob to travel
 * cleanly from one end to the other with a hairline of track showing past it on either side, not a wide
 * lever throw.
 *
 * [knobContent] rides inside the knob and travels with it, given [checked] so it can invert its own
 * color the same way the knob's fill does. The single-[ImageVector] overload below covers the common
 * case (an icon that doesn't change, only moves); `feature:camera`'s `ModeToggle` is the example of
 * supplying custom [knobContent] directly instead, for a state where the two positions need genuinely
 * different content (its A/M letter swap), not just a repositioned icon.
 */
@Composable
fun Toggle(
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    knobContent: @Composable (checked: Boolean) -> Unit = {},
) {
    val haptic = LocalHapticFeedback.current
    val t by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(durationMillis = 320, easing = MinimalChrome.KnobOvershootEasing),
        label = "toggleKnobTravel",
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.size(TrackWidth, TrackHeight)) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Switch,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onToggle()
                        },
                    ),
            ) {
                drawTrackOutline()
                translate(left = KnobTravel.toPx() * t) { drawKnob(filled = checked) }
            }
            Box(
                modifier = Modifier
                    .offset(x = KnobInset + KnobTravel * t, y = KnobInset)
                    .size(KnobDiameter),
                contentAlignment = Alignment.Center,
            ) {
                knobContent(checked)
            }
        }
    }
}

/**
 * Convenience overload for the common case — a single [icon] that rides inside the knob unchanged,
 * only moving side to side with it. Tinted [MinimalChrome.Background] when checked/filled or
 * [MinimalChrome.Ink] when unchecked/outlined, so it stays legible against whichever fill the knob is
 * currently drawn with, mirroring the base [Toggle]'s own [knobContent] contract.
 */
@Composable
fun Toggle(
    checked: Boolean,
    onToggle: () -> Unit,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    iconContentDescription: String? = null,
) = Toggle(checked = checked, onToggle = onToggle, modifier = modifier) { isChecked ->
    Icon(
        imageVector = icon,
        contentDescription = iconContentDescription,
        tint = if (isChecked) MinimalChrome.Background else MinimalChrome.Ink,
        modifier = Modifier.size(IconSize),
    )
}

// KnobDiameter is sized so TrackHeight (the knob plus its inset on both sides) lands on exactly
// SettingsButton's own 48dp circle — matching the *track's* height to that button, not the knob
// itself, is what actually reads as "the same size" sitting next to it in the toolbar row.
internal val KnobDiameter = 40.dp
internal val KnobInset = 4.dp
internal val TrackHeight = KnobDiameter + KnobInset * 2
internal val TrackWidth = KnobDiameter * 2 + KnobInset * 2
internal val KnobTravel = TrackWidth - KnobDiameter - KnobInset * 2

private val IconSize = 20.dp

/** Capsule track outline — corner radius is half the canvas's own shorter side, so this reads correctly
 *  regardless of the canvas's aspect ratio (a wide [Toggle] track or [SteppedToggle]'s longer, still
 *  fixed-thickness track alike) rather than assuming a fixed [TrackHeight]. `internal` so
 *  [SteppedToggle] — same track stroke language, generalized to N detents — reuses this exact drawing
 *  instead of re-deriving it. */
internal fun DrawScope.drawTrackOutline() {
    val corner = CornerRadius(size.minDimension / 2)
    val path = Path().apply { addRoundRect(RoundRect(Rect(Offset.Zero, size), corner)) }
    drawPath(path, color = MinimalChrome.Ink, style = Stroke(width = MinimalChrome.StrokeWidth.toPx()))
}

/** `internal` so [SteppedToggle] can reuse the exact same knob circle (same [KnobDiameter]/[KnobInset],
 *  same fill logic) via [androidx.compose.ui.graphics.drawscope.translate] to its own live position,
 *  instead of re-deriving the geometry. */
internal fun DrawScope.drawKnob(filled: Boolean) {
    val radius = KnobDiameter.toPx() / 2f
    val center = Offset(KnobInset.toPx() + radius, KnobInset.toPx() + radius)
    if (filled) {
        drawCircle(color = MinimalChrome.Ink, radius = radius, center = center)
    } else {
        drawCircle(color = MinimalChrome.Background, radius = radius, center = center)
        drawCircle(
            color = MinimalChrome.Ink,
            radius = radius,
            center = center,
            style = Stroke(width = MinimalChrome.StrokeWidth.toPx()),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFAF6EC)
@Composable
private fun TogglePreview() {
    XCameraTheme {
        Row(modifier = Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Toggle(checked = false, onToggle = {}, icon = Icons.Default.FlashOn)
            Toggle(checked = true, onToggle = {}, icon = Icons.Default.FlashOn)
        }
    }
}