package com.dragote.xcamera.feature.diagnostics.presentation

import app.cash.turbine.test
import com.dragote.xcamera.shared.common.domain.result.DataError
import com.dragote.xcamera.shared.common.domain.result.Result
import com.dragote.xcamera.shared.diagnostics.domain.model.FeatureSupport
import com.dragote.xcamera.shared.diagnostics.domain.model.LensDiagnostics
import com.dragote.xcamera.shared.diagnostics.domain.model.LensSnapshot
import com.dragote.xcamera.shared.diagnostics.domain.usecase.GetLensDiagnosticsUseCase
import com.dragote.xcamera.shared.testing.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class DiagnosticsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var getLensDiagnostics: GetLensDiagnosticsUseCase

    private val lens = LensDiagnostics(
        displayLabel = "1× MAIN",
        snapshot = LensSnapshot(
            logicalCameraId = "0",
            physicalCameraId = null,
            zoomRatio = 1f,
            focalLengthMm = 6.86f,
            equivalentFocalLengthMm = 24.3f,
            sensorWidthMm = 9.8f,
            sensorHeightMm = 7.3f,
            pixelArrayWidth = 4032,
            pixelArrayHeight = 3024,
            apertureFNumber = 1.8f,
        ),
        rawCapture = FeatureSupport.Supported("12 MP RAW"),
        manualIsoAndShutter = FeatureSupport.Supported("ISO 50–3200"),
        manualFocus = FeatureSupport.Supported("down to 10 cm"),
    )

    @Before
    fun setUp() {
        getLensDiagnostics = mockk()
    }

    @Test
    fun `uiState loads the lens list on init`() = runTest {
        every { getLensDiagnostics() } returns Result.Success(listOf(lens))

        val viewModel = DiagnosticsViewModel(getLensDiagnostics)

        viewModel.uiState.test {
            assertEquals(DiagnosticsUiState.Loaded(listOf(lens)), awaitItem())
        }
    }

    @Test
    fun `uiState surfaces an error state on failure`() = runTest {
        every { getLensDiagnostics() } returns Result.Error(DataError.Local.CAMERA_ACCESS_UNAVAILABLE)

        val viewModel = DiagnosticsViewModel(getLensDiagnostics)

        viewModel.uiState.test {
            assertEquals(DiagnosticsUiState.Error("Couldn't read camera lens characteristics"), awaitItem())
        }
    }

    // getLensDiagnostics() isn't suspend, so both the Loading write and the result write in
    // loadDiagnostics() happen synchronously with no suspension point in between — with
    // MainDispatcherRule's UnconfinedTestDispatcher a StateFlow collector never observes the
    // transient Loading here, only the final result (the same StateFlow-conflation shape
    // SettingsViewModelTest's own isImportingLut tests document, just with no way to inject a real
    // suspension point since this call chain has none to begin with).
    @Test
    fun `loadDiagnostics reloads with a fresh result`() = runTest {
        every { getLensDiagnostics() } returns Result.Success(listOf(lens))
        val viewModel = DiagnosticsViewModel(getLensDiagnostics)

        every { getLensDiagnostics() } returns Result.Error(DataError.Local.CAMERA_ACCESS_UNAVAILABLE)

        viewModel.uiState.test {
            assertEquals(DiagnosticsUiState.Loaded(listOf(lens)), awaitItem())

            viewModel.loadDiagnostics()

            assertEquals(DiagnosticsUiState.Error("Couldn't read camera lens characteristics"), awaitItem())
        }
    }
}
