package com.dragote.xcamera.feature.camera.di

import com.dragote.xcamera.feature.camera.domain.repository.CameraRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * `bindCamera` needs a Compose `LifecycleOwner` + `Preview.SurfaceProvider` (ui-layer types
 * `CameraViewModel` must never import — see `.claude/agents/camera-engineer.md` and
 * `CameraRepository`'s own doc), so `ui/CameraScreen` obtains [CameraRepository] straight from Hilt
 * for that one call instead of routing it through the ViewModel like every other camera operation.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface CameraRepositoryEntryPoint {
    fun cameraRepository(): CameraRepository
}
