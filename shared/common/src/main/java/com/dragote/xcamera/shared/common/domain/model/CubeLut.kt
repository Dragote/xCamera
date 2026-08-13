package com.dragote.xcamera.shared.common.domain.model

/**
 * A parsed 3D LUT, ready to upload as a `GL_TEXTURE_3D` (see `feature:camera`'s
 * `ui/gl/CameraPreviewRenderer` and `data/gl/LutJpegProcessor`) — [size] is the standard `.cube`
 * file's `LUT_3D_SIZE N` (the texture is NxNxN), [values] is `size*size*size*3` floats in `[0,1]`,
 * laid out exactly as `CubeLutParser` reads them off the file: blue-fastest, then green, then red
 * (the ASCII `.cube` format's own row order — see that parser's own doc), RGB triplets within each
 * row. A plain `class` (not `data class`) because [FloatArray] has no structural `equals`/`hashCode`
 * of its own — [equals]/[hashCode] below are hand-written so this can still be compared/used in tests
 * and `MutableStateFlow` value checks the way a data class normally would be.
 *
 * Lives in `shared:common` (moved from `feature:camera` in issue #43's follow-up) because both
 * `feature:camera` (real-time preview/capture grading) and `feature:settings` (import-time
 * validation/resampling, see [resampleCubeLut]) need the same parse/resample logic — per this
 * project's "duplication vs. abstraction" convention, cross-feature domain logic belongs here, not
 * duplicated or force-routed through one feature depending on the other.
 */
class CubeLut(val size: Int, val values: FloatArray) {

    override fun equals(other: Any?): Boolean =
        other is CubeLut && size == other.size && values.contentEquals(other.values)

    override fun hashCode(): Int = 31 * size + values.contentHashCode()
}
