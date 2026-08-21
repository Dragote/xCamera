package com.dragote.xcamera.feature.camera.di

import android.content.Context
import com.dragote.xcamera.feature.camera.data.camera.CameraController
import com.dragote.xcamera.feature.camera.data.repository.CameraRepositoryImpl
import com.dragote.xcamera.feature.camera.domain.repository.CameraRepository
import com.dragote.xcamera.shared.common.domain.repository.LutResolutionRepository
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

    /** [CameraRepositoryImpl] also backs `shared:common`'s [LutResolutionRepository] — `feature:settings`
     *  consumes it to show a per-chip loading spinner while `setLut` is still resolving a selection (see
     *  that interface's own doc), mirroring `feature:settings`' `SettingsModule` binding `CameraSettingsRepository`
     *  and `LutRepository` in the reverse direction. */
    @Binds
    abstract fun bindLutResolutionRepository(impl: CameraRepositoryImpl): LutResolutionRepository

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
