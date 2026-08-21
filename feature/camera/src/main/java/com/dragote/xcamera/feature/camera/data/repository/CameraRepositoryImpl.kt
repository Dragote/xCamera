package com.dragote.xcamera.feature.camera.data.repository

import android.hardware.camera2.CameraAccessException
import android.media.Image
import android.net.Uri
import android.os.Handler
import androidx.lifecycle.LifecycleOwner
import com.dragote.xcamera.feature.camera.data.camera.CameraController
import com.dragote.xcamera.feature.camera.data.LutFileReader
import com.dragote.xcamera.feature.camera.domain.model.ActiveLut
import com.dragote.xcamera.feature.camera.domain.model.AeCompensationCapability
import com.dragote.xcamera.feature.camera.domain.model.AfConvergenceState
import com.dragote.xcamera.feature.camera.domain.model.CameraLens
import com.dragote.xcamera.feature.camera.domain.model.FlashMode
import com.dragote.xcamera.feature.camera.domain.model.HistogramData
import com.dragote.xcamera.feature.camera.domain.model.ManualFocusCapability
import com.dragote.xcamera.feature.camera.domain.model.ManualIsoCapability
import com.dragote.xcamera.feature.camera.domain.model.RawCaptureCapability
import com.dragote.xcamera.feature.camera.domain.model.ZebraMask
import com.dragote.xcamera.feature.camera.domain.repository.CameraRepository
import com.dragote.xcamera.shared.common.domain.model.CubeLut
import com.dragote.xcamera.shared.common.domain.model.parseCubeLutBinary
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
     * [setLut]'s own doc.
     *
     * Access-order [LinkedHashMap] capped at [ResolvedLutCacheCapacity], evicting the least-recently-
     * used entry once exceeded — mirrors `CameraPreviewRenderer.lutTextureCache`'s own LRU (same
     * `removeEldestEntry` idiom), just without that one's GL-resource cleanup step: an evicted [CubeLut]
     * is a plain heap object, dropping the last reference is enough, no explicit release needed. Each
     * cached entry is a 33³ `FloatArray` (~420KB) since every `.cube` is resampled to the same canonical
     * size on import — bounding this was worth doing once a user's real usage pattern (trying many
     * different LUTs across a long session, not just a fixed handful) could otherwise grow this
     * unboundedly for the lifetime of this `@Singleton`.
     */
    private val resolvedLutCache = object : LinkedHashMap<String, CubeLut>(ResolvedLutCacheCapacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CubeLut>): Boolean =
            size > ResolvedLutCacheCapacity
    }

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

    override fun rawCaptureCapability(lens: CameraLens?): RawCaptureCapability? =
        cameraController.rawCaptureCapability(lens)

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
     * file-read + [parseCubeLutBinary] work is skipped on a cache hit, not this existence check.
     *
     * `null` (either `lutId` itself, an id not present in the list, an unreadable file, or a malformed
     * stored LUT) always means "no LUT" to [CameraController.setLut] — never throws. A non-null [lutId]
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
            cameraController.setLut(null, null, intensityPercent)
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
                    // The file-read + decode stays inside this withContext even though
                    // parseCubeLutBinary's own bulk-byte-read decode is far cheaper than the old text
                    // parser it replaced (issue #43 follow-up) — this is still called from
                    // CameraViewModel's cameraSettings collector, potentially on the main thread, and
                    // any disk IO belongs off it regardless of how fast the decode itself now is.
                    withContext(Dispatchers.IO) {
                        lutFileReader.readBytes(preset.filePath)?.let { bytes -> parseCubeLutBinary(bytes) }
                    }?.also { resolvedLutCache[lutId] = it }
                }
            }
            if (cubeLut == null) {
                _resolutionFailures.tryEmit(lutId)
            }
            cameraController.setLut(lutId, cubeLut, intensityPercent)
        } finally {
            _resolvingLutId.value = null
        }
    }

    override fun observeActiveLut(): Flow<ActiveLut?> = cameraController.activeLut

    override fun observeResolvingLutId(): Flow<String?> = _resolvingLutId.asStateFlow()

    override fun observeResolutionFailures(): Flow<String> = _resolutionFailures.asSharedFlow()

    override suspend fun takePhoto(includeRaw: Boolean): Result<Uri, DataError.Local> = try {
        Result.Success(cameraController.takePhoto(includeRaw))
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

    private companion object {
        /** See [resolvedLutCache]'s own doc — generous relative to `CameraPreviewRenderer`'s 8-slot GPU
         *  texture cache since a resolved [CubeLut] is much cheaper to hold (~420KB heap vs. a live GPU
         *  texture), so there's room to remember more distinct LUTs than are ever simultaneously GPU-
         *  resident before this cache needs to start evicting. */
        const val ResolvedLutCacheCapacity = 20
    }
}
