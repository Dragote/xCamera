package com.dragote.xcamera.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dragote.xcamera.shared.common.domain.repository.CameraSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val cameraSettingsRepository: CameraSettingsRepository,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState?> = cameraSettingsRepository.observeSettings()
        .map { SettingsUiState(it.showGrid, it.showHistogram, it.showHorizonLine) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun onShowGridToggled(enabled: Boolean) {
        viewModelScope.launch { cameraSettingsRepository.setShowGrid(enabled) }
    }

    fun onShowHistogramToggled(enabled: Boolean) {
        viewModelScope.launch { cameraSettingsRepository.setShowHistogram(enabled) }
    }

    fun onShowHorizonLineToggled(enabled: Boolean) {
        viewModelScope.launch { cameraSettingsRepository.setShowHorizonLine(enabled) }
    }
}
