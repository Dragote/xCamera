package com.dragote.xcamera.feature.camera.presentation

import android.net.Uri
import app.cash.turbine.test
import com.dragote.xcamera.feature.camera.domain.model.CameraLens
import com.dragote.xcamera.feature.camera.domain.model.CameraPermissionStatus
import com.dragote.xcamera.feature.camera.domain.model.FlashMode
import com.dragote.xcamera.feature.camera.domain.model.ManualControlTarget
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
    fun `default manual target is ISO`() = runTest {
        viewModel.uiState.test {
            assertEquals(ManualControlTarget.ISO, awaitItem().manualTarget)
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
    fun `dragging the dial enters manual mode without requiring an index change`() = runTest {
        val capability = ManualIsoCapability(isoRange = 100..3200, exposureTimeRange = 1_000L..500_000_000L)

        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.onManualIsoCapabilityChanged(capability)
            assertFalse(awaitItem().manualModeEnabled)

            viewModel.onManualExposureDialDragStarted()
            assertTrue(awaitItem().manualModeEnabled)
        }
    }

    @Test
    fun `dragging the dial while the active target has no stops is a no-op`() = runTest {
        // ISO range aligns to the standard ladder, exposure-time range doesn't cover any stop.
        val capability = ManualIsoCapability(isoRange = 100..3200, exposureTimeRange = 1L..10L)

        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.onManualIsoCapabilityChanged(capability)
            val loaded = awaitItem()
            assertTrue(loaded.isoStops.isNotEmpty())
            assertTrue(loaded.shutterStops.isEmpty())

            viewModel.onManualTargetSelected(ManualControlTarget.SHUTTER_SPEED)
            assertEquals(ManualControlTarget.SHUTTER_SPEED, awaitItem().manualTarget)

            // Active target (SHUTTER_SPEED) has no stops to offer — dragging shouldn't engage manual.
            viewModel.onManualExposureDialDragStarted()
            expectNoEvents()
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
    fun `onShutterIndexChanged clamps the index, engages manual mode and marks it initialized`() = runTest {
        val capability = ManualIsoCapability(isoRange = 100..3200, exposureTimeRange = 1_000L..500_000_000L)

        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.onManualIsoCapabilityChanged(capability)
            val loaded = awaitItem()
            val lastIndex = loaded.shutterStops.lastIndex

            viewModel.onShutterIndexChanged(lastIndex + 10) // out of range on purpose
            val updated = awaitItem()
            assertEquals(lastIndex, updated.selectedShutterIndex)
            assertTrue(updated.manualModeEnabled)
        }
    }

    @Test
    fun `switching manual target preserves both indices independently`() = runTest {
        val capability = ManualIsoCapability(isoRange = 100..3200, exposureTimeRange = 1_000L..500_000_000L)

        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.onManualIsoCapabilityChanged(capability)
            awaitItem() // loaded

            viewModel.onIsoIndexChanged(3)
            val isoSet = awaitItem()
            assertEquals(3, isoSet.selectedIsoIndex)

            viewModel.onManualTargetSelected(ManualControlTarget.SHUTTER_SPEED)
            val switchedToShutter = awaitItem()
            assertEquals(ManualControlTarget.SHUTTER_SPEED, switchedToShutter.manualTarget)
            assertEquals(3, switchedToShutter.selectedIsoIndex) // untouched by the switch

            viewModel.onShutterIndexChanged(1)
            val shutterSet = awaitItem()
            assertEquals(1, shutterSet.selectedShutterIndex)

            viewModel.onManualTargetSelected(ManualControlTarget.ISO)
            val switchedBackToIso = awaitItem()
            assertEquals(ManualControlTarget.ISO, switchedBackToIso.manualTarget)
            assertEquals(3, switchedBackToIso.selectedIsoIndex) // still untouched
            assertEquals(1, switchedBackToIso.selectedShutterIndex) // still untouched
        }
    }

    @Test
    fun `onManualShutterResolutionNeeded resolves the nearest stop to the auto exposure value`() = runTest {
        val capability = ManualIsoCapability(isoRange = 100..3200, exposureTimeRange = 1_000L..500_000_000L)

        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.onManualIsoCapabilityChanged(capability)
            val loaded = awaitItem()
            // shutterStops starts [125_000 (1/8000), 250_000 (1/4000), 500_000 (1/2000), ...]
            assertEquals(0, loaded.selectedShutterIndex)

            viewModel.onManualShutterResolutionNeeded(240_000L) // nearest is 250_000 (index 1)
            val resolved = awaitItem()
            assertEquals(1, resolved.selectedShutterIndex)
        }
    }

    @Test
    fun `onManualShutterResolutionNeeded is a no-op once the shutter index is already initialized`() = runTest {
        val capability = ManualIsoCapability(isoRange = 100..3200, exposureTimeRange = 1_000L..500_000_000L)

        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.onManualIsoCapabilityChanged(capability)
            awaitItem() // loaded

            viewModel.onShutterIndexChanged(2) // real user drag, marks it initialized
            val userSet = awaitItem()
            assertEquals(2, userSet.selectedShutterIndex)

            viewModel.onManualShutterResolutionNeeded(125_000L) // would otherwise resolve to index 0
            expectNoEvents()
        }
    }

    @Test
    fun `onManualShutterResolutionNeeded is a no-op with no auto exposure value yet`() = runTest {
        val capability = ManualIsoCapability(isoRange = 100..3200, exposureTimeRange = 1_000L..500_000_000L)

        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.onManualIsoCapabilityChanged(capability)
            awaitItem() // loaded

            viewModel.onManualShutterResolutionNeeded(null)
            expectNoEvents()
        }
    }

    @Test
    fun `onManualModeExitRequested falls back to auto and resets shutter index initialization`() = runTest {
        val capability = ManualIsoCapability(isoRange = 100..3200, exposureTimeRange = 1_000L..500_000_000L)

        viewModel.uiState.test {
            awaitItem() // initial

            viewModel.onManualIsoCapabilityChanged(capability)
            awaitItem()

            viewModel.onManualExposureDialDragStarted()
            assertTrue(awaitItem().manualModeEnabled)

            viewModel.onShutterIndexChanged(2)
            awaitItem()

            viewModel.onManualModeExitRequested()
            assertFalse(awaitItem().manualModeEnabled)

            // Re-entering manual mode should be able to re-resolve the shutter index fresh.
            viewModel.onManualExposureDialDragStarted()
            assertTrue(awaitItem().manualModeEnabled)

            viewModel.onManualShutterResolutionNeeded(500_000L) // nearest is index 2 (500_000) already, but exercises the reset path
            val resolved = awaitItem()
            assertEquals(2, resolved.selectedShutterIndex)
        }
    }

    @Test
    fun `losing all manual capability on a lens switch falls back to auto instead of leaving a stale index`() = runTest {
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

            viewModel.onIsoIndexChanged(2)
            val manual = awaitItem()
            assertTrue(manual.manualModeEnabled)

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
}
