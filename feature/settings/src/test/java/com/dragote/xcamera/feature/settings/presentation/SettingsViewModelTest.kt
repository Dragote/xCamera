package com.dragote.xcamera.feature.settings.presentation

import app.cash.turbine.test
import com.dragote.xcamera.shared.common.domain.model.CameraSettings
import com.dragote.xcamera.shared.common.domain.repository.CameraSettingsRepository
import com.dragote.xcamera.shared.testing.MainDispatcherRule
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
    private lateinit var settingsFlow: MutableStateFlow<CameraSettings>
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        cameraSettingsRepository = mockk(relaxUnitFun = true)
        settingsFlow = MutableStateFlow(CameraSettings())
        every { cameraSettingsRepository.observeSettings() } returns settingsFlow
        viewModel = SettingsViewModel(cameraSettingsRepository)
    }

    @Test
    fun `uiState mirrors the repository's settings`() = runTest {
        // uiState's stateIn initial value is null (not-yet-loaded), but settingsFlow already holds
        // a value before subscription and MainDispatcherRule's UnconfinedTestDispatcher runs the
        // WhileSubscribed upstream collection synchronously on subscribe — so the null is replaced
        // before this first awaitItem(), and the loaded state is all this test ever observes here.
        viewModel.uiState.test {
            assertEquals(SettingsUiState(), awaitItem())

            settingsFlow.value = CameraSettings(showGrid = true, showHistogram = false, showHorizonLine = false)
            assertEquals(SettingsUiState(showGrid = true, showHistogram = false, showHorizonLine = false), awaitItem())
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
}
