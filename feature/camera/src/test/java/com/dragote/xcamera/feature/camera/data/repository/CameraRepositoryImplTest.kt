package com.dragote.xcamera.feature.camera.data.repository

import android.hardware.camera2.CameraAccessException
import android.media.Image
import android.net.Uri
import android.os.Handler
import androidx.lifecycle.LifecycleOwner
import com.dragote.xcamera.feature.camera.data.camera.CameraController
import com.dragote.xcamera.feature.camera.data.LutFileReader
import com.dragote.xcamera.feature.camera.domain.model.ActiveLut
import com.dragote.xcamera.feature.camera.domain.model.AeCompensationCapability
import com.dragote.xcamera.feature.camera.domain.model.AfConvergenceState
import com.dragote.xcamera.feature.camera.domain.model.CameraLens
import com.dragote.xcamera.feature.camera.domain.model.FlashMode
import com.dragote.xcamera.feature.camera.domain.model.ManualFocusCapability
import com.dragote.xcamera.feature.camera.domain.model.ManualIsoCapability
import com.dragote.xcamera.feature.camera.domain.model.RawCaptureCapability
import com.dragote.xcamera.feature.camera.domain.model.ZebraMask
import com.dragote.xcamera.shared.common.domain.repository.LutRepository
import com.dragote.xcamera.shared.common.domain.result.DataError
import com.dragote.xcamera.shared.common.domain.result.Result
import com.dragote.xcamera.shared.common.domain.model.CubeLut
import com.dragote.xcamera.shared.common.domain.model.LutPreset
import com.dragote.xcamera.shared.common.domain.model.toBinary
import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraRepositoryImplTest {

    private val cameraController = mockk<CameraController>()
    private val lutRepository = mockk<LutRepository> {
        every { observeLuts() } returns flowOf(emptyList())
    }
    private val lutFileReader = mockk<LutFileReader>()
    private val repository = CameraRepositoryImpl(cameraController, lutRepository, lutFileReader)

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
        coEvery { cameraController.takePhoto(false) } returns uri

        val result = repository.takePhoto()

        assertEquals(Result.Success(uri), result)
    }

    @Test
    fun `takePhoto wraps a CameraAccessException in Result Error`() = runTest {
        coEvery { cameraController.takePhoto(false) } throws mockk<CameraAccessException>(relaxed = true)

        val result = repository.takePhoto()

        assertEquals(Result.Error(DataError.Local.UNKNOWN), result)
    }

    @Test
    fun `takePhoto wraps an unbound-camera IllegalStateException in Result Error`() = runTest {
        coEvery { cameraController.takePhoto(false) } throws IllegalStateException("Camera not bound yet")

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
    fun `rawCaptureCapability delegates to the controller and returns its result`() {
        val lens = CameraLens(logicalCameraId = "0", physicalCameraId = null, zoomRatio = 1f)
        val capability = RawCaptureCapability(sensorWidth = 4032, sensorHeight = 3024)
        every { cameraController.rawCaptureCapability(lens) } returns capability

        assertEquals(capability, repository.rawCaptureCapability(lens))
    }

    @Test
    fun `rawCaptureCapability delegates to the controller and returns null for an unsupported lens`() {
        val lens = CameraLens(logicalCameraId = "0", physicalCameraId = "1", zoomRatio = 0.5f)
        every { cameraController.rawCaptureCapability(lens) } returns null

        assertNull(repository.rawCaptureCapability(lens))
    }

    @Test
    fun `takePhoto with includeRaw true delegates the flag to the controller`() = runTest {
        val uri = mockk<Uri>()
        coEvery { cameraController.takePhoto(true) } returns uri

        val result = repository.takePhoto(includeRaw = true)

        assertEquals(Result.Success(uri), result)
        coVerify { cameraController.takePhoto(true) }
    }

    @Test
    fun `takePhoto defaults includeRaw to false`() = runTest {
        val uri = mockk<Uri>()
        coEvery { cameraController.takePhoto(false) } returns uri

        val result = repository.takePhoto()

        assertEquals(Result.Success(uri), result)
        coVerify { cameraController.takePhoto(false) }
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

    @Test
    fun `observeAfConvergenceState delegates to the controller's afConvergenceState flow`() {
        val afConvergenceStateFlow = MutableStateFlow<AfConvergenceState?>(AfConvergenceState.SCANNING)
        every { cameraController.afConvergenceState } returns afConvergenceStateFlow

        assertEquals(afConvergenceStateFlow, repository.observeAfConvergenceState())
    }

    @Test
    fun `setLut with a null id clears the controller's active LUT`() = runTest {
        every { cameraController.setLut(any(), null, 50) } returns Unit

        repository.setLut(null, 50)

        verify { cameraController.setLut(any(), null, 50) }
    }

    @Test
    fun `setLut with an unknown id resolves to no LUT`() = runTest {
        every { lutRepository.observeLuts() } returns flowOf(emptyList())
        every { cameraController.setLut(any(), null, 80) } returns Unit

        repository.setLut("missing-id", 80)

        verify { cameraController.setLut(any(), null, 80) }
    }

    @Test
    fun `observeActiveLut delegates to the controller's activeLut flow`() {
        val activeLutFlow = MutableStateFlow<ActiveLut?>(null)
        every { cameraController.activeLut } returns activeLutFlow

        assertEquals(activeLutFlow, repository.observeActiveLut())
    }

    @Test
    fun `setLut sets the resolving id before the parse work and clears it once done`() = runTest {
        val preset = LutPreset(id = "1", displayName = "Test", filePath = "/nonexistent/path.cube")
        every { lutRepository.observeLuts() } returns flowOf(listOf(preset))
        every { lutFileReader.readBytes(preset.filePath) } returns null
        var resolvingIdDuringSetLut: String? = "not-captured"
        every { cameraController.setLut(any(), any(), any()) } answers {
            resolvingIdDuringSetLut = (repository.observeResolvingLutId() as StateFlow<String?>).value
        }

        repository.setLut("1", 50)

        assertEquals("1", resolvingIdDuringSetLut)
        assertNull((repository.observeResolvingLutId() as StateFlow<String?>).value)
    }

    @Test
    fun `setLut with a null id never surfaces a resolving id`() = runTest {
        every { cameraController.setLut(any(), null, 50) } returns Unit

        repository.setLut(null, 50)

        assertNull((repository.observeResolvingLutId() as StateFlow<String?>).value)
    }

    @Test
    fun `setLut with an unknown id still toggles the resolving id around the call`() = runTest {
        every { lutRepository.observeLuts() } returns flowOf(emptyList())
        var resolvingIdDuringSetLut: String? = "not-captured"
        every { cameraController.setLut(any(), null, 80) } answers {
            resolvingIdDuringSetLut = (repository.observeResolvingLutId() as StateFlow<String?>).value
        }

        repository.setLut("missing-id", 80)

        assertEquals("missing-id", resolvingIdDuringSetLut)
        assertNull((repository.observeResolvingLutId() as StateFlow<String?>).value)
    }

    @Test
    fun `observeResolvingLutId starts out null`() {
        assertNull((repository.observeResolvingLutId() as StateFlow<String?>).value)
    }

    @Test
    fun `setLut with an id not found in the LUT list emits a resolution failure`() = runTest {
        every { lutRepository.observeLuts() } returns flowOf(emptyList())
        every { cameraController.setLut(any(), null, 80) } returns Unit

        repository.observeResolutionFailures().test {
            repository.setLut("missing-id", 80)
            assertEquals("missing-id", awaitItem())
        }
    }

    @Test
    fun `setLut with a preset whose file can't be read emits a resolution failure`() = runTest {
        val preset = LutPreset(id = "1", displayName = "Test", filePath = "/nonexistent/path.cube")
        every { lutRepository.observeLuts() } returns flowOf(listOf(preset))
        every { lutFileReader.readBytes(preset.filePath) } returns null
        every { cameraController.setLut(any(), null, 50) } returns Unit

        repository.observeResolutionFailures().test {
            repository.setLut("1", 50)
            assertEquals("1", awaitItem())
        }
    }

    @Test
    fun `setLut with malformed cube content emits a resolution failure`() = runTest {
        val preset = LutPreset(id = "2", displayName = "Malformed", filePath = "/luts/2.lutbin")
        every { lutRepository.observeLuts() } returns flowOf(listOf(preset))
        every { lutFileReader.readBytes(preset.filePath) } returns "not a valid lutbin file".toByteArray()
        every { cameraController.setLut(any(), null, 50) } returns Unit

        repository.observeResolutionFailures().test {
            repository.setLut("2", 50)
            assertEquals("2", awaitItem())
        }
    }

    @Test
    fun `setLut with a null id never emits a resolution failure`() = runTest {
        every { cameraController.setLut(any(), null, 50) } returns Unit

        repository.observeResolutionFailures().test {
            repository.setLut(null, 50)
            expectNoEvents()
        }
    }

    @Test
    fun `setLut that successfully resolves a LUT doesn't emit a resolution failure`() = runTest {
        val preset = LutPreset(id = "3", displayName = "Valid", filePath = "/luts/3.cube")
        every { lutRepository.observeLuts() } returns flowOf(listOf(preset))
        every { lutFileReader.readBytes(preset.filePath) } returns validCubeBytes
        every { cameraController.setLut(any(), any(), 50) } returns Unit

        repository.observeResolutionFailures().test {
            repository.setLut("3", 50)
            expectNoEvents()
        }
    }

    @Test
    fun `a repeat setLut call with the same id skips the file read (resolve cache)`() = runTest {
        val preset = LutPreset(id = "3", displayName = "Valid", filePath = "/luts/3.cube")
        every { lutRepository.observeLuts() } returns flowOf(listOf(preset))
        every { lutFileReader.readBytes(preset.filePath) } returns validCubeBytes
        every { cameraController.setLut(any(), any(), 50) } returns Unit

        repository.setLut("3", 50)
        repository.setLut("3", 50)

        verify(exactly = 1) { lutFileReader.readBytes(preset.filePath) }
    }

    @Test
    fun `a cached LUT is never served once its id is no longer present in the LUT list`() = runTest {
        val preset = LutPreset(id = "3", displayName = "Valid", filePath = "/luts/3.cube")
        every { lutRepository.observeLuts() } returnsMany listOf(flowOf(listOf(preset)), flowOf(emptyList()))
        every { lutFileReader.readBytes(preset.filePath) } returns validCubeBytes
        every { cameraController.setLut(any(), any(), 50) } returns Unit
        every { cameraController.setLut(any(), null, 50) } returns Unit

        repository.setLut("3", 50) // resolves and caches
        repository.setLut("3", 50) // preset no longer in the (now-empty) list — must not use the cache

        verify { cameraController.setLut(any(), null, 50) }
    }

    private val validCubeBytes = CubeLut(
        size = 2,
        values = floatArrayOf(
            0f, 0f, 0f,
            0f, 0f, 1f,
            0f, 1f, 0f,
            0f, 1f, 1f,
            1f, 0f, 0f,
            1f, 0f, 1f,
            1f, 1f, 0f,
            1f, 1f, 1f,
        ),
    ).toBinary()
}
