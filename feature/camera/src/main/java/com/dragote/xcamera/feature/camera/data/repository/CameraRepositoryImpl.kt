package com.dragote.xcamera.feature.camera.data.repository

import android.hardware.camera2.CameraAccessException
import android.media.Image
import android.net.Uri
import android.os.Handler
import androidx.lifecycle.LifecycleOwner
import com.dragote.xcamera.feature.camera.data.CameraController
import com.dragote.xcamera.feature.camera.domain.model.ActiveLut
import com.dragote.xcamera.feature.camera.domain.model.AeCompensationCapability
import com.dragote.xcamera.feature.camera.domain.model.AfConvergenceState
import com.dragote.xcamera.feature.camera.domain.model.CameraLens
import com.dragote.xcamera.feature.camera.domain.model.FlashMode
import com.dragote.xcamera.feature.camera.domain.model.HistogramData
import com.dragote.xcamera.feature.camera.domain.model.ManualFocusCapability
import com.dragote.xcamera.feature.camera.domain.model.ManualIsoCapability
import com.dragote.xcamera.feature.camera.domain.model.ZebraMask
import com.dragote.xcamera.feature.camera.domain.model.parseCubeLut
import com.dragote.xcamera.feature.camera.domain.repository.CameraRepository
import com.dragote.xcamera.shared.common.domain.repository.LutRepository
import com.dragote.xcamera.shared.common.domain.result.DataError
import com.dragote.xcamera.shared.common.domain.result.Result
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CameraRepositoryImpl @Inject constructor(
    private val cameraController: CameraController,
    private val lutRepository: LutRepository,
) : CameraRepository {

    override suspend fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewViewWidth: Int,
        previewViewHeight: Int,
        lens: CameraLens?,
    ) = cameraController.bindCamera(lifecycleOwner, previewViewWidth, previewViewHeight, lens)

    override fun unbindCamera() = cameraController.unbindCamera()

    override fun setPreviewFrameListener(handler: Handler?, listener: ((Image) -> Unit)?) =
        cameraController.setPreviewFrameListener(handler, listener)

    override fun previewRotationDegrees(lens: CameraLens?): Int =
        cameraController.previewRotationDegrees(lens)

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

    override fun setZebraAnalysisEnabled(enabled: Boolean) = cameraController.setZebraAnalysisEnabled(enabled)

    override fun observeZebraMask(): Flow<ZebraMask?> = cameraController.zebraMask

    override fun observeHistogramData(): Flow<HistogramData?> = cameraController.histogramData

    override fun manualFocusCapability(lens: CameraLens?): ManualFocusCapability? =
        cameraController.manualFocusCapability(lens)

    override fun triggerAutoFocus(displayXFraction: Float, displayYFraction: Float) =
        cameraController.triggerAutoFocus(displayXFraction, displayYFraction)

    override fun setManualFocusDistance(distanceDiopters: Float?) =
        cameraController.setManualFocusDistance(distanceDiopters)

    override fun observeFocusDistance(): Flow<Float?> = cameraController.autoFocusDistanceDiopters

    override fun observeAfConvergenceState(): Flow<AfConvergenceState?> = cameraController.afConvergenceState

    /**
     * Looks [lutId] up in [lutRepository]'s current list (a one-shot [first] read, not a live
     * subscription — LUT selection changes are infrequent user actions, not something that needs to
     * react to the *list* changing mid-resolution), then reads + parses that preset's file off disk.
     * `null` (either `lutId` itself, an id not present in the list, an unreadable file, or a malformed
     * `.cube`) always means "no LUT" to [CameraController.setLut] — never throws.
     */
    override suspend fun setLut(lutId: String?, intensityPercent: Int) {
        val cubeLut = lutId?.let { id ->
            lutRepository.observeLuts().first().find { it.id == id }
        }?.let { preset ->
            withContext(Dispatchers.IO) {
                runCatching { File(preset.filePath).readText() }.getOrNull()
            }?.let { content -> parseCubeLut(content) }
        }
        cameraController.setLut(cubeLut, intensityPercent)
    }

    override fun observeActiveLut(): Flow<ActiveLut?> = cameraController.activeLut

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
