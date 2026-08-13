package com.dragote.xcamera.feature.settings.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.dragote.xcamera.feature.settings.data.repository.CameraSettingsRepositoryImpl
import com.dragote.xcamera.feature.settings.data.repository.LutRepositoryImpl
import com.dragote.xcamera.shared.common.domain.repository.CameraSettingsRepository
import com.dragote.xcamera.shared.common.domain.repository.LutRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsModule {

    @Binds
    abstract fun bindCameraSettingsRepository(impl: CameraSettingsRepositoryImpl): CameraSettingsRepository

    /** Bound here (not `feature:camera`) even though `feature:camera` is its main consumer for
     *  rendering — LUT import (Storage Access Framework) and its app-private-storage bookkeeping live
     *  alongside `CameraSettingsRepository`'s own DataStore persistence, both owned by this feature's
     *  data layer, mirroring exactly how `CameraSettingsRepository` itself is implemented here and
     *  consumed by `feature:camera`. */
    @Binds
    abstract fun bindLutRepository(impl: LutRepositoryImpl): LutRepository

    companion object {
        @Provides
        @Singleton
        fun provideCameraSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(produceFile = { context.preferencesDataStoreFile("camera_settings") })
    }
}
