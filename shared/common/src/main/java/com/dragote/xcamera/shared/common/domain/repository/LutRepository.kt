package com.dragote.xcamera.shared.common.domain.repository

import android.net.Uri
import com.dragote.xcamera.shared.common.domain.model.LutPreset
import com.dragote.xcamera.shared.common.domain.result.DataError
import com.dragote.xcamera.shared.common.domain.result.Result
import kotlinx.coroutines.flow.Flow

/**
 * The second cross-feature domain contract this app needed (after
 * [CameraSettingsRepository]) — `feature:settings` owns the implementation (Storage Access
 * Framework import, copied into app-private storage), `feature:camera` consumes it to resolve
 * [com.dragote.xcamera.shared.common.domain.model.CameraSettings.selectedLutId] into an actual
 * `.cube` file to parse and upload as a `GL_TEXTURE_3D`. Neither feature module depends on the
 * other directly.
 *
 * [android.net.Uri] appears in [importLut]'s signature the same way it already does in
 * `feature.camera.domain.repository.CameraRepository` — a plain opaque identifier, not one of the
 * `android.hardware.camera2.*`/`androidx.camera.*` hardware types this project's camera conventions
 * actually ban from domain-facing code.
 */
interface LutRepository {

    /** All imported LUT presets, newest-last. Empty (not an error) when nothing's been imported yet. */
    fun observeLuts(): Flow<List<LutPreset>>

    /**
     * Copies the SAF-picked `.cube` file at [sourceUri] into app-private storage under [displayName],
     * returning the resulting [LutPreset] on success. Mirrors `CameraRepository.takePhoto`'s own
     * `Result`-wrapping rationale (a real, user-facing failure mode: the source can't be read, or the
     * copy fails).
     */
    suspend fun importLut(sourceUri: Uri, displayName: String): Result<LutPreset, DataError.Local>

    /**
     * Deletes the imported LUT identified by [id] — removes its backing `.cube` file and drops it from
     * [observeLuts]'s list. `Result.Error` for an unknown [id] or a file-deletion failure, mirroring
     * [importLut]'s own `Result`-wrapping. Callers (`feature:settings`' `SettingsViewModel`) are
     * responsible for clearing `CameraSettingsRepository.setSelectedLutId` first if [id] is the
     * currently-selected LUT — this call has no visibility into that setting on its own.
     */
    suspend fun deleteLut(id: String): Result<Unit, DataError.Local>
}
