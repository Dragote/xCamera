package com.dragote.xcamera.feature.camera.domain.repository

import android.media.Image
import android.net.Uri
import android.os.Handler
import androidx.lifecycle.LifecycleOwner
import com.dragote.xcamera.feature.camera.domain.model.AeCompensationCapability
import com.dragote.xcamera.feature.camera.domain.model.CameraLens
import com.dragote.xcamera.feature.camera.domain.model.FlashMode
import com.dragote.xcamera.feature.camera.domain.model.ManualIsoCapability
import com.dragote.xcamera.feature.camera.domain.model.ZebraMask
import com.dragote.xcamera.shared.common.domain.result.DataError
import com.dragote.xcamera.shared.common.domain.result.Result
import kotlinx.coroutines.flow.Flow

/**
 * Domain-facing contract for raw `Camera2` capture, mirroring the operations `data.CameraController`
 * performs against the hardware. `bindCamera`/`setPreviewFrameListener`/`previewRotationDegrees`
 * inherently need a Compose `LifecycleOwner` and/or `android.media.Image`/`android.os.Handler` (see
 * `CameraController`'s own doc for why the live preview is rendered by app-owned GLES rather than
 * targeted at a caller-supplied `Surface` — an `ImageReader`'s dimensions are a verifiable
 * construction-time contract a `SurfaceTexture`'s requested buffer size isn't), which is why this
 * interface — not just its `CameraController` implementation — keeps those hardware-adjacent types in
 * its signature; per this module's camera conventions, `presentation/CameraViewModel` must never
 * import them, so those calls are made directly against this repository from `ui/`, everything else
 * is routed through the ViewModel.
 *
 * Only [takePhoto] wraps its result in [Result] — it's the one operation with a real, user-facing
 * failure mode (hardware/IO error mid-capture). The rest either can't meaningfully fail
 * (setters/queries against already-validated state) or return `null` to mean "not available", which
 * callers already handle without needing a full [Result] wrapper.
 */
interface CameraRepository {

    /**
     * [previewViewWidth]/[previewViewHeight] are the on-screen `TextureView`'s own measured pixel
     * dimensions, used internally to size the actual preview stream — see `CameraController`'s own
     * doc for why there's no `Surface` parameter here; register a frame consumer via
     * [setPreviewFrameListener] separately to actually see pixels. Internally observes
     * [lifecycleOwner]'s lifecycle to open the camera device/session at `ON_START` and release it at
     * `ON_STOP`, so callers don't need to manage that themselves — call [unbindCamera] only when the
     * hosting view itself is going away, not on every lifecycle pause.
     */
    suspend fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewViewWidth: Int,
        previewViewHeight: Int,
        lens: CameraLens? = null,
    )

    /** Call once whatever [bindCamera] was backing is no longer valid (e.g. the hosting view is
     *  destroyed). */
    fun unbindCamera()

    /**
     * Registers (or, passing both `null`, unregisters) the render-thread consumer of every delivered
     * live preview frame — in practice `ui/CameraScreen`'s `CameraPreviewRenderer`, which draws each
     * [Image] onto the on-screen `TextureView` via app-owned GLES and takes ownership of closing it.
     * [handler] must be bound to whatever thread owns the GL context frames get drawn with — Camera2/
     * `ImageReader` callbacks run on `CameraController`'s own background thread, not the caller's.
     */
    fun setPreviewFrameListener(handler: Handler?, listener: ((Image) -> Unit)?)

    /**
     * `CameraCharacteristics.SENSOR_ORIENTATION` for [lens] — the render-thread consumer registered
     * via [setPreviewFrameListener] needs this to rotate raw preview frames into display orientation
     * itself, since (unlike a `TextureView`'s own on-screen `SurfaceTexture`) an `ImageReader` surface
     * gets no automatic producer-side rotation. `0` if unavailable.
     */
    fun previewRotationDegrees(lens: CameraLens?): Int

    fun setFlashMode(flashMode: FlashMode)

    fun manualIsoCapability(lens: CameraLens?): ManualIsoCapability?

    fun aeCompensationCapability(lens: CameraLens?): AeCompensationCapability?

    /**
     * Continuously reflects auto-exposure's live ISO (via a session-wide Camera2 capture callback)
     * for as long as manual mode is off — meant to be collected for the ISO dial's live rotation
     * while auto exposure is active. Emits `null` before the first frame lands (e.g. right after a
     * fresh bind).
     */
    fun observeAutoIso(): Flow<Int?>

    /**
     * Mirrors [observeAutoIso] for shutter speed — meant to be collected for the shutter dial's live
     * rotation the same way [observeAutoIso] drives the ISO dial's.
     */
    fun observeAutoExposureTime(): Flow<Long?>

    fun setManualExposure(iso: Int?, shutterTimeNs: Long?)

    fun setExposureCompensation(value: Int)

    /**
     * Gates the third analysis stream's per-frame cost entirely — `true` only while the ISO/shutter
     * dial is actively being dragged (see `ui/CameraScreen`'s `DialWheel.onDragActiveChanged` wiring).
     * A no-op on a lens with no `MANUAL_SENSOR` support, where there's no analysis stream to enable in
     * the first place.
     */
    fun setZebraAnalysisEnabled(enabled: Boolean)

    /**
     * Grid-coarse over/under-exposure clipping mask for the live viewfinder, meant to be collected
     * directly by whatever renders the zebra-stripe overlay — not folded into a single UI-state object
     * (see `CameraController.zebraMask`'s own doc for why). Emits `null` whenever there's nothing to
     * show.
     */
    fun observeZebraMask(): Flow<ZebraMask?>

    suspend fun takePhoto(): Result<Uri, DataError.Local>

    fun listBackLenses(): List<CameraLens>

    suspend fun latestGalleryPhotoUri(): Uri?

    /** Call when the composable hosting camera capture leaves composition. */
    fun stopOrientationListener()
}
