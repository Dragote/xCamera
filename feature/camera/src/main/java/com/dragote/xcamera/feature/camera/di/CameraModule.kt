package com.dragote.xcamera.feature.camera.di

import android.content.Context
import com.dragote.xcamera.feature.camera.data.CameraController
import com.dragote.xcamera.feature.camera.data.repository.CameraRepositoryImpl
import com.dragote.xcamera.feature.camera.domain.repository.CameraRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class CameraModule {

    @Binds
    abstract fun bindCameraRepository(impl: CameraRepositoryImpl): CameraRepository

    companion object {
        @Provides
        fun provideCameraController(@ApplicationContext context: Context): CameraController =
            CameraController(context)
    }
}
