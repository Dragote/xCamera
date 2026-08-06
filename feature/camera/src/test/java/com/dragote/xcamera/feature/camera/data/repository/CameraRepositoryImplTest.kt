package com.dragote.xcamera.feature.camera.data.repository

import android.hardware.camera2.CameraAccessException
import android.media.Image
import android.net.Uri
import android.os.Handler
import androidx.lifecycle.LifecycleOwner
import com.dragote.xcamera.feature.camera.data.CameraController
import com.dragote.xcamera.feature.camera.domain.model.AeCompensationCapability
import com.dragote.xcamera.feature.camera.domain.model.CameraLens
import com.dragote.xcamera.feature.camera.domain.model.FlashMode
import com.dragote.xcamera.feature.camera.domain.model.ManualFocusCapability
import com.dragote.xcamera.feature.camera.domain.model.ManualIsoCapability
import com.dragote.xcamera.feature.camera.domain.model.ZebraMask
import com.dragote.xcamera.shared.common.domain.result.DataError
import com.dragote.xcamera.shared.common.domain.result.Result
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraRepositoryImplTest {

    private val cameraController = mockk<CameraController>()
    private val repository = CameraRepositoryImpl(cameraController)

    @Test
    fun `bindCamera delegates to the controller`() = runTest {
        val lifecycleOwner = mockk<LifecycleOwner>()
        val lens = CameraLens(logicalCameraId = "0", physicalCameraId = null, zoomRatio = 1f)
        coEvery { cameraController.bindCamera(lifecycleOwner, 1080, 2400, lens) } returns Unit

        repository.bindCamera(lifecycleOwner, 1080, 2400, lens)

        coVerify { cameraController.bindCamera(lifecycleOwner, 1080, 2400, lens) }
    }

    @Test
    fun `unbindCamera delegates to the controller`() {
        every { cameraController.unbindCamera() } returns Unit

        repository.unbindCamera()

        verify { cameraController.unbindCamera() }
    }

    @Test
    fun `setPreviewFrameListener delegates to the controller`() {
        val handler = mockk<Handler>()
        val listener: (Image) -> Unit = {}
        every { cameraController.setPreviewFrameListener(handler, listener) } returns Unit

        repository.setPreviewFrameListener(handler, listener)

        verify { cameraController.setPreviewFrameListener(handler, listener) }
    }

    @Test
    fun `previewRotationDegrees delegates to the controller and returns its result`() {
        val lens = CameraLens(logicalCameraId = "0", physicalCameraId = null, zoomRatio = 1f)
        every { cameraController.previewRotationDegrees(lens) } returns 90

        assertEquals(90, repository.previewRotationDegrees(lens))
    }

    @Test
    fun `setFlashMode delegates to the controller`() {
        every { cameraController.setFlashMode(FlashMode.ON) } returns Unit

        repository.setFlashMode(FlashMode.ON)

        verify { cameraController.setFlashMode(FlashMode.ON) }
    }

    @Test
    fun `manualIsoCapability delegates to the controller and returns its result`() {
        val lens = CameraLens(logicalCameraId = "0", physicalCameraId = null, zoomRatio = 1f)
        val capability = ManualIsoCapability(isoRange = 100..3200, exposureTimeRange = 1_000L..500_000_000L)
        every { cameraController.manualIsoCapability(lens) } returns capability

        assertEquals(capability, repository.manualIsoCapability(lens))
    }

    @Test
    fun `aeCompensationCapability delegates to the controller and returns its result`() {
        val lens = CameraLens(logicalCameraId = "0", physicalCameraId = null, zoomRatio = 1f)
        val capability = AeCompensationCapability(range = -6..6, stepEv = 1f / 3f)
        every { cameraController.aeCompensationCapability(lens) } returns capability

        assertEquals(capability, repository.aeCompensationCapability(lens))
    }

    @Test
    fun `observeAutoIso delegates to the controller's autoIso flow`() {
        val autoIsoFlow = MutableStateFlow<Int?>(400)
        every { cameraController.autoIso } returns autoIsoFlow

        assertEquals(autoIsoFlow, repository.observeAutoIso())
    }

    @Test
    fun `observeAutoExposureTime delegates to the controller's autoExposureTimeNs flow`() {
        val autoExposureTimeFlow = MutableStateFlow<Long?>(250_000L)
        every { cameraController.autoExposureTimeNs } returns autoExposureTimeFlow

        assertEquals(autoExposureTimeFlow, repository.observeAutoExposureTime())
    }

    @Test
    fun `setManualExposure delegates to the controller`() {
        every { cameraController.setManualExposure(400, 250_000L) } returns Unit

        repository.setManualExposure(400, 250_000L)

        verify { cameraController.setManualExposure(400, 250_000L) }
    }

    @Test
    fun `setExposureCompensation delegates to the controller`() {
        every { cameraController.setExposureCompensation(3) } returns Unit

        repository.setExposureCompensation(3)

        verify { cameraController.setExposureCompensation(3) }
    }

    @Test
    fun `setZebraAnalysisEnabled delegates to the controller`() {
        every { cameraController.setZebraAnalysisEnabled(true) } returns Unit

        repository.setZebraAnalysisEnabled(true)

        verify { cameraController.setZebraAnalysisEnabled(true) }
    }

    @Test
    fun `observeZebraMask delegates to the controller's zebraMask flow`() {
        val zebraMaskFlow = MutableStateFlow<ZebraMask?>(null)
        every { cameraController.zebraMask } returns zebraMaskFlow

        assertEquals(zebraMaskFlow, repository.observeZebraMask())
    }

    @Test
    fun `listBackLenses delegates to the controller`() {
        val lenses = listOf(CameraLens(logicalCameraId = "0", physicalCameraId = null, zoomRatio = 1f))
        every { cameraController.listBackLenses() } returns lenses

        assertEquals(lenses, repository.listBackLenses())
    }

    @Test
    fun `latestGalleryPhotoUri delegates to the controller`() = runTest {
        val uri = mockk<Uri>()
        coEvery { cameraController.latestGalleryPhotoUri() } returns uri

        assertEquals(uri, repository.latestGalleryPhotoUri())
    }

    @Test
    fun `stopOrientationListener delegates to the controller`() {
        every { cameraController.stopOrientationListener() } returns Unit

        repository.stopOrientationListener()

        verify { cameraController.stopOrientationListener() }
    }

    @Test
    fun `takePhoto wraps a successful capture in Result Success`() = runTest {
        val uri = mockk<Uri>()
        coEvery { cameraController.takePhoto() } returns uri

        val result = repository.takePhoto()

        assertEquals(Result.Success(uri), result)
    }

    @Test
    fun `takePhoto wraps a CameraAccessException in Result Error`() = runTest {
        coEvery { cameraController.takePhoto() } throws mockk<CameraAccessException>(relaxed = true)

        val result = repository.takePhoto()

        assertEquals(Result.Error(DataError.Local.UNKNOWN), result)
    }

    @Test
    fun `takePhoto wraps an unbound-camera IllegalStateException in Result Error`() = runTest {
        coEvery { cameraController.takePhoto() } throws IllegalStateException("Camera not bound yet")

        val result = repository.takePhoto()

        assertEquals(Result.Error(DataError.Local.UNKNOWN), result)
    }

    @Test
    fun `manualFocusCapability delegates to the controller and returns its result`() {
        val lens = CameraLens(logicalCameraId = "0", physicalCameraId = null, zoomRatio = 1f)
        val capability = ManualFocusCapability(maxFocusDistanceDiopters = 10f)
        every { cameraController.manualFocusCapability(lens) } returns capability

        assertEquals(capability, repository.manualFocusCapability(lens))
    }

    @Test
    fun `triggerAutoFocus delegates to the controller`() {
        every { cameraController.triggerAutoFocus(0.4f, 0.7f) } returns Unit

        repository.triggerAutoFocus(0.4f, 0.7f)

        verify { cameraController.triggerAutoFocus(0.4f, 0.7f) }
    }

    @Test
    fun `setManualFocusDistance delegates to the controller`() {
        every { cameraController.setManualFocusDistance(2.5f) } returns Unit

        repository.setManualFocusDistance(2.5f)

        verify { cameraController.setManualFocusDistance(2.5f) }
    }

    @Test
    fun `observeFocusDistance delegates to the controller's autoFocusDistanceDiopters flow`() {
        val focusDistanceFlow = MutableStateFlow<Float?>(3.5f)
        every { cameraController.autoFocusDistanceDiopters } returns focusDistanceFlow

        assertEquals(focusDistanceFlow, repository.observeFocusDistance())
    }
}
