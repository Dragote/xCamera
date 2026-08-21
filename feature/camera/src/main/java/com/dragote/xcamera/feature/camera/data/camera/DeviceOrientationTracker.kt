package com.dragote.xcamera.feature.camera.data.camera

import android.content.Context
import android.view.OrientationEventListener
import android.view.Surface

/**
 * The activity is locked to portrait (see AndroidManifest) so the skeuomorphic UI never rotates,
 * which means there's no configuration-change signal to derive a target rotation from. This
 * listener tracks the phone's *physical* orientation via the accelerometer instead, bucketed into
 * the same four [Surface.ROTATION_0]-style buckets a `targetRotation` API would use, so a photo
 * taken while the phone is held in landscape is still saved landscape (see
 * [StillCaptureController.jpegOrientation]) rather than being locked to portrait output.
 *
 * Not to be confused with `ui/component/DeviceOrientationQuadrant.kt`'s
 * `rememberDeviceOrientationQuadrant`/`rememberDeviceOrientationState` — that's a separate,
 * Compose-side [OrientationEventListener] registration for overlay corner-snapping (`HistogramOverlay`,
 * `ViewfinderThumbnailChip`, `HorizonLineOverlay`), unrelated to this class's job of computing
 * `JPEG_ORIENTATION` for saved captures. Same underlying Android API, genuinely different consumers and
 * value shapes (a raw [Surface]-rotation bucket here vs. a hysteresis-debounced display quadrant
 * there) — not a duplication to collapse.
 */
class DeviceOrientationTracker(private val context: Context) {

    private var orientationEventListener: OrientationEventListener? = null

    var targetRotation: Int = Surface.ROTATION_0
        private set

    fun ensureListening() {
        if (orientationEventListener != null) return
        orientationEventListener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                targetRotation = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
            }
        }.apply { enable() }
    }

    /** Call when the composable hosting [CameraController] leaves composition. */
    fun stopListening() {
        orientationEventListener?.disable()
        orientationEventListener = null
    }
}
