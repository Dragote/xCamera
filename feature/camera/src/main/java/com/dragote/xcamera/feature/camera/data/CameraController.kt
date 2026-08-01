package com.dragote.xcamera.feature.camera.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import android.view.OrientationEventListener
import android.view.Surface
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.dragote.xcamera.feature.camera.domain.model.CameraLens
import com.dragote.xcamera.feature.camera.domain.model.FlashMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.sqrt

/**
 * Thin wrapper around CameraX's provider/bind/capture lifecycle. Kept out of the ViewModel since
 * binding inherently needs a Compose LifecycleOwner + Preview.SurfaceProvider, which are ui-layer
 * types — see CLAUDE.md's data-layer-owns-hardware convention.
 *
 * Camera2Interop usage below (physical-lens enumeration/selection) is opted in project-wide via
 * this module's lint.xml rather than per-call-site @OptIn, which has no effect here — see that
 * file for why.
 */
class CameraController(private val context: Context) {

    private var imageCapture: ImageCapture? = null
    private var pendingFlashMode: FlashMode = FlashMode.OFF

    /**
     * The activity is locked to portrait (see AndroidManifest) so the skeuomorphic UI never
     * rotates, which means [ImageCapture] no longer gets a fresh target rotation for free from
     * the Activity's own configuration changes. This listener tracks the phone's *physical*
     * orientation via the accelerometer instead, so a photo taken while the phone is held in
     * landscape is still saved landscape rather than being locked to portrait output.
     */
    private var orientationEventListener: OrientationEventListener? = null

    private fun ensureOrientationListener() {
        if (orientationEventListener != null) return
        orientationEventListener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                imageCapture?.targetRotation = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
            }
        }.apply { enable() }
    }

    /** Call when the composable hosting this controller leaves composition. */
    fun stopOrientationListener() {
        orientationEventListener?.disable()
        orientationEventListener = null
    }

    /**
     * [lens] is null for the plain default back camera. When non-null, [CameraLens.physicalCameraId]
     * (if set) is pinned via Camera2Interop — most multi-lens phones expose their extra lenses as
     * physical sub-cameras of one logical camera rather than as separate top-level camera IDs, and
     * a bare [CameraSelector] filter can only pick among top-level IDs.
     */
    suspend fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        lens: CameraLens? = null,
    ) {
        val cameraProvider = getCameraProvider()
        val cameraSelector = lens?.let { selectorFor(it) } ?: CameraSelector.DEFAULT_BACK_CAMERA

        val previewBuilder = Preview.Builder()
        val captureBuilder = ImageCapture.Builder()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lens?.physicalCameraId?.let { physicalCameraId ->
                Camera2Interop.Extender(previewBuilder).setPhysicalCameraId(physicalCameraId)
                Camera2Interop.Extender(captureBuilder).setPhysicalCameraId(physicalCameraId)
            }
        }

        val preview = previewBuilder.build().apply { setSurfaceProvider(surfaceProvider) }
        val capture = captureBuilder.build().apply {
            flashMode = pendingFlashMode.toImageCaptureFlashMode()
        }

        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, capture)
        imageCapture = capture
        ensureOrientationListener()
    }

    private fun selectorFor(lens: CameraLens): CameraSelector = CameraSelector.Builder()
        .addCameraFilter { cameraInfos ->
            cameraInfos.filter { Camera2CameraInfo.from(it).cameraId == lens.logicalCameraId }
        }
        .build()

    /**
     * Cached in [pendingFlashMode] so a mode set before [bindCamera] completes (e.g. the initial
     * composition) still applies once the ImageCapture use case is bound, instead of being lost.
     */
    fun setFlashMode(flashMode: FlashMode) {
        pendingFlashMode = flashMode
        imageCapture?.flashMode = flashMode.toImageCaptureFlashMode()
    }

    private fun FlashMode.toImageCaptureFlashMode(): Int = when (this) {
        FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
        FlashMode.ON -> ImageCapture.FLASH_MODE_ON
    }

    /**
     * Most multi-lens phones fuse ultra-wide/main/tele into one LOGICAL_MULTI_CAMERA logical
     * camera ID rather than exposing them as separate top-level IDs, so this walks each back
     * logical camera's [CameraCharacteristics.getPhysicalCameraIds] instead of just
     * [CameraManager.getCameraIdList]. Zoom ratio is each lens's *35mm-equivalent* focal length
     * (see [equivalentFocalLength]) relative to the median equivalent focal length among all back
     * lenses — main/wide is always the middle value between ultra-wide (shortest) and tele
     * (longest), which holds regardless of whether lenses are separate IDs or physical sub-cameras
     * of one logical ID.
     *
     * Some devices (many Samsung) list a physical sub-camera *both* ways — as its own top-level
     * ID in [CameraManager.getCameraIdList] and inside its logical camera's physicalCameraIds — so
     * a physical ID already covered by its own top-level entry is skipped here to avoid double-counting the same lens.
     *
     * Pixels additionally expose *virtual* 2x-crop IDs for smooth zoom transitions: same
     * [CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS] and
     * [CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE] as a real lens, but with
     * [CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE] reported at exactly half width/height —
     * i.e. same pixel count claimed on a synthetically smaller chip, which is not a distinct piece
     * of glass. Grouping candidates by (focal length, pixel array size) and keeping only the
     * largest-sensor-area entry per group below collapses those back into their real lens.
     */
    fun listBackLenses(): List<CameraLens> {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val allCameraIds = cameraManager.cameraIdList.toSet()

        data class Candidate(
            val logicalCameraId: String,
            val physicalCameraId: String?,
            val focalLength: Float,
            val pixelArraySize: android.util.Size,
            val sensorAreaMm2: Float,
            val equivFocalLength: Float,
        )

        fun candidateOf(logicalCameraId: String, physicalCameraId: String?, characteristics: CameraCharacteristics): Candidate? {
            // Depth/mono auxiliary sensors can show up as physical sub-cameras too, but aren't a
            // normal photo lens — BACKWARD_COMPATIBLE is what guarantees a plain JPEG/YUV output.
            val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            if (CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE !in capabilities) return null

            val focalLength = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.minOrNull()
                ?: return null
            val pixelArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) ?: return null
            val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
            val equivFocalLength = equivalentFocalLength(focalLength, sensorSize) ?: return null
            return Candidate(
                logicalCameraId,
                physicalCameraId,
                focalLength,
                pixelArraySize,
                sensorAreaMm2 = sensorSize.width * sensorSize.height,
                equivFocalLength,
            )
        }

        val candidates = mutableListOf<Candidate>()
        for (logicalCameraId in allCameraIds) {
            val characteristics = cameraManager.getCameraCharacteristics(logicalCameraId)
            if (characteristics.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) {
                continue
            }

            val physicalCameraIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                characteristics.physicalCameraIds
            } else {
                emptySet()
            }

            if (physicalCameraIds.isEmpty()) {
                candidates += candidateOf(logicalCameraId, physicalCameraId = null, characteristics) ?: continue
            } else {
                for (physicalCameraId in physicalCameraIds) {
                    // Already (or will be) represented by its own top-level entry above/below.
                    if (physicalCameraId in allCameraIds) continue
                    val physicalCharacteristics = cameraManager.getCameraCharacteristics(physicalCameraId)
                    candidates += candidateOf(logicalCameraId, physicalCameraId, physicalCharacteristics) ?: continue
                }
            }
        }

        if (candidates.isEmpty()) return emptyList()

        // Some devices reference the same physical sensor from more than one logical camera ID
        // (e.g. separate "primary" and "assistant" multi-camera groupings), which the per-ID skip
        // above doesn't catch since it only compares against top-level IDs. Collapse by physical
        // sensor identity as a final pass — physicalCameraId when pinned, otherwise the camera's
        // own (unique) top-level ID.
        val dedupedById = candidates.distinctBy { it.physicalCameraId ?: it.logicalCameraId }

        // Collapse virtual 2x-crop duplicates: same focal length + pixel count, keep the one with
        // the larger (real) reported sensor area.
        val realLenses = dedupedById
            .groupBy { it.focalLength to it.pixelArraySize }
            .values
            .map { group -> group.maxBy { it.sensorAreaMm2 } }

        val mainEquivFocalLength = realLenses.map { it.equivFocalLength }.sorted().let { it[(it.size - 1) / 2] }

        return realLenses
            .map { CameraLens(it.logicalCameraId, it.physicalCameraId, zoomRatio = it.equivFocalLength / mainEquivFocalLength) }
            .sortedBy { it.zoomRatio }
    }

    /**
     * Raw [CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS] alone isn't comparable across
     * lens modules — a periscope telephoto's physical focal length can look deceptively close to
     * the main lens's because its sensor is much smaller, not because its real-world zoom is
     * similar. Normalizing by sensor size (the standard 35mm-equivalent focal length formula) makes
     * focal length comparable across modules with different sensor sizes.
     */
    private fun equivalentFocalLength(focalLength: Float, sensorSize: android.util.SizeF): Float? {
        val sensorDiagonalMm = sqrt(sensorSize.width * sensorSize.width + sensorSize.height * sensorSize.height)
        if (sensorDiagonalMm <= 0f) return null

        val fullFrameDiagonalMm = 43.27f // sqrt(36^2 + 24^2), the standard 35mm/full-frame reference
        return focalLength * (fullFrameDiagonalMm / sensorDiagonalMm)
    }

    suspend fun takePhoto(): Uri {
        val capture = checkNotNull(imageCapture) { "Camera not bound yet" }

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
        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues,
        ).build()

        return suspendCancellableCoroutine { continuation ->
            capture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        val uri = outputFileResults.savedUri
                        if (uri != null) {
                            continuation.resume(uri)
                        } else {
                            continuation.resumeWithException(IllegalStateException("Photo saved but no URI returned"))
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        continuation.resumeWithException(exception)
                    }
                },
            )
        }
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

    private suspend fun getCameraProvider(): ProcessCameraProvider = suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            { continuation.resume(future.get()) },
            ContextCompat.getMainExecutor(context),
        )
    }
}