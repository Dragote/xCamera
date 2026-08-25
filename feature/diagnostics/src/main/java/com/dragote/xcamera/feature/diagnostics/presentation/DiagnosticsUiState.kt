package com.dragote.xcamera.feature.diagnostics.presentation

import com.dragote.xcamera.shared.diagnostics.domain.model.LensDiagnostics

sealed interface DiagnosticsUiState {
    data object Loading : DiagnosticsUiState
    data class Loaded(val lenses: List<LensDiagnostics>) : DiagnosticsUiState
    data class Error(val message: String) : DiagnosticsUiState
}
