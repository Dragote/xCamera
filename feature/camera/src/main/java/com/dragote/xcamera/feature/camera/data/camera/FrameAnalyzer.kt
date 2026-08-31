package com.dragote.xcamera.feature.camera.data.camera

import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.SystemClock
import com.dragote.xcamera.feature.camera.domain.model.HistogramData
import com.dragote.xcamera.feature.camera.domain.model.ZebraMask
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Zebra-stripe clipping + live tonal histogram analysis of the live preview stream, and the hand-off of
 * each preview [Image] onward to a render-thread consumer — split out of [CameraController] since it's
 * fairly self-contained around "given an [Image] delivered by [imageAvailableListener], update these
 * [StateFlow]s and forward the frame to whoever's registered via [setFrameListener]."
 *
 * Every preview frame arrives via [imageAvailableListener] (registered by [CameraController] as the
 * [ImageReader.OnImageAvailableListener] on its own preview [ImageReader]) as a side effect of the
 * repeating request Camera2 is already running — there's no separate capture mechanism to drive:
 * classification just piggybacks on frames that are already flowing.
 */
class FrameAnalyzer {

    /**
     * `CameraCharacteristics.SENSOR_ORIENTATION` for whichever lens is currently bound, kept in sync by
     * [CameraController.openCamera] via [setRotationDegrees] — an [ImageReader] (unlike a `TextureView`'s
     * own on-screen `SurfaceTexture`) gets no automatic producer-side rotation, so [classifyZebraIfDue]
     * rotates by this angle itself (via [ZebraMask.rotatedBy]) to line the mask up with what the fixed-
     * portrait viewfinder actually shows. Also read by [CameraController.triggerAutoFocus] to convert
     * a display-space tap point into the sensor's own coordinate space — see
     * [com.dragote.xcamera.feature.camera.domain.model.displayFractionToSensorFraction].
     */
    var rotationDegrees: Int = 0
        private set

    /** Throttles zebra classification in [imageAvailableListener] — see [ZebraThrottleMs]. */
    private var lastZebraClassifyUptimeMs = 0L

    /** Throttles histogram classification in [imageAvailableListener] — see [HistogramThrottleMs]. */
    private var lastHistogramClassifyUptimeMs = 0L

    /**
     * Set via [setZebraAnalysisEnabled] — true only while the ISO/shutter/EV dial is actively being
     * dragged (see `ui/CameraScreen`'s wiring of `DialWheel.onDragActiveChanged`). Read by
     * [imageAvailableListener] to decide whether it's worth classifying the current frame at all; while
     * false, classification is skipped entirely, so there's zero analysis cost outside an actual drag
     * even though the preview stream itself is always running.
     */
    private var zebraAnalysisEnabled = false

    /** Set via [setFrameListener] — the render-thread [Handler] to post each delivered preview [Image]
     *  onto, paired with [previewFrameListener]. Both `null` until `ui/CameraScreen` registers its
     *  renderer, and cleared again once the caller unregisters. */
    private var previewFrameHandler: Handler? = null

    /** See [previewFrameHandler]'s own doc — receives ownership of each delivered preview [Image]
     *  (must eventually `close()` it) via [previewFrameHandler]. */
    private var previewFrameListener: ((Image) -> Unit)? = null

    /**
     * Guards against exceeding the preview [ImageReader]'s `maxImages` (2): the renderer's EGL/shader
     * setup can take long enough that several frames' worth of `onImageAvailable` callbacks fire
     * before the render thread has processed (and therefore closed) even the first handed-off `Image`,
     * and `acquireLatestImage()` throws `IllegalStateException` rather than silently coping once that
     * many of *our own* acquired-but-unclosed images pile up. Set `true` right after acquiring (before
     * this listener returns), cleared only once the frame has genuinely finished being drawn.
     * `@Volatile` since it's written
     * from both this listener's own thread (Camera2's background handler) and the render thread that
     * eventually closes the frame.
     */
    @Volatile
    private var previewFrameInFlight = false

    /**
     * Grid-coarse clipping mask for the live viewfinder, `null` whenever there's nothing to show
     * (analysis disabled or no frame has landed yet). Its own [StateFlow], not folded into a single
     * UI-state data class, since collapsing a ~15fps stream of updates into one big state object would
     * force everything reading that object to recompose on every emission.
     */
    private val _zebraMask = MutableStateFlow<ZebraMask?>(null)
    val zebraMask: StateFlow<ZebraMask?> = _zebraMask.asStateFlow()

    /**
     * Live tonal histogram for the viewfinder — unlike [_zebraMask] this has no enable/disable gate
     * (see [classifyHistogramIfDue]'s own doc): it's classified on every throttled preview frame for
     * the entire time the preview is running, only ever `null` before the first frame lands or right
     * after [reset] tears the session down. Its own [StateFlow] for the same "high-frequency data
     * doesn't belong in one shared UI-state object" reasoning [_zebraMask] docs.
     */
    private val _histogramData = MutableStateFlow<HistogramData?>(null)
    val histogramData: StateFlow<HistogramData?> = _histogramData.asStateFlow()

    /**
     * Zebra classification (throttled to [ZebraThrottleMs], gated on [zebraAnalysisEnabled]) reads
     * [image]'s luma plane directly, *before* the frame is handed off to [previewFrameListener] — both
     * reads are safe against the same `Image` regardless of order, since [ZebraMask.fromLumaPlane]
     * only ever uses absolute (position-independent) `ByteBuffer.get(index)` calls, never mutating the
     * plane buffer's position.
     *
     * Ownership of the acquired `Image` transfers to [previewFrameListener] via [previewFrameHandler] —
     * if neither is registered (e.g. briefly during a rebind before `ui/CameraScreen`'s renderer
     * re-registers), or if posting onto an already-shutting-down render thread fails, this closes it
     * (and clears [previewFrameInFlight]) here instead so nothing leaks or wedges future frames open
     * forever.
     */
    val imageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        if (previewFrameInFlight) return@OnImageAvailableListener
        // acquireLatestImage() itself can throw if reader.close() (see CameraController.closeImageReaders)
        // runs for a frame that was already in flight through the camera HAL when teardown started —
        // treated the same as "no frame available" rather than propagating.
        val image = try {
            reader.acquireLatestImage() ?: return@OnImageAvailableListener
        } catch (e: IllegalStateException) {
            return@OnImageAvailableListener
        }

        if (zebraAnalysisEnabled) classifyZebraIfDue(image)
        classifyHistogramIfDue(image)

        val listener = previewFrameListener
        val handler = previewFrameHandler
        if (listener != null && handler != null) {
            previewFrameInFlight = true
            val posted = handler.post {
                try {
                    listener(image)
                } finally {
                    previewFrameInFlight = false
                }
            }
            if (!posted) {
                image.close()
                previewFrameInFlight = false
            }
        } else {
            image.close()
        }
    }

    /** Called by [CameraController.openCamera] alongside creating its preview [ImageReader] — see
     *  [rotationDegrees]'s own doc. */
    fun setRotationDegrees(degrees: Int) {
        rotationDegrees = degrees
    }

    /**
     * Registers (or, passing both `null`, unregisters) the render-thread consumer of every delivered
     * preview [Image] — see [previewFrameHandler]/[previewFrameListener]'s own docs. `ui/CameraScreen`
     * calls this (via [CameraController.setPreviewFrameListener]) with its `CameraPreviewRenderer`'s own
     * [Handler]-bound `onPreviewFrame` before (or independently of) `bindCamera` — registration and
     * binding aren't ordered relative to each other, frames simply have nowhere to go (closed
     * immediately, see [imageAvailableListener]) until both are in place.
     */
    fun setFrameListener(handler: Handler?, listener: ((Image) -> Unit)?) {
        previewFrameHandler = handler
        previewFrameListener = listener
    }

    /**
     * See [zebraAnalysisEnabled]'s own doc — [imageAvailableListener] starts/stops classifying frames
     * immediately, no separate kick-off needed since frames are already flowing continuously. Clears
     * [_zebraMask] on the way to disabled so a stale mask from right before the drag ended doesn't
     * linger on screen.
     */
    fun setZebraAnalysisEnabled(enabled: Boolean) {
        if (zebraAnalysisEnabled == enabled) return
        zebraAnalysisEnabled = enabled
        if (!enabled) _zebraMask.value = null
    }

    /** Called by [CameraController.closeCameraAndSessionLocked] — clears both live overlays the same
     *  way the auto-exposure/focus `StateFlow`s reset on session teardown. */
    fun reset() {
        _zebraMask.value = null
        _histogramData.value = null
    }

    /**
     * Skips (not just throttles the *result* of, the *work* of) classification entirely if less than
     * [ZebraThrottleMs] has passed since the last real one — preview frames can arrive at up to ~30fps,
     * far more often than the overlay needs to visibly update. See [rotationDegrees]'s own doc for why
     * an `ImageReader` buffer needs rotation handling at all: a 90/270 [rotationDegrees] swaps width
     * for height, so the *raw* grid requested
     * from [ZebraMask.fromLumaPlane] is swapped accordingly too (matching the buffer's own landscape
     * aspect, avoiding a stretched grid), then [ZebraMask.rotatedBy] rotates the finished small grid
     * (cheap — [ZebraGridColumns]x[ZebraGridRows] cells, not the raw frame) into the shape the portrait
     * `ui/component/ZebraOverlay` canvas actually expects.
     */
    private fun classifyZebraIfDue(image: Image) {
        val now = SystemClock.uptimeMillis()
        if (now - lastZebraClassifyUptimeMs < ZebraThrottleMs) return
        lastZebraClassifyUptimeMs = now

        // Camera2 can invalidate this Image's buffer out from under an in-flight callback (e.g. the
        // camera service tearing down the underlying ImageReader) with no way to check for it upfront
        // — see .claude/docs/features/camera-capture.md's key decisions. Dropping just this one frame on that
        // narrow failure is preferable to propagating, since a fresh frame is already on its way.
        try {
            val plane = image.planes[0]
            val quarterTurn = rotationDegrees == 90 || rotationDegrees == 270
            val rawMask = ZebraMask.fromLumaPlane(
                buffer = plane.buffer,
                rowStride = plane.rowStride,
                pixelStride = plane.pixelStride,
                width = image.width,
                height = image.height,
                columns = if (quarterTurn) ZebraGridRows else ZebraGridColumns,
                rows = if (quarterTurn) ZebraGridColumns else ZebraGridRows,
            )
            _zebraMask.value = rawMask.rotatedBy(rotationDegrees)
        } catch (e: IllegalStateException) {
            // Buffer went inaccessible mid-read — skip this frame, next one picks classification back up.
        }
    }

    /**
     * Mirrors [classifyZebraIfDue]'s own throttle-skip reasoning, just unconditionally (no
     * [zebraAnalysisEnabled]-style gate — see [_histogramData]'s own doc for why a histogram is
     * always-on) and at a lighter [HistogramThrottleMs] floor, since this now runs for the entire
     * preview lifetime rather than only during a drag burst. No rotation handling needed here (unlike
     * [classifyZebraIfDue]'s [ZebraMask.rotatedBy]) — [HistogramData] is a plain bucket-count
     * distribution with no spatial layout to rotate, so however the raw buffer's rows/columns are
     * oriented makes no difference to the result.
     */
    private fun classifyHistogramIfDue(image: Image) {
        val now = SystemClock.uptimeMillis()
        if (now - lastHistogramClassifyUptimeMs < HistogramThrottleMs) return
        lastHistogramClassifyUptimeMs = now

        // See classifyZebraIfDue's own doc for why this is guarded the same way.
        try {
            val plane = image.planes[0]
            _histogramData.value = HistogramData.fromLumaPlane(
                buffer = plane.buffer,
                rowStride = plane.rowStride,
                pixelStride = plane.pixelStride,
                width = image.width,
                height = image.height,
                bucketCount = HistogramBucketCount,
            )
        } catch (e: IllegalStateException) {
            // Buffer went inaccessible mid-read — skip this frame, next one picks classification back up.
        }
    }

    private companion object {
        /** Grid dimensions [ZebraMask.fromLumaPlane] buckets the analysis frame into — coarse/blocky
         *  (not per-pixel) like the reference implementation's own clipping mask, but doubled from
         *  that reference's 24x32 (same 3:4 aspect) for a visibly finer overlay — `ZebraOverlay`'s own
         *  per-cell rendering (rounded corners, dot/stripe fill) reads noticeably smoother with more,
         *  smaller sectors. Still trivial per-frame cost — see [ZebraMask.fromLumaPlane]'s own doc for
         *  the sample-budget math this scales. */
        const val ZebraGridColumns = 48
        const val ZebraGridRows = 64

        /** Floor between real [ZebraMask] recomputations — see [classifyZebraIfDue]. */
        const val ZebraThrottleMs = 66L

        /** Floor between real [HistogramData] recomputations — see [classifyHistogramIfDue]. Lighter
         *  than [ZebraThrottleMs] (10fps vs. 15fps) since this runs for the entire preview lifetime
         *  rather than only during a dial-drag burst. */
        const val HistogramThrottleMs = 100L

        /** Bucket count for [HistogramData.fromLumaPlane] — deliberately coarse (not the domain
         *  default of 64) to match `HistogramOverlay`'s dot-per-bucket rendering, where each bucket
         *  gets its own visibly distinct baseline dot rather than blurring into a dense continuous
         *  bar chart. */
        const val HistogramBucketCount = 16
    }
}
