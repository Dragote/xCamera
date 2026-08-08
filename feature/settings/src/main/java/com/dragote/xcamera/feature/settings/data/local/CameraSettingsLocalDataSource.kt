package com.dragote.xcamera.feature.settings.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.dragote.xcamera.shared.common.domain.model.CameraSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** Thin wrapper around [DataStore]<[Preferences]> — the only place in this module that knows the
 *  actual preference keys. */
class CameraSettingsLocalDataSource @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    private val showGridKey = booleanPreferencesKey("show_grid")
    private val showHistogramKey = booleanPreferencesKey("show_histogram")
    private val showHorizonLineKey = booleanPreferencesKey("show_horizon_line")

    /** Falls back to an empty [Preferences] on a corrupt preferences file rather than propagating
     *  the read failure — a settings read has no meaningful failure mode a caller could act on, it
     *  just means every toggle reads as its default. */
    val settings: Flow<CameraSettings> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { preferences ->
            CameraSettings(
                showGrid = preferences[showGridKey] ?: CameraSettings().showGrid,
                showHistogram = preferences[showHistogramKey] ?: CameraSettings().showHistogram,
                showHorizonLine = preferences[showHorizonLineKey] ?: CameraSettings().showHorizonLine,
            )
        }

    suspend fun setShowGrid(enabled: Boolean) {
        dataStore.edit { it[showGridKey] = enabled }
    }

    suspend fun setShowHistogram(enabled: Boolean) {
        dataStore.edit { it[showHistogramKey] = enabled }
    }

    suspend fun setShowHorizonLine(enabled: Boolean) {
        dataStore.edit { it[showHorizonLineKey] = enabled }
    }
}
