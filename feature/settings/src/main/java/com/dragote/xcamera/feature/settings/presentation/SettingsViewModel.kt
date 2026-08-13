package com.dragote.xcamera.feature.settings.presentation

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dragote.xcamera.shared.common.domain.model.FocusPeakingSensitivity
import com.dragote.xcamera.shared.common.domain.repository.CameraSettingsRepository
import com.dragote.xcamera.shared.common.domain.repository.LutRepository
import com.dragote.xcamera.shared.common.domain.result.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val cameraSettingsRepository: CameraSettingsRepository,
    private val lutRepository: LutRepository,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState?> = combine(
        cameraSettingsRepository.observeSettings(),
        lutRepository.observeLuts(),
    ) { settings, luts ->
        SettingsUiState(
            showGrid = settings.showGrid,
            showHistogram = settings.showHistogram,
            showHorizonLine = settings.showHorizonLine,
            focusPeakingSensitivity = settings.focusPeakingSensitivity,
            luts = luts,
            selectedLutId = settings.selectedLutId,
            lutIntensityPercent = settings.lutIntensityPercent,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * A transient one-shot signal, not folded into [uiState] — mirrors `CameraViewModel`'s own
     * "high-frequency/independent state doesn't belong in one shared object" reasoning, just for a
     * different reason here: a `combine`d `StateFlow` re-emits this same object on every unrelated
     * settings change, which would make an error linger/re-surface in the UI long after the import
     * attempt that caused it. `ui/SettingsScreen` shows this as a one-off toast, the same way
     * `ui/CameraScreen` does for `CameraUiState.captureError`.
     */
    private val _lutImportError = MutableStateFlow<String?>(null)
    val lutImportError: StateFlow<String?> = _lutImportError.asStateFlow()

    fun onShowGridToggled(enabled: Boolean) {
        viewModelScope.launch { cameraSettingsRepository.setShowGrid(enabled) }
    }

    fun onShowHistogramToggled(enabled: Boolean) {
        viewModelScope.launch { cameraSettingsRepository.setShowHistogram(enabled) }
    }

    fun onShowHorizonLineToggled(enabled: Boolean) {
        viewModelScope.launch { cameraSettingsRepository.setShowHorizonLine(enabled) }
    }

    fun onFocusPeakingSensitivityChanged(sensitivity: FocusPeakingSensitivity) {
        viewModelScope.launch { cameraSettingsRepository.setFocusPeakingSensitivity(sensitivity) }
    }

    /** `null` selects "OFF" — disables LUT grading entirely (see `CameraSettings.selectedLutId`'s own
     *  doc). */
    fun onLutSelected(id: String?) {
        viewModelScope.launch { cameraSettingsRepository.setSelectedLutId(id) }
    }

    fun onLutIntensityChanged(percent: Int) {
        viewModelScope.launch { cameraSettingsRepository.setLutIntensityPercent(percent) }
    }

    /**
     * [sourceUri] is the Storage Access Framework `ACTION_OPEN_DOCUMENT` result; [displayName] is
     * whatever `ui/SettingsScreen` resolved as the picked document's own display name. Auto-selects
     * the newly imported LUT on success — importing a LUT with no visible effect (still "OFF") would
     * read as broken.
     */
    fun onLutImportRequested(sourceUri: Uri, displayName: String) {
        viewModelScope.launch {
            when (val result = lutRepository.importLut(sourceUri, displayName)) {
                is Result.Success -> cameraSettingsRepository.setSelectedLutId(result.data.id)
                is Result.Error -> _lutImportError.value = result.error.name
            }
        }
    }

    fun onLutImportErrorShown() {
        _lutImportError.value = null
    }
}
