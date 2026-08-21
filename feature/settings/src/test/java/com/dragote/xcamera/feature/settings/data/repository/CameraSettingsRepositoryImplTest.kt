package com.dragote.xcamera.feature.settings.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.turbine.test
import com.dragote.xcamera.feature.settings.data.local.CameraSettingsLocalDataSource
import com.dragote.xcamera.shared.common.domain.model.CameraSettings
import com.dragote.xcamera.shared.common.domain.model.FocusPeakingSensitivity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A real (not mocked) round-trip test — DataStore's own file-backed implementation, pointed at a
 * JUnit temp folder, is cheap enough to exercise directly rather than faking its behavior.
 */
class CameraSettingsRepositoryImplTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun buildRepository(): CameraSettingsRepositoryImpl {
        val dataStore = PreferenceDataStoreFactory.create(
            produceFile = { temporaryFolder.newFile("camera_settings_test.preferences_pb") },
        )
        return CameraSettingsRepositoryImpl(CameraSettingsLocalDataSource(dataStore))
    }

    @Test
    fun `observeSettings reflects defaults before anything is written`() = runTest {
        val repository = buildRepository()

        repository.observeSettings().test {
            assertEquals(CameraSettings(), awaitItem())
        }
    }

    @Test
    fun `setShowGrid persists and is reflected by observeSettings`() = runTest {
        val repository = buildRepository()

        repository.observeSettings().test {
            assertEquals(CameraSettings(), awaitItem())

            repository.setShowGrid(true)
            assertEquals(CameraSettings(showGrid = true), awaitItem())
        }
    }

    @Test
    fun `setShowHistogram persists and is reflected by observeSettings`() = runTest {
        val repository = buildRepository()

        repository.observeSettings().test {
            assertEquals(CameraSettings(), awaitItem())

            repository.setShowHistogram(false)
            assertEquals(CameraSettings(showHistogram = false), awaitItem())
        }
    }

    @Test
    fun `setShowHorizonLine persists and is reflected by observeSettings`() = runTest {
        val repository = buildRepository()

        repository.observeSettings().test {
            assertEquals(CameraSettings(), awaitItem())

            repository.setShowHorizonLine(false)
            assertEquals(CameraSettings(showHorizonLine = false), awaitItem())
        }
    }

    @Test
    fun `setFocusPeakingSensitivity persists and is reflected by observeSettings`() = runTest {
        val repository = buildRepository()

        repository.observeSettings().test {
            assertEquals(CameraSettings(), awaitItem())

            repository.setFocusPeakingSensitivity(FocusPeakingSensitivity.HIGH)
            assertEquals(CameraSettings(focusPeakingSensitivity = FocusPeakingSensitivity.HIGH), awaitItem())
        }
    }

    @Test
    fun `setSelectedLutId persists and is reflected by observeSettings`() = runTest {
        val repository = buildRepository()

        repository.observeSettings().test {
            assertEquals(CameraSettings(), awaitItem())

            repository.setSelectedLutId("abc-123")
            assertEquals(CameraSettings(selectedLutId = "abc-123"), awaitItem())
        }
    }

    @Test
    fun `setSelectedLutId with null clears a previously selected id`() = runTest {
        val repository = buildRepository()

        repository.observeSettings().test {
            assertEquals(CameraSettings(), awaitItem())

            repository.setSelectedLutId("abc-123")
            assertEquals(CameraSettings(selectedLutId = "abc-123"), awaitItem())

            repository.setSelectedLutId(null)
            assertEquals(CameraSettings(selectedLutId = null), awaitItem())
        }
    }

    @Test
    fun `setLutIntensityPercent persists and is reflected by observeSettings`() = runTest {
        val repository = buildRepository()

        repository.observeSettings().test {
            assertEquals(CameraSettings(), awaitItem())

            repository.setLutIntensityPercent(42)
            assertEquals(CameraSettings(lutIntensityPercent = 42), awaitItem())
        }
    }

    @Test
    fun `setCaptureRawByDefault persists and is reflected by observeSettings`() = runTest {
        val repository = buildRepository()

        repository.observeSettings().test {
            assertEquals(CameraSettings(), awaitItem())

            repository.setCaptureRawByDefault(true)
            assertEquals(CameraSettings(captureRawByDefault = true), awaitItem())
        }
    }

    @Test
    fun `setMinimalChromeInverted persists and is reflected by observeSettings`() = runTest {
        val repository = buildRepository()

        repository.observeSettings().test {
            assertEquals(CameraSettings(), awaitItem())

            repository.setMinimalChromeInverted(true)
            assertEquals(CameraSettings(minimalChromeInverted = true), awaitItem())
        }
    }

    @Test
    fun `each setter's write is independent of the other two settings`() = runTest {
        val repository = buildRepository()

        repository.observeSettings().test {
            awaitItem() // defaults

            repository.setShowGrid(true)
            assertEquals(CameraSettings(showGrid = true), awaitItem())

            repository.setShowHistogram(false)
            assertEquals(CameraSettings(showGrid = true, showHistogram = false), awaitItem())

            repository.setShowHorizonLine(false)
            assertEquals(
                CameraSettings(showGrid = true, showHistogram = false, showHorizonLine = false),
                awaitItem(),
            )
        }
    }
}
