package com.dragote.xcamera.shared.diagnostics.data.repository

import android.hardware.camera2.CameraAccessException
import com.dragote.xcamera.shared.common.domain.result.DataError
import com.dragote.xcamera.shared.common.domain.result.Result
import com.dragote.xcamera.shared.diagnostics.data.LensEnumerator
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Only covers what's mockable without a real `CameraCharacteristics` instance (empty-list success
 * and the `CameraAccessException` error path) — mocking `CameraCharacteristics` itself to exercise the
 * actual `LensCandidate` -> `LensDiagnostics` mapping isn't practical with MockK here, the same
 * precedent `feature:camera`'s own `BackLensEnumerator` sets by having no unit tests of its own.
 */
class DiagnosticsRepositoryImplTest {

    private lateinit var lensEnumerator: LensEnumerator
    private lateinit var repository: DiagnosticsRepositoryImpl

    @Before
    fun setUp() {
        lensEnumerator = mockk()
        repository = DiagnosticsRepositoryImpl(lensEnumerator)
    }

    @Test
    fun `getLensDiagnostics returns an empty list when no back lenses are found`() {
        every { lensEnumerator.listBackLenses() } returns emptyList()

        val result = repository.getLensDiagnostics()

        assertEquals(Result.Success(emptyList<Nothing>()), result)
    }

    @Test
    fun `getLensDiagnostics maps a CameraAccessException to CAMERA_ACCESS_UNAVAILABLE`() {
        every { lensEnumerator.listBackLenses() } throws mockk<CameraAccessException>(relaxed = true)

        val result = repository.getLensDiagnostics()

        assertEquals(Result.Error(DataError.Local.CAMERA_ACCESS_UNAVAILABLE), result)
    }
}
