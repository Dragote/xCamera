package com.dragote.xcamera.feature.camera.domain.model

/**
 * A physical back-facing lens exposed by the device, expressed as its zoom ratio relative to the
 * device's primary ("1x") lens — e.g. an ultra-wide lens is < 1f, a telephoto lens is > 1f.
 *
 * Most multi-lens phones (Pixel, Samsung flagships) expose ultra-wide/tele not as separate
 * top-level camera IDs but as [physicalCameraId]s within one LOGICAL_MULTI_CAMERA [logicalCameraId]
 * — binding always targets [logicalCameraId], with [physicalCameraId] (if non-null) pinning which
 * physical sensor backs it via Camera2Interop.
 */
data class CameraLens(
    val logicalCameraId: String,
    val physicalCameraId: String?,
    val zoomRatio: Float,
)
