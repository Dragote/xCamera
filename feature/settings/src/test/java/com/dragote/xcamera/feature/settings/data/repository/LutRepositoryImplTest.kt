package com.dragote.xcamera.feature.settings.data.repository

import android.net.Uri
import app.cash.turbine.test
import com.dragote.xcamera.feature.settings.data.local.LutLocalDataSource
import com.dragote.xcamera.shared.common.domain.model.LutPreset
import com.dragote.xcamera.shared.common.domain.result.Result
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LutRepositoryImplTest {

    private val localDataSource = mockk<LutLocalDataSource>()
    private val repository = LutRepositoryImpl(localDataSource)

    @Test
    fun `observeLuts refreshes from disk on first collection`() = runTest {
        val preset = LutPreset(id = "1", displayName = "Portra", filePath = "/luts/1.cube")
        every { localDataSource.listLuts() } returns listOf(preset)

        repository.observeLuts().test {
            assertEquals(listOf(preset), awaitItem())
        }
        verify(exactly = 1) { localDataSource.listLuts() }
    }

    @Test
    fun `observeLuts only scans disk once across repeated collections`() = runTest {
        every { localDataSource.listLuts() } returns emptyList()

        repository.observeLuts().test { awaitItem() }
        repository.observeLuts().test { awaitItem() }

        verify(exactly = 1) { localDataSource.listLuts() }
    }

    @Test
    fun `importLut appends the new preset without a fresh disk scan`() = runTest {
        every { localDataSource.listLuts() } returns emptyList()
        val uri = mockk<Uri>()
        val preset = LutPreset(id = "new", displayName = "My LUT", filePath = "/luts/new.cube")
        every { localDataSource.importLut(uri, "My LUT") } returns preset

        repository.observeLuts().test {
            assertEquals(emptyList<LutPreset>(), awaitItem())

            val result = repository.importLut(uri, "My LUT")
            assertEquals(Result.Success(preset), result)
            assertEquals(listOf(preset), awaitItem())
        }
        verify(exactly = 1) { localDataSource.listLuts() }
    }

    @Test
    fun `importLut wraps an IOException as a Result Error`() = runTest {
        val uri = mockk<Uri>()
        every { localDataSource.importLut(uri, "My LUT") } throws IOException("boom")

        val result = repository.importLut(uri, "My LUT")

        assertTrue(result is Result.Error)
    }

    @Test
    fun `deleteLut removes the preset from the list on success`() = runTest {
        val preset = LutPreset(id = "1", displayName = "Portra", filePath = "/luts/1.cube")
        every { localDataSource.listLuts() } returns listOf(preset)
        every { localDataSource.deleteLut(preset.filePath) } returns true

        repository.observeLuts().test {
            assertEquals(listOf(preset), awaitItem())

            val result = repository.deleteLut("1")
            assertEquals(Result.Success(Unit), result)
            assertEquals(emptyList<LutPreset>(), awaitItem())
        }
    }

    @Test
    fun `deleteLut for an unknown id returns an error without touching the list`() = runTest {
        every { localDataSource.listLuts() } returns emptyList()

        val result = repository.deleteLut("missing")

        assertTrue(result is Result.Error)
        verify(exactly = 0) { localDataSource.deleteLut(any()) }
    }

    @Test
    fun `deleteLut wraps a failed on-disk delete as a Result Error and keeps the list unchanged`() = runTest {
        val preset = LutPreset(id = "1", displayName = "Portra", filePath = "/luts/1.cube")
        every { localDataSource.listLuts() } returns listOf(preset)
        every { localDataSource.deleteLut(preset.filePath) } returns false

        repository.observeLuts().test {
            assertEquals(listOf(preset), awaitItem())

            val result = repository.deleteLut("1")
            assertTrue(result is Result.Error)
            expectNoEvents()
        }
    }
}
