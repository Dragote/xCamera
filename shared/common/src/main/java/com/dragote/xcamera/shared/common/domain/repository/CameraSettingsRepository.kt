package com.dragote.xcamera.shared.common.domain.repository

import com.dragote.xcamera.shared.common.domain.model.CameraSettings
import com.dragote.xcamera.shared.common.domain.model.FocusPeakingSensitivity
import kotlinx.coroutines.flow.Flow

/**
 * The first real cross-feature domain contract this app needed: `feature:settings` owns the
 * implementation (backed by DataStore Preferences), `feature:camera` consumes it to gate its
 * viewfinder overlays — neither feature module depends on the other directly.
 *
 * No [com.dragote.xcamera.shared.common.domain.result.Result] wrapping here — mirrors
 * `feature.camera.domain.repository.CameraRepository`'s own documented rationale: only operations
 * with a real, user-facing failure mode wrap `Result`. A DataStore write has no meaningful
 * user-facing failure mode either.
 */
interface CameraSettingsRepository {

    fun observeSettings(): Flow<CameraSettings>

    suspend fun setShowGrid(enabled: Boolean)

    suspend fun setShowHistogram(enabled: Boolean)

    suspend fun setShowHorizonLine(enabled: Boolean)

    suspend fun setFocusPeakingSensitivity(sensitivity: FocusPeakingSensitivity)

    /** `null` disables LUT grading entirely — see [CameraSettings.selectedLutId]'s own doc. */
    suspend fun setSelectedLutId(id: String?)

    suspend fun setLutIntensityPercent(percent: Int)

    suspend fun setCaptureRawByDefault(enabled: Boolean)
}
