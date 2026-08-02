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
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CameraModule {

    @Binds
    abstract fun bindCameraRepository(impl: CameraRepositoryImpl): CameraRepository

    companion object {
        // Unscoped would mean every injection point (the ViewModel's constructor injection vs.
        // ui/CameraScreen's separate EntryPointAccessors call) resolves its own fresh instance,
        // leaving imageCapture/camera2CameraControl/currentLens permanently null on whichever copy
        // isn't the one bindCamera() was actually called on — see CameraRepositoryEntryPoint's doc.
        @Provides
        @Singleton
        fun provideCameraController(@ApplicationContext context: Context): CameraController =
            CameraController(context)
    }
}
