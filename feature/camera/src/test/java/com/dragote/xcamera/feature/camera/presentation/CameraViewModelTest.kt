package com.dragote.xcamera.feature.camera.presentation

import android.net.Uri
import app.cash.turbine.test
import com.dragote.xcamera.feature.camera.domain.model.CameraLens
import com.dragote.xcamera.feature.camera.domain.model.CameraPermissionStatus
import com.dragote.xcamera.feature.camera.domain.model.FlashMode
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
}
