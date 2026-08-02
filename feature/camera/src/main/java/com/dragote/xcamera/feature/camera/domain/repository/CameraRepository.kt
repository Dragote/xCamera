package com.dragote.xcamera.feature.camera.domain.repository

import android.net.Uri
import android.util.Size
import android.view.Surface
import androidx.lifecycle.LifecycleOwner
import com.dragote.xcamera.feature.camera.domain.model.CameraLens
import com.dragote.xcamera.feature.camera.domain.model.FlashMode
import com.dragote.xcamera.feature.camera.domain.model.ManualIsoCapability
import com.dragote.xcamera.shared.common.domain.result.DataError
import com.dragote.xcamera.shared.common.domain.result.Result
import kotlinx.coroutines.flow.Flow

/**
 * Domain-facing contract for raw `Camera2` capture, mirroring the operations `data.CameraController`
 * performs against the hardware. `bindCamera`/`unbindCamera`/`previewOutputSize` inherently need a
 * Compose `LifecycleOwner` and/or a raw preview `Surface` (see `CameraController`'s own doc for why
 * the live preview's repeating request and a still capture's one-off request must be genuinely
 * independent `CameraCaptureSession` requests rather than routed through a higher-level abstraction),
 * which is why this interface — not just its `CameraController` implementation — keeps those
 * hardware-adjacent types in its signature; per this module's camera conventions,
 * `presentation/CameraViewModel` must never import them, so those three calls are made directly
 * against this repository from `ui/`, everything else is routed through the ViewModel.
 *
 * Only [takePhoto] wraps its result in [Result] — it's the one operation with a real, user-facing
 * failure mode (hardware/IO error mid-capture). The rest either can't meaningfully fail
 * (setters/queries against already-validated state) or return `null` to mean "not available", which
 * callers already handle without needing a full [Result] wrapper.
 */
interface CameraRepository {

    /**
     * [surface] is expected to already have its backing buffer sized (e.g. via
     * `SurfaceTexture.setDefaultBufferSize`) to whatever [previewOutputSize] returned for [lens] —
     * `CameraController` configures the capture session against the surface as handed to it, it does
     * not itself resize the buffer. Internally observes [lifecycleOwner]'s lifecycle to open the
     * camera device/session at `ON_START` and release it at `ON_STOP`, so callers don't need to
     * manage that themselves — call [unbindCamera] only when the surface itself is going away (e.g.
     * the hosting view is destroyed), not on every lifecycle pause.
     */
    suspend fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        surface: Surface,
        lens: CameraLens? = null,
    )

    /** Call once the preview surface backing a previous [bindCamera] call is no longer valid. */
    fun unbindCamera()

    /**
     * The `SurfaceTexture` preview size to request for [lens], closest in aspect ratio to a
     * [targetWidth]x[targetHeight] view without being needlessly larger than the viewfinder actually
     * needs. Callers configure their own `SurfaceTexture`'s default buffer size with this before
     * wrapping it in the `Surface` passed to [bindCamera].
     */
    fun previewOutputSize(lens: CameraLens?, targetWidth: Int, targetHeight: Int): Size

    fun setFlashMode(flashMode: FlashMode)

    fun manualIsoCapability(lens: CameraLens?): ManualIsoCapability?

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

    suspend fun takePhoto(): Result<Uri, DataError.Local>

    fun listBackLenses(): List<CameraLens>

    suspend fun latestGalleryPhotoUri(): Uri?

    /** Call when the composable hosting camera capture leaves composition. */
    fun stopOrientationListener()
}
