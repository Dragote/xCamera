package com.dragote.xcamera.feature.camera.data.repository

import android.hardware.camera2.CameraAccessException
import android.media.Image
import android.net.Uri
import android.os.Handler
import androidx.lifecycle.LifecycleOwner
import com.dragote.xcamera.feature.camera.data.CameraController
import com.dragote.xcamera.feature.camera.data.LutFileReader
import com.dragote.xcamera.feature.camera.domain.model.ActiveLut
import com.dragote.xcamera.feature.camera.domain.model.AeCompensationCapability
import com.dragote.xcamera.feature.camera.domain.model.AfConvergenceState
import com.dragote.xcamera.feature.camera.domain.model.CameraLens
import com.dragote.xcamera.feature.camera.domain.model.FlashMode
import com.dragote.xcamera.feature.camera.domain.model.HistogramData
import com.dragote.xcamera.feature.camera.domain.model.ManualFocusCapability
import com.dragote.xcamera.feature.camera.domain.model.ManualIsoCapability
import com.dragote.xcamera.feature.camera.domain.model.ZebraMask
import com.dragote.xcamera.feature.camera.domain.repository.CameraRepository
import com.dragote.xcamera.shared.common.domain.model.CubeLut
import com.dragote.xcamera.shared.common.domain.model.parseCubeLut
import com.dragote.xcamera.shared.common.domain.repository.LutRepository
import com.dragote.xcamera.shared.common.domain.repository.LutResolutionRepository
import com.dragote.xcamera.shared.common.domain.result.DataError
import com.dragote.xcamera.shared.common.domain.result.Result
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CameraRepositoryImpl @Inject constructor(
    private val cameraController: CameraController,
    private val lutRepository: LutRepository,
    private val lutFileReader: LutFileReader,
) : CameraRepository, LutResolutionRepository {

    /**
     * In-memory cache of already-resolved LUTs, keyed by [com.dragote.xcamera.shared.common.domain
     * .model.LutPreset.id] (issue #43 follow-up) — a repeat selection of a `lutId` already resolved
     * this session skips the file-read + [parseCubeLut] work entirely, going straight to
     * [cameraController.setLut]. Deliberately *not* what gates whether a `lutId` still exists: [setLut]
     * still re-checks [lutRepository]'s current list on every call (seeing whether a preset still
     * resolves to a file path at all) before ever consulting this cache, so a stale entry for a
     * since-deleted LUT (`SettingsViewModel.onLutDeleteRequested`, or the resolution-failure auto-
     * cleanup path from issue #43's earlier round) is never served as if it still existed — see
     * [setLut]'s own doc. A plain unbounded `MutableMap`, no LRU/eviction — a user's LUT library is
     * realistically tens of entries, not thousands, per this project's minimal-infra preference.
     */
    private val resolvedLutCache = mutableMapOf<String, CubeLut>()

    /** Backs [observeResolvingLutId] — see [LutResolutionRepository]'s own doc for why this only ever
     *  holds a non-null `lutId`, never a "resolving null" state. */
    private val _resolvingLutId = MutableStateFlow<String?>(null)

    /** Backs [observeResolutionFailures] — see [LutResolutionRepository]'s own doc. `extraBufferCapacity
     *  = 1` (not the default `0`) so a failure emitted with no collector currently subscribed (e.g. the
     *  Settings screen not on screen at the moment `setLut` runs) isn't just dropped — `tryEmit` below
     *  would otherwise silently fail against an unbuffered `SharedFlow` with no ready collector. */
    private val _resolutionFailures = MutableSharedFlow<String>(extraBufferCapacity = 1)

    override suspend fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewViewWidth: Int,
        previewViewHeight: Int,
        lens: CameraLens?,
    ) = cameraController.bindCamera(lifecycleOwner, previewViewWidth, previewViewHeight, lens)

    override fun unbindCamera() = cameraController.unbindCamera()

    override fun setPreviewFrameListener(handler: Handler?, listener: ((Image) -> Unit)?) =
        cameraController.setPreviewFrameListener(handler, listener)

    override fun previewRotationDegrees(lens: CameraLens?): Int =
        cameraController.previewRotationDegrees(lens)

    override fun setFlashMode(flashMode: FlashMode) = cameraController.setFlashMode(flashMode)

    override fun manualIsoCapability(lens: CameraLens?): ManualIsoCapability? =
        cameraController.manualIsoCapability(lens)

    override fun aeCompensationCapability(lens: CameraLens?): AeCompensationCapability? =
        cameraController.aeCompensationCapability(lens)

    override fun observeAutoIso(): Flow<Int?> = cameraController.autoIso

    override fun observeAutoExposureTime(): Flow<Long?> = cameraController.autoExposureTimeNs

    override fun setManualExposure(iso: Int?, shutterTimeNs: Long?) =
        cameraController.setManualExposure(iso, shutterTimeNs)

    override fun setExposureCompensation(value: Int) = cameraController.setExposureCompensation(value)

    override fun setZebraAnalysisEnabled(enabled: Boolean) = cameraController.setZebraAnalysisEnabled(enabled)

    override fun observeZebraMask(): Flow<ZebraMask?> = cameraController.zebraMask

    override fun observeHistogramData(): Flow<HistogramData?> = cameraController.histogramData

    override fun manualFocusCapability(lens: CameraLens?): ManualFocusCapability? =
        cameraController.manualFocusCapability(lens)

    override fun triggerAutoFocus(displayXFraction: Float, displayYFraction: Float) =
        cameraController.triggerAutoFocus(displayXFraction, displayYFraction)

    override fun setManualFocusDistance(distanceDiopters: Float?) =
        cameraController.setManualFocusDistance(distanceDiopters)

    override fun observeFocusDistance(): Flow<Float?> = cameraController.autoFocusDistanceDiopters

    override fun observeAfConvergenceState(): Flow<AfConvergenceState?> = cameraController.afConvergenceState

    /**
     * Looks [lutId] up in [lutRepository]'s current list (a one-shot [first] read, not a live
     * subscription — LUT selection changes are infrequent user actions, not something that needs to
     * react to the *list* changing mid-resolution) *every* call, even for a [lutId] already present in
     * [resolvedLutCache] — this is what keeps a stale cache entry for a since-deleted LUT from ever
     * being served: a deleted [lutId] has no matching preset any more, so this short-circuits to `null`
     * before [resolvedLutCache] is ever consulted, regardless of what's still sitting in it. Only the
     * file-read + [parseCubeLut] work is skipped on a cache hit, not this existence check.
     *
     * `null` (either `lutId` itself, an id not present in the list, an unreadable file, or a malformed
     * `.cube`) always means "no LUT" to [CameraController.setLut] — never throws. A non-null [lutId]
     * that still resolves to a `null` LUT is also reported via [_resolutionFailures] — see
     * [observeResolutionFailures]'s own doc for why that's the file-type-validation gate for import
     * (issue #43's follow-up: `feature:settings` can't validate `.cube` content itself without
     * depending on `feature:camera`).
     */
    override suspend fun setLut(lutId: String?, intensityPercent: Int) {
        if (lutId == null) {
            // "Off" clears the active LUT synchronously — nothing to resolve, so _resolvingLutId
            // never toggles for this path (see LutResolutionRepository's own doc), and there's nothing
            // that could fail either, so _resolutionFailures is untouched too.
            cameraController.setLut(null, intensityPercent)
            return
        }
        _resolvingLutId.value = lutId
        try {
            val preset = lutRepository.observeLuts().first().find { it.id == lutId }
            val cubeLut = if (preset == null) {
                resolvedLutCache.remove(lutId) // no longer a valid id — drop any stale cached entry too
                null
            } else {
                resolvedLutCache[lutId] ?: run {
                    // Parsing (parseCubeLut, not just the file read) must stay inside this withContext —
                    // a 33+-size .cube file is tens of thousands of data rows, and this is called from
                    // CameraViewModel's cameraSettings collector, potentially on the main thread; parsing
                    // outside the IO dispatcher switch previously froze the UI (confirmed: hangs hard
                    // when applying a LUT).
                    withContext(Dispatchers.IO) {
                        lutFileReader.readText(preset.filePath)?.let { content -> parseCubeLut(content) }
                    }?.also { resolvedLutCache[lutId] = it }
                }
            }
            if (cubeLut == null) {
                _resolutionFailures.tryEmit(lutId)
            }
            cameraController.setLut(cubeLut, intensityPercent)
        } finally {
            _resolvingLutId.value = null
        }
    }

    override fun observeActiveLut(): Flow<ActiveLut?> = cameraController.activeLut

    override fun observeResolvingLutId(): Flow<String?> = _resolvingLutId.asStateFlow()

    override fun observeResolutionFailures(): Flow<String> = _resolutionFailures.asSharedFlow()

    override suspend fun takePhoto(): Result<Uri, DataError.Local> = try {
        Result.Success(cameraController.takePhoto())
    } catch (e: CancellationException) {
        throw e
    } catch (e: IllegalStateException) {
        Result.Error(DataError.Local.UNKNOWN)
    } catch (e: CameraAccessException) {
        Result.Error(DataError.Local.UNKNOWN)
    }

    override fun listBackLenses(): List<CameraLens> = cameraController.listBackLenses()

    override suspend fun latestGalleryPhotoUri(): Uri? = cameraController.latestGalleryPhotoUri()

    override fun stopOrientationListener() = cameraController.stopOrientationListener()
}
