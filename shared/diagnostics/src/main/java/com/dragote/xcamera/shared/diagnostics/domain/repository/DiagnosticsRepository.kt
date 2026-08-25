package com.dragote.xcamera.shared.diagnostics.domain.repository

import com.dragote.xcamera.shared.common.domain.result.DataError
import com.dragote.xcamera.shared.common.domain.result.Result
import com.dragote.xcamera.shared.diagnostics.domain.model.LensDiagnostics

interface DiagnosticsRepository {

    /** Static `CameraCharacteristics` introspection only — no live capture, no suspension, so this
     *  is a plain (non-suspend) call unlike this module's other data-layer boundaries. */
    fun getLensDiagnostics(): Result<List<LensDiagnostics>, DataError>
}
