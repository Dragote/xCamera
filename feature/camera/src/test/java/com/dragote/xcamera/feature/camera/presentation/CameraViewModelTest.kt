package com.dragote.xcamera.feature.camera.presentation

import android.net.Uri
import app.cash.turbine.test
import com.dragote.xcamera.feature.camera.domain.model.CameraLens
import com.dragote.xcamera.feature.camera.domain.model.CameraPermissionStatus
import com.dragote.xcamera.feature.camera.domain.model.FlashMode
import com.dragote.xcamera.feature.camera.domain.model.ManualIsoCapability
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraViewModelTest {

    private val viewModel = CameraViewModel()

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
    fun `onManualIsoCapabilityChanged derives supported ISO stops from the capability's sensitivity range`() = runTest {
        val capability = ManualIsoCapability(isoRange = 100..3200, exposureTimeRange = 1_000L..500_000_000L)

        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.onManualIsoCapabilityChanged(capability)
            val updated = awaitItem()
            assertTrue(updated.manualIsoSupported)
            assertEquals(listOf(100, 200, 400, 800, 1600, 3200), updated.isoStops)
        }
    }

    @Test
    fun `onManualIsoCapabilityChanged with no capability disables manual ISO`() = runTest {
        val capability = ManualIsoCapability(isoRange = 100..3200, exposureTimeRange = 1_000L..500_000_000L)

        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.onManualIsoCapabilityChanged(capability)
            assertTrue(awaitItem().manualIsoSupported)

            viewModel.onManualIsoCapabilityChanged(null)
            val updated = awaitItem()
            assertFalse(updated.manualIsoSupported)
            assertEquals(emptyList<Int>(), updated.isoStops)
        }
    }

    @Test
    fun `dragging the ISO dial enters manual mode without requiring an index change`() = runTest {
        val capability = ManualIsoCapability(isoRange = 100..3200, exposureTimeRange = 1_000L..500_000_000L)

        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.onManualIsoCapabilityChanged(capability)
            assertFalse(awaitItem().manualModeEnabled)

            viewModel.onIsoDialDragStarted()
            assertTrue(awaitItem().manualModeEnabled)
        }
    }

    @Test
    fun `onIsoIndexChanged clamps the index and engages manual mode`() = runTest {
        val capability = ManualIsoCapability(isoRange = 100..3200, exposureTimeRange = 1_000L..500_000_000L)

        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.onManualIsoCapabilityChanged(capability)
            val loaded = awaitItem()
            val lastIndex = loaded.isoStops.lastIndex

            viewModel.onIsoIndexChanged(lastIndex + 10) // out of range on purpose
            val updated = awaitItem()
            assertEquals(lastIndex, updated.selectedIsoIndex)
            assertTrue(updated.manualModeEnabled)
        }
    }

    @Test
    fun `onManualModeExitRequested falls back to auto`() = runTest {
        val capability = ManualIsoCapability(isoRange = 100..3200, exposureTimeRange = 1_000L..500_000_000L)

        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.onManualIsoCapabilityChanged(capability)
            awaitItem()

            viewModel.onIsoDialDragStarted()
            assertTrue(awaitItem().manualModeEnabled)

            viewModel.onManualModeExitRequested()
            assertFalse(awaitItem().manualModeEnabled)
        }
    }

    @Test
    fun `losing manual ISO capability on a lens switch falls back to auto instead of leaving a stale index`() = runTest {
        val wideCapability = ManualIsoCapability(isoRange = 100..3200, exposureTimeRange = 1_000L..500_000_000L)

        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.onManualIsoCapabilityChanged(wideCapability)
            val loaded = awaitItem()

            viewModel.onIsoIndexChanged(loaded.isoStops.lastIndex) // manual mode on, pointed at the top stop
            val manual = awaitItem()
            assertTrue(manual.manualModeEnabled)

            // Switch to an ultra-wide lens lacking MANUAL_SENSOR entirely.
            viewModel.onManualIsoCapabilityChanged(null)
            val fellBackToAuto = awaitItem()
            assertFalse(fellBackToAuto.manualModeEnabled)
            assertFalse(fellBackToAuto.manualIsoSupported)
            assertEquals(emptyList<Int>(), fellBackToAuto.isoStops)
            assertEquals(0, fellBackToAuto.selectedIsoIndex)
        }
    }

    @Test
    fun `a narrower stop list on lens switch re-clamps a dangling selected index`() = runTest {
        val wideCapability = ManualIsoCapability(isoRange = 100..25600, exposureTimeRange = 1_000L..500_000_000L)
        val narrowCapability = ManualIsoCapability(isoRange = 100..400, exposureTimeRange = 1_000L..100_000_000L)

        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.onManualIsoCapabilityChanged(wideCapability)
            val loaded = awaitItem()

            viewModel.onIsoIndexChanged(loaded.isoStops.lastIndex) // e.g. index for 25600
            awaitItem()

            viewModel.onManualIsoCapabilityChanged(narrowCapability)
            val narrowed = awaitItem()
            assertEquals(listOf(100, 200, 400), narrowed.isoStops)
            assertEquals(narrowed.isoStops.lastIndex, narrowed.selectedIsoIndex)
        }
    }
}
