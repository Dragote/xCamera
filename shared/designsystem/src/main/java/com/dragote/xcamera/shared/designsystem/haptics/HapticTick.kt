package com.dragote.xcamera.shared.designsystem.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Bypasses [android.view.View.performHapticFeedback] and goes straight to [Vibrator] — but a plain
 * `vibrator.vibrate(effect)` turned out NOT to be enough on its own: modern Android tags any
 * vibration that doesn't say otherwise as [VibrationAttributes.USAGE_TOUCH] by default, and the
 * platform vibrator service gates USAGE_TOUCH on the exact same "Touch feedback" system toggle that
 * gates `performHapticFeedback` — confirmed by testing with that toggle off, where this still
 * produced nothing. [VibrationAttributes.USAGE_HARDWARE_FEEDBACK] (API 33+) is a different category
 * — "feedback for a hardware component, such as a physical button" — that isn't gated by that
 * toggle, so tagging the tick with it is what actually gets it through regardless of that setting.
 * Below API 33 there's no equivalent override; the tick just falls back to whatever the system
 * setting allows. A future iteration should expose its own in-app haptics on/off (or "match system")
 * preference rather than always silently overriding what the user chose in system settings — this is
 * a deliberate, known gap, not an oversight.
 */
@Composable
fun rememberHapticTickVibrator(): Vibrator {
    val context = LocalContext.current
    return remember(context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }
}

fun Vibrator.hapticTick() {
    if (!hasVibrator()) return
    val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
    } else {
        VibrationEffect.createOneShot(12L, VibrationEffect.DEFAULT_AMPLITUDE)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val attributes = VibrationAttributes.Builder()
            .setUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK)
            .build()
        vibrate(effect, attributes)
    } else {
        vibrate(effect)
    }
}
