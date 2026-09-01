package com.dragote.xcamera.feature.settings.presentation

import android.net.Uri
import app.cash.turbine.test
import com.dragote.xcamera.shared.common.domain.model.AccentColor
import com.dragote.xcamera.shared.common.domain.model.CameraSettings
import com.dragote.xcamera.shared.common.domain.model.FocusPeakingSensitivity
import com.dragote.xcamera.shared.common.domain.model.LutPreset
import com.dragote.xcamera.shared.common.domain.repository.CameraSettingsRepository
import com.dragote.xcamera.shared.common.domain.repository.LutRepository
import com.dragote.xcamera.shared.common.domain.repository.LutResolutionRepository
import com.dragote.xcamera.shared.common.domain.result.DataError
import com.dragote.xcamera.shared.common.domain.result.Result
import com.dragote.xcamera.shared.testing.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
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
    private lateinit var lutResolutionRepository: LutResolutionRepository
    private lateinit var settingsFlow: MutableStateFlow<CameraSettings>
    private lateinit var lutsFlow: MutableStateFlow<List<LutPreset>>
    private lateinit var resolvingLutIdFlow: MutableStateFlow<String?>
    private lateinit var resolutionFailuresFlow: MutableSharedFlow<String>
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        cameraSettingsRepository = mockk(relaxUnitFun = true)
        lutRepository = mockk(relaxUnitFun = true)
        lutResolutionRepository = mockk(relaxUnitFun = true)
        settingsFlow = MutableStateFlow(CameraSettings())
        lutsFlow = MutableStateFlow(emptyList())
        resolvingLutIdFlow = MutableStateFlow(null)
        // extraBufferCapacity = 1 mirrors CameraRepositoryImpl's own _resolutionFailures — never
        // actually relied on by these tests (each test that emits into it does so after the
        // ViewModel's init collector has already started, thanks to MainDispatcherRule's
        // UnconfinedTestDispatcher), but keeps this default stub emit-safe regardless.
        resolutionFailuresFlow = MutableSharedFlow(extraBufferCapacity = 1)
        every { cameraSettingsRepository.observeSettings() } returns settingsFlow
        every { lutRepository.observeLuts() } returns lutsFlow
        every { lutResolutionRepository.observeResolvingLutId() } returns resolvingLutIdFlow
        every { lutResolutionRepository.observeResolutionFailures() } returns resolutionFailuresFlow
        viewModel = SettingsViewModel(cameraSettingsRepository, lutRepository, lutResolutionRepository)
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
                captureRawByDefault = true,
                minimalChromeInverted = true,
                hapticFeedbackEnabled = false,
                accentColor = AccentColor.GREEN,
            )
            assertEquals(
                SettingsUiState(
                    showGrid = true,
                    showHistogram = false,
                    showHorizonLine = false,
                    focusPeakingSensitivity = FocusPeakingSensitivity.HIGH,
                    selectedLutId = "abc",
                    lutIntensityPercent = 42,
                    captureRawByDefault = true,
                    minimalChromeInverted = true,
                    hapticFeedbackEnabled = false,
                    accentColor = AccentColor.GREEN,
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
    fun `uiState reflects the resolving LUT id`() = runTest {
        viewModel.uiState.test {
            assertEquals(null, awaitItem()?.resolvingLutId)

            resolvingLutIdFlow.value = "1"
            assertEquals("1", awaitItem()?.resolvingLutId)

            resolvingLutIdFlow.value = null
            assertEquals(null, awaitItem()?.resolvingLutId)
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
    fun `onCaptureRawByDefaultToggled delegates to the repository`() = runTest {
        viewModel.onCaptureRawByDefaultToggled(true)

        coVerify { cameraSettingsRepository.setCaptureRawByDefault(true) }
    }

    @Test
    fun `onMinimalChromeInvertedToggled delegates to the repository`() = runTest {
        viewModel.onMinimalChromeInvertedToggled(true)

        coVerify { cameraSettingsRepository.setMinimalChromeInverted(true) }
    }

    @Test
    fun `onHapticFeedbackEnabledToggled delegates to the repository`() = runTest {
        viewModel.onHapticFeedbackEnabledToggled(false)

        coVerify { cameraSettingsRepository.setHapticFeedbackEnabled(false) }
    }

    @Test
    fun `onAccentColorSelected delegates to the repository`() = runTest {
        viewModel.onAccentColorSelected(AccentColor.ORANGE)

        coVerify { cameraSettingsRepository.setAccentColor(AccentColor.ORANGE) }
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

    // A real suspension point (delay) inside the mocked repository call, not an instantaneous
    // coEvery — with MainDispatcherRule's UnconfinedTestDispatcher, an instantaneous mocked suspend
    // function completes the whole true→false transition in one synchronous stretch with no
    // opportunity for a Turbine-collected StateFlow to observe the transient true in between (a
    // classic StateFlow-conflation trap for this exact test shape), so onLutImportRequested's own
    // isImportingLut flip would otherwise appear to just stay false. Delaying gives the collector a
    // genuine chance to see the intermediate state, mirroring how the real file-copy this flag guards
    // is itself never instantaneous either.
    @Test
    fun `isImportingLut toggles true then false around a successful import`() = runTest {
        val uri = mockk<Uri>()
        val preset = LutPreset(id = "new-id", displayName = "My LUT", filePath = "/luts/new-id.cube")
        coEvery { lutRepository.importLut(uri, "My LUT") } coAnswers {
            delay(1)
            Result.Success(preset)
        }

        viewModel.isImportingLut.test {
            assertEquals(false, awaitItem())

            viewModel.onLutImportRequested(uri, "My LUT")
            assertEquals(true, awaitItem())
            assertEquals(false, awaitItem())
        }
    }

    @Test
    fun `isImportingLut toggles true then false even when the import fails`() = runTest {
        val uri = mockk<Uri>()
        coEvery { lutRepository.importLut(uri, "My LUT") } coAnswers {
            delay(1)
            Result.Error(DataError.Local.UNKNOWN)
        }

        viewModel.isImportingLut.test {
            assertEquals(false, awaitItem())

            viewModel.onLutImportRequested(uri, "My LUT")
            assertEquals(true, awaitItem())
            assertEquals(false, awaitItem())
        }
    }

    @Test
    fun `onLutDeleteRequested clears the selection first when deleting the currently-selected LUT`() = runTest {
        settingsFlow.value = CameraSettings(selectedLutId = "1")
        coEvery { lutRepository.deleteLut("1") } returns Result.Success(Unit)

        viewModel.onLutDeleteRequested("1")

        coVerifyOrder {
            cameraSettingsRepository.setSelectedLutId(null)
            lutRepository.deleteLut("1")
        }
    }

    @Test
    fun `onLutDeleteRequested doesn't touch selection when deleting an unselected LUT`() = runTest {
        settingsFlow.value = CameraSettings(selectedLutId = "1")
        coEvery { lutRepository.deleteLut("2") } returns Result.Success(Unit)

        viewModel.onLutDeleteRequested("2")

        coVerify(exactly = 0) { cameraSettingsRepository.setSelectedLutId(any()) }
        coVerify { lutRepository.deleteLut("2") }
    }

    @Test
    fun `a resolution failure deletes the broken LUT, clears a matching selection, and surfaces an error`() = runTest {
        settingsFlow.value = CameraSettings(selectedLutId = "1")
        coEvery { lutRepository.deleteLut("1") } returns Result.Success(Unit)

        viewModel.lutImportError.test {
            assertEquals(null, awaitItem())

            resolutionFailuresFlow.emit("1")

            assertEquals("Invalid or unreadable LUT file", awaitItem())
        }
        coVerifyOrder {
            cameraSettingsRepository.setSelectedLutId(null)
            lutRepository.deleteLut("1")
        }
    }

    @Test
    fun `a resolution failure for a LUT that's no longer selected still deletes it but doesn't touch selection`() = runTest {
        settingsFlow.value = CameraSettings(selectedLutId = "2") // a newer selection made in the meantime
        coEvery { lutRepository.deleteLut("1") } returns Result.Success(Unit)

        viewModel.lutImportError.test {
            assertEquals(null, awaitItem())

            resolutionFailuresFlow.emit("1")

            assertEquals("Invalid or unreadable LUT file", awaitItem())
        }
        coVerify(exactly = 0) { cameraSettingsRepository.setSelectedLutId(any()) }
        coVerify { lutRepository.deleteLut("1") }
    }
}
