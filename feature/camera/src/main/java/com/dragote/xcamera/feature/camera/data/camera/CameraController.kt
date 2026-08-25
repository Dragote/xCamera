package com.dragote.xcamera.feature.camera.data.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.dragote.xcamera.feature.camera.domain.model.ActiveLut
import com.dragote.xcamera.feature.camera.domain.model.AfConvergenceState
import com.dragote.xcamera.feature.camera.domain.model.FlashMode
import com.dragote.xcamera.feature.camera.domain.model.HistogramData
import com.dragote.xcamera.feature.camera.domain.model.ZebraMask
import com.dragote.xcamera.shared.common.domain.model.CubeLut
import com.dragote.xcamera.shared.diagnostics.data.LensEnumerator
import com.dragote.xcamera.shared.diagnostics.data.rawCaptureCapabilityFrom
import com.dragote.xcamera.shared.diagnostics.domain.model.AeCompensationCapability
import com.dragote.xcamera.shared.diagnostics.domain.model.LensSnapshot
import com.dragote.xcamera.shared.diagnostics.domain.model.ManualFocusCapability
import com.dragote.xcamera.shared.diagnostics.domain.model.ManualIsoCapability
import com.dragote.xcamera.shared.diagnostics.domain.model.RawCaptureCapability
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import javax.inject.Singleton

/**
 * Raw `android.hardware.camera2` wrapper: opens a [CameraDevice] and configures one
 * [CameraCaptureSession] with two independent output surfaces — a private preview
 * [android.media.ImageReader] ([previewImageReader], `YUV_420_888`) and a private still
 * [android.media.ImageReader] ([imageReader], JPEG) — driven by genuinely separate Camera2 requests:
 * a live-updatable [CameraCaptureSession.setRepeatingRequest] for the preview reader (see
 * [PreviewRequestController.buildPreviewRequest]), and a one-off [CameraCaptureSession.capture] for
 * the still reader (see [StillCaptureController.takePhoto]) that carries the real, preview-uncapped
 * user-selected manual exposure when manual mode is active. The two share no mutable request state —
 * they only both read the same pending manual ISO/shutter cache owned by [previewRequestController].
 *
 * This class owns only the device/session lifecycle (open/close, lens resolution, output-reader
 * creation) — see this package's other files for the collaborators it composes:
 * [PreviewRequestController] (preview repeating request + manual exposure/focus/AF state machine),
 * [FrameAnalyzer] (zebra/histogram live analysis), [StillCaptureController] (JPEG/RAW capture +
 * MediaStore save), [DeviceOrientationTracker] (physical device rotation for `JPEG_ORIENTATION`),
 * `shared:diagnostics`' [LensEnumerator] (lens discovery/zoom-ratio computation), and its pure
 * capability-query functions in `CameraCapabilityChecks.kt`.
 *
 * The live preview is *rendered*, not just captured, by this class's own surrounding infrastructure:
 * [setPreviewFrameListener] hands each delivered preview [Image] to a caller-supplied listener/
 * [Handler] (in practice, `ui/CameraScreen`'s `CameraPreviewRenderer`, which draws it onto the
 * on-screen `TextureView` via app-owned GLES) — an `ImageReader`'s dimensions are a hard, verifiable
 * construction-time contract, unlike a `SurfaceTexture`'s requested buffer size (see
 * `docs/features/camera-capture.md` for the rationale).
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

    private val orientationTracker = DeviceOrientationTracker(context)
    private val frameAnalyzer = FrameAnalyzer()
    private val lensEnumerator = LensEnumerator(cameraManager)
    private val previewRequestController = PreviewRequestController(
        scope = controllerScope,
        characteristicsFor = ::characteristicsFor,
        onRepeatingRequestNeedsRefresh = ::updatePreviewRepeating,
    )
    private val stillCaptureController = StillCaptureController(
        context = context,
        previewRequestController = previewRequestController,
        targetRotationDegrees = { orientationTracker.targetRotation },
    )

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

    /**
     * The `RAW_SENSOR` output for a "with RAW" still capture — `null` whenever the currently bound
     * lens either doesn't report [CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW] at all
     * (see [rawCaptureCapability]) or the 3-surface (preview + JPEG + RAW) session configuration
     * wasn't actually verified supported via [CameraDevice.isSessionConfigurationSupported] when the
     * session was opened (see [openCamera]/[createCaptureSession]) — either way, a lens reporting the
     * `RAW` capability alone doesn't guarantee this is non-null. `maxImages = 1`: a single
     * uncompressed `RAW_SENSOR` buffer is already ~20-50MB, and only ever one still capture is in
     * flight at a time (the shutter is disabled while a capture is already running).
     */
    private var rawImageReader: ImageReader? = null

    /** Cached from [bindCamera]'s parameters — the `TextureView`'s own measured pixel size, used to
     *  compute [previewOutputSize] for the preview [ImageReader]. */
    private var previewViewWidth = 0
    private var previewViewHeight = 0

    /**
     * Set once by the first [bindCamera] call and cleared by [unbindCamera] — the signal
     * [onStateChanged]/[openCamera] gate on to know there's something to (re)bind.
     */
    private var isBound = false

    /**
     * Completed by [CameraDevice.StateCallback.onClosed] for whichever device [openCameraDevice] most
     * recently opened — `CameraDevice.close()`/`CameraCaptureSession.close()` (see
     * [closeCameraAndSessionLocked]) are asynchronous, the call returning immediately while teardown
     * continues on the HAL/driver side; without waiting for the real `onClosed()` signal, a reopen of
     * the *same* camera ID (the only path that can happen — see [openCamera]'s own doc) can race an
     * incompletely-released previous instance of that ID, a known Camera2 pitfall (Google's own
     * `Camera2Basic` sample explicitly gates its next open behind exactly this kind of wait). Captured
     * as a local inside [openCameraDevice] and assigned here so the specific device instance that
     * eventually calls back into it is unambiguous even if a *newer* open has already replaced this
     * field by the time an old device's `onClosed` fires (shouldn't happen given [cameraLock]
     * serializes opens after this field's own await completes, but the local capture makes that not
     * load-bearing for correctness).
     */
    private var deviceClosedSignal: CompletableDeferred<Unit>? = null

    private var currentLens: LensSnapshot? = null
    private var boundLifecycle: Lifecycle? = null

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

    /**
     * [lens] is null for the plain default back camera. When non-null, [LensSnapshot.physicalCameraId]
     * (if set) is pinned per output surface via [OutputConfiguration.setPhysicalCameraId] — most
     * multi-lens phones expose their extra lenses as physical sub-cameras of one logical camera
     * rather than as separate top-level camera IDs.
     *
     * [previewViewWidth]/[previewViewHeight] are the on-screen `TextureView`'s own measured pixel
     * dimensions — this class computes/owns the actual preview [ImageReader] ([previewImageReader])
     * internally from them (see [previewOutputSize]/[createPreviewImageReader]). Register a frame
     * consumer via [setPreviewFrameListener] separately to actually see any pixels.
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
        lens: LensSnapshot? = null,
    ) {
        ensureBackgroundThread()
        isBound = true
        // See PreviewRequestController.resetForLensSwitch's own doc for why this only happens on an
        // actual lens change.
        if (currentLens != lens) {
            previewRequestController.resetForLensSwitch()
        }
        currentLens = lens
        this.previewViewWidth = previewViewWidth
        this.previewViewHeight = previewViewHeight

        val lifecycle = lifecycleOwner.lifecycle
        if (boundLifecycle !== lifecycle) {
            boundLifecycle?.removeObserver(this)
            boundLifecycle = lifecycle
            lifecycle.addObserver(this)
        }

        orientationTracker.ensureListening()

        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            openCamera()
        }
    }

    /**
     * Call once whatever [bindCamera] was backing is no longer valid (e.g. `ui/CameraScreen`'s
     * `onSurfaceTextureDestroyed`, once its `CameraPreviewRenderer` has been stopped). Clears
     * [isBound] (so a lifecycle event arriving mid-teardown can't resurrect the session — see
     * [onStateChanged]) and the registered preview frame listener (so no further frames get handed to
     * a renderer that's going away), then closes the device/session/readers the same way
     * [closeCameraAndSession] does.
     */
    fun unbindCamera() {
        boundLifecycle?.removeObserver(this)
        boundLifecycle = null
        isBound = false
        frameAnalyzer.setFrameListener(null, null)
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

        val previewReader = createPreviewImageReader()
        previewImageReader = previewReader
        frameAnalyzer.setRotationDegrees(effectiveCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0)

        // A lens reporting RAW at all (see rawCaptureCapability's own doc) is only the first gate —
        // whether the 3-surface session it needs is actually configurable on this hardware is verified
        // below in createCaptureSession, per-lens, every time openCamera runs (i.e. on every lens
        // switch, not just once at app start).
        val rawCandidateReader = rawCaptureCapability(currentLens)?.let { createRawImageReader(effectiveCharacteristics) }

        val (session, rawIncluded) = try {
            createCaptureSession(device, previewReader.surface, reader.surface, rawCandidateReader?.surface, currentLens)
        } catch (e: CameraAccessException) {
            device.close()
            cameraDevice = null
            reader.close()
            imageReader = null
            previewReader.close()
            previewImageReader = null
            rawCandidateReader?.close()
            return@withLock
        } catch (e: IllegalStateException) {
            device.close()
            cameraDevice = null
            reader.close()
            imageReader = null
            previewReader.close()
            previewImageReader = null
            rawCandidateReader?.close()
            return@withLock
        }
        captureSession = session
        // See rawCandidateReader's own local doc above — only actually kept (and its buffers held)
        // once the session negotiation above confirmed the 3-surface configuration genuinely
        // configured, otherwise it's released immediately rather than sitting on an idle ~20-50MB
        // buffer no capture will ever target.
        if (rawIncluded) {
            rawImageReader = rawCandidateReader
        } else {
            rawCandidateReader?.close()
            rawImageReader = null
        }
        previewRequestController.startPreviewRepeating(device, session, previewReader.surface, currentLens, backgroundHandler)
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

    /**
     * Returns the opened session alongside whether [rawSurface] actually ended up part of it — a
     * lens reporting the `RAW` capability (see [rawCaptureCapability]) doesn't by itself guarantee
     * Camera2 can genuinely configure a 3-surface (preview + JPEG + `RAW_SENSOR`) session on this
     * hardware, so [rawSurface] non-`null` is only ever a *candidate*, verified via
     * [CameraDevice.isSessionConfigurationSupported] before being included — on any failure of that
     * check (including it not being available at all below API 29, or a HAL that throws
     * [UnsupportedOperationException] for it), this falls back to the plain 2-surface session, i.e.
     * treats RAW as unavailable for this bind rather than attempting a configuration that was never
     * confirmed and risking [onConfigureFailed] tearing down the *whole* session (preview included)
     * over it. Below API 28 ([OutputConfiguration]/[SessionConfiguration] themselves unavailable), RAW
     * is never attempted at all, for the same reason.
     */
    private suspend fun createCaptureSession(
        device: CameraDevice,
        previewSurface: Surface,
        stillSurface: Surface,
        rawSurface: Surface?,
        lens: LensSnapshot?,
    ): Pair<CameraCaptureSession, Boolean> {
        fun outputConfigFor(surface: Surface) = OutputConfiguration(surface).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lens?.physicalCameraId?.let { setPhysicalCameraId(it) }
            }
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            val session = suspendCancellableCoroutine { continuation ->
                @Suppress("DEPRECATION")
                device.createCaptureSession(
                    listOf(previewSurface, stillSurface),
                    sessionStateCallback(continuation),
                    backgroundHandler,
                )
            }
            return session to false
        }

        val baseOutputs = listOf(outputConfigFor(previewSurface), outputConfigFor(stillSurface))
        val rawIncluded = rawSurface != null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            isRawSessionConfigurationSupported(device, baseOutputs + outputConfigFor(rawSurface))
        val outputs = if (rawIncluded) baseOutputs + outputConfigFor(rawSurface!!) else baseOutputs

        val session = suspendCancellableCoroutine { continuation ->
            val sessionConfiguration = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputs,
                ContextCompat.getMainExecutor(context),
                sessionStateCallback(continuation),
            )
            device.createCaptureSession(sessionConfiguration)
        }
        return session to rawIncluded
    }

    private fun sessionStateCallback(
        continuation: CancellableContinuation<CameraCaptureSession>,
    ) = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) {
            if (continuation.isActive) continuation.resume(session)
        }

        override fun onConfigureFailed(session: CameraCaptureSession) {
            if (continuation.isActive) {
                continuation.resumeWithException(IllegalStateException("Capture session configuration failed"))
            }
        }
    }

    /**
     * The actual "is a 3-surface RAW session supported" check — a static feasibility query, not a
     * real session attempt, so the [CameraCaptureSession.StateCallback] it's constructed
     * with is never invoked for this call. Caught broadly (relevant undocumented failure modes vary
     * by OEM HAL): [CameraAccessException]/[UnsupportedOperationException]/[IllegalArgumentException]
     * all fall back to "not supported" rather than propagating and failing the *whole* session open —
     * see [createCaptureSession]'s own doc for why that fallback matters.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun isRawSessionConfigurationSupported(device: CameraDevice, outputs: List<OutputConfiguration>): Boolean {
        val noOpCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) = Unit
            override fun onConfigureFailed(session: CameraCaptureSession) = Unit
        }
        val candidateConfiguration = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            outputs,
            ContextCompat.getMainExecutor(context),
            noOpCallback,
        )
        return try {
            device.isSessionConfigurationSupported(candidateConfiguration)
        } catch (e: CameraAccessException) {
            false
        } catch (e: UnsupportedOperationException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    /**
     * Live-updates the already-running preview repeating request to reflect the current manual
     * exposure/focus/AF state — passed into [previewRequestController] as its
     * `onRepeatingRequestNeedsRefresh` callback, called on every pending-state change. A no-op if no
     * session is open yet (e.g. a manual-exposure setter called from the ViewModel before
     * [bindCamera]/[openCamera] has completed) or if it races a concurrent close/reopen; either way
     * the next [openCamera] call picks up the current pending state fresh regardless, so there's
     * nothing to recover here.
     */
    private fun updatePreviewRepeating() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val surface = previewImageReader?.surface ?: return
        try {
            previewRequestController.startPreviewRepeating(device, session, surface, currentLens, backgroundHandler)
        } catch (e: CameraAccessException) {
            // Session/device is mid-teardown — ignore, a fresh openCamera() will reflect current state.
        } catch (e: IllegalStateException) {
            // Session already closed — same as above.
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
        closeImageReaders()
        stillCaptureController.onSessionClosed()
        previewRequestController.resetConvergenceState()
        frameAnalyzer.reset()
        if (hadDevice) {
            withTimeoutOrNull(CameraCloseTimeoutMs) { deviceClosedSignal?.await() }
        }
    }

    /**
     * Closes [imageReader]/[previewImageReader]/[rawImageReader] as a task run on
     * [backgroundHandler]'s own `Looper` instead of directly on this (main-thread) coroutine — every
     * `OnImageAvailableListener` registered on these readers ([FrameAnalyzer.imageAvailableListener],
     * [StillCaptureController.imageAvailableListener]/`rawImageAvailableListener`) is also delivered on
     * that same `Looper`, so posting the close there rather than calling it from another thread means it
     * can only ever run before a queued callback starts or after one finishes, never *during* one — a
     * `Looper` processes one message at a time. See `docs/features/camera-capture.md`'s key decisions for
     * why calling `close()` from a different thread was previously able to invalidate a buffer a callback
     * was mid-read on. Bounded by [ImageReaderCloseTimeoutMs] as a safety net (the close itself is a
     * near-instant native call) rather than the expected path, so a wedged background thread can't hang
     * every future rebind. Fields are nulled synchronously, before the actual `close()` runs, so nothing
     * else on the main thread can observe a reader that's about to become invalid.
     */
    private suspend fun closeImageReaders() {
        val readers = listOfNotNull(imageReader, previewImageReader, rawImageReader)
        imageReader = null
        previewImageReader = null
        rawImageReader = null
        if (readers.isEmpty()) return

        val handler = backgroundHandler
        if (handler == null) {
            readers.forEach { it.close() }
            return
        }

        val closed = CompletableDeferred<Unit>()
        val posted = handler.post {
            readers.forEach { it.close() }
            closed.complete(Unit)
        }
        if (!posted) {
            readers.forEach { it.close() }
            return
        }
        withTimeoutOrNull(ImageReaderCloseTimeoutMs) { closed.await() }
    }

    private fun resolveLogicalCameraId(lens: LensSnapshot?): String? = lens?.logicalCameraId ?: defaultBackCameraId()

    private fun createImageReader(characteristics: CameraCharacteristics): ImageReader {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val jpegSize = map?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width.toLong() * it.height }
            ?: FallbackStillSize
        return ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2).apply {
            setOnImageAvailableListener(stillCaptureController.imageAvailableListener, backgroundHandler)
        }
    }

    /**
     * The live preview's own output — see this class's own doc for why this is an `ImageReader`
     * rather than a caller-supplied `Surface`. `maxImages = 2` mirrors [createImageReader]'s still
     * reader; a live preview only ever wants the latest frame, so [FrameAnalyzer.imageAvailableListener]
     * always calls `acquireLatestImage`, never queuing.
     */
    private fun createPreviewImageReader(): ImageReader {
        val size = previewOutputSize(currentLens, previewViewWidth, previewViewHeight)
        return ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2).apply {
            setOnImageAvailableListener(frameAnalyzer.imageAvailableListener, backgroundHandler)
        }
    }

    /**
     * The candidate `RAW_SENSOR` output for a "with RAW" still capture — only ever a *candidate*,
     * see [rawImageReader]'s own doc for why the caller must still confirm the session
     * negotiation in [createCaptureSession] actually included it before relying on it. `null` if this
     * lens's [CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP] reports no `RAW_SENSOR` output
     * sizes at all — shouldn't happen for a lens [rawCaptureCapability] already confirmed reports the
     * `RAW` capability, but this stays defensive rather than assuming the two characteristics always
     * agree. `maxImages = 1` — see [rawImageReader]'s own doc.
     */
    private fun createRawImageReader(characteristics: CameraCharacteristics): ImageReader? {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val rawSize = map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height } ?: return null
        return ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 1).apply {
            setOnImageAvailableListener(stillCaptureController.rawImageAvailableListener, backgroundHandler)
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
     * transform makes the same swap assumption on the consuming side. Internal-only, used solely by
     * [createPreviewImageReader].
     */
    private fun previewOutputSize(lens: LensSnapshot?, targetWidth: Int, targetHeight: Int): Size {
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
     * preview [Image] — see [FrameAnalyzer.setFrameListener]. `ui/CameraScreen` calls this with its
     * `CameraPreviewRenderer`'s own [handler]/[Handler]-bound `onPreviewFrame` before (or independently
     * of) [bindCamera] — registration and binding aren't ordered relative to each other, frames simply
     * have nowhere to go (closed immediately) until both are in place.
     */
    fun setPreviewFrameListener(handler: Handler?, listener: ((Image) -> Unit)?) {
        frameAnalyzer.setFrameListener(handler, listener)
    }

    /**
     * Pure `CameraCharacteristics.SENSOR_ORIENTATION` lookup for [lens] — `CameraPreviewRenderer`
     * needs this to rotate raw preview frames into display orientation itself, the same reason
     * [FrameAnalyzer]'s own rotation tracking exists for zebra (an `ImageReader` surface gets no
     * automatic producer-side rotation the way a `TextureView`'s own on-screen `SurfaceTexture` used
     * to). `0` if unavailable — same fallback [FrameAnalyzer] defaults to.
     */
    fun previewRotationDegrees(lens: LensSnapshot?): Int =
        characteristicsFor(lens)?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

    fun setFlashMode(flashMode: FlashMode) = stillCaptureController.setFlashMode(flashMode)

    /**
     * `null` for [lens] queries the plain default back camera, mirroring [bindCamera]'s own
     * convention. `null` return means "hide/disable manual ISO for this lens" — see
     * [manualIsoCapabilityFrom]'s own doc for the underlying [CameraCharacteristics] gate.
     */
    fun manualIsoCapability(lens: LensSnapshot?): ManualIsoCapability? = previewRequestController.manualIsoCapability(lens)

    /** See [aeCompensationCapabilityFrom]'s own doc. */
    fun aeCompensationCapability(lens: LensSnapshot?): AeCompensationCapability? =
        previewRequestController.aeCompensationCapability(lens)

    /** See [manualFocusCapabilityFrom]'s own doc. */
    fun manualFocusCapability(lens: LensSnapshot?): ManualFocusCapability? = previewRequestController.manualFocusCapability(lens)

    /** See [rawCaptureCapabilityFrom]'s own doc. */
    fun rawCaptureCapability(lens: LensSnapshot?): RawCaptureCapability? =
        characteristicsFor(lens)?.let(::rawCaptureCapabilityFrom)

    /** See [PreviewRequestController.triggerAutoFocus]'s own doc — [device]/[session]/[surface] are
     *  supplied here (this class is the one that knows whether a session is currently open); a no-op
     *  before a session is actually open. */
    fun triggerAutoFocus(displayXFraction: Float, displayYFraction: Float) {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val surface = previewImageReader?.surface ?: return
        previewRequestController.triggerAutoFocus(
            displayXFraction = displayXFraction,
            displayYFraction = displayYFraction,
            device = device,
            session = session,
            surface = surface,
            lens = currentLens,
            rotationDegrees = frameAnalyzer.rotationDegrees,
            handler = backgroundHandler,
        )
    }

    val autoIso: StateFlow<Int?> get() = previewRequestController.autoIso
    val autoExposureTimeNs: StateFlow<Long?> get() = previewRequestController.autoExposureTimeNs
    val autoFocusDistanceDiopters: StateFlow<Float?> get() = previewRequestController.autoFocusDistanceDiopters
    val afConvergenceState: StateFlow<AfConvergenceState?> get() = previewRequestController.afConvergenceState

    fun setManualFocusDistance(distanceDiopters: Float?) = previewRequestController.setManualFocusDistance(distanceDiopters)

    fun setManualExposure(iso: Int?, shutterTimeNs: Long?) = previewRequestController.setManualExposure(iso, shutterTimeNs)

    fun setExposureCompensation(value: Int) = previewRequestController.setExposureCompensation(value)

    fun setZebraAnalysisEnabled(enabled: Boolean) = frameAnalyzer.setZebraAnalysisEnabled(enabled)

    val zebraMask: StateFlow<ZebraMask?> get() = frameAnalyzer.zebraMask
    val histogramData: StateFlow<HistogramData?> get() = frameAnalyzer.histogramData

    fun setLut(lutId: String?, cubeLut: CubeLut?, intensityPercent: Int) =
        stillCaptureController.setLut(lutId, cubeLut, intensityPercent)

    val activeLut: StateFlow<ActiveLut?> get() = stillCaptureController.activeLut

    /**
     * Shared by [manualIsoCapability], still-capture orientation/exposure resolution, and preview/
     * still surface sizing — physical-lens characteristics (when [LensSnapshot.physicalCameraId] is
     * set) instead of the logical camera's own, per the same per-physical-lens reasoning
     * [manualIsoCapability] documents.
     */
    private fun characteristicsFor(lens: LensSnapshot?): CameraCharacteristics? {
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
     * Issues the still-capture request and writes the resulting JPEG (and, when [includeRaw],
     * additionally a `.dng`) to `MediaStore` — see [StillCaptureController.takePhoto]'s own doc for the
     * capture/save mechanics. This wrapper's own job is just the "is a camera actually bound"
     * precondition (the checks below) and handing [StillCaptureController] the currently-open
     * device/session/readers it needs, which only [CameraController] itself tracks the lifecycle of.
     */
    suspend fun takePhoto(includeRaw: Boolean): Uri {
        val device = checkNotNull(cameraDevice) { "Camera not bound yet" }
        val session = checkNotNull(captureSession) { "Camera not bound yet" }
        val reader = checkNotNull(imageReader) { "Camera not bound yet" }
        val characteristics = characteristicsFor(currentLens)
            ?: throw IllegalStateException("No CameraCharacteristics available for the bound lens")
        return stillCaptureController.takePhoto(
            device = device,
            session = session,
            reader = reader,
            rawReader = rawImageReader,
            characteristics = characteristics,
            lens = currentLens,
            includeRaw = includeRaw,
            handler = backgroundHandler,
        )
    }

    /** See [LensEnumerator.listBackLenses]'s own doc. */
    fun listBackLenses(): List<LensSnapshot> = lensEnumerator.listBackLenses().map { it.snapshot }

    /** Call when the composable hosting this controller leaves composition. */
    fun stopOrientationListener() = orientationTracker.stopListening()

    /** See [StillCaptureController.latestGalleryPhotoUri]'s own doc. */
    suspend fun latestGalleryPhotoUri(): Uri? = stillCaptureController.latestGalleryPhotoUri()

    private companion object {
        /** Bounded wait for [CameraDevice.StateCallback.onClosed] in [closeCameraAndSessionLocked] —
         *  generous relative to how fast a real close normally completes, just there so a device that
         *  never calls back can't wedge every future reopen behind an unbounded await. */
        const val CameraCloseTimeoutMs = 1_500L

        /** Bounded wait for [closeImageReaders]'s posted close task to run on [backgroundHandler] —
         *  see that function's own doc; generous relative to how fast closing an `ImageReader` actually
         *  takes, just there so a wedged background thread can't wedge every future rebind. */
        const val ImageReaderCloseTimeoutMs = 500L

        /** Preview stream resolution cap — plenty for a full-screen viewfinder, keeps frame cost down. */
        const val MaxPreviewDimension = 1920

        /** Preview stream resolution floor — see [previewOutputSize]'s own doc for why a floor matters
         *  just as much as the cap. Comfortably above every non-preview (video-call/analysis-class)
         *  size real devices tend to also list alongside genuine preview sizes. */
        const val MinPreviewDimension = 720

        val FallbackPreviewSize = Size(1920, 1080)
        val FallbackStillSize = Size(1920, 1080)
    }
}
