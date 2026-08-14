package com.dragote.xcamera.feature.settings.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.dragote.xcamera.shared.common.domain.model.CameraSettings
import com.dragote.xcamera.shared.common.domain.model.FocusPeakingSensitivity
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
    private val focusPeakingSensitivityKey = stringPreferencesKey("focus_peaking_sensitivity")
    private val selectedLutIdKey = stringPreferencesKey("selected_lut_id")
    private val lutIntensityPercentKey = intPreferencesKey("lut_intensity_percent")
    private val captureRawByDefaultKey = booleanPreferencesKey("capture_raw_by_default")

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
                // A stored value that no longer matches a FocusPeakingSensitivity constant (e.g. this
                // enum's own name ever changes) falls back to the default rather than crashing the
                // settings read — same "corrupt data reads as default" posture as the catch above.
                focusPeakingSensitivity = preferences[focusPeakingSensitivityKey]
                    ?.let { stored -> runCatching { FocusPeakingSensitivity.valueOf(stored) }.getOrNull() }
                    ?: CameraSettings().focusPeakingSensitivity,
                // No corrupt-value fallback needed here beyond the type mismatch DataStore itself
                // already guards against (a stringPreferencesKey read against a differently-typed
                // stored value throws inside dataStore.data, caught by the `catch` above) — any string
                // is a structurally valid id, resolving it to an actual LUT is CameraRepository's job,
                // not this data source's.
                selectedLutId = preferences[selectedLutIdKey],
                lutIntensityPercent = (preferences[lutIntensityPercentKey] ?: CameraSettings().lutIntensityPercent)
                    .coerceIn(0, 100),
                captureRawByDefault = preferences[captureRawByDefaultKey] ?: CameraSettings().captureRawByDefault,
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

    suspend fun setFocusPeakingSensitivity(sensitivity: FocusPeakingSensitivity) {
        dataStore.edit { it[focusPeakingSensitivityKey] = sensitivity.name }
    }

    /** `null` removes the key entirely (rather than storing a sentinel) so a missing key and an
     *  explicit "off" read back identically as `null` above. */
    suspend fun setSelectedLutId(id: String?) {
        dataStore.edit { preferences ->
            if (id == null) preferences.remove(selectedLutIdKey) else preferences[selectedLutIdKey] = id
        }
    }

    suspend fun setLutIntensityPercent(percent: Int) {
        dataStore.edit { it[lutIntensityPercentKey] = percent.coerceIn(0, 100) }
    }

    suspend fun setCaptureRawByDefault(enabled: Boolean) {
        dataStore.edit { it[captureRawByDefaultKey] = enabled }
    }
}
