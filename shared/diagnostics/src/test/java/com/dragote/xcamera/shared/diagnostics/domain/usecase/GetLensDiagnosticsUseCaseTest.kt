package com.dragote.xcamera.shared.diagnostics.domain.usecase

import com.dragote.xcamera.shared.common.domain.result.DataError
import com.dragote.xcamera.shared.common.domain.result.Result
import com.dragote.xcamera.shared.diagnostics.domain.model.FeatureSupport
import com.dragote.xcamera.shared.diagnostics.domain.model.LensDiagnostics
import com.dragote.xcamera.shared.diagnostics.domain.model.LensSnapshot
import com.dragote.xcamera.shared.diagnostics.domain.repository.DiagnosticsRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class GetLensDiagnosticsUseCaseTest {

    private lateinit var diagnosticsRepository: DiagnosticsRepository
    private lateinit var useCase: GetLensDiagnosticsUseCase

    @Before
    fun setUp() {
        diagnosticsRepository = mockk()
        useCase = GetLensDiagnosticsUseCase(diagnosticsRepository)
    }

    @Test
    fun `invoke delegates to the repository and returns its result on success`() {
        val lens = LensDiagnostics(
            displayLabel = "1× MAIN",
            snapshot = LensSnapshot(
                logicalCameraId = "0",
                physicalCameraId = null,
                zoomRatio = 1f,
                focalLengthMm = 6.86f,
                equivalentFocalLengthMm = 24.3f,
                sensorWidthMm = 9.8f,
                sensorHeightMm = 7.3f,
                pixelArrayWidth = 4032,
                pixelArrayHeight = 3024,
                apertureFNumber = 1.8f,
            ),
            rawCapture = FeatureSupport.Supported("12 MP RAW"),
            manualIsoAndShutter = FeatureSupport.Supported("ISO 50–3200"),
            manualFocus = FeatureSupport.Supported("down to 10 cm"),
        )
        every { diagnosticsRepository.getLensDiagnostics() } returns Result.Success(listOf(lens))

        val result = useCase()

        assertEquals(Result.Success(listOf(lens)), result)
        verify { diagnosticsRepository.getLensDiagnostics() }
    }

    @Test
    fun `invoke delegates to the repository and returns its result on error`() {
        every { diagnosticsRepository.getLensDiagnostics() } returns Result.Error(DataError.Local.CAMERA_ACCESS_UNAVAILABLE)

        val result = useCase()

        assertEquals(Result.Error(DataError.Local.CAMERA_ACCESS_UNAVAILABLE), result)
    }
}
