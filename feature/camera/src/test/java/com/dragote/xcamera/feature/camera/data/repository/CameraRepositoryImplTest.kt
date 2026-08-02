package com.dragote.xcamera.feature.camera.data.repository

import android.hardware.camera2.CameraAccessException
import android.net.Uri
import android.util.Size
import android.view.Surface
import androidx.lifecycle.LifecycleOwner
import com.dragote.xcamera.feature.camera.data.CameraController
import com.dragote.xcamera.feature.camera.domain.model.CameraLens
import com.dragote.xcamera.feature.camera.domain.model.FlashMode
import com.dragote.xcamera.feature.camera.domain.model.ManualIsoCapability
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
        val surface = mockk<Surface>()
        val lens = CameraLens(logicalCameraId = "0", physicalCameraId = null, zoomRatio = 1f)
        coEvery { cameraController.bindCamera(lifecycleOwner, surface, lens) } returns Unit

        repository.bindCamera(lifecycleOwner, surface, lens)

        coVerify { cameraController.bindCamera(lifecycleOwner, surface, lens) }
    }

    @Test
    fun `unbindCamera delegates to the controller`() {
        every { cameraController.unbindCamera() } returns Unit

        repository.unbindCamera()

        verify { cameraController.unbindCamera() }
    }

    @Test
    fun `previewOutputSize delegates to the controller and returns its result`() {
        // android.util.Size's own constructor/getters/equals are all unmocked on the JVM unit test
        // classpath, so this uses a relaxed mock rather than a real Size instance.
        val lens = CameraLens(logicalCameraId = "0", physicalCameraId = null, zoomRatio = 1f)
        val size = mockk<Size>()
        every { cameraController.previewOutputSize(lens, 1080, 2400) } returns size

        assertEquals(size, repository.previewOutputSize(lens, 1080, 2400))
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
    fun `currentAutoExposureTimeNs delegates to the controller`() {
        every { cameraController.currentAutoExposureTimeNs() } returns 250_000L

        assertEquals(250_000L, repository.currentAutoExposureTimeNs())
    }

    @Test
    fun `observeAutoIso delegates to the controller's autoIso flow`() {
        val autoIsoFlow = MutableStateFlow<Int?>(400)
        every { cameraController.autoIso } returns autoIsoFlow

        assertEquals(autoIsoFlow, repository.observeAutoIso())
    }

    @Test
    fun `setManualExposure delegates to the controller`() {
        every { cameraController.setManualExposure(400, 250_000L) } returns Unit

        repository.setManualExposure(400, 250_000L)

        verify { cameraController.setManualExposure(400, 250_000L) }
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
}
