package com.dragote.xcamera.feature.settings.data.repository

import android.net.Uri
import com.dragote.xcamera.feature.settings.data.local.LutLocalDataSource
import com.dragote.xcamera.shared.common.domain.model.LutPreset
import com.dragote.xcamera.shared.common.domain.repository.LutRepository
import com.dragote.xcamera.shared.common.domain.result.DataError
import com.dragote.xcamera.shared.common.domain.result.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline-first the same way this project's other repositories are (see root `CLAUDE.md`'s data-layer
 * conventions), just against a plain file-system "source of truth" (see [LutLocalDataSource]'s own
 * doc) instead of Room: [observeLuts] refreshes from disk once per process lifetime on first
 * collection (`.onStart`), not on every collection — imports (the only way this list changes) always
 * go through [importLut] on this same instance, which updates [lutsFlow] directly, so a disk rescan on
 * every re-subscription would be redundant, not just wasteful.
 */
@Singleton
class LutRepositoryImpl @Inject constructor(
    private val localDataSource: LutLocalDataSource,
) : LutRepository {

    private val lutsFlow = MutableStateFlow<List<LutPreset>>(emptyList())
    private var initialized = false
    private val initMutex = Mutex()

    override fun observeLuts(): Flow<List<LutPreset>> = lutsFlow.asStateFlow().onStart { refreshIfNeeded() }

    private suspend fun refreshIfNeeded() {
        initMutex.withLock {
            if (initialized) return
            initialized = true
            lutsFlow.value = withContext(Dispatchers.IO) { localDataSource.listLuts() }
        }
    }

    override suspend fun importLut(sourceUri: Uri, displayName: String): Result<LutPreset, DataError.Local> =
        try {
            val preset = withContext(Dispatchers.IO) { localDataSource.importLut(sourceUri, displayName) }
            lutsFlow.value = lutsFlow.value + preset
            Result.Success(preset)
        } catch (e: IOException) {
            Result.Error(DataError.Local.UNKNOWN)
        } catch (e: SecurityException) {
            // The SAF grant for sourceUri can be revoked/expired between pick and import.
            Result.Error(DataError.Local.UNKNOWN)
        }
}
