package com.dragote.xcamera.feature.camera.data.repository

import android.net.Uri
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.lifecycle.LifecycleOwner
import com.dragote.xcamera.feature.camera.data.CameraController
import com.dragote.xcamera.feature.camera.domain.model.CameraLens
import com.dragote.xcamera.feature.camera.domain.model.FlashMode
import com.dragote.xcamera.feature.camera.domain.model.ManualIsoCapability
import com.dragote.xcamera.feature.camera.domain.repository.CameraRepository
import com.dragote.xcamera.shared.common.domain.result.DataError
import com.dragote.xcamera.shared.common.domain.result.Result
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

class CameraRepositoryImpl @Inject constructor(
    private val cameraController: CameraController,
) : CameraRepository {

    override suspend fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        lens: CameraLens?,
    ) = cameraController.bindCamera(lifecycleOwner, surfaceProvider, lens)

    override fun setFlashMode(flashMode: FlashMode) = cameraController.setFlashMode(flashMode)

    override fun manualIsoCapability(lens: CameraLens?): ManualIsoCapability? =
        cameraController.manualIsoCapability(lens)

    override fun currentAutoExposureTimeNs(): Long? = cameraController.currentAutoExposureTimeNs()

    override fun setManualExposure(iso: Int?, shutterTimeNs: Long?) =
        cameraController.setManualExposure(iso, shutterTimeNs)

    override suspend fun takePhoto(): Result<Uri, DataError.Local> = try {
        Result.Success(cameraController.takePhoto())
    } catch (e: CancellationException) {
        throw e
    } catch (e: ImageCaptureException) {
        Result.Error(DataError.Local.UNKNOWN)
    } catch (e: IllegalStateException) {
        Result.Error(DataError.Local.UNKNOWN)
    }

    override fun listBackLenses(): List<CameraLens> = cameraController.listBackLenses()

    override suspend fun latestGalleryPhotoUri(): Uri? = cameraController.latestGalleryPhotoUri()

    override fun stopOrientationListener() = cameraController.stopOrientationListener()
}
