package com.dragote.xcamera.feature.camera.data.repository

import android.hardware.camera2.CameraAccessException
import android.net.Uri
import android.util.Size
import android.view.Surface
import androidx.lifecycle.LifecycleOwner
import com.dragote.xcamera.feature.camera.data.CameraController
import com.dragote.xcamera.feature.camera.domain.model.AeCompensationCapability
import com.dragote.xcamera.feature.camera.domain.model.CameraLens
import com.dragote.xcamera.feature.camera.domain.model.FlashMode
import com.dragote.xcamera.feature.camera.domain.model.ManualIsoCapability
import com.dragote.xcamera.feature.camera.domain.repository.CameraRepository
import com.dragote.xcamera.shared.common.domain.result.DataError
import com.dragote.xcamera.shared.common.domain.result.Result
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CameraRepositoryImpl @Inject constructor(
    private val cameraController: CameraController,
) : CameraRepository {

    override suspend fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        surface: Surface,
        lens: CameraLens?,
    ) = cameraController.bindCamera(lifecycleOwner, surface, lens)

    override fun unbindCamera() = cameraController.unbindCamera()

    override fun previewOutputSize(lens: CameraLens?, targetWidth: Int, targetHeight: Int): Size =
        cameraController.previewOutputSize(lens, targetWidth, targetHeight)

    override fun setFlashMode(flashMode: FlashMode) = cameraController.setFlashMode(flashMode)

    override fun manualIsoCapability(lens: CameraLens?): ManualIsoCapability? =
        cameraController.manualIsoCapability(lens)

    override fun aeCompensationCapability(lens: CameraLens?): AeCompensationCapability? =
        cameraController.aeCompensationCapability(lens)

    override fun observeAutoIso(): Flow<Int?> = cameraController.autoIso

    override fun observeAutoExposureTime(): Flow<Long?> = cameraController.autoExposureTimeNs

    override fun setManualExposure(iso: Int?, shutterTimeNs: Long?) =
        cameraController.setManualExposure(iso, shutterTimeNs)

    override fun setExposureCompensation(value: Int) = cameraController.setExposureCompensation(value)

    override suspend fun takePhoto(): Result<Uri, DataError.Local> = try {
        Result.Success(cameraController.takePhoto())
    } catch (e: CancellationException) {
        throw e
    } catch (e: IllegalStateException) {
        Result.Error(DataError.Local.UNKNOWN)
    } catch (e: CameraAccessException) {
        Result.Error(DataError.Local.UNKNOWN)
    }

    override fun listBackLenses(): List<CameraLens> = cameraController.listBackLenses()

    override suspend fun latestGalleryPhotoUri(): Uri? = cameraController.latestGalleryPhotoUri()

    override fun stopOrientationListener() = cameraController.stopOrientationListener()
}
