package com.dragote.xcamera.feature.camera.domain.model

/**
 * A parsed 3D LUT, ready to upload as a `GL_TEXTURE_3D` (see `ui/gl/CameraPreviewRenderer` and
 * `data/gl/LutJpegProcessor`) — [size] is the standard `.cube` file's `LUT_3D_SIZE N` (the texture is
 * NxNxN), [values] is `size*size*size*3` floats in `[0,1]`, laid out exactly as `CubeLutParser` read
 * them off the file: blue-fastest, then green, then red (the ASCII `.cube` format's own row order —
 * see that parser's own doc), RGB triplets within each row. A plain `class` (not `data class`)
 * because [FloatArray] has no structural `equals`/`hashCode` of its own — [equals]/[hashCode] below
 * are hand-written so this can still be compared/used in tests and `MutableStateFlow` value checks
 * the way a data class normally would be.
 */
class CubeLut(val size: Int, val values: FloatArray) {

    override fun equals(other: Any?): Boolean =
        other is CubeLut && size == other.size && values.contentEquals(other.values)

    override fun hashCode(): Int = 31 * size + values.contentHashCode()
}

/** [CubeLut] plus the blend intensity to mix it in at — the one thing `ui/CameraScreen`'s preview
 *  renderer and `CameraController`'s still-capture path both need, cached together in `CameraController
 *  .activeLut` the same "single pending value, reapplied everywhere it's needed" way
 *  `pendingManualIso`/`pendingManualShutterNs` already are. */
data class ActiveLut(val cubeLut: CubeLut, val intensityPercent: Int)
