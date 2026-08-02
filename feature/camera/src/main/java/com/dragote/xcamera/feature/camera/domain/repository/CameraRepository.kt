package com.dragote.xcamera.feature.camera.domain.repository

import android.net.Uri
import androidx.camera.core.Preview
import androidx.lifecycle.LifecycleOwner
import com.dragote.xcamera.feature.camera.domain.model.CameraLens
import com.dragote.xcamera.feature.camera.domain.model.FlashMode
import com.dragote.xcamera.feature.camera.domain.model.ManualIsoCapability
import com.dragote.xcamera.shared.common.domain.result.DataError
import com.dragote.xcamera.shared.common.domain.result.Result
import kotlinx.coroutines.flow.Flow

/**
 * Domain-facing contract for CameraX/Camera2 capture, mirroring the operations
 * `data.CameraController` performs against the hardware. `bindCamera` inherently needs a Compose
 * `LifecycleOwner` + `Preview.SurfaceProvider` (see `CameraController`'s own doc), which is why this
 * interface — not just its `CameraController` implementation — keeps those two ui-layer types in its
 * signature; per this module's camera conventions, `presentation/CameraViewModel` must never import
 * them, so `bindCamera` is called directly against this repository from `ui/`, everything else is
 * routed through the ViewModel.
 *
 * Only [takePhoto] wraps its result in [Result] — it's the one operation with a real, user-facing
 * failure mode (hardware/IO error mid-capture). The rest either can't meaningfully fail
 * (setters/queries against already-validated state) or return `null` to mean "not available", which
 * callers already handle without needing a full [Result] wrapper.
 */
interface CameraRepository {

    suspend fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        lens: CameraLens? = null,
    )

    fun setFlashMode(flashMode: FlashMode)

    fun manualIsoCapability(lens: CameraLens?): ManualIsoCapability?

    fun currentAutoExposureTimeNs(): Long?

    /**
     * Continuously reflects auto-exposure's live ISO (via a session-wide Camera2 capture callback)
     * for as long as manual mode is off — unlike [currentAutoExposureTimeNs]'s one-shot pull, this is
     * meant to be collected for the ISO dial's live rotation while auto exposure is active. Emits
     * `null` before the first frame lands (e.g. right after a fresh bind).
     */
    fun observeAutoIso(): Flow<Int?>

    fun setManualExposure(iso: Int?, shutterTimeNs: Long?)

    suspend fun takePhoto(): Result<Uri, DataError.Local>

    fun listBackLenses(): List<CameraLens>

    suspend fun latestGalleryPhotoUri(): Uri?

    /** Call when the composable hosting camera capture leaves composition. */
    fun stopOrientationListener()
}
