package com.dragote.xcamera.shared.diagnostics.data.repository

import android.hardware.camera2.CameraAccessException
import com.dragote.xcamera.shared.common.domain.result.DataError
import com.dragote.xcamera.shared.common.domain.result.Result
import com.dragote.xcamera.shared.diagnostics.data.LensEnumerator
import com.dragote.xcamera.shared.diagnostics.data.mapper.toLensDiagnostics
import com.dragote.xcamera.shared.diagnostics.domain.model.LensDiagnostics
import com.dragote.xcamera.shared.diagnostics.domain.repository.DiagnosticsRepository
import javax.inject.Inject

class DiagnosticsRepositoryImpl @Inject constructor(
    private val lensEnumerator: LensEnumerator,
) : DiagnosticsRepository {

    override fun getLensDiagnostics(): Result<List<LensDiagnostics>, DataError> = try {
        Result.Success(lensEnumerator.listBackLenses().map { it.toLensDiagnostics() })
    } catch (e: CameraAccessException) {
        Result.Error(DataError.Local.CAMERA_ACCESS_UNAVAILABLE)
    }
}
