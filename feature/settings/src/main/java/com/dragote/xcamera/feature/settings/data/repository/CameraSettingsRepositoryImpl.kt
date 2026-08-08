package com.dragote.xcamera.feature.settings.data.repository

import com.dragote.xcamera.feature.settings.data.local.CameraSettingsLocalDataSource
import com.dragote.xcamera.shared.common.domain.model.CameraSettings
import com.dragote.xcamera.shared.common.domain.repository.CameraSettingsRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CameraSettingsRepositoryImpl @Inject constructor(
    private val localDataSource: CameraSettingsLocalDataSource,
) : CameraSettingsRepository {

    override fun observeSettings(): Flow<CameraSettings> = localDataSource.settings

    override suspend fun setShowGrid(enabled: Boolean) = localDataSource.setShowGrid(enabled)

    override suspend fun setShowHistogram(enabled: Boolean) = localDataSource.setShowHistogram(enabled)

    override suspend fun setShowHorizonLine(enabled: Boolean) = localDataSource.setShowHorizonLine(enabled)
}
