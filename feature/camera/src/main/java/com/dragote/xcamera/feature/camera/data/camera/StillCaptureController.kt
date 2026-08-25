package com.dragote.xcamera.feature.camera.data.camera

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.provider.MediaStore
import android.view.Surface
import androidx.exifinterface.media.ExifInterface
import com.dragote.xcamera.feature.camera.data.gl.LutJpegProcessor
import com.dragote.xcamera.feature.camera.domain.model.ActiveLut
import com.dragote.xcamera.feature.camera.domain.model.FlashMode
import com.dragote.xcamera.shared.common.domain.model.CubeLut
import com.dragote.xcamera.shared.diagnostics.domain.model.LensSnapshot
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.io.IOException

/**
 * Still-capture (JPEG + optional RAW/DNG) and MediaStore-save half of [CameraController]: issues the
 * still-capture `CaptureRequest`, correlates its JPEG/RAW halves, applies the active LUT to the JPEG,
 * and writes both files to `MediaStore`. Split out as "given an opened
 * device/session/readers (supplied per-call by [CameraController], which owns their lifecycle), capture
 * a photo" — [previewRequestController] is a direct collaborator reference (not a lambda) since exposure/
 * focus request-building is genuinely shared logic ([PreviewRequestController.applyExposure]/
 * [PreviewRequestController.applyFocusSettings]), not just a value this class reads once.
 */
class StillCaptureController(
    private val context: Context,
    private val previewRequestController: PreviewRequestController,
    private val targetRotationDegrees: () -> Int,
) {

    /**
     * Cached the same way manual exposure is cached in [PreviewRequestController], so a mode set before
     * the next still capture (or after a lens switch) still applies. Flash never applies to the live
     * preview, only the still-capture request built fresh by [captureStillJpeg] on every [takePhoto]
     * call — there's nothing to push to the sensor immediately when [setFlashMode] is called.
     */
    private var pendingFlashMode: FlashMode = FlashMode.OFF

    /** Resolved by [captureStillJpeg] once the pending still capture's JPEG bytes are delivered. */
    private var pendingCapture: CancellableContinuation<ByteArray>? = null

    /**
     * Unlike [FrameAnalyzer.imageAvailableListener]'s continuous stream, a still capture has exactly
     * one pending [Image] to correlate with the [pendingCapture] continuation, so an
     * [IllegalStateException] here (see `CameraController.closeImageReaders`'s own doc for how a buffer
     * can go inaccessible) can't be silently skipped the way a dropped preview frame can — there's no
     * next frame coming to retry against, and leaving [pendingCapture] unresolved would hang whatever
     * called [captureStillJpeg] forever. Failing the continuation instead surfaces as a normal capture
     * failure through [CameraRepositoryImpl.takePhoto]'s existing `IllegalStateException` handling.
     */
    val imageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        val image = try {
            reader.acquireLatestImage()
        } catch (e: IllegalStateException) {
            pendingCapture?.takeIf { it.isActive }?.resumeWithException(e)
            pendingCapture = null
            return@OnImageAvailableListener
        }
        if (image == null) return@OnImageAvailableListener
        val bytes = try {
            val buffer = image.planes[0].buffer
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        } catch (e: IllegalStateException) {
            pendingCapture?.takeIf { it.isActive }?.resumeWithException(e)
            pendingCapture = null
            return@OnImageAvailableListener
        } finally {
            image.close()
        }
        pendingCapture?.takeIf { it.isActive }?.resume(bytes)
        pendingCapture = null
    }

    /**
     * Completed by [stillCaptureCallback]/[rawImageAvailableListener] (whichever of the two delivers
     * second) once a "with RAW" still capture's [Image]/[TotalCaptureResult] pair is fully correlated
     * — see [pendingRawImagesByTimestamp]/[pendingRawResultsByTimestamp]'s own doc for why matching is
     * done by `CaptureResult.SENSOR_TIMESTAMP`/`Image.getTimestamp()`, not by which callback happens
     * to fire last. `null` whenever the in-flight still capture didn't request a RAW buffer at all —
     * both [stillCaptureCallback] and [rawImageAvailableListener] no-op (or, for the latter, just
     * close the stray [Image]) while this is `null`.
     */
    private var pendingRawCapture: CompletableDeferred<Pair<Image, TotalCaptureResult>>? = null

    /**
     * A single still-capture request targeting both the JPEG and RAW surfaces yields exactly one
     * [Image] from the RAW reader and one [TotalCaptureResult] from [stillCaptureCallback], but
     * they arrive via two independent async callbacks with no guaranteed order — buffering whichever
     * arrives first here (keyed by the sensor timestamp both sides independently carry:
     * `CaptureResult.SENSOR_TIMESTAMP` / `Image.getTimestamp()`) and completing [pendingRawCapture]
     * once the other half shows up is what lets [captureStillJpeg] correlate them by the frame they
     * actually both belong to, rather than assuming "whatever result callback fired most recently"
     * matches "whatever image callback fired most recently". In practice at most one entry ever
     * accumulates here (only one still capture is ever in flight at
     * a time — the shutter is disabled while a capture is already running), but keying by timestamp
     * rather than a single mutable field is what actually makes that safe rather than just assumed.
     */
    private val pendingRawImagesByTimestamp = mutableMapOf<Long, Image>()

    /** Mirrors [pendingRawImagesByTimestamp] for the [TotalCaptureResult] side — see that field's own
     *  doc. */
    private val pendingRawResultsByTimestamp = mutableMapOf<Long, TotalCaptureResult>()

    /**
     * Dedicated to still-capture requests (both plain-JPEG and "with RAW") — unlike
     * [PreviewRequestController.captureCallback], this only ever does anything while [pendingRawCapture]
     * is non-null, i.e. the in-flight still capture actually requested a RAW buffer; a no-op for a plain
     * JPEG-only capture; the JPEG bytes themselves are still delivered via [imageAvailableListener]/
     * [pendingCapture], independent of this callback. See [pendingRawImagesByTimestamp]'s own doc for
     * the timestamp-based correlation this performs.
     */
    val stillCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val deferred = pendingRawCapture ?: return
            val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
            val image = pendingRawImagesByTimestamp.remove(timestamp)
            if (image != null) {
                if (deferred.isActive) deferred.complete(image to result) else image.close()
            } else {
                pendingRawResultsByTimestamp[timestamp] = result
            }
        }
    }

    /**
     * See [pendingRawImagesByTimestamp]'s own doc. `acquireNextImage` (not `acquireLatestImage`,
     * unlike [FrameAnalyzer.imageAvailableListener]'s deliberate frame-skipping) — the RAW reader's
     * `maxImages = 1` and there's exactly one RAW buffer worth having per still capture, never a
     * backlog of frames to skip through.
     */
    val rawImageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        // Unlike imageAvailableListener's JPEG side, a missing RAW buffer here doesn't need to fail the
        // whole capture — captureStillJpeg's own RawCaptureTimeoutMs bound already treats "RAW never
        // arrived" as "capture without RAW" (see pendingRawCapture's own doc), so an IllegalStateException
        // acquiring it (see CameraController.closeImageReaders's own doc) is just folded into that same
        // existing fallback rather than needing its own handling.
        val image = try {
            reader.acquireNextImage()
        } catch (e: IllegalStateException) {
            null
        } ?: return@OnImageAvailableListener
        val deferred = pendingRawCapture
        if (deferred == null) {
            // No RAW capture actually in flight (shouldn't normally happen — see CameraController's
            // rawImageReader doc — but closing rather than buffering forever is the safe fallback
            // either way).
            image.close()
            return@OnImageAvailableListener
        }
        val timestamp = image.timestamp
        val result = pendingRawResultsByTimestamp.remove(timestamp)
        if (result != null) {
            if (deferred.isActive) deferred.complete(image to result) else image.close()
        } else {
            pendingRawImagesByTimestamp[timestamp] = image
        }
    }

    /**
     * The currently active LUT + blend intensity, `null` meaning grading is off — set via
     * [setLut] (in practice, `CameraRepositoryImpl` resolving `CameraSettings.selectedLutId` through
     * `LutRepository` and `CubeLutParser`, this class never does that resolution itself). Two
     * independent consumers read this: `ui/CameraScreen` collects it to push into
     * `CameraPreviewRenderer.setLut` for the live preview, and [takePhoto] reads it synchronously
     * (this field, not the `Flow`) to decide whether a still capture needs [lutJpegProcessor]'s
     * offscreen pass at all.
     */
    private val _activeLut = MutableStateFlow<ActiveLut?>(null)
    val activeLut: StateFlow<ActiveLut?> = _activeLut.asStateFlow()

    /** See its own class doc — a thin, per-call offscreen GLES processor, not held across captures. */
    private val lutJpegProcessor = LutJpegProcessor()

    /**
     * Cached in [pendingFlashMode] — see that field's own doc.
     */
    fun setFlashMode(flashMode: FlashMode) {
        pendingFlashMode = flashMode
    }

    /**
     * See [_activeLut]'s own doc — [cubeLut] `null` means "no LUT selected," disabling grading
     * entirely for both the live preview (once `ui/CameraScreen` observes this and pushes it into
     * `CameraPreviewRenderer.setLut`) and the next still capture ([takePhoto]). [lutId] is only ever
     * meaningful alongside a non-null [cubeLut] — see [ActiveLut.lutId]'s own doc for what it's for.
     */
    fun setLut(lutId: String?, cubeLut: CubeLut?, intensityPercent: Int) {
        _activeLut.value = if (cubeLut != null && lutId != null) {
            ActiveLut(lutId, cubeLut, intensityPercent.coerceIn(0, 100))
        } else {
            null
        }
    }

    /**
     * Issues the still-capture request and writes the resulting JPEG to `MediaStore`. While manual
     * exposure is active, the real (preview-uncapped) ISO/shutter the user selected is carried
     * directly on this one-off request via [PreviewRequestController.applyExposure] — completely
     * independent of whatever the live preview's repeating request is doing.
     *
     * When [_activeLut] is non-null, the raw JPEG is additionally run through [lutJpegProcessor]'s
     * offscreen decode -> shader -> re-encode pass before being written, replacing the plain
     * direct-to-MediaStore write only in that case. A `null` result from [LutJpegProcessor.apply]
     * (any processing failure) falls back to the original, ungraded [bytes] rather than losing the
     * capture — see that class's own doc. LUT grading never applies to the RAW/DNG output below — it
     * stays unprocessed sensor data.
     *
     * [includeRaw] requests an *additional* `.dng` written to `MediaStore` alongside the JPEG — only
     * actually honored when [rawReader] is non-null, i.e. [CameraController] already confirmed this
     * lens's `RAW` capability *and* the 3-surface session it needs when the camera was bound;
     * requesting RAW on a lens/session that doesn't actually have it configured silently falls back to
     * a plain JPEG-only capture rather than throwing, the same "capability absent -> feature quietly
     * unavailable" convention the capability queries already follow. A RAW/DNG write failure (disk,
     * `MediaStore`, or `DngCreator` itself) is swallowed rather than failing this whole call — the
     * JPEG that already succeeded must not be lost over a failed *bonus* file.
     */
    suspend fun takePhoto(
        device: CameraDevice,
        session: CameraCaptureSession,
        reader: ImageReader,
        rawReader: ImageReader?,
        characteristics: CameraCharacteristics,
        lens: LensSnapshot?,
        includeRaw: Boolean,
        handler: Handler?,
    ): Uri {
        val rawReaderForThisShot = rawReader.takeIf { includeRaw }

        val captured = captureStillJpeg(device, session, reader, characteristics, lens, rawReaderForThisShot, handler)
        val activeLut = _activeLut.value
        val outputBytes = if (activeLut != null) {
            withContext(Dispatchers.Default) {
                lutJpegProcessor.apply(captured.jpegBytes, activeLut.cubeLut, activeLut.intensityPercent)
            } ?: captured.jpegBytes
        } else {
            captured.jpegBytes
        }
        val uri = withContext(Dispatchers.IO) { saveJpegToMediaStore(outputBytes) }

        captured.raw?.let { raw ->
            try {
                // DngCreator.writeImage does real file/encoder IO — must not run on the caller's
                // thread, mirroring saveJpegToMediaStore's own Dispatchers.IO above.
                withContext(Dispatchers.IO) {
                    saveRawDngToMediaStore(raw, characteristics, jpegOrientation(characteristics))
                }
            } catch (e: IOException) {
                // See this function's own doc — a failed DNG write must not lose the JPEG capture
                // that already succeeded.
            } catch (e: IllegalStateException) {
                // MediaStore insert/output-stream failure — same reasoning as above.
            } finally {
                raw.image.close()
            }
        }
        return uri
    }

    /**
     * A single still-capture request carries both the JPEG and (when [rawReader] is non-null) RAW
     * targets — Camera2 only guarantees they come from the identical sensor frame when captured
     * together on one request, which is exactly what [DngCreator] needs matched to the JPEG's own
     * companion shot. The JPEG side is delivered via the existing [imageAvailableListener]/
     * [pendingCapture] flow unchanged; the RAW side (when requested) is awaited independently via
     * [pendingRawCapture], bounded by [RawCaptureTimeoutMs] so a HAL that never delivers one of the
     * two RAW pieces can't hang a capture forever — see [pendingRawImagesByTimestamp]'s own doc for
     * how the two are correlated.
     */
    private suspend fun captureStillJpeg(
        device: CameraDevice,
        session: CameraCaptureSession,
        reader: ImageReader,
        characteristics: CameraCharacteristics,
        lens: LensSnapshot?,
        rawReader: ImageReader?,
        handler: Handler?,
    ): CapturedStill {
        val rawDeferred = rawReader?.let { CompletableDeferred<Pair<Image, TotalCaptureResult>>() }
        pendingRawCapture = rawDeferred

        val jpegBytes = suspendCancellableCoroutine { continuation ->
            pendingCapture = continuation
            continuation.invokeOnCancellation {
                if (pendingCapture === continuation) pendingCapture = null
                rawDeferred?.cancel()
            }

            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                rawReader?.let { addTarget(it.surface) }
                set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation(characteristics))
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)

                previewRequestController.applyExposure(this, lens, previewSafe = false)
                previewRequestController.applyFocusSettings(this, lens)

                set(
                    CaptureRequest.FLASH_MODE,
                    if (pendingFlashMode == FlashMode.ON) CaptureRequest.FLASH_MODE_SINGLE else CaptureRequest.FLASH_MODE_OFF,
                )
            }.build()

            try {
                session.capture(request, stillCaptureCallback, handler)
            } catch (e: CameraAccessException) {
                pendingCapture = null
                pendingRawCapture = null
                continuation.resumeWithException(e)
            } catch (e: IllegalStateException) {
                pendingCapture = null
                pendingRawCapture = null
                continuation.resumeWithException(e)
            }
        }

        val raw = rawDeferred?.let { deferred -> withTimeoutOrNull(RawCaptureTimeoutMs) { deferred.await() } }
        pendingRawCapture = null
        if (raw == null && rawDeferred != null) {
            // Timed out (or was cancelled) with at most a half-arrived pair — drop whatever's left so
            // it can't leak or wrongly get matched against a later, unrelated capture.
            pendingRawImagesByTimestamp.values.forEach { it.close() }
            pendingRawImagesByTimestamp.clear()
            pendingRawResultsByTimestamp.clear()
        }
        return CapturedStill(jpegBytes, raw?.let { (image, result) -> RawCaptureResult(image, result) })
    }

    /**
     * `(sensorOrientation - surfaceRotationDegrees + 360) % 360`, the standard back-camera Camera2
     * `JPEG_ORIENTATION` formula (front cameras additionally mirror, not needed here since this app
     * only ever binds back lenses — see [CameraController.listBackLenses]). [targetRotationDegrees]
     * substitutes for a live `Display.getRotation()` query since the activity is locked to portrait
     * (see [DeviceOrientationTracker]'s own doc).
     */
    private fun jpegOrientation(characteristics: CameraCharacteristics): Int {
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val surfaceRotationDegrees = when (targetRotationDegrees()) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return (sensorOrientation - surfaceRotationDegrees + 360) % 360
    }

    /**
     * [totalCaptureResult] and the underlying `RAW_SENSOR` buffer inside [raw] are already matched to
     * the same captured frame by [captureStillJpeg]'s timestamp correlation — this just feeds them to
     * [DngCreator], the framework's own spec-valid DNG writer, rather than hand-rolling DNG output.
     * [orientationDegrees] mirrors [jpegOrientation]'s own value so the DNG rotates for a gallery
     * viewer exactly the same way its JPEG companion does. Caller closes [raw]'s [Image] — this
     * function only writes it, matching [saveJpegToMediaStore]'s own "caller owns the bytes" shape.
     */
    private fun saveRawDngToMediaStore(raw: RawCaptureResult, characteristics: CameraCharacteristics, orientationDegrees: Int): Uri {
        val name = "xCamera_${System.currentTimeMillis()}.dng"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Same album as the JPEG companion (see saveJpegToMediaStore) so both land side by
                // side in the gallery.
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Camera")
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw IllegalStateException("MediaStore insert failed for DNG")
        context.contentResolver.openOutputStream(uri)?.use { out ->
            DngCreator(characteristics, raw.totalCaptureResult).apply {
                setOrientation(exifOrientationFor(orientationDegrees))
            }.writeImage(out, raw.image)
        } ?: throw IllegalStateException("Couldn't open an output stream for DNG $uri")
        return uri
    }

    /** [DngCreator.setOrientation] takes an EXIF orientation constant, not degrees — mirrors
     *  [jpegOrientation]'s own degrees value into the equivalent [ExifInterface.ORIENTATION_*]. */
    private fun exifOrientationFor(degrees: Int): Int = when (degrees) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }

    /** [captureStillJpeg]'s combined result — [raw] is `null` whenever the shot didn't request (or
     *  didn't end up with) a RAW buffer. */
    private class CapturedStill(val jpegBytes: ByteArray, val raw: RawCaptureResult?)

    /** An [image] already matched (by [captureStillJpeg]'s timestamp correlation) to the
     *  [totalCaptureResult] from the very same captured frame — see [pendingRawImagesByTimestamp]'s
     *  own doc. Caller owns closing [image]. */
    private class RawCaptureResult(val image: Image, val totalCaptureResult: TotalCaptureResult)

    private fun saveJpegToMediaStore(bytes: ByteArray): Uri {
        val name = "xCamera_${System.currentTimeMillis()}.jpg"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // DCIM/Camera is the same album the stock camera app writes to, so shots land in
                // the main gallery/camera roll instead of a separate xCamera-only album.
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Camera")
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw IllegalStateException("MediaStore insert failed")
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: throw IllegalStateException("Couldn't open an output stream for $uri")
        return uri
    }

    /**
     * The most recent photo in the device's gallery (not just ones this app took) — backs the
     * viewfinder's thumbnail chip. Without the gallery-read permission granted (see
     * `CameraScreen`'s soft, independent request for it), scoped storage silently narrows this
     * query down to only this app's own MediaStore rows rather than throwing.
     */
    suspend fun latestGalleryPhotoUri(): Uri? = withContext(Dispatchers.IO) {
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
            ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        }
    }

    /** Called by [CameraController.closeCameraAndSessionLocked] — an in-flight "with RAW" capture has
     *  nothing left to resolve once the session it was running against is gone; cancelling (rather than
     *  leaving it dangling) lets [captureStillJpeg]'s awaiting caller unwind instead of hanging forever.
     *  Any half-arrived [Image] already buffered in [pendingRawImagesByTimestamp] must be closed
     *  explicitly — Camera2 never reclaims an `Image` the app itself is still holding a reference to. */
    fun onSessionClosed() {
        pendingRawCapture?.let { if (it.isActive) it.cancel() }
        pendingRawCapture = null
        pendingRawImagesByTimestamp.values.forEach { it.close() }
        pendingRawImagesByTimestamp.clear()
        pendingRawResultsByTimestamp.clear()
    }

    private companion object {
        /** Bounded safety net for a "with RAW" capture's [pendingRawCapture] await — generous
         *  relative to how fast a HAL normally delivers both the RAW [Image] and its
         *  [TotalCaptureResult] after a single capture request, just there so a HAL that never
         *  delivers one of the two can't hang [captureStillJpeg]'s caller (and therefore the shutter)
         *  forever; the JPEG side is unaffected either way since it's awaited independently. */
        const val RawCaptureTimeoutMs = 5_000L
    }
}
