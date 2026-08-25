package com.dragote.xcamera.shared.diagnostics.domain.usecase

import com.dragote.xcamera.shared.common.domain.result.DataError
import com.dragote.xcamera.shared.common.domain.result.Result
import com.dragote.xcamera.shared.diagnostics.domain.model.LensDiagnostics
import com.dragote.xcamera.shared.diagnostics.domain.repository.DiagnosticsRepository
import javax.inject.Inject

class GetLensDiagnosticsUseCase @Inject constructor(
    private val diagnosticsRepository: DiagnosticsRepository,
) {
    operator fun invoke(): Result<List<LensDiagnostics>, DataError> = diagnosticsRepository.getLensDiagnostics()
}
