package com.dragote.xcamera.shared.designsystem.haptics

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.dragote.xcamera.shared.designsystem.theme.MinimalChrome

/**
 * The single switch every haptic in the app passes through — [hapticTick] (a direct `Vibrator`
 * effect) and [hapticPress] (Compose's own [HapticFeedback]) both consult it, so no individual
 * control has to know the preference exists.
 *
 * A plain object-level `mutableStateOf` rather than a `CompositionLocal`, mirroring
 * [MinimalChrome.current]: this module depends on neither `shared:common` nor Hilt, and both gate
 * points are reached from gesture/sensor callbacks that aren't `@Composable`. Snapshot state rather
 * than a plain `var` because whichever screen observes the persisted setting writes it from a
 * composable body, once per recomposition.
 *
 * It's a global app preference, so a screen that observes no settings of its own inherits whatever
 * was last written instead of needing to set it — `feature:camera`'s screen writes it before any
 * other screen can be reached.
 */
object Haptics {

    var enabled: Boolean by mutableStateOf(true)
}

/**
 * A firmer press than [hapticTick]'s detent — button, lever and pill taps, where the gesture is a
 * discrete commit rather than one notch of continuous travel. Silent while [Haptics.enabled] is
 * false.
 */
fun HapticFeedback.hapticPress() {
    if (!Haptics.enabled) return
    performHapticFeedback(HapticFeedbackType.LongPress)
}
