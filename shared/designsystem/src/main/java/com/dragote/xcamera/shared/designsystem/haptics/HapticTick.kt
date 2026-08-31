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
 * Bypasses [android.view.View.performHapticFeedback] and goes straight to [Vibrator], since a plain
 * `vibrator.vibrate(effect)` defaults to [VibrationAttributes.USAGE_TOUCH], which the platform
 * vibrator service gates on the same "Touch feedback" system toggle `performHapticFeedback` respects.
 * [VibrationAttributes.USAGE_HARDWARE_FEEDBACK] (API 33+) is a different category — "feedback for a
 * hardware component, such as a physical button" — that isn't gated by that toggle, so tagging the
 * tick with it gets it through regardless of that setting. Below API 33 there's no equivalent
 * override; the tick falls back to whatever the system setting allows.
 *
 * Overriding the system toggle that way is only defensible because [Haptics.enabled] gives the user
 * an in-app switch instead — see its own doc.
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

/** The detent tick — one notch of a dial, lever or stepped toggle. Silent while [Haptics.enabled]
 *  is false. */
fun Vibrator.hapticTick() {
    if (!Haptics.enabled) return
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
