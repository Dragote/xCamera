package com.dragote.xcamera.feature.camera.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.dragote.xcamera.feature.camera.domain.model.CameraLens
import com.dragote.xcamera.feature.camera.domain.model.FlashMode
import com.dragote.xcamera.feature.camera.domain.model.ManualIsoCapability
import kotlinx.coroutines.CancellableContinuation
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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import javax.inject.Singleton

/**
 * Raw `android.hardware.camera2` wrapper: opens a [CameraDevice], configures one
 * [CameraCaptureSession] with two independent output surfaces (the live preview surface handed to
 * [bindCamera], and a private [android.media.ImageReader] for JPEG stills), and issues genuinely
 * separate Camera2 requests against each — a live-updatable [CameraCaptureSession
 * .setRepeatingRequest] for the preview surface (see [buildPreviewRequest]), and a one-off
 * [CameraCaptureSession.capture] for the still surface (see [captureStillJpeg]) that carries the
 * real, preview-uncapped user-selected manual exposure when manual mode is active. The two share no
 * mutable request state — they only both read the same [pendingManualIso]/[pendingManualShutterNs]
 * cache and independently resolve/clamp it via [resolveManualExposure].
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
 * Kept out of the ViewModel since [bindCamera] inherently needs a Compose `LifecycleOwner` + a raw
 * preview `Surface`, which are ui-layer-adjacent types — see CLAUDE.md's data-layer-owns-hardware
 * convention.
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

    private var previewSurface: Surface? = null
    private var currentLens: CameraLens? = null
    private var boundLifecycle: Lifecycle? = null

    private var pendingFlashMode: FlashMode = FlashMode.OFF

    /** Cached the same way [pendingFlashMode] is, so it survives a rebind (e.g. a lens switch). */
    private var pendingManualIso: Int? = null

    /** Cached alongside [pendingManualIso] for the same rebind-survival reason. */
    private var pendingManualShutterNs: Long? = null

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
     * While manual mode is active ([pendingManualIso]/[pendingManualShutterNs] non-null), the
     * preview's repeating request carries a forced capped-manual exposure (see
     * [buildPreviewRequest]), not a genuine AE convergence value — so this must skip updating
     * [_autoIso]/[_autoExposureTimeNs] in that case, exactly as it did before live manual preview
     * feedback was reintroduced, otherwise the ISO/shutter dials' live auto-tracking would both get
     * fed a bogus "auto" value that's actually just whatever the preview cap forced.
     */
    private val previewCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            if (pendingManualIso != null || pendingManualShutterNs != null) return
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { _autoExposureTimeNs.value = it }
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { _autoIso.value = it }
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
     * [surface] must already have its backing buffer sized (e.g. via [SurfaceTexture
     * .setDefaultBufferSize]) to whatever [previewOutputSize] returned for [lens] — this function
     * configures the capture session against the surface as handed to it, it does not resize it.
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
        surface: Surface,
        lens: CameraLens? = null,
    ) {
        ensureBackgroundThread()
        previewSurface = surface
        currentLens = lens

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

    /** Call once the preview surface backing a previous [bindCamera] call is no longer valid. */
    fun unbindCamera() {
        boundLifecycle?.removeObserver(this)
        boundLifecycle = null
        previewSurface = null
        // Close (and only then stop the background thread its callbacks run on) under cameraLock —
        // see closeCameraAndSession's own doc for why serializing against an in-flight openCamera
        // matters here.
        controllerScope.launch {
            cameraLock.withLock { closeCameraAndSessionLocked() }
            stopBackgroundThread()
        }
    }

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_START -> {
                if (previewSurface != null) controllerScope.launch { openCamera() }
            }
            Lifecycle.Event.ON_STOP -> closeCameraAndSession()
            Lifecycle.Event.ON_DESTROY -> unbindCamera()
            else -> Unit
        }
    }

    private suspend fun openCamera() = cameraLock.withLock {
        val surface = previewSurface ?: return@withLock
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

        val session = try {
            createCaptureSession(device, surface, reader.surface, currentLens)
        } catch (e: CameraAccessException) {
            device.close()
            cameraDevice = null
            reader.close()
            imageReader = null
            return@withLock
        } catch (e: IllegalStateException) {
            device.close()
            cameraDevice = null
            reader.close()
            imageReader = null
            return@withLock
        }
        captureSession = session
        startPreviewRepeating(device, session, surface)
    }

    @Suppress("MissingPermission") // CAMERA permission is gated by ui/CameraScreen before bindCamera is ever called.
    private suspend fun openCameraDevice(cameraId: String): CameraDevice =
        suspendCancellableCoroutine { continuation ->
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
        val surface = previewSurface ?: return
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
        }

        return builder.build()
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

    private fun closeCameraAndSessionLocked() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        _autoExposureTimeNs.value = null
        _autoIso.value = null
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
     * Picks the [android.hardware.camera2.params.StreamConfigurationMap]-supported `SurfaceTexture`
     * preview size closest in aspect ratio to a [targetWidth]x[targetHeight] view without being
     * needlessly larger than the viewfinder actually needs (keeps sensor readout/preview frame cost
     * down). [targetWidth]/[targetHeight] are the *view's* own pixel dimensions (portrait, since the
     * activity is locked portrait); output sizes from [CameraCharacteristics] are expressed in the
     * sensor's own native pixel-array coordinate convention, which for essentially every phone's back
     * camera is landscape (a physically-rotated sensor) regardless of how the device is held — so
     * matching is done against the width/height-swapped target. `ui/CameraScreen`'s own preview
     * transform makes the same swap assumption on the consuming side.
     */
    fun previewOutputSize(lens: CameraLens?, targetWidth: Int, targetHeight: Int): Size {
        if (targetWidth <= 0 || targetHeight <= 0) return FallbackPreviewSize
        val characteristics = characteristicsFor(lens) ?: return FallbackPreviewSize
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return FallbackPreviewSize
        val candidates = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        if (candidates.isEmpty()) return FallbackPreviewSize

        val targetAspect = targetWidth.toFloat() / targetHeight.toFloat()
        val withinCap = candidates.filter { it.width <= MaxPreviewDimension && it.height <= MaxPreviewDimension }
        return (withinCap.ifEmpty { candidates })
            .minByOrNull { size -> abs(size.height.toFloat() / size.width.toFloat() - targetAspect) }
            ?: FallbackPreviewSize
    }

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
            }

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
        /** Preview stream resolution cap — plenty for a full-screen viewfinder, keeps frame cost down. */
        const val MaxPreviewDimension = 1920

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
    }
}
