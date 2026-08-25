package com.dragote.xcamera.shared.diagnostics.data

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import com.dragote.xcamera.shared.diagnostics.domain.model.LensSnapshot
import kotlin.math.sqrt

/** One physical back lens, paired with the [CameraCharacteristics] it was resolved from. */
data class LensCandidate(
    val snapshot: LensSnapshot,
    val characteristics: CameraCharacteristics,
)

/**
 * Enumerates the device's back-facing lenses (main/ultra-wide/tele) as [LensCandidate]s with a
 * computed zoom ratio and their raw sensor/aperture characteristics — a diagnostics-focused port of
 * `feature:camera`'s `BackLensEnumerator`, richer in what it surfaces per lens (sensor size, pixel
 * array, aperture) but identical in its lens-discovery algorithm.
 */
class LensEnumerator(private val cameraManager: CameraManager) {

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
    fun listBackLenses(): List<LensCandidate> {
        val allCameraIds = cameraManager.cameraIdList.toSet()

        data class Candidate(
            val logicalCameraId: String,
            val physicalCameraId: String?,
            val characteristics: CameraCharacteristics,
            val focalLength: Float,
            val pixelArraySize: android.util.Size,
            val sensorSize: android.util.SizeF,
            val sensorAreaMm2: Float,
            val apertureFNumber: Float?,
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
            val apertureFNumber = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull()
            return Candidate(
                logicalCameraId,
                physicalCameraId,
                characteristics,
                focalLength,
                pixelArraySize,
                sensorSize,
                sensorAreaMm2 = sensorSize.width * sensorSize.height,
                apertureFNumber,
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
            .map {
                LensCandidate(
                    snapshot = LensSnapshot(
                        logicalCameraId = it.logicalCameraId,
                        physicalCameraId = it.physicalCameraId,
                        zoomRatio = it.equivFocalLength / mainEquivFocalLength,
                        focalLengthMm = it.focalLength,
                        equivalentFocalLengthMm = it.equivFocalLength,
                        sensorWidthMm = it.sensorSize.width,
                        sensorHeightMm = it.sensorSize.height,
                        pixelArrayWidth = it.pixelArraySize.width,
                        pixelArrayHeight = it.pixelArraySize.height,
                        apertureFNumber = it.apertureFNumber,
                    ),
                    characteristics = it.characteristics,
                )
            }
            .sortedBy { it.snapshot.zoomRatio }
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
}
