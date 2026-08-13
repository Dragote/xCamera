package com.dragote.xcamera.feature.settings.presentation

import android.net.Uri
import app.cash.turbine.test
import com.dragote.xcamera.shared.common.domain.model.CameraSettings
import com.dragote.xcamera.shared.common.domain.model.FocusPeakingSensitivity
import com.dragote.xcamera.shared.common.domain.model.LutPreset
import com.dragote.xcamera.shared.common.domain.repository.CameraSettingsRepository
import com.dragote.xcamera.shared.common.domain.repository.LutRepository
import com.dragote.xcamera.shared.common.domain.result.DataError
import com.dragote.xcamera.shared.common.domain.result.Result
import com.dragote.xcamera.shared.testing.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var cameraSettingsRepository: CameraSettingsRepository
    private lateinit var lutRepository: LutRepository
    private lateinit var settingsFlow: MutableStateFlow<CameraSettings>
    private lateinit var lutsFlow: MutableStateFlow<List<LutPreset>>
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        cameraSettingsRepository = mockk(relaxUnitFun = true)
        lutRepository = mockk(relaxUnitFun = true)
        settingsFlow = MutableStateFlow(CameraSettings())
        lutsFlow = MutableStateFlow(emptyList())
        every { cameraSettingsRepository.observeSettings() } returns settingsFlow
        every { lutRepository.observeLuts() } returns lutsFlow
        viewModel = SettingsViewModel(cameraSettingsRepository, lutRepository)
    }

    @Test
    fun `uiState mirrors the repository's settings`() = runTest {
        // uiState's stateIn initial value is null (not-yet-loaded), but settingsFlow already holds
        // a value before subscription and MainDispatcherRule's UnconfinedTestDispatcher runs the
        // WhileSubscribed upstream collection synchronously on subscribe — so the null is replaced
        // before this first awaitItem(), and the loaded state is all this test ever observes here.
        viewModel.uiState.test {
            assertEquals(SettingsUiState(), awaitItem())

            settingsFlow.value = CameraSettings(
                showGrid = true,
                showHistogram = false,
                showHorizonLine = false,
                focusPeakingSensitivity = FocusPeakingSensitivity.HIGH,
                selectedLutId = "abc",
                lutIntensityPercent = 42,
            )
            assertEquals(
                SettingsUiState(
                    showGrid = true,
                    showHistogram = false,
                    showHorizonLine = false,
                    focusPeakingSensitivity = FocusPeakingSensitivity.HIGH,
                    selectedLutId = "abc",
                    lutIntensityPercent = 42,
                ),
                awaitItem(),
            )
        }
    }

    @Test
    fun `uiState reflects the LUT list`() = runTest {
        viewModel.uiState.test {
            assertEquals(emptyList<LutPreset>(), awaitItem()?.luts)

            val lut = LutPreset(id = "1", displayName = "Portra", filePath = "/luts/1.cube")
            lutsFlow.value = listOf(lut)
            assertEquals(listOf(lut), awaitItem()?.luts)
        }
    }

    @Test
    fun `onShowGridToggled delegates to the repository`() = runTest {
        viewModel.onShowGridToggled(true)

        coVerify { cameraSettingsRepository.setShowGrid(true) }
    }

    @Test
    fun `onShowHistogramToggled delegates to the repository`() = runTest {
        viewModel.onShowHistogramToggled(false)

        coVerify { cameraSettingsRepository.setShowHistogram(false) }
    }

    @Test
    fun `onShowHorizonLineToggled delegates to the repository`() = runTest {
        viewModel.onShowHorizonLineToggled(false)

        coVerify { cameraSettingsRepository.setShowHorizonLine(false) }
    }

    @Test
    fun `onFocusPeakingSensitivityChanged delegates to the repository`() = runTest {
        viewModel.onFocusPeakingSensitivityChanged(FocusPeakingSensitivity.LOW)

        coVerify { cameraSettingsRepository.setFocusPeakingSensitivity(FocusPeakingSensitivity.LOW) }
    }

    @Test
    fun `onLutSelected delegates to the repository`() = runTest {
        viewModel.onLutSelected("abc")

        coVerify { cameraSettingsRepository.setSelectedLutId("abc") }
    }

    @Test
    fun `onLutSelected with null turns grading off`() = runTest {
        viewModel.onLutSelected(null)

        coVerify { cameraSettingsRepository.setSelectedLutId(null) }
    }

    @Test
    fun `onLutIntensityChanged delegates to the repository`() = runTest {
        viewModel.onLutIntensityChanged(65)

        coVerify { cameraSettingsRepository.setLutIntensityPercent(65) }
    }

    @Test
    fun `onLutImportRequested selects the newly imported LUT on success`() = runTest {
        val uri = mockk<Uri>()
        val preset = LutPreset(id = "new-id", displayName = "My LUT", filePath = "/luts/new-id.cube")
        coEvery { lutRepository.importLut(uri, "My LUT") } returns Result.Success(preset)

        viewModel.onLutImportRequested(uri, "My LUT")

        coVerify { cameraSettingsRepository.setSelectedLutId("new-id") }
    }

    @Test
    fun `onLutImportRequested surfaces an error and doesn't change selection on failure`() = runTest {
        val uri = mockk<Uri>()
        coEvery { lutRepository.importLut(uri, "My LUT") } returns Result.Error(DataError.Local.UNKNOWN)

        viewModel.lutImportError.test {
            assertEquals(null, awaitItem())

            viewModel.onLutImportRequested(uri, "My LUT")
            assertEquals(DataError.Local.UNKNOWN.name, awaitItem())
        }
        coVerify(exactly = 0) { cameraSettingsRepository.setSelectedLutId(any()) }
    }

    @Test
    fun `onLutImportErrorShown clears the error`() = runTest {
        val uri = mockk<Uri>()
        coEvery { lutRepository.importLut(uri, "My LUT") } returns Result.Error(DataError.Local.UNKNOWN)

        viewModel.lutImportError.test {
            awaitItem() // null

            viewModel.onLutImportRequested(uri, "My LUT")
            awaitItem() // the error

            viewModel.onLutImportErrorShown()
            assertEquals(null, awaitItem())
        }
    }
}
