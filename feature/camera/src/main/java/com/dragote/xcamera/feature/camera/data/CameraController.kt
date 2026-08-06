package com.dragote.xcamera.feature.camera.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.dragote.xcamera.feature.camera.domain.model.AeCompensationCapability
import com.dragote.xcamera.feature.camera.domain.model.CameraLens
import com.dragote.xcamera.feature.camera.domain.model.FlashMode
import com.dragote.xcamera.feature.camera.domain.model.FocusRegionSizeFraction
import com.dragote.xcamera.feature.camera.domain.model.ManualFocusCapability
import com.dragote.xcamera.feature.camera.domain.model.ManualIsoCapability
import com.dragote.xcamera.feature.camera.domain.model.ZebraMask
import com.dragote.xcamera.feature.camera.domain.model.displayFractionToSensorFraction
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import javax.inject.Singleton

/**
 * Raw `android.hardware.camera2` wrapper: opens a [CameraDevice], configures one
 * [CameraCaptureSession] with two independent output surfaces — a private preview
 * [android.media.ImageReader] ([previewImageReader], `YUV_420_888`) and a private still
 * [android.media.ImageReader] ([imageReader], JPEG) — and issues genuinely separate Camera2 requests
 * against each — a live-updatable [CameraCaptureSession.setRepeatingRequest] for the preview reader
 * (see [buildPreviewRequest]), and a one-off [CameraCaptureSession.capture] for the still reader (see
 * [captureStillJpeg]) that carries the real, preview-uncapped user-selected manual exposure when
 * manual mode is active. The two share no mutable request state — they only both read the same
 * [pendingManualIso]/[pendingManualShutterNs] cache and independently resolve/clamp it via
 * [resolveManualExposure].
 *
 * The live preview is *rendered*, not just captured, by this class's own surrounding infrastructure:
 * [setPreviewFrameListener] hands each delivered preview [Image] to a caller-supplied listener/
 * [Handler] (in practice, `ui/CameraScreen`'s `CameraPreviewRenderer`, which draws it onto the
 * on-screen `TextureView` via app-owned GLES) — see that class's own doc for why the live preview
 * deliberately isn't targeted at a caller-supplied `SurfaceTexture`-backed `Surface` the way it used
 * to be: an `ImageReader`'s dimensions are a hard, verifiable construction-time contract, unlike a
 * `SurfaceTexture`'s requested buffer size, which this device's Camera2 HAL was found not to always
 * honor across a session reopen (see `docs/features/camera-capture.md`'s history of that bug).
 *
 * This replaces an earlier CameraX (`Preview`/`ImageCapture` use case)-based implementation. CameraX's
 * `Camera2Interop`/`Camera2CameraControl` only exposes session-wide dynamic `CaptureRequestOptions`
 * overrides — there is no supported way to keep a fast repeating preview request running while
 * submitting an independent still-capture request with unrelated exposure parameters through that
 * API, since both are merged through the same session-wide override surface before being submitted
 * to the camera's single serialized executor. That coupling is what caused long manual shutter
 * speeds (2s/4s/8s+) to visibly freeze the live viewfinder and, at the longest speeds, to fail
 * capture outright once several long-exposure repeating frames backed up in the HAL ahead of the
 * still request. Managing the `CameraCaptureSession` directly removes that coupling entirely: the
 * preview's repeating request and a still capture's one-off request are independent Camera2 requests
 * from the start, so there is nothing for a manual exposure choice to back up behind.
 *
 * Kept out of the ViewModel since [bindCamera] inherently needs a Compose `LifecycleOwner`, which is
 * a ui-layer-adjacent type — see CLAUDE.md's data-layer-owns-hardware convention.
 *
 * `@Singleton`-scoped (see `di/CameraModule`'s `provideCameraController`) so every injection path —
 * `CameraViewModel`'s constructor injection and `ui/CameraScreen`'s separate `EntryPointAccessors`
 * call — shares the one instance [bindCamera] is actually called on.
 */
@Singleton
class CameraController(private val context: Context) : LifecycleEventObserver {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    // All Camera2 device/session/ImageReader callbacks run on a dedicated background thread rather
    // than the main thread — standard Camera2 practice (mirrors Google's own Camera2Basic sample),
    // since camera IO/JPEG decoding is too heavy for the main/UI thread.
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    // This class is @Singleton-scoped and lives for the process, same as the Camera2 resources it
    // owns, so lifecycle-driven reopen/close (see onStateChanged) can launch suspend work here
    // without depending on whichever caller's coroutine originally triggered bindCamera still being
    // active.
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Guards openCamera()'s teardown-then-rebuild sequence against overlapping callers — bindCamera
    // (driven by ui/CameraScreen's LaunchedEffect) and the ON_START lifecycle callback below can both
    // trigger it independently.
    private val cameraLock = Mutex()

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null

    /**
     * The live preview's own output — see this class's own doc and [setPreviewFrameListener] for why
     * this is an `ImageReader` (`YUV_420_888`) rather than a caller-supplied `Surface`. Created fresh
     * in [openCamera] alongside [imageReader]; the session stays a 2-output configuration exactly as
     * before this rewrite (preview + still), not 3 — see [createCaptureSession].
     */
    private var previewImageReader: ImageReader? = null

    /** Set via [setPreviewFrameListener] — the render-thread [Handler] to post each delivered preview
     *  [Image] onto, paired with [previewFrameListener]. Both `null` until `ui/CameraScreen` registers
     *  its renderer, and cleared again in [unbindCamera]. */
    private var previewFrameHandler: Handler? = null

    /** See [previewFrameHandler]'s own doc — receives ownership of each delivered preview [Image]
     *  (must eventually `close()` it) via [previewFrameHandler]. */
    private var previewFrameListener: ((Image) -> Unit)? = null

    /** Cached from [bindCamera]'s parameters — the `TextureView`'s own measured pixel size, needed to
     *  compute [previewOutputSize] internally now that there's no caller-supplied, already-sized
     *  `Surface` to size a stream against. */
    private var previewViewWidth = 0
    private var previewViewHeight = 0

    /**
     * Set once by the first [bindCamera] call and cleared by [unbindCamera] — the signal
     * [onStateChanged]/[openCamera] gate on to know there's something to (re)bind, now that there's
     * no caller-supplied `Surface` whose mere presence used to mean that.
     */
    private var isBound = false

    /**
     * Completed by [CameraDevice.StateCallback.onClosed] for whichever device [openCameraDevice] most
     * recently opened — `CameraDevice.close()`/`CameraCaptureSession.close()` (see
     * [closeCameraAndSessionLocked]) are asynchronous, the call returning immediately while teardown
     * continues on the HAL/driver side; without waiting for the real `onClosed()` signal, a reopen of
     * the *same* camera ID (the only path that can happen — see [openCamera]'s own doc) can race an
     * incompletely-released previous instance of that ID, a known Camera2 pitfall (Google's own
     * `Camera2Basic` sample explicitly gates its next open behind exactly this kind of wait). On-device
     * testing traced a live-preview aspect-ratio bug specifically to `ON_STOP`→`ON_START` reopens of
     * the same camera ID — this wait is the fix. Captured as a local inside [openCameraDevice] and
     * assigned here so the specific device instance that eventually calls back into it is unambiguous
     * even if a *newer* open has already replaced this field by the time an old device's `onClosed`
     * fires (shouldn't happen given [cameraLock] serializes opens after this field's own await
     * completes, but the local capture makes that not load-bearing for correctness).
     */
    private var deviceClosedSignal: CompletableDeferred<Unit>? = null

    /**
     * `CameraCharacteristics.SENSOR_ORIENTATION` for whichever lens is currently bound, set alongside
     * [previewImageReader] in [openCamera] — an `ImageReader` (unlike a `TextureView`'s own on-screen
     * `SurfaceTexture`) gets no automatic producer-side rotation, so [previewImageAvailableListener]'s
     * zebra classification rotates by this angle itself (via [ZebraMask.rotatedBy]) to line the mask
     * up with what the fixed-portrait viewfinder actually shows — the same angle [jpegOrientation]
     * bakes into still captures' `JPEG_ORIENTATION`, minus the live device-tilt component
     * ([targetRotation]) that only applies to *saved* JPEG metadata, not to the
     * always-upright-in-its-own-view live preview. [CameraPreviewRenderer] needs this same value too
     * (for its own rotation, see [previewRotationDegrees]) — computed independently there since it's a
     * cheap, pure `CameraCharacteristics` lookup, not shared mutable state.
     */
    private var analysisRotationDegrees = 0

    /** Throttles zebra classification in [previewImageAvailableListener] — see [ZebraThrottleMs]. */
    private var lastZebraClassifyUptimeMs = 0L

    private var currentLens: CameraLens? = null
    private var boundLifecycle: Lifecycle? = null

    private var pendingFlashMode: FlashMode = FlashMode.OFF

    /** Cached the same way [pendingFlashMode] is, so it survives a rebind (e.g. a lens switch). */
    private var pendingManualIso: Int? = null

    /** Cached alongside [pendingManualIso] for the same rebind-survival reason. */
    private var pendingManualShutterNs: Long? = null

    /**
     * Non-null while a manual-focus hold gesture (issue #21) is active or has just released — locks
     * `CONTROL_AF_MODE_OFF` + `LENS_FOCUS_DISTANCE` at this exact value on both the preview's repeating
     * request ([buildPreviewRequest]) and the next still capture ([captureStillJpeg]), same "cached
     * pending value, reapplied on every request" pattern as [pendingManualIso]/[pendingManualShutterNs].
     * Per the issue's own "release-to-lock" requirement, releasing the hold does *not* clear this —
     * only [triggerAutoFocus] (a fresh tap) does, resuming continuous AF. Survives a rebind the same
     * way manual exposure does.
     */
    private var pendingManualFocusDiopters: Float? = null

    /**
     * Set by [triggerAutoFocus], applied on every subsequent request while [pendingManualFocusDiopters]
     * is `null` — Camera2's `CONTROL_AF_TRIGGER_START` only needs to be sent once (see
     * [triggerAutoFocus]'s own one-off [CameraCaptureSession.capture] call), but the *region* itself
     * needs to keep being set on every following request for continuous AF to keep tracking around it,
     * exactly like [pendingAeCompensation] being reapplied on every auto-exposure request. Reset to
     * `null` on a lens switch (see [bindCamera]) since it's expressed in the *previous* lens's own
     * `SENSOR_INFO_ACTIVE_ARRAY_SIZE` coordinate space, not portable across lenses.
     */
    private var pendingAfRegion: MeteringRectangle? = null

    /**
     * Unlike [pendingManualIso]/[pendingManualShutterNs], this has no "absent" state to represent —
     * `0` is always a valid, meaningful "no compensation" value, so this is non-null rather than
     * `Int?`. Applied only while auto-exposure (`CONTROL_AE_MODE_ON`) is active — see
     * [buildPreviewRequest]/[captureStillJpeg] — Camera2 ignores this key entirely under
     * `CONTROL_AE_MODE_OFF`, so there's nothing to suppress/reset when manual mode is engaged.
     */
    private var pendingAeCompensation: Int = 0

    /**
     * The most recent auto-AE-converged ISO, continuously observed via this [StateFlow] rather than a
     * one-shot pull — the ISO dial reflects auto-exposure's live ISO the whole time manual mode is off
     * (see `CameraViewModel`'s collector), not just the first time it's touched. While manual mode is
     * on, the preview's repeating request instead carries a *forced* capped-manual exposure (see
     * [buildPreviewRequest]) that must **not** be mistaken for a real auto-converged value, which is
     * exactly what [previewCaptureCallback]'s [pendingManualIso]/[pendingManualShutterNs] guard
     * prevents.
     */
    private val _autoIso = MutableStateFlow<Int?>(null)
    val autoIso: StateFlow<Int?> = _autoIso.asStateFlow()

    /**
     * Mirrors [autoIso] for shutter speed — the shutter dial tracks live auto-exposure the same way
     * the ISO dial does (see `CameraViewModel`'s two collectors), not just once on first touch. Also
     * read synchronously by [resolveManualExposure] as the fallback shutter time whenever only ISO is
     * currently pinned by manual mode (e.g. this lens has no aligned shutter-speed stops), the same
     * "starts from wherever auto last settled" behavior this had before it became a [StateFlow].
     */
    private val _autoExposureTimeNs = MutableStateFlow<Long?>(null)
    val autoExposureTimeNs: StateFlow<Long?> = _autoExposureTimeNs.asStateFlow()

    /**
     * Mirrors [autoIso]/[autoExposureTimeNs] for `LENS_FOCUS_DISTANCE` — the most recent
     * continuous-AF-converged focus distance (diopters), continuously observed rather than a one-shot
     * pull. Read synchronously by `CameraViewModel`'s own tracking the same way, and used by
     * `ui/component/FocusRing`'s hold gesture as the starting point a rotation adjusts *from* (see
     * [manualFocusDistanceForRotation][com.dragote.xcamera.feature.camera.domain.model.manualFocusDistanceForRotation]).
     */
    private val _autoFocusDistanceDiopters = MutableStateFlow<Float?>(null)
    val autoFocusDistanceDiopters: StateFlow<Float?> = _autoFocusDistanceDiopters.asStateFlow()

    /**
     * While manual mode is active ([pendingManualIso]/[pendingManualShutterNs] non-null), the
     * preview's repeating request carries a forced capped-manual exposure (see
     * [buildPreviewRequest]), not a genuine AE convergence value — so this must skip updating
     * [_autoIso]/[_autoExposureTimeNs] in that case, exactly as it did before live manual preview
     * feedback was reintroduced, otherwise the ISO/shutter dials' live auto-tracking would both get
     * fed a bogus "auto" value that's actually just whatever the preview cap forced. AF and AE are
     * independent axes (issue #21) — [pendingManualFocusDiopters] gates [_autoFocusDistanceDiopters]
     * on its own, regardless of whichever exposure mode is active.
     */
    private val previewCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            if (pendingManualIso == null && pendingManualShutterNs == null) {
                result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { _autoExposureTimeNs.value = it }
                result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { _autoIso.value = it }
            }
            if (pendingManualFocusDiopters == null) {
                result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { _autoFocusDistanceDiopters.value = it }
            }
        }
    }

    /** Resolved by [captureStillJpeg] once the pending still capture's JPEG bytes are delivered. */
    private var pendingCapture: CancellableContinuation<ByteArray>? = null

    private val imageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        val image = reader.acquireLatestImage()
        if (image == null) return@OnImageAvailableListener
        val bytes = try {
            val buffer = image.planes[0].buffer
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        } finally {
            image.close()
        }
        pendingCapture?.takeIf { it.isActive }?.resume(bytes)
        pendingCapture = null
    }

    /**
     * Set via [setZebraAnalysisEnabled] — true only while the ISO/shutter/EV dial is actively being
     * dragged (see `ui/CameraScreen`'s wiring of `DialWheel.onDragActiveChanged`). Read by
     * [previewImageAvailableListener] to decide whether it's worth classifying the current frame at
     * all; while false, classification is skipped entirely, so there's zero analysis cost outside an
     * actual drag even though the preview stream itself is always running.
     */
    private var zebraAnalysisEnabled = false

    /**
     * Grid-coarse clipping mask for the live viewfinder, `null` whenever there's nothing to show
     * (analysis disabled or no frame has landed yet). See [CameraUiState]-adjacent reasoning in
     * `CameraViewModel`: this is deliberately its own [StateFlow], not folded into a single UI-state
     * data class, since collapsing a ~15fps stream of updates into one big state object would force
     * everything reading that object to recompose on every emission.
     */
    private val _zebraMask = MutableStateFlow<ZebraMask?>(null)
    val zebraMask: StateFlow<ZebraMask?> = _zebraMask.asStateFlow()

    /**
     * Guards against exceeding the preview [ImageReader]'s `maxImages` (2) — confirmed on-device this
     * is a real, not just theoretical, risk: `renderer.start()`'s EGL/shader setup takes long enough
     * that several frames' worth of `onImageAvailable` callbacks can fire before the render thread has
     * processed (and therefore closed) even the first handed-off `Image`, and `acquireLatestImage()`
     * throws `IllegalStateException` rather than silently coping once that many of *our own* acquired-
     * but-unclosed images pile up (it *does* internally skip/release intermediate frames on its own
     * behalf while draining to the newest one, but it has no way to reclaim an image the app itself
     * is still holding, e.g. one already handed off but not yet drawn+closed by the render thread).
     * Set `true` right after acquiring (before this listener returns), cleared only once the frame has
     * genuinely finished being drawn — see [previewImageAvailableListener]. `@Volatile` since it's
     * written from both this listener's own thread ([backgroundHandler]) and the render thread that
     * eventually closes the frame.
     */
    @Volatile
    private var previewFrameInFlight = false

    /**
     * Every preview frame arrives here (on [backgroundHandler]) as a side effect of the repeating
     * request Camera2 is already running — unlike zebra's previous `PixelCopy`-polling design, there's
     * no separate capture mechanism to drive: classification just piggybacks on frames that are
     * already flowing. Skips acquiring anything at all while [previewFrameInFlight] — see that field's
     * own doc for why this is required, not just a minor efficiency tweak — the next callback (there's
     * always another one shortly, Camera2 delivers these continuously) picks up whatever's newest once
     * the render thread catches up, via `acquireLatestImage()`'s own "skip to newest" behavior.
     *
     * Zebra classification (throttled to [ZebraThrottleMs], gated on [zebraAnalysisEnabled]) reads
     * [image]'s luma plane directly, *before* the frame is handed off to [previewFrameListener] — both
     * reads are safe against the same `Image` regardless of order, since [ZebraMask.fromLumaPlane]
     * only ever uses absolute (position-independent) `ByteBuffer.get(index)` calls, never mutating the
     * plane buffer's position.
     *
     * Ownership of [image] transfers to [previewFrameListener] via [previewFrameHandler] — if neither
     * is registered (e.g. briefly during a rebind before `ui/CameraScreen`'s renderer re-registers),
     * or if posting onto an already-shutting-down render thread fails, this closes it (and clears
     * [previewFrameInFlight]) here instead so nothing leaks or wedges future frames open forever.
     */
    private val previewImageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        if (previewFrameInFlight) return@OnImageAvailableListener
        val image = reader.acquireLatestImage() ?: return@OnImageAvailableListener

        if (zebraAnalysisEnabled) classifyZebraIfDue(image)

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

    /**
     * Skips (not just throttles the *result* of, the *work* of) classification entirely if less than
     * [ZebraThrottleMs] has passed since the last real one — preview frames can arrive at up to ~30fps,
     * far more often than the overlay needs to visibly update. Rotation handling mirrors the deleted
     * `PixelCopy`-era design exactly (see [analysisRotationDegrees]'s own doc for why an `ImageReader`
     * buffer needs this at all): a 90/270 [analysisRotationDegrees] swaps width for height, so the
     * *raw* grid requested from [ZebraMask.fromLumaPlane] is swapped accordingly too (matching the
     * buffer's own landscape aspect, avoiding a stretched grid), then [ZebraMask.rotatedBy] rotates the
     * finished small grid (cheap — [ZebraGridColumns]x[ZebraGridRows] cells, not the raw frame) into
     * the shape the portrait [com.dragote.xcamera.feature.camera.ui.component.ZebraOverlay] canvas
     * actually expects.
     */
    private fun classifyZebraIfDue(image: Image) {
        val now = SystemClock.uptimeMillis()
        if (now - lastZebraClassifyUptimeMs < ZebraThrottleMs) return
        lastZebraClassifyUptimeMs = now

        val plane = image.planes[0]
        val quarterTurn = analysisRotationDegrees == 90 || analysisRotationDegrees == 270
        val rawMask = ZebraMask.fromLumaPlane(
            buffer = plane.buffer,
            rowStride = plane.rowStride,
            pixelStride = plane.pixelStride,
            width = image.width,
            height = image.height,
            columns = if (quarterTurn) ZebraGridRows else ZebraGridColumns,
            rows = if (quarterTurn) ZebraGridColumns else ZebraGridRows,
        )
        _zebraMask.value = rawMask.rotatedBy(analysisRotationDegrees)
    }

    /**
     * The activity is locked to portrait (see AndroidManifest) so the skeuomorphic UI never rotates,
     * which means there's no configuration-change signal to derive a target rotation from. This
     * listener tracks the phone's *physical* orientation via the accelerometer instead, bucketed into
     * the same four [Surface.ROTATION_0]-style buckets a `targetRotation` API would use, so a photo
     * taken while the phone is held in landscape is still saved landscape (see [jpegOrientation])
     * rather than being locked to portrait output.
     */
    private var orientationEventListener: OrientationEventListener? = null
    private var targetRotation: Int = Surface.ROTATION_0

    private fun ensureOrientationListener() {
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

    /** Call when the composable hosting this controller leaves composition. */
    fun stopOrientationListener() {
        orientationEventListener?.disable()
        orientationEventListener = null
    }

    /**
     * [lens] is null for the plain default back camera. When non-null, [CameraLens.physicalCameraId]
     * (if set) is pinned per output surface via [OutputConfiguration.setPhysicalCameraId] — most
     * multi-lens phones expose their extra lenses as physical sub-cameras of one logical camera
     * rather than as separate top-level camera IDs.
     *
     * [previewViewWidth]/[previewViewHeight] are the on-screen `TextureView`'s own measured pixel
     * dimensions — this class computes/owns the actual preview [ImageReader] ([previewImageReader])
     * internally from them (see [previewOutputSize]/[createPreviewImageReader]) rather than accepting
     * an already-sized `Surface` the way it used to; see this class's own doc for why. Register a
     * frame consumer via [setPreviewFrameListener] separately to actually see any pixels.
     *
     * Camera2 has no lifecycle-aware bind/unbind equivalent to CameraX's `bindToLifecycle`, so this
     * registers as a [LifecycleEventObserver] on [lifecycleOwner]'s lifecycle to open the device/
     * session at `ON_START` and close it at `ON_STOP` — otherwise the camera would stay open with no
     * lifecycle-driven release once the app backgrounds, a resource leak that also blocks every other
     * app from using the camera. [unbindCamera] is for the separate, surface-destroyed case (e.g. the
     * hosting view leaves composition) — call it independently, not as an ON_STOP substitute.
     */
    suspend fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewViewWidth: Int,
        previewViewHeight: Int,
        lens: CameraLens? = null,
    ) {
        ensureBackgroundThread()
        isBound = true
        // pendingAfRegion is expressed in the *previous* lens's own SENSOR_INFO_ACTIVE_ARRAY_SIZE
        // coordinate space — not portable to a different lens, see pendingAfRegion's own doc.
        // pendingManualFocusDiopters intentionally survives a lens switch (same reasoning as
        // pendingManualIso/pendingManualShutterNs) since diopters aren't lens-active-array-relative.
        if (currentLens != lens) pendingAfRegion = null
        currentLens = lens
        this.previewViewWidth = previewViewWidth
        this.previewViewHeight = previewViewHeight

        val lifecycle = lifecycleOwner.lifecycle
        if (boundLifecycle !== lifecycle) {
            boundLifecycle?.removeObserver(this)
            boundLifecycle = lifecycle
            lifecycle.addObserver(this)
        }

        ensureOrientationListener()

        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            openCamera()
        }
    }

    /**
     * Call once whatever [bindCamera] was backing is no longer valid (e.g. `ui/CameraScreen`'s
     * `onSurfaceTextureDestroyed`, once its `CameraPreviewRenderer` has been stopped). Clears
     * [isBound] (so a lifecycle event arriving mid-teardown can't resurrect the session — see
     * [onStateChanged]) and [previewFrameHandler]/[previewFrameListener] (so no further frames get
     * handed to a renderer that's going away), then closes the device/session/readers the same way
     * [closeCameraAndSession] does.
     */
    fun unbindCamera() {
        boundLifecycle?.removeObserver(this)
        boundLifecycle = null
        isBound = false
        previewFrameHandler = null
        previewFrameListener = null
        controllerScope.launch {
            cameraLock.withLock { closeCameraAndSessionLocked() }
            stopBackgroundThread()
        }
    }

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_START -> {
                if (isBound) controllerScope.launch { openCamera() }
            }
            Lifecycle.Event.ON_STOP -> closeCameraAndSession()
            Lifecycle.Event.ON_DESTROY -> unbindCamera()
            else -> Unit
        }
    }

    private suspend fun openCamera() = cameraLock.withLock {
        if (!isBound) return@withLock
        closeCameraAndSessionLocked()

        val cameraId = resolveLogicalCameraId(currentLens) ?: return@withLock
        val effectiveCharacteristics = characteristicsFor(currentLens) ?: return@withLock

        val device = try {
            openCameraDevice(cameraId)
        } catch (e: CameraAccessException) {
            return@withLock
        } catch (e: SecurityException) {
            return@withLock
        } catch (e: IllegalStateException) {
            return@withLock
        }
        cameraDevice = device

        val reader = createImageReader(effectiveCharacteristics)
        imageReader = reader

        val previewReader = createPreviewImageReader(effectiveCharacteristics)
        previewImageReader = previewReader
        analysisRotationDegrees = effectiveCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

        val session = try {
            createCaptureSession(device, previewReader.surface, reader.surface, currentLens)
        } catch (e: CameraAccessException) {
            device.close()
            cameraDevice = null
            reader.close()
            imageReader = null
            previewReader.close()
            previewImageReader = null
            return@withLock
        } catch (e: IllegalStateException) {
            device.close()
            cameraDevice = null
            reader.close()
            imageReader = null
            previewReader.close()
            previewImageReader = null
            return@withLock
        }
        captureSession = session
        startPreviewRepeating(device, session, previewReader.surface)
    }

    @Suppress("MissingPermission") // CAMERA permission is gated by ui/CameraScreen before bindCamera is ever called.
    private suspend fun openCameraDevice(cameraId: String): CameraDevice =
        suspendCancellableCoroutine { continuation ->
            val closedSignal = CompletableDeferred<Unit>()
            deviceClosedSignal = closedSignal
            cameraManager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (continuation.isActive) continuation.resume(camera) else camera.close()
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        if (continuation.isActive) {
                            continuation.resumeWithException(IllegalStateException("Camera disconnected"))
                        }
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        if (continuation.isActive) {
                            continuation.resumeWithException(IllegalStateException("Camera error: $error"))
                        }
                    }

                    // See deviceClosedSignal's own doc — this is what closeCameraAndSessionLocked
                    // actually waits on before letting the same camera ID be reopened.
                    override fun onClosed(camera: CameraDevice) {
                        closedSignal.complete(Unit)
                    }
                },
                backgroundHandler,
            )
        }

    private suspend fun createCaptureSession(
        device: CameraDevice,
        previewSurface: Surface,
        stillSurface: Surface,
        lens: CameraLens?,
    ): CameraCaptureSession = suspendCancellableCoroutine { continuation ->
        val stateCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (continuation.isActive) continuation.resume(session)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                if (continuation.isActive) {
                    continuation.resumeWithException(IllegalStateException("Capture session configuration failed"))
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val previewConfig = OutputConfiguration(previewSurface).apply {
                lens?.physicalCameraId?.let(::setPhysicalCameraId)
            }
            val stillConfig = OutputConfiguration(stillSurface).apply {
                lens?.physicalCameraId?.let(::setPhysicalCameraId)
            }
            val sessionConfiguration = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(previewConfig, stillConfig),
                ContextCompat.getMainExecutor(context),
                stateCallback,
            )
            device.createCaptureSession(sessionConfiguration)
        } else {
            @Suppress("DEPRECATION")
            device.createCaptureSession(listOf(previewSurface, stillSurface), stateCallback, backgroundHandler)
        }
    }

    /**
     * Starts (or restarts) the live preview repeating request against [surface], built fresh from
     * whatever [pendingManualIso]/[pendingManualShutterNs] are set to *right now* — see
     * [buildPreviewRequest]. Called once from [openCamera] when the session is first configured (so a
     * lens switch while already in manual mode reopens with the capped-manual preview active rather
     * than silently reverting to auto), and again any time later via [updatePreviewRepeating] whenever
     * manual exposure changes — `session.setRepeatingRequest` is cheap to call repeatedly and doesn't
     * require reconfiguring the session itself.
     */
    private fun startPreviewRepeating(device: CameraDevice, session: CameraCaptureSession, surface: Surface) {
        session.setRepeatingRequest(buildPreviewRequest(device, surface), previewCaptureCallback, backgroundHandler)
    }

    /**
     * Live-updates the already-running preview repeating request to reflect the current
     * [pendingManualIso]/[pendingManualShutterNs] — called by [setManualExposure] on every change.
     * A no-op if no session is open yet (e.g. `setManualExposure` called from the ViewModel before
     * [bindCamera]/[openCamera] has completed) or if it races a concurrent close/reopen; either way
     * the next [openCamera] call picks up the current pending state fresh regardless, so there's
     * nothing to recover here.
     */
    private fun updatePreviewRepeating() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val surface = previewImageReader?.surface ?: return
        try {
            startPreviewRepeating(device, session, surface)
        } catch (e: CameraAccessException) {
            // Session/device is mid-teardown — ignore, a fresh openCamera() will reflect current state.
        } catch (e: IllegalStateException) {
            // Session already closed — same as above.
        }
    }

    /**
     * Plain auto-exposure (`CONTROL_AE_MODE_ON`) when manual mode is off. While manual mode is on,
     * this reproduces the live-feedback-but-safe preview behavior manual mode always had before the
     * Camera2 migration: `CONTROL_AE_MODE_OFF` with the *real* selected exposure (via
     * [resolveManualExposure], same resolution [captureStillJpeg] uses) further capped at
     * [PreviewMaxExposureTimeNs] so the preview frame rate never degrades, with `SENSOR_SENSITIVITY`
     * boosted to compensate for the brightness the cap costs (re-clamped to [capability]'s ISO range —
     * a very long selected shutter speed may not be fully compensable within the sensor's ISO ceiling,
     * in which case the live preview just runs a bit dark; the *captured* photo is unaffected since
     * [captureStillJpeg] never applies this cap). This is a completely independent request from
     * [captureStillJpeg]'s still-capture request — the only thing shared between them is reading the
     * same [pendingManualIso]/[pendingManualShutterNs] cache, not any Camera2-level session state.
     */
    private fun buildPreviewRequest(device: CameraDevice, surface: Surface): CaptureRequest {
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        }

        val manualCapability = if (pendingManualIso != null || pendingManualShutterNs != null) {
            manualIsoCapability(currentLens)
        } else {
            null
        }

        if (manualCapability != null) {
            val (iso, shutterNs) = resolveManualExposure(pendingManualIso, pendingManualShutterNs, manualCapability)
            val previewShutterNs = shutterNs.coerceAtMost(PreviewMaxExposureTimeNs)
            val compensation = if (previewShutterNs > 0) shutterNs.toDouble() / previewShutterNs else 1.0
            val previewIso = (iso * compensation).roundToInt()
                .coerceIn(manualCapability.isoRange.first, manualCapability.isoRange.last)

            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, previewIso)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, previewShutterNs)
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, pendingAeCompensation)
        }

        applyFocusSettings(builder)

        return builder.build()
    }

    /**
     * AF is an independent axis from AE (see [buildPreviewRequest]'s own AE branch just above) — issue
     * #21's tap-to-focus and hold-and-rotate manual focus ring both drive `CONTROL_AF_MODE`/
     * `LENS_FOCUS_DISTANCE`/`CONTROL_AF_REGIONS`, orthogonal to whichever ISO/shutter mode is active.
     * Shared between [buildPreviewRequest] and [captureStillJpeg] so a locked manual focus distance
     * (or an active tap-to-focus region) applies identically to both the live preview and the actual
     * capture, the same "one cache, reapplied to every request" pattern
     * [pendingManualIso]/[pendingManualShutterNs] already use for exposure. A no-op (falls through to
     * the request template's own AF default) on a lens with no [manualFocusCapability] — i.e. a
     * fixed-focus lens, where there's no `LENS_FOCUS_DISTANCE` control surface to touch at all.
     */
    private fun applyFocusSettings(builder: CaptureRequest.Builder) {
        val focusCapability = manualFocusCapability(currentLens) ?: return

        val manualDistance = pendingManualFocusDiopters
        if (manualDistance != null) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(
                CaptureRequest.LENS_FOCUS_DISTANCE,
                manualDistance.coerceIn(0f, focusCapability.maxFocusDistanceDiopters),
            )
        } else {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            pendingAfRegion?.let { builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(it)) }
        }
    }

    /**
     * Fire-and-forget, but still serialized against [openCamera] through [cameraLock] — both run on
     * [controllerScope]'s `Dispatchers.Main.immediate`, so without this a `ON_STOP` arriving while an
     * in-flight [openCamera] call is still suspended awaiting the device/session callbacks could null
     * out state out from under it, then have that stale [openCamera] call overwrite the closed state
     * once it resumes, leaking an open camera past the lifecycle event that was supposed to close it.
     */
    private fun closeCameraAndSession() {
        controllerScope.launch { cameraLock.withLock { closeCameraAndSessionLocked() } }
    }

    /**
     * `suspend` specifically so it can await [deviceClosedSignal] (see that field's own doc) before
     * returning — every caller already runs inside a [cameraLock]-held suspend block ([openCamera],
     * [unbindCamera], [closeCameraAndSession]), so this simply extends how long that lock is held
     * rather than needing any new coordination of its own; that's the whole point, since the lock is
     * exactly what's supposed to keep the *next* [openCamera] call from reopening the same camera ID
     * before this one has genuinely finished releasing it. [CameraCloseTimeoutMs] is a bounded safety
     * net, not the expected path — a device that never calls `onClosed()` shouldn't be able to
     * deadlock every future rebind.
     */
    private suspend fun closeCameraAndSessionLocked() {
        captureSession?.close()
        captureSession = null
        val hadDevice = cameraDevice != null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        previewImageReader?.close()
        previewImageReader = null
        _autoExposureTimeNs.value = null
        _autoIso.value = null
        _autoFocusDistanceDiopters.value = null
        _zebraMask.value = null
        if (hadDevice) {
            withTimeoutOrNull(CameraCloseTimeoutMs) { deviceClosedSignal?.await() }
        }
    }

    private fun ensureBackgroundThread() {
        if (backgroundThread != null) return
        val thread = HandlerThread("CameraController-Camera2").apply { start() }
        backgroundThread = thread
        backgroundHandler = Handler(thread.looper)
    }

    private fun stopBackgroundThread() {
        val thread = backgroundThread ?: return
        thread.quitSafely()
        try {
            thread.join()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        backgroundThread = null
        backgroundHandler = null
    }

    private fun resolveLogicalCameraId(lens: CameraLens?): String? = lens?.logicalCameraId ?: defaultBackCameraId()

    private fun createImageReader(characteristics: CameraCharacteristics): ImageReader {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val jpegSize = map?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width.toLong() * it.height }
            ?: FallbackStillSize
        return ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2).apply {
            setOnImageAvailableListener(imageAvailableListener, backgroundHandler)
        }
    }

    /**
     * The live preview's own output — see this class's own doc for why this is an `ImageReader`
     * rather than a caller-supplied `Surface`. `maxImages = 2` mirrors [createImageReader]'s still
     * reader; a live preview only ever wants the latest frame, so [previewImageAvailableListener]
     * always calls `acquireLatestImage`, never queuing.
     */
    private fun createPreviewImageReader(characteristics: CameraCharacteristics): ImageReader {
        val size = previewOutputSize(currentLens, previewViewWidth, previewViewHeight)
        return ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2).apply {
            setOnImageAvailableListener(previewImageAvailableListener, backgroundHandler)
        }
    }

    /**
     * Picks the [android.hardware.camera2.params.StreamConfigurationMap]-supported `YUV_420_888`
     * preview size closest in aspect ratio to a [targetWidth]x[targetHeight] view without being
     * needlessly larger than the viewfinder actually needs (keeps sensor readout/preview frame cost
     * down). [targetWidth]/[targetHeight] are the *view's* own pixel dimensions (portrait, since the
     * activity is locked portrait); output sizes from [CameraCharacteristics] are expressed in the
     * sensor's own native pixel-array coordinate convention, which for essentially every phone's back
     * camera is landscape (a physically-rotated sensor) regardless of how the device is held — so
     * matching is done against the width/height-swapped target. `CameraPreviewRenderer`'s own crop
     * transform makes the same swap assumption on the consuming side. Now internal-only —
     * `ui/CameraScreen` no longer calls this directly (there's no `Surface` for it to size), it's used
     * solely by [createPreviewImageReader].
     */
    private fun previewOutputSize(lens: CameraLens?, targetWidth: Int, targetHeight: Int): Size {
        if (targetWidth <= 0 || targetHeight <= 0) return FallbackPreviewSize
        val characteristics = characteristicsFor(lens) ?: return FallbackPreviewSize
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return FallbackPreviewSize
        val candidates = map.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
        if (candidates.isEmpty()) return FallbackPreviewSize

        val targetAspect = targetWidth.toFloat() / targetHeight.toFloat()
        // Both bounds matter: MaxPreviewDimension keeps frame cost down, but MinPreviewDimension is
        // just as important — without a floor, a small analysis/video-call-class size (e.g. 352x288,
        // listed by real devices alongside genuine preview sizes) can end up numerically *closer* in
        // aspect ratio to an odd target (e.g. an 11:9 candidate beating every real 4:3/16:9 one purely
        // by coincidence) than any properly-sized candidate, silently picking a viewfinder resolution
        // far too small to fill the screen without visible pixelation. Excluding anything below the
        // floor first means aspect-closeness only ever competes among genuinely preview-sized options.
        val inRange = candidates.filter {
            it.width <= MaxPreviewDimension && it.height <= MaxPreviewDimension &&
                minOf(it.width, it.height) >= MinPreviewDimension
        }
        return (inRange.ifEmpty { candidates })
            .minByOrNull { size -> abs(size.height.toFloat() / size.width.toFloat() - targetAspect) }
            ?: FallbackPreviewSize
    }

    /**
     * Registers (or, passing both `null`, unregisters) the render-thread consumer of every delivered
     * preview [Image] — see [previewFrameHandler]/[previewFrameListener]'s own docs and
     * [previewImageAvailableListener]. `ui/CameraScreen` calls this with its `CameraPreviewRenderer`'s
     * own [handler]/[Handler]-bound `onPreviewFrame` before (or independently of) [bindCamera] —
     * registration and binding aren't ordered relative to each other, frames simply have nowhere to go
     * (closed immediately, see [previewImageAvailableListener]) until both are in place.
     */
    fun setPreviewFrameListener(handler: Handler?, listener: ((Image) -> Unit)?) {
        previewFrameHandler = handler
        previewFrameListener = listener
    }

    /**
     * Pure `CameraCharacteristics.SENSOR_ORIENTATION` lookup for [lens] — `CameraPreviewRenderer`
     * needs this to rotate raw preview frames into display orientation itself, the same reason
     * [analysisRotationDegrees] exists for zebra (an `ImageReader` surface gets no automatic
     * producer-side rotation the way a `TextureView`'s own on-screen `SurfaceTexture` used to). `0` if
     * unavailable — same fallback [analysisRotationDegrees] defaults to.
     */
    fun previewRotationDegrees(lens: CameraLens?): Int =
        characteristicsFor(lens)?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

    /**
     * Cached in [pendingFlashMode]/[pendingManualIso]/[pendingManualShutterNs] the same way manual
     * exposure is cached below, so a mode set before the next still capture (or after a lens switch)
     * still applies. Since flash/manual exposure now only ever apply to the still-capture request
     * built fresh by [captureStillJpeg] on every [takePhoto] call — never the live preview, which
     * always stays on plain auto-exposure — there's nothing to push to the sensor immediately when
     * this is called, unlike the previous CameraX-based implementation.
     */
    fun setFlashMode(flashMode: FlashMode) {
        pendingFlashMode = flashMode
    }

    /**
     * `null` for [lens] queries the plain default back camera, mirroring [bindCamera]'s own
     * convention. `null` return means "hide/disable manual ISO for this lens" —
     * [CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR] and
     * [CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE] are per-*physical*-lens characteristics,
     * not per-device, so an ultra-wide/tele auxiliary lens can lack them even when the main lens has
     * them (see [listBackLenses]'s own physical-vs-logical characteristics lookup for the same
     * reasoning). Pure static [CameraCharacteristics] lookup, independent of whether a camera is
     * bound yet.
     */
    fun manualIsoCapability(lens: CameraLens?): ManualIsoCapability? {
        val characteristics = characteristicsFor(lens) ?: return null

        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        if (CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR !in capabilities) return null

        val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: return null
        val exposureTimeRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: return null

        return ManualIsoCapability(
            isoRange = isoRange.lower..isoRange.upper,
            exposureTimeRange = exposureTimeRange.lower..exposureTimeRange.upper,
        )
    }

    /**
     * Deliberately independent of [manualIsoCapability]/`MANUAL_SENSOR` — exposure compensation
     * biases plain auto-exposure and is supported on nearly every camera, not just ones with full
     * manual sensor control. [CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE] being exactly
     * `[0,0]` is Camera2's own convention for "not supported", which this returns `null` for rather
     * than a `[0,0]`-range [AeCompensationCapability] a caller could mistake for "supported but with
     * zero range". [CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP] is a `Rational`, converted to
     * a plain `Float` here so the domain-facing [AeCompensationCapability] stays Camera2-type-free.
     */
    fun aeCompensationCapability(lens: CameraLens?): AeCompensationCapability? {
        val characteristics = characteristicsFor(lens) ?: return null

        val range = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: return null
        if (range.lower == 0 && range.upper == 0) return null

        val step = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP) ?: return null

        return AeCompensationCapability(
            range = range.lower..range.upper,
            stepEv = step.toFloat(),
        )
    }

    /**
     * `null` return means "hide/no-op both tap-to-focus and the manual focus ring for this lens"
     * (issue #21) — [CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE] being exactly `0` is
     * Camera2's own convention for a fixed-focus lens with no `LENS_FOCUS_DISTANCE` control surface at
     * all, distinct from [manualIsoCapability]'s `MANUAL_SENSOR` gate — a lens can support one without
     * the other. Per-physical-lens, not per-device, mirroring [manualIsoCapability]'s own reasoning
     * (an ultra-wide/tele auxiliary lens can have a different minimum focus distance, or none, even
     * when the main lens supports full manual focus). Pure static [CameraCharacteristics] lookup,
     * independent of whether a camera is bound yet.
     */
    fun manualFocusCapability(lens: CameraLens?): ManualFocusCapability? {
        val characteristics = characteristicsFor(lens) ?: return null
        val minimumFocusDistance = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
            ?: return null
        if (minimumFocusDistance <= 0f) return null
        return ManualFocusCapability(maxFocusDistanceDiopters = minimumFocusDistance)
    }

    /**
     * Standard Camera2 tap-to-focus: [displayXFraction]/[displayYFraction] (`0f..1f`, top-left origin)
     * are a tap point expressed as a fraction of the *displayed* viewfinder — converted into the
     * sensor's own active-array coordinate space via [displayFractionToSensorFraction] (see that
     * function's own doc for the approximation it makes), then built into a
     * [MeteringRectangle] centered on the tap ([FocusRegionSizeFraction] of the active array's own
     * width/height) and pushed as `CONTROL_AF_REGIONS` alongside a one-off
     * `CONTROL_AF_TRIGGER_START` capture — the standard Camera2 idiom for "focus here now". Always
     * clears [pendingManualFocusDiopters] first — a tap always resumes continuous AF, overriding
     * whatever manual focus lock a previous hold gesture may have left in place, matching the issue's
     * own "until the next tap or hold" wording. A no-op on a lens with no [manualFocusCapability], or
     * before a session is actually open (nothing to focus yet).
     */
    fun triggerAutoFocus(displayXFraction: Float, displayYFraction: Float) {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val surface = previewImageReader?.surface ?: return
        val characteristics = characteristicsFor(currentLens) ?: return
        manualFocusCapability(currentLens) ?: return
        val activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return

        pendingManualFocusDiopters = null

        val (sensorXFraction, sensorYFraction) =
            displayFractionToSensorFraction(displayXFraction, displayYFraction, analysisRotationDegrees)
        val region = afRegionAround(sensorXFraction, sensorYFraction, activeArray)
        pendingAfRegion = region

        try {
            val triggerRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            }.build()
            session.capture(triggerRequest, null, backgroundHandler)
        } catch (e: CameraAccessException) {
            // Session/device is mid-teardown — nothing to recover, the next bind starts fresh.
        } catch (e: IllegalStateException) {
            // Session already closed — same as above.
        }

        // The one-off trigger capture above only fires CONTROL_AF_TRIGGER_START once (repeating it on
        // every frame would keep re-triggering a fresh AF search instead of letting it converge); the
        // repeating request still needs the *region* itself reapplied on every subsequent frame for
        // continuous AF to keep tracking around it — see applyFocusSettings.
        updatePreviewRepeating()
    }

    /** [FocusRegionSizeFraction] of the active array's own width/height, centered on
     *  ([centerXFraction], [centerYFraction]) and clamped within the array's bounds. */
    private fun afRegionAround(centerXFraction: Float, centerYFraction: Float, activeArray: Rect): MeteringRectangle {
        val regionWidth = (activeArray.width() * FocusRegionSizeFraction).roundToInt().coerceAtLeast(1)
        val regionHeight = (activeArray.height() * FocusRegionSizeFraction).roundToInt().coerceAtLeast(1)
        val centerX = (activeArray.left + centerXFraction * activeArray.width()).roundToInt()
        val centerY = (activeArray.top + centerYFraction * activeArray.height()).roundToInt()
        val left = (centerX - regionWidth / 2).coerceIn(activeArray.left, (activeArray.right - 1).coerceAtLeast(activeArray.left))
        val top = (centerY - regionHeight / 2).coerceIn(activeArray.top, (activeArray.bottom - 1).coerceAtLeast(activeArray.top))
        val right = (left + regionWidth).coerceAtMost(activeArray.right)
        val bottom = (top + regionHeight).coerceAtMost(activeArray.bottom)
        return MeteringRectangle(left, top, (right - left).coerceAtLeast(1), (bottom - top).coerceAtLeast(1), MeteringRectangle.METERING_WEIGHT_MAX)
    }

    /**
     * Drives the hold-and-rotate manual focus ring (issue #21): [distanceDiopters] non-`null` locks
     * `CONTROL_AF_MODE_OFF` + `LENS_FOCUS_DISTANCE` at that value on both the preview's repeating
     * request and the next still capture (see [applyFocusSettings]) — called on every rotation tick
     * while the ring is held, and left at whatever value it was last called with once the finger
     * releases (this function itself is never called with `null` on release — "commit/lock" per the
     * issue is simply *not clearing* [pendingManualFocusDiopters], the same way manual exposure has no
     * separate "commit" step beyond having already set the pending value). `null` resumes continuous
     * AF — only [triggerAutoFocus] (a fresh tap) does that today. Immediately live-updates the
     * preview's repeating request via [updatePreviewRepeating], mirroring [setManualExposure].
     */
    fun setManualFocusDistance(distanceDiopters: Float?) {
        pendingManualFocusDiopters = distanceDiopters
        updatePreviewRepeating()
    }

    /**
     * Shared by [manualIsoCapability], still-capture orientation/exposure resolution, and preview/
     * still surface sizing — physical-lens characteristics (when [CameraLens.physicalCameraId] is
     * set) instead of the logical camera's own, per the same per-physical-lens reasoning
     * [manualIsoCapability] documents.
     */
    private fun characteristicsFor(lens: CameraLens?): CameraCharacteristics? {
        val physicalCameraId = lens?.physicalCameraId
        return try {
            if (physicalCameraId != null) {
                cameraManager.getCameraCharacteristics(physicalCameraId)
            } else {
                val logicalCameraId = lens?.logicalCameraId ?: defaultBackCameraId() ?: return null
                cameraManager.getCameraCharacteristics(logicalCameraId)
            }
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun defaultBackCameraId(): String? = cameraManager.cameraIdList
        .firstOrNull { cameraManager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }

    /**
     * Both `null` means the next still capture runs plain auto-exposure; either non-null fixes both
     * ISO and shutter speed together on that capture — per Camera2, `CONTROL_AE_MODE_OFF` fixes ISO
     * *and* exposure time simultaneously, there's no "ISO manual, shutter auto" mode (or vice versa).
     * Cached in [pendingManualIso]/[pendingManualShutterNs] so it's reapplied to the next still
     * capture regardless of lens rebinds in between — see [captureStillJpeg]. Also immediately
     * live-updates the preview's repeating request via [updatePreviewRepeating] (a no-op if no
     * session is open yet) so the viewfinder visually reflects every dial tick, capped to a
     * preview-safe exposure time — see [buildPreviewRequest] for why that's safe now in a way it
     * wasn't through CameraX's session-wide `CaptureRequestOptions`: this only ever touches the
     * preview's own repeating request, [captureStillJpeg]'s still-capture request is built completely
     * independently and is never affected by it.
     */
    fun setManualExposure(iso: Int?, shutterTimeNs: Long?) {
        pendingManualIso = iso
        pendingManualShutterNs = shutterTimeNs
        updatePreviewRepeating()
    }

    /**
     * Cached in [pendingAeCompensation] the same way manual exposure is cached above, and
     * immediately live-updates the preview's repeating request via [updatePreviewRepeating] so the
     * viewfinder reflects every EXPOSURE-dial tick. Harmless to call while manual mode is active —
     * [buildPreviewRequest]/[captureStillJpeg] only ever apply this in their `CONTROL_AE_MODE_ON`
     * branch, so it's simply unused (not cleared/reset) until auto-exposure is active again.
     */
    fun setExposureCompensation(value: Int) {
        pendingAeCompensation = value
        updatePreviewRepeating()
    }

    /**
     * See [zebraAnalysisEnabled]'s own doc — [previewImageAvailableListener] starts/stops classifying
     * frames immediately, no separate kick-off needed since frames are already flowing continuously.
     * Clears [_zebraMask] on the way to disabled so a stale mask from right before the drag ended
     * doesn't linger on screen — mirrors [closeCameraAndSessionLocked]'s own reset for the same reason.
     */
    fun setZebraAnalysisEnabled(enabled: Boolean) {
        if (zebraAnalysisEnabled == enabled) return
        zebraAnalysisEnabled = enabled
        if (!enabled) _zebraMask.value = null
    }

    /**
     * Resolves [iso]/[shutterTimeNs] for the still-capture request: whichever is null (i.e. that
     * parameter's stop list is currently empty for this lens, so its own dial has nothing to pin —
     * both dials are always visible now, but an unsupported/unaligned range can still leave one of
     * them without a value to contribute) falls back to [_autoExposureTimeNs]'s current value for
     * shutter or the range floor for ISO, then both are clamped to [capability]'s supported sensor
     * range, so engaging manual mode for one parameter doesn't itself cause a brightness jump from
     * whatever the other was left at.
     */
    private fun resolveManualExposure(iso: Int?, shutterTimeNs: Long?, capability: ManualIsoCapability): Pair<Int, Long> {
        val clampedIso = (iso ?: capability.isoRange.first)
            .coerceIn(capability.isoRange.first, capability.isoRange.last)
        val clampedShutterNs = (shutterTimeNs ?: _autoExposureTimeNs.value ?: capability.exposureTimeRange.first)
            .coerceIn(capability.exposureTimeRange.first, capability.exposureTimeRange.last)
        return clampedIso to clampedShutterNs
    }

    /**
     * `(sensorOrientation - surfaceRotationDegrees + 360) % 360`, the standard back-camera Camera2
     * `JPEG_ORIENTATION` formula (front cameras additionally mirror, not needed here since this app
     * only ever binds back lenses — see [listBackLenses]). [targetRotation] substitutes for a live
     * `Display.getRotation()` query since the activity is locked to portrait (see
     * [orientationEventListener]'s own doc).
     */
    private fun jpegOrientation(characteristics: CameraCharacteristics): Int {
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val surfaceRotationDegrees = when (targetRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return (sensorOrientation - surfaceRotationDegrees + 360) % 360
    }

    /**
     * Most multi-lens phones fuse ultra-wide/main/tele into one LOGICAL_MULTI_CAMERA logical
     * camera ID rather than exposing them as separate top-level IDs, so this walks each back
     * logical camera's [CameraCharacteristics.getPhysicalCameraIds] instead of just
     * [CameraManager.getCameraIdList]. Zoom ratio is each lens's *35mm-equivalent* focal length
     * (see [equivalentFocalLength]) relative to the median equivalent focal length among all back
     * lenses — main/wide is always the middle value between ultra-wide (shortest) and tele
     * (longest), which holds regardless of whether lenses are separate IDs or physical sub-cameras
     * of one logical ID.
     *
     * Some devices (many Samsung) list a physical sub-camera *both* ways — as its own top-level
     * ID in [CameraManager.getCameraIdList] and inside its logical camera's physicalCameraIds — so
     * a physical ID already covered by its own top-level entry is skipped here to avoid double-counting the same lens.
     *
     * Pixels additionally expose *virtual* 2x-crop IDs for smooth zoom transitions: same
     * [CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS] and
     * [CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE] as a real lens, but with
     * [CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE] reported at exactly half width/height —
     * i.e. same pixel count claimed on a synthetically smaller chip, which is not a distinct piece
     * of glass. Grouping candidates by (focal length, pixel array size) and keeping only the
     * largest-sensor-area entry per group below collapses those back into their real lens.
     */
    fun listBackLenses(): List<CameraLens> {
        val allCameraIds = cameraManager.cameraIdList.toSet()

        data class Candidate(
            val logicalCameraId: String,
            val physicalCameraId: String?,
            val focalLength: Float,
            val pixelArraySize: android.util.Size,
            val sensorAreaMm2: Float,
            val equivFocalLength: Float,
        )

        fun candidateOf(logicalCameraId: String, physicalCameraId: String?, characteristics: CameraCharacteristics): Candidate? {
            // Depth/mono auxiliary sensors can show up as physical sub-cameras too, but aren't a
            // normal photo lens — BACKWARD_COMPATIBLE is what guarantees a plain JPEG/YUV output.
            val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            if (CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE !in capabilities) return null

            val focalLength = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.minOrNull()
                ?: return null
            val pixelArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) ?: return null
            val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
            val equivFocalLength = equivalentFocalLength(focalLength, sensorSize) ?: return null
            return Candidate(
                logicalCameraId,
                physicalCameraId,
                focalLength,
                pixelArraySize,
                sensorAreaMm2 = sensorSize.width * sensorSize.height,
                equivFocalLength,
            )
        }

        val candidates = mutableListOf<Candidate>()
        for (logicalCameraId in allCameraIds) {
            val characteristics = cameraManager.getCameraCharacteristics(logicalCameraId)
            if (characteristics.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) {
                continue
            }

            val physicalCameraIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                characteristics.physicalCameraIds
            } else {
                emptySet()
            }

            if (physicalCameraIds.isEmpty()) {
                candidates += candidateOf(logicalCameraId, physicalCameraId = null, characteristics) ?: continue
            } else {
                for (physicalCameraId in physicalCameraIds) {
                    // Already (or will be) represented by its own top-level entry above/below.
                    if (physicalCameraId in allCameraIds) continue
                    val physicalCharacteristics = cameraManager.getCameraCharacteristics(physicalCameraId)
                    candidates += candidateOf(logicalCameraId, physicalCameraId, physicalCharacteristics) ?: continue
                }
            }
        }

        if (candidates.isEmpty()) return emptyList()

        // Some devices reference the same physical sensor from more than one logical camera ID
        // (e.g. separate "primary" and "assistant" multi-camera groupings), which the per-ID skip
        // above doesn't catch since it only compares against top-level IDs. Collapse by physical
        // sensor identity as a final pass — physicalCameraId when pinned, otherwise the camera's
        // own (unique) top-level ID.
        val dedupedById = candidates.distinctBy { it.physicalCameraId ?: it.logicalCameraId }

        // Collapse virtual 2x-crop duplicates: same focal length + pixel count, keep the one with
        // the larger (real) reported sensor area.
        val realLenses = dedupedById
            .groupBy { it.focalLength to it.pixelArraySize }
            .values
            .map { group -> group.maxBy { it.sensorAreaMm2 } }

        val mainEquivFocalLength = realLenses.map { it.equivFocalLength }.sorted().let { it[(it.size - 1) / 2] }

        return realLenses
            .map { CameraLens(it.logicalCameraId, it.physicalCameraId, zoomRatio = it.equivFocalLength / mainEquivFocalLength) }
            .sortedBy { it.zoomRatio }
    }

    /**
     * Raw [CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS] alone isn't comparable across
     * lens modules — a periscope telephoto's physical focal length can look deceptively close to
     * the main lens's because its sensor is much smaller, not because its real-world zoom is
     * similar. Normalizing by sensor size (the standard 35mm-equivalent focal length formula) makes
     * focal length comparable across modules with different sensor sizes.
     */
    private fun equivalentFocalLength(focalLength: Float, sensorSize: android.util.SizeF): Float? {
        val sensorDiagonalMm = sqrt(sensorSize.width * sensorSize.width + sensorSize.height * sensorSize.height)
        if (sensorDiagonalMm <= 0f) return null

        val fullFrameDiagonalMm = 43.27f // sqrt(36^2 + 24^2), the standard 35mm/full-frame reference
        return focalLength * (fullFrameDiagonalMm / sensorDiagonalMm)
    }

    /**
     * Issues the still-capture request and writes the resulting JPEG to `MediaStore`. While manual
     * exposure is active, the real (preview-uncapped) ISO/shutter the user selected is carried
     * directly on this one-off request via [resolveManualExposure] — completely independent of
     * whatever the live preview's repeating request is doing (see this class's own doc for why that
     * decoupling is the whole point of the Camera2 migration).
     */
    suspend fun takePhoto(): Uri {
        val device = checkNotNull(cameraDevice) { "Camera not bound yet" }
        val session = checkNotNull(captureSession) { "Camera not bound yet" }
        val reader = checkNotNull(imageReader) { "Camera not bound yet" }
        val characteristics = characteristicsFor(currentLens)
            ?: throw IllegalStateException("No CameraCharacteristics available for the bound lens")

        val bytes = captureStillJpeg(device, session, reader, characteristics)
        return withContext(Dispatchers.IO) { saveJpegToMediaStore(bytes) }
    }

    private suspend fun captureStillJpeg(
        device: CameraDevice,
        session: CameraCaptureSession,
        reader: ImageReader,
        characteristics: CameraCharacteristics,
    ): ByteArray = suspendCancellableCoroutine { continuation ->
        pendingCapture = continuation
        continuation.invokeOnCancellation { if (pendingCapture === continuation) pendingCapture = null }

        val manualCapability = if (pendingManualIso != null || pendingManualShutterNs != null) {
            manualIsoCapability(currentLens)
        } else {
            null
        }

        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(reader.surface)
            set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation(characteristics))
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)

            if (manualCapability != null) {
                val (iso, shutterNs) = resolveManualExposure(pendingManualIso, pendingManualShutterNs, manualCapability)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterNs)
            } else {
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, pendingAeCompensation)
            }

            applyFocusSettings(this)

            set(
                CaptureRequest.FLASH_MODE,
                if (pendingFlashMode == FlashMode.ON) CaptureRequest.FLASH_MODE_SINGLE else CaptureRequest.FLASH_MODE_OFF,
            )
        }.build()

        try {
            session.capture(request, null, backgroundHandler)
        } catch (e: CameraAccessException) {
            pendingCapture = null
            continuation.resumeWithException(e)
        } catch (e: IllegalStateException) {
            pendingCapture = null
            continuation.resumeWithException(e)
        }
    }

    private fun saveJpegToMediaStore(bytes: ByteArray): Uri {
        val name = "xCamera_${System.currentTimeMillis()}.jpg"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // DCIM/Camera is the same album the stock camera app writes to, so shots land in
                // the main gallery/camera roll instead of a separate xCamera-only album.
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Camera")
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw IllegalStateException("MediaStore insert failed")
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: throw IllegalStateException("Couldn't open an output stream for $uri")
        return uri
    }

    /**
     * The most recent photo in the device's gallery (not just ones this app took) — backs the
     * viewfinder's thumbnail chip. Without the gallery-read permission granted (see
     * `CameraScreen`'s soft, independent request for it), scoped storage silently narrows this
     * query down to only this app's own MediaStore rows rather than throwing.
     */
    suspend fun latestGalleryPhotoUri(): Uri? = withContext(Dispatchers.IO) {
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
            ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        }
    }

    private companion object {
        /** Bounded wait for [CameraDevice.StateCallback.onClosed] in [closeCameraAndSessionLocked] —
         *  generous relative to how fast a real close normally completes, just there so a device that
         *  never calls back can't wedge every future reopen behind an unbounded await. */
        const val CameraCloseTimeoutMs = 1_500L

        /** Preview stream resolution cap — plenty for a full-screen viewfinder, keeps frame cost down. */
        const val MaxPreviewDimension = 1920

        /** Preview stream resolution floor — see [previewOutputSize]'s own doc for why a floor matters
         *  just as much as the cap. Comfortably above every non-preview (video-call/analysis-class)
         *  size real devices tend to also list alongside genuine preview sizes. */
        const val MinPreviewDimension = 720

        val FallbackPreviewSize = Size(1920, 1080)
        val FallbackStillSize = Size(1920, 1080)

        /**
         * Ceiling on the exposure time pushed to the *live preview's* repeating request while manual
         * mode is active, regardless of how long a shutter speed the user has actually dragged to —
         * see [buildPreviewRequest]. Once `CONTROL_AE_MODE_OFF` is set, each preview frame's duration
         * *is* the configured `SENSOR_EXPOSURE_TIME`; pushing an 8s exposure straight to the repeating
         * request would drop the viewfinder to ~0.125fps and leave it visibly frozen, since the sensor
         * has to finish reading out whatever long-exposure frames were already queued before a fresh
         * fast one can land. 1/15s keeps manual-mode preview comfortably fluid (a frame rate a dim-
         * light *auto*-exposure preview already commonly runs at) while still long enough that the
         * `SENSOR_SENSITIVITY` compensation needed to match brightness rarely needs to leave a
         * flagship sensor's usable ISO range. Unlike the pre-Camera2-migration version of this same
         * mechanism, this only ever affects the preview's own repeating request — the still capture in
         * [captureStillJpeg] always uses the real, uncapped selected shutter speed, with no shared
         * Camera2-level state between the two requests.
         */
        const val PreviewMaxExposureTimeNs = 1_000_000_000L / 15

        /** Grid dimensions [ZebraMask.fromLumaPlane] buckets the analysis frame into — matches the
         *  reference implementation's own coarse/blocky (not per-pixel) clipping mask. */
        const val ZebraGridColumns = 24
        const val ZebraGridRows = 32

        /** Floor between real [ZebraMask] recomputations — see [classifyZebraIfDue]. */
        const val ZebraThrottleMs = 66L
    }
}
