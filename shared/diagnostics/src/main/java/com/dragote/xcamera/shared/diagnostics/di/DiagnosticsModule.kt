package com.dragote.xcamera.shared.diagnostics.di

import android.content.Context
import android.hardware.camera2.CameraManager
import com.dragote.xcamera.shared.diagnostics.data.LensEnumerator
import com.dragote.xcamera.shared.diagnostics.data.repository.DiagnosticsRepositoryImpl
import com.dragote.xcamera.shared.diagnostics.domain.repository.DiagnosticsRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DiagnosticsModule {

    @Binds
    abstract fun bindDiagnosticsRepository(impl: DiagnosticsRepositoryImpl): DiagnosticsRepository

    companion object {
        @Provides
        @Singleton
        fun provideCameraManager(@ApplicationContext context: Context): CameraManager =
            context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        @Provides
        @Singleton
        fun provideLensEnumerator(cameraManager: CameraManager): LensEnumerator = LensEnumerator(cameraManager)
    }
}
