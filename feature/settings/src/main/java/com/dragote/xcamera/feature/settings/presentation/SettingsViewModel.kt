package com.dragote.xcamera.feature.settings.presentation

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dragote.xcamera.shared.common.domain.model.FocusPeakingSensitivity
import com.dragote.xcamera.shared.common.domain.repository.CameraSettingsRepository
import com.dragote.xcamera.shared.common.domain.repository.LutRepository
import com.dragote.xcamera.shared.common.domain.repository.LutResolutionRepository
import com.dragote.xcamera.shared.common.domain.result.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val cameraSettingsRepository: CameraSettingsRepository,
    private val lutRepository: LutRepository,
    private val lutResolutionRepository: LutResolutionRepository,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState?> = combine(
        cameraSettingsRepository.observeSettings(),
        lutRepository.observeLuts(),
        lutResolutionRepository.observeResolvingLutId(),
    ) { settings, luts, resolvingLutId ->
        SettingsUiState(
            showGrid = settings.showGrid,
            showHistogram = settings.showHistogram,
            showHorizonLine = settings.showHorizonLine,
            focusPeakingSensitivity = settings.focusPeakingSensitivity,
            luts = luts,
            selectedLutId = settings.selectedLutId,
            lutIntensityPercent = settings.lutIntensityPercent,
            resolvingLutId = resolvingLutId,
            captureRawByDefault = settings.captureRawByDefault,
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

    /**
     * Transient, like [lutImportError] — `true` only for the file-copy step of an import
     * ([onLutImportRequested]'s own [LutRepository.importLut] call), not folded into the persisted-
     * settings [combine] chain above since it has nothing to do with [CameraSettingsRepository] at all.
     * Distinct from [SettingsUiState.resolvingLutId] (the *parse-after-selection* step, once the file
     * is already on disk) — this covers the copy itself, which for a large `.cube` file is its own
     * real, user-visible delay.
     */
    private val _isImportingLut = MutableStateFlow(false)
    val isImportingLut: StateFlow<Boolean> = _isImportingLut.asStateFlow()

    init {
        // Issue #43 follow-up: a LUT selection that resolves to a null CubeLut (unsupported file type
        // on import, or a file that went missing/corrupt afterward — see LutResolutionRepository's own
        // doc) is cleaned up automatically rather than left as a permanently-broken chip. Ordering here
        // matters: selection is cleared *before* the file is deleted, so a deliberate delete of the
        // active LUT (SettingsViewModel.onLutDeleteRequested, which clears selection first/atomically
        // itself) never round-trips through this same failure path — by the time delete's own file
        // removal could make a stale in-flight resolve fail, CameraScreen's LaunchedEffect has already
        // re-fired with lutId = null, which CameraRepositoryImpl.setLut's early-return handles without
        // ever touching _resolutionFailures.
        viewModelScope.launch {
            lutResolutionRepository.observeResolutionFailures().collect { failedId ->
                val current = cameraSettingsRepository.observeSettings().first()
                if (current.selectedLutId == failedId) {
                    cameraSettingsRepository.setSelectedLutId(null)
                }
                lutRepository.deleteLut(failedId)
                _lutImportError.value = "Invalid or unreadable LUT file"
            }
        }
    }

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

    fun onCaptureRawByDefaultToggled(enabled: Boolean) {
        viewModelScope.launch { cameraSettingsRepository.setCaptureRawByDefault(enabled) }
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
            _isImportingLut.value = true
            try {
                when (val result = lutRepository.importLut(sourceUri, displayName)) {
                    is Result.Success -> cameraSettingsRepository.setSelectedLutId(result.data.id)
                    is Result.Error -> _lutImportError.value = result.error.name
                }
            } finally {
                _isImportingLut.value = false
            }
        }
    }

    fun onLutImportErrorShown() {
        _lutImportError.value = null
    }

    /**
     * Clears [id] from selection *before* deleting its file if it's the currently-selected LUT — see
     * this class's own `init` block doc for why that ordering is what keeps a deliberate delete from
     * round-tripping through [LutResolutionRepository.observeResolutionFailures]'s auto-cleanup path.
     */
    fun onLutDeleteRequested(id: String) {
        viewModelScope.launch {
            val current = cameraSettingsRepository.observeSettings().first()
            if (current.selectedLutId == id) {
                cameraSettingsRepository.setSelectedLutId(null)
            }
            lutRepository.deleteLut(id)
        }
    }
}
