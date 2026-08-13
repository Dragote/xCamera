package com.dragote.xcamera.feature.camera.presentation

import android.net.Uri
import app.cash.turbine.test
import com.dragote.xcamera.feature.camera.domain.model.AeCompensationCapability
import com.dragote.xcamera.feature.camera.domain.model.AfConvergenceState
import com.dragote.xcamera.feature.camera.domain.model.CameraLens
import com.dragote.xcamera.feature.camera.domain.model.CameraPermissionStatus
import com.dragote.xcamera.feature.camera.domain.model.FlashMode
import com.dragote.xcamera.feature.camera.domain.model.HistogramData
import com.dragote.xcamera.feature.camera.domain.model.ManualFocusCapability
import com.dragote.xcamera.feature.camera.domain.model.ManualIsoCapability
import com.dragote.xcamera.feature.camera.domain.model.ZebraClipping
import com.dragote.xcamera.feature.camera.domain.model.ZebraMask
import com.dragote.xcamera.feature.camera.domain.repository.CameraRepository
import com.dragote.xcamera.shared.common.domain.model.CameraSettings
import com.dragote.xcamera.shared.common.domain.repository.CameraSettingsRepository
import com.dragote.xcamera.shared.common.domain.repository.LutResolutionRepository
import com.dragote.xcamera.shared.common.domain.result.DataError
import com.dragote.xcamera.shared.common.domain.result.Result
import com.dragote.xcamera.shared.testing.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class CameraViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var cameraRepository: CameraRepository
    private lateinit var cameraSettingsRepository: CameraSettingsRepository
    private lateinit var lutResolutionRepository: LutResolutionRepository
    private lateinit var autoIsoFlow: MutableStateFlow<Int?>
    private lateinit var autoExposureTimeFlow: MutableStateFlow<Long?>
    private lateinit var zebraMaskFlow: MutableStateFlow<ZebraMask?>
    private lateinit var histogramDataFlow: MutableStateFlow<HistogramData?>
    private lateinit var focusDistanceFlow: MutableStateFlow<Float?>
    private lateinit var afConvergenceStateFlow: MutableStateFlow<AfConvergenceState?>
    private lateinit var cameraSettingsFlow: MutableStateFlow<CameraSettings>
    private lateinit var resolvingLutIdFlow: MutableStateFlow<String?>
    private lateinit var viewModel: CameraViewModel

    @Before
    fun setUp() {
        cameraRepository = mockk()
        cameraSettingsRepository = mockk()
        lutResolutionRepository = mockk()
        autoIsoFlow = MutableStateFlow(null)
        autoExposureTimeFlow = MutableStateFlow(null)
        zebraMaskFlow = MutableStateFlow(null)
        histogramDataFlow = MutableStateFlow(null)
        focusDistanceFlow = MutableStateFlow(null)
        afConvergenceStateFlow = MutableStateFlow(null)
        cameraSettingsFlow = MutableStateFlow(CameraSettings())
        resolvingLutIdFlow = MutableStateFlow(null)
        every { cameraRepository.observeAutoIso() } returns autoIsoFlow
        every { cameraRepository.observeAutoExposureTime() } returns autoExposureTimeFlow
        every { cameraRepository.observeZebraMask() } returns zebraMaskFlow
        every { cameraRepository.observeHistogramData() } returns histogramDataFlow
        every { cameraRepository.observeFocusDistance() } returns focusDistanceFlow
        every { cameraRepository.observeAfConvergenceState() } returns afConvergenceStateFlow
        every { cameraSettingsRepository.observeSettings() } returns cameraSettingsFlow
        every { lutResolutionRepository.observeResolvingLutId() } returns resolvingLutIdFlow
        viewModel = CameraViewModel(cameraRepository, cameraSettingsRepository, lutResolutionRepository)
    }

    @Test
    fun `onPermissionResult maps granted flag to permission status`() = runTest {
        viewModel.uiState.test {
            assertEquals(CameraPermissionStatus.Unknown, awaitItem().permissionStatus)

            viewModel.onPermissionResult(true)
            assertEquals(CameraPermissionStatus.Granted, awaitItem().permissionStatus)

            viewModel.onPermissionResult(false)
            assertEquals(CameraPermissionStatus.Denied, awaitItem().permissionStatus)
        }
    }

    @Test
    fun `capture lifecycle updates isCapturing and lastSavedUri, clearing any previous error`() = runTest {
        val uri = mockk<Uri>()

        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.onCaptureError("boom")
            val afterError = awaitItem()
            assertEquals(false, afterError.isCapturing)
            assertEquals("boom", afterError.captureError)

            viewModel.onCaptureStarted()
            val started = awaitItem()
            assertEquals(true, started.isCapturing)
            assertEquals(null, started.captureError)

            viewModel.onPhotoSaved(uri)
            val saved = awaitItem()
            assertEquals(false, saved.isCapturing)
            assertEquals(uri, saved.lastSavedUri)
        }
    }

    @Test
    fun `onFlashModeToggled toggles OFF to ON and back to OFF`() = runTest {
        viewModel.uiState.test {
            assertEquals(FlashMode.OFF, awaitItem().flashMode)

            viewModel.onFlashModeToggled()
            assertEquals(FlashMode.ON, awaitItem().flashMode)

            viewModel.onFlashModeToggled()
            assertEquals(FlashMode.OFF, awaitItem().flashMode)
        }
    }

    @Test
    fun `onLensesLoaded picks the lens closest to 1x as the default selection`() = runTest {
        val ultraWide = CameraLens(logicalCameraId = "0", physicalCameraId = "2", zoomRatio = 0.5f)
        val main = CameraLens(logicalCameraId = "0", physicalCameraId = "0", zoomRatio = 1f)
        val tele = CameraLens(logicalCameraId = "0", physicalCameraId = "3", zoomRatio = 2.9f)

        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.onLensesLoaded(listOf(ultraWide, main, tele))
            val loaded = awaitItem()
            assertEquals(listOf(ultraWide, main, tele), loaded.availableLenses)
            assertEquals(main, loaded.selectedLens)
        }
    }

    @Test
    fun `onLensSelected updates the selected lens`() = runTest {
        val ultraWide = CameraLens(logicalCameraId = "0", physicalCameraId = "2", zoomRatio = 0.5f)
        val tele = CameraLens(logicalCameraId = "0", physicalCameraId = "3", zoomRatio = 2.9f)

        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.onLensesLoaded(listOf(ultraWide, tele))
            awaitItem() // loaded, defaults to closest-to-1x

            viewModel.onLensSelected(tele)
            assertEquals(tele, awaitItem().selectedLens)
        }
    }

    @Test
    fun `onManualIsoCapabilityChanged derives supported ISO and shutter stops from the capability's ranges`() = runTest {
        val capability = ManualIsoCapability(isoRange = 100..3200, exposureTimeRange = 1_000L..500_000_000L)

        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.onManualIsoCapabilityChanged(capability)
            val updated = awaitItem()
            assertTrue(updated.manualIsoSupported)
            assertEquals(listOf(100, 200, 400, 800, 1600, 3200), updated.isoStops)
            assertEquals(
                listOf(
                    125_000L, 250_000L, 500_000L, 1_000_000L, 2_000_000L, 4_000_000L, 8_000_000L,
                    16_666_667L, 33_333_333L, 66_666_667L, 125_000_000L, 250_000_000L, 500_000_000L,
                ),
                updated.shutterStops,
            )
        }
    }

    @Test
    fun `onManualIsoCapabilityChanged with no capability disables manual mode entirely`() = runTest {
        val capability = ManualIsoCapability(isoRange = 100..3200, exposureTimeRange = 1_000L..500_000_000L)

        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.onManualIsoCapabilityChanged(capability)
            assertTrue(awaitItem().manualIsoSupported)

            viewModel.onManualIsoCapabilityChanged(null)
            val updated = awaitItem()
            assertFalse(updated.manualIsoSupported)
            assertEquals(emptyList<Int>(), updated.isoStops)
            assertEquals(emptyList<Long>(), updated.shutterStops)
        }
    }

    @Test
    fun `onManualModeToggled enters manual mode when ISO or shutter stops are available`() = runTest {
        val capability = ManualIsoCapability(isoRange = 100..3200, exposureTimeRange = 1_000L..500_000_000L)

        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.onManualIsoCapabilityChanged(capability)
            assertFalse(awaitItem().manualModeEnabled)

            viewModel.onManualModeToggled()
            assertTrue(awaitItem().manualModeEnabled)

            viewModel.onManualModeToggled()
            assertFalse(awaitItem().manualModeEnabled)
        }
    }

    @Test
    fun `onManualModeToggled is a no-op when neither ISO nor shutter has any stops`() = runTest {
        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.onManualModeToggled()
            expectNoEvents()
        }
    }

    @Test
    fun `onManualExposurePinningReady sets manualExposurePinned once manual mode is engaged`() = runTest {
        val capability = ManualIsoCapability(isoRange = 100..3200, exposureTimeRange = 1_000L..500_000_000L)

        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.onManualIsoCapabilityChanged(capability)
            awaitItem() // loaded

            viewModel.onManualModeToggled()
            val manual = awaitItem()
            assertTrue(manual.manualModeEnabled)
            assertFalse(manual.manualExposurePinned)

            viewModel.onManualExposurePinningReady()
            val pinned = awaitItem()
            assertTrue(pinned.manualExposurePinned)
        }
    }

    @Test
    fun `onManualExposurePinningReady is a no-op if manual mode was never engaged`() = runTest {
        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.onManualExposurePinningReady()
            expectNoEvents()
        }
    }

    @Test
    fun `onManualExposurePinningReady is a no-op if manual mode was exited before it fired`() = runTest {
        val capability = ManualIsoCapability(isoRange = 100..3200, exposureTimeRange = 1_000L..500_000_000L)

        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.onManualIsoCapabilityChanged(capability)
            awaitItem() // loaded

            viewModel.onManualModeToggled()
            assertTrue(awaitItem().manualModeEnabled)

            viewModel.onManualModeToggled() // fast double-tap back to auto
            assertFalse(awaitItem().manualModeEnabled)

            viewModel.onManualExposurePinningReady() // the delayed call from the first tap, arriving late
            expectNoEvents()
        }
    }

    @Test
    fun `onIsoIndexChanged clamps the index`() = runTest {
        val capability = ManualIsoCapability(isoRange = 100..3200, exposureTimeRange = 1_000L..500_000_000L)

        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.onManualIsoCapabilityChanged(capability)
            val loaded = awaitItem()
            val lastIndex = loaded.isoStops.lastIndex

            viewModel.onIsoIndexChanged(lastIndex + 10) // out of range on purpose
            val updated = awaitItem()
            assertEquals(lastIndex, updated.selectedIsoIndex)
        }
    }

    @Test
    fun `onShutterIndexChanged clamps the index`() = runTest {
        val capability = ManualIsoCapability(isoRange = 100..3200, exposureTimeRange = 1_000L..500_000_000L)

        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.onManualIsoCapabilityChanged(capability)
            val loaded = awaitItem()
            val lastIndex = loaded.shutterStops.lastIndex

            viewModel.onShutterIndexChanged(lastIndex + 10) // out of range on purpose
            val updated = awaitItem()
            assertEquals(lastIndex, updated.selectedShutterIndex)
        }
    }

    @Test
    fun `onIsoIndexChanged and onShutterIndexChanged update both indices independently`() = runTest {
        val capability = ManualIsoCapability(isoRange = 100..3200, exposureTimeRange = 1_000L..500_000_000L)

        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.onManualIsoCapabilityChanged(capability)
            awaitItem() // loaded

            viewModel.onIsoIndexChanged(3)
            val isoSet = awaitItem()
            assertEquals(3, isoSet.selectedIsoIndex)

            viewModel.onShutterIndexChanged(1)
            val shutterSet = awaitItem()
            assertEquals(1, shutterSet.selectedShutterIndex)
            assertEquals(3, shutterSet.selectedIsoIndex) // untouched by the shutter change
        }
    }

    @Test
    fun `onManualModeToggled falls back to auto from manual`() = runTest {
        val capability = ManualIsoCapability(isoRange = 100..3200, exposureTimeRange = 1_000L..500_000_000L)

        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.onManualIsoCapabilityChanged(capability)
            awaitItem()

            viewModel.onManualModeToggled()
            assertTrue(awaitItem().manualModeEnabled)

            viewModel.onShutterIndexChanged(2)
            awaitItem()

            viewModel.onManualModeToggled()
            assertFalse(awaitItem().manualModeEnabled)
        }
    }

    @Test
    fun `losing all manual capability on a lens switch falls back to auto instead of leaving a stale index`() = runTest {
        val wideCapability = ManualIsoCapability(isoRange = 100..3200, exposureTimeRange = 1_000L..500_000_000L)

        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.onManualIsoCapabilityChanged(wideCapability)
            val loaded = awaitItem()

            viewModel.onManualModeToggled()
            assertTrue(awaitItem().manualModeEnabled)

            viewModel.onIsoIndexChanged(loaded.isoStops.lastIndex) // pointed at the top stop
            val manual = awaitItem()
            assertTrue(manual.manualModeEnabled)

            // Switch to an ultra-wide lens lacking MANUAL_SENSOR entirely.
            viewModel.onManualIsoCapabilityChanged(null)
            val fellBackToAuto = awaitItem()
            assertFalse(fellBackToAuto.manualModeEnabled)
            assertFalse(fellBackToAuto.manualIsoSupported)
            assertEquals(emptyList<Int>(), fellBackToAuto.isoStops)
            assertEquals(emptyList<Long>(), fellBackToAuto.shutterStops)
            assertEquals(0, fellBackToAuto.selectedIsoIndex)
            assertEquals(0, fellBackToAuto.selectedShutterIndex)
        }
    }

    @Test
    fun `losing only shutter capability keeps manual mode on for the still-supported ISO parameter`() = runTest {
        val wideCapability = ManualIsoCapability(isoRange = 100..3200, exposureTimeRange = 1_000L..500_000_000L)
        // ISO range still aligns to the standard ladder; exposure-time range no longer covers any stop.
        val isoOnlyCapability = ManualIsoCapability(isoRange = 100..3200, exposureTimeRange = 1L..10L)

        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.onManualIsoCapabilityChanged(wideCapability)
            awaitItem() // loaded

            viewModel.onManualModeToggled()
            val manual = awaitItem()
            assertTrue(manual.manualModeEnabled)

            viewModel.onIsoIndexChanged(2)
            awaitItem()

            viewModel.onManualIsoCapabilityChanged(isoOnlyCapability)
            val narrowed = awaitItem()
            assertTrue(narrowed.manualModeEnabled) // ISO stops still available, so manual mode stays on
            assertTrue(narrowed.manualIsoSupported)
            assertEquals(emptyList<Long>(), narrowed.shutterStops)
            assertEquals(0, narrowed.selectedShutterIndex) // re-clamped rather than left dangling
        }
    }

    @Test
    fun `a narrower stop list on lens switch re-clamps a dangling selected index for both parameters`() = runTest {
        val wideCapability = ManualIsoCapability(isoRange = 100..25600, exposureTimeRange = 1_000L..500_000_000L)
        val narrowCapability = ManualIsoCapability(isoRange = 100..400, exposureTimeRange = 1_000L..100_000_000L)

        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.onManualIsoCapabilityChanged(wideCapability)
            val loaded = awaitItem()

            viewModel.onIsoIndexChanged(loaded.isoStops.lastIndex) // e.g. index for 25600
            awaitItem()

            viewModel.onShutterIndexChanged(loaded.shutterStops.lastIndex)
            awaitItem()

            viewModel.onManualIsoCapabilityChanged(narrowCapability)
            val narrowed = awaitItem()
            assertEquals(listOf(100, 200, 400), narrowed.isoStops)
            assertEquals(narrowed.isoStops.lastIndex, narrowed.selectedIsoIndex)
            assertEquals(narrowed.shutterStops.lastIndex, narrowed.selectedShutterIndex)
        }
    }

    @Test
    fun `setFlashMode delegates to the repository`() {
        every { cameraRepository.setFlashMode(FlashMode.ON) } returns Unit

        viewModel.setFlashMode(FlashMode.ON)

        verify { cameraRepository.setFlashMode(FlashMode.ON) }
    }

    @Test
    fun `manualIsoCapability delegates to the repository and returns its result`() {
        val lens = CameraLens(logicalCameraId = "0", physicalCameraId = null, zoomRatio = 1f)
        val capability = ManualIsoCapability(isoRange = 100..3200, exposureTimeRange = 1_000L..500_000_000L)
        every { cameraRepository.manualIsoCapability(lens) } returns capability

        assertEquals(capability, viewModel.manualIsoCapability(lens))
        verify { cameraRepository.manualIsoCapability(lens) }
    }

    @Test
    fun `setManualExposure delegates to the repository`() {
        every { cameraRepository.setManualExposure(400, 250_000L) } returns Unit

        viewModel.setManualExposure(400, 250_000L)

        verify { cameraRepository.setManualExposure(400, 250_000L) }
    }

    @Test
    fun `listBackLenses delegates to the repository`() {
        val lenses = listOf(CameraLens(logicalCameraId = "0", physicalCameraId = null, zoomRatio = 1f))
        every { cameraRepository.listBackLenses() } returns lenses

        assertEquals(lenses, viewModel.listBackLenses())
    }

    @Test
    fun `latestGalleryPhotoUri delegates to the repository`() = runTest {
        val uri = mockk<Uri>()
        coEvery { cameraRepository.latestGalleryPhotoUri() } returns uri

        assertEquals(uri, viewModel.latestGalleryPhotoUri())
    }

    @Test
    fun `stopOrientationListener delegates to the repository`() {
        every { cameraRepository.stopOrientationListener() } returns Unit

        viewModel.stopOrientationListener()

        verify { cameraRepository.stopOrientationListener() }
    }

    @Test
    fun `takePhoto delegates to the repository and passes through a successful Result`() = runTest {
        val uri = mockk<Uri>()
        coEvery { cameraRepository.takePhoto() } returns Result.Success(uri)

        val result = viewModel.takePhoto()

        assertEquals(Result.Success(uri), result)
        coVerify { cameraRepository.takePhoto() }
    }

    @Test
    fun `takePhoto delegates to the repository and passes through a failed Result`() = runTest {
        coEvery { cameraRepository.takePhoto() } returns Result.Error(DataError.Local.UNKNOWN)

        val result = viewModel.takePhoto()

        assertEquals(Result.Error(DataError.Local.UNKNOWN), result)
    }

    @Test
    fun `auto ISO updates move selectedIsoIndex to the nearest stop while auto exposure is active`() = runTest {
        val capability = ManualIsoCapability(isoRange = 100..3200, exposureTimeRange = 1_000L..500_000_000L)

        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.onManualIsoCapabilityChanged(capability)
            val loaded = awaitItem()
            assertEquals(0, loaded.selectedIsoIndex) // stops start [100, 200, 400, 800, 1600, 3200]

            autoIsoFlow.value = 340 // nearest is 400 (index 2)
            val updated = awaitItem()
            assertEquals(2, updated.selectedIsoIndex)
            assertFalse(updated.manualModeEnabled)

            autoIsoFlow.value = 1500 // nearest is 1600 (index 4), tracks live as lighting changes
            val trackedAgain = awaitItem()
            assertEquals(4, trackedAgain.selectedIsoIndex)
        }
    }

    @Test
    fun `auto ISO updates keep tracking through the grace period, then stop once exposure is pinned`() = runTest {
        val capability = ManualIsoCapability(isoRange = 100..3200, exposureTimeRange = 1_000L..500_000_000L)

        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.onManualIsoCapabilityChanged(capability)
            awaitItem() // loaded

            viewModel.onManualModeToggled()
            assertTrue(awaitItem().manualModeEnabled)

            // Dials are visible, but exposure isn't pinned yet — still tracking live auto-ISO so a
            // just-dialed EV compensation has time to actually land before manual mode freezes on it.
            autoIsoFlow.value = 3234 // not a ladder stop — verifies liveAutoIso keeps the exact reading
            val tracked = awaitItem()
            assertEquals(5, tracked.selectedIsoIndex) // nearest ladder stop, 3200, at index 5
            assertEquals(3234, tracked.liveAutoIso) // exact reading, not rounded to the ladder stop

            viewModel.onManualExposurePinningReady()
            assertTrue(awaitItem().manualExposurePinned)

            // Now genuinely pinned — must not fight the user's manual drag.
            autoIsoFlow.value = 100
            expectNoEvents()
        }
    }

    @Test
    fun `onIsoIndexChanged clears liveAutoIso so a manual drag isn't overridden by a stale exact reading`() = runTest {
        val capability = ManualIsoCapability(isoRange = 100..3200, exposureTimeRange = 1_000L..500_000_000L)

        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.onManualIsoCapabilityChanged(capability)
            awaitItem() // loaded

            viewModel.onManualModeToggled()
            assertTrue(awaitItem().manualModeEnabled)

            autoIsoFlow.value = 3234
            assertEquals(3234, awaitItem().liveAutoIso)

            viewModel.onManualExposurePinningReady()
            assertTrue(awaitItem().manualExposurePinned)

            viewModel.onIsoIndexChanged(1)
            val dragged = awaitItem()
            assertEquals(1, dragged.selectedIsoIndex)
            assertEquals(null, dragged.liveAutoIso)
        }
    }

    @Test
    fun `auto ISO updates are a no-op with no ISO stops loaded yet`() = runTest {
        viewModel.uiState.test {
            awaitItem() // initial

            autoIsoFlow.value = 400
            expectNoEvents()
        }
    }

    @Test
    fun `entering manual mode starts from whatever auto-ISO last settled on`() = runTest {
        val capability = ManualIsoCapability(isoRange = 100..3200, exposureTimeRange = 1_000L..500_000_000L)

        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.onManualIsoCapabilityChanged(capability)
            awaitItem() // loaded

            autoIsoFlow.value = 1500 // auto settles on ~1600 (index 4)
            val tracked = awaitItem()
            assertEquals(4, tracked.selectedIsoIndex)

            viewModel.onManualModeToggled()
            val manual = awaitItem()
            assertTrue(manual.manualModeEnabled)
            assertEquals(4, manual.selectedIsoIndex) // unchanged by merely engaging manual mode
        }
    }

    @Test
    fun `auto exposure time updates move selectedShutterIndex to the nearest stop while auto exposure is active`() = runTest {
        val capability = ManualIsoCapability(isoRange = 100..3200, exposureTimeRange = 1_000L..500_000_000L)

        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.onManualIsoCapabilityChanged(capability)
            val loaded = awaitItem()
            // shutterStops starts [125_000 (1/8000), 250_000 (1/4000), 500_000 (1/2000), ...]
            assertEquals(0, loaded.selectedShutterIndex)

            autoExposureTimeFlow.value = 240_000L // nearest is 250_000 (index 1)
            val updated = awaitItem()
            assertEquals(1, updated.selectedShutterIndex)
            assertFalse(updated.manualModeEnabled)

            autoExposureTimeFlow.value = 900_000L // nearest is 1_000_000 (index 3), tracks live
            val trackedAgain = awaitItem()
            assertEquals(3, trackedAgain.selectedShutterIndex)
        }
    }

    @Test
    fun `auto exposure time updates keep tracking through the grace period, then stop once pinned`() = runTest {
        val capability = ManualIsoCapability(isoRange = 100..3200, exposureTimeRange = 1_000L..500_000_000L)

        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.onManualIsoCapabilityChanged(capability)
            awaitItem() // loaded

            viewModel.onManualModeToggled()
            assertTrue(awaitItem().manualModeEnabled)

            // Dials are visible, but exposure isn't pinned yet — still tracking live auto-exposure.
            autoExposureTimeFlow.value = 490_000_000L // nearest ladder stop is the 500_000_000 top stop
            val tracked = awaitItem()
            assertEquals(tracked.shutterStops.lastIndex, tracked.selectedShutterIndex)
            assertEquals(490_000_000L, tracked.liveAutoExposureTimeNs) // exact reading, not the rounded stop

            viewModel.onManualExposurePinningReady()
            assertTrue(awaitItem().manualExposurePinned)

            // Now genuinely pinned — must not fight the user's manual drag.
            autoExposureTimeFlow.value = 1_000_000L
            expectNoEvents()
        }
    }

    @Test
    fun `onShutterIndexChanged clears liveAutoExposureTimeNs so a manual drag isn't overridden`() = runTest {
        val capability = ManualIsoCapability(isoRange = 100..3200, exposureTimeRange = 1_000L..500_000_000L)

        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.onManualIsoCapabilityChanged(capability)
            awaitItem() // loaded

            viewModel.onManualModeToggled()
            assertTrue(awaitItem().manualModeEnabled)

            autoExposureTimeFlow.value = 490_000_000L
            assertEquals(490_000_000L, awaitItem().liveAutoExposureTimeNs)

            viewModel.onManualExposurePinningReady()
            assertTrue(awaitItem().manualExposurePinned)

            viewModel.onShutterIndexChanged(1)
            val dragged = awaitItem()
            assertEquals(1, dragged.selectedShutterIndex)
            assertEquals(null, dragged.liveAutoExposureTimeNs)
        }
    }

    @Test
    fun `auto exposure time updates are a no-op with no shutter stops loaded yet`() = runTest {
        viewModel.uiState.test {
            awaitItem() // initial

            autoExposureTimeFlow.value = 250_000L
            expectNoEvents()
        }
    }

    @Test
    fun `entering manual mode starts from whatever auto exposure last settled on`() = runTest {
        val capability = ManualIsoCapability(isoRange = 100..3200, exposureTimeRange = 1_000L..500_000_000L)

        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.onManualIsoCapabilityChanged(capability)
            awaitItem() // loaded

            autoExposureTimeFlow.value = 900_000L // auto settles on ~1_000_000 (index 3)
            val tracked = awaitItem()
            assertEquals(3, tracked.selectedShutterIndex)

            viewModel.onManualModeToggled()
            val manual = awaitItem()
            assertTrue(manual.manualModeEnabled)
            assertEquals(3, manual.selectedShutterIndex) // unchanged by merely engaging manual mode
        }
    }

    @Test
    fun `aeCompensationCapability delegates to the repository and returns its result`() {
        val lens = CameraLens(logicalCameraId = "0", physicalCameraId = null, zoomRatio = 1f)
        val capability = AeCompensationCapability(range = -6..6, stepEv = 1f / 3f)
        every { cameraRepository.aeCompensationCapability(lens) } returns capability

        assertEquals(capability, viewModel.aeCompensationCapability(lens))
        verify { cameraRepository.aeCompensationCapability(lens) }
    }

    @Test
    fun `setExposureCompensation delegates to the repository`() {
        every { cameraRepository.setExposureCompensation(3) } returns Unit

        viewModel.setExposureCompensation(3)

        verify { cameraRepository.setExposureCompensation(3) }
    }

    @Test
    fun `onAeCompensationCapabilityChanged derives stops and step, resetting the index to 0 EV`() = runTest {
        val capability = AeCompensationCapability(range = -6..6, stepEv = 1f / 3f)

        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.onAeCompensationCapabilityChanged(capability)
            val updated = awaitItem()
            assertEquals((-6..6).toList(), updated.aeCompensationStops)
            assertEquals(1f / 3f, updated.aeCompensationStepEv)
            assertEquals(updated.aeCompensationStops.indexOf(0), updated.selectedAeCompensationIndex)
        }
    }

    @Test
    fun `onAeCompensationCapabilityChanged with no capability clears the stops`() = runTest {
        val capability = AeCompensationCapability(range = -6..6, stepEv = 1f / 3f)

        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.onAeCompensationCapabilityChanged(capability)
            awaitItem()

            viewModel.onAeCompensationCapabilityChanged(null)
            val cleared = awaitItem()
            assertEquals(emptyList<Int>(), cleared.aeCompensationStops)
            assertEquals(0f, cleared.aeCompensationStepEv)
            assertEquals(0, cleared.selectedAeCompensationIndex)
        }
    }

    @Test
    fun `onAeCompensationIndexChanged clamps the index`() = runTest {
        val capability = AeCompensationCapability(range = -6..6, stepEv = 1f / 3f)

        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.onAeCompensationCapabilityChanged(capability)
            val loaded = awaitItem()
            val lastIndex = loaded.aeCompensationStops.lastIndex

            viewModel.onAeCompensationIndexChanged(lastIndex + 10) // out of range on purpose
            val updated = awaitItem()
            assertEquals(lastIndex, updated.selectedAeCompensationIndex)
        }
    }

    @Test
    fun `onAeCompensationIndexChanged is a no-op with no stops loaded`() = runTest {
        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.onAeCompensationIndexChanged(2)
            expectNoEvents()
        }
    }

    @Test
    fun `setZebraAnalysisEnabled delegates to the repository`() {
        every { cameraRepository.setZebraAnalysisEnabled(true) } returns Unit

        viewModel.setZebraAnalysisEnabled(true)

        verify { cameraRepository.setZebraAnalysisEnabled(true) }
    }

    @Test
    fun `zebraMask mirrors the repository's flow`() = runTest {
        viewModel.zebraMask.test {
            assertEquals(null, awaitItem())

            val mask = ZebraMask(columns = 1, rows = 1, cells = listOf(ZebraClipping.SHADOW))
            zebraMaskFlow.value = mask
            assertEquals(mask, awaitItem())
        }
    }

    @Test
    fun `histogramData mirrors the repository's flow`() = runTest {
        viewModel.histogramData.test {
            assertEquals(null, awaitItem())

            val data = HistogramData(listOf(1, 2, 3))
            histogramDataFlow.value = data
            assertEquals(data, awaitItem())
        }
    }

    @Test
    fun `cameraSettings mirrors the settings repository's flow`() = runTest {
        viewModel.cameraSettings.test {
            assertEquals(CameraSettings(), awaitItem())

            val settings = CameraSettings(showGrid = true, showHistogram = false, showHorizonLine = false)
            cameraSettingsFlow.value = settings
            assertEquals(settings, awaitItem())
        }
    }

    @Test
    fun `afConvergenceState mirrors the repository's flow`() = runTest {
        viewModel.afConvergenceState.test {
            assertEquals(null, awaitItem())

            afConvergenceStateFlow.value = AfConvergenceState.SCANNING
            assertEquals(AfConvergenceState.SCANNING, awaitItem())

            afConvergenceStateFlow.value = AfConvergenceState.FOCUSED
            assertEquals(AfConvergenceState.FOCUSED, awaitItem())
        }
    }

    @Test
    fun `manualFocusCapability delegates to the repository and returns its result`() {
        val lens = CameraLens(logicalCameraId = "0", physicalCameraId = null, zoomRatio = 1f)
        val capability = ManualFocusCapability(maxFocusDistanceDiopters = 10f)
        every { cameraRepository.manualFocusCapability(lens) } returns capability

        assertEquals(capability, viewModel.manualFocusCapability(lens))
        verify { cameraRepository.manualFocusCapability(lens) }
    }

    @Test
    fun `triggerAutoFocus delegates to the repository`() {
        every { cameraRepository.triggerAutoFocus(0.3f, 0.6f) } returns Unit

        viewModel.triggerAutoFocus(0.3f, 0.6f)

        verify { cameraRepository.triggerAutoFocus(0.3f, 0.6f) }
    }

    @Test
    fun `setManualFocusDistance delegates to the repository`() {
        every { cameraRepository.setManualFocusDistance(2.5f) } returns Unit

        viewModel.setManualFocusDistance(2.5f)

        verify { cameraRepository.setManualFocusDistance(2.5f) }
    }

    @Test
    fun `setLut delegates to the repository`() = runTest {
        coEvery { cameraRepository.setLut("abc", 70) } returns Unit

        viewModel.setLut("abc", 70)

        coVerify { cameraRepository.setLut("abc", 70) }
    }

    @Test
    fun `onManualFocusCapabilityChanged reflects a supported lens`() = runTest {
        val capability = ManualFocusCapability(maxFocusDistanceDiopters = 6.5f)

        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.onManualFocusCapabilityChanged(capability)
            val updated = awaitItem()
            assertTrue(updated.manualFocusSupported)
            assertEquals(6.5f, updated.maxFocusDistanceDiopters)
        }
    }

    @Test
    fun `onManualFocusCapabilityChanged with no capability hides the feature`() = runTest {
        val capability = ManualFocusCapability(maxFocusDistanceDiopters = 6.5f)

        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.onManualFocusCapabilityChanged(capability)
            assertTrue(awaitItem().manualFocusSupported)

            viewModel.onManualFocusCapabilityChanged(null)
            val updated = awaitItem()
            assertFalse(updated.manualFocusSupported)
            assertEquals(0f, updated.maxFocusDistanceDiopters)
        }
    }

    @Test
    fun `isLutResolving mirrors whether observeResolvingLutId holds a non-null id`() = runTest {
        viewModel.isLutResolving.test {
            assertEquals(false, awaitItem())

            resolvingLutIdFlow.value = "1"
            assertEquals(true, awaitItem())

            resolvingLutIdFlow.value = null
            assertEquals(false, awaitItem())
        }
    }

    @Test
    fun `liveFocusDistanceDiopters tracks the repository's focus distance flow`() = runTest {
        viewModel.uiState.test {
            awaitItem() // initial

            focusDistanceFlow.value = 3.2f
            assertEquals(3.2f, awaitItem().liveFocusDistanceDiopters)

            focusDistanceFlow.value = 1.1f
            assertEquals(1.1f, awaitItem().liveFocusDistanceDiopters)
        }
    }
}
