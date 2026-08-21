package com.dragote.xcamera.shared.common.domain.model

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A compact internal on-disk format for a [CubeLut] — a fixed 12-byte header ([Magic]/[FormatVersion]/
 * `size`, each a 4-byte int) followed by [CubeLut.values] as raw `nativeOrder()` floats, no text
 * encoding at all. Used *only* for `feature:settings`' own app-private library storage
 * (`LutLocalDataSource`), never for anything the user picks or sees directly — the ASCII `.cube`
 * format ([parseCubeLut]/[toCubeFileContent]) stays the only format this app ever reads *from* a
 * user-picked file or would ever write back out for interchange/export.
 *
 * Reading this format is a single bulk byte read into a [FloatArray] with no text-parsing step at all
 * on `CameraRepositoryImpl`'s cold/first-selection path — see `docs/features/lut-color-grading.md` for
 * why this format exists alongside ASCII `.cube`. Since it skips the float→decimal-string→float
 * round-trip [toCubeFileContent]/[parseCubeLut] would otherwise do on every import, it's strictly at
 * least as precise as the text format, never less (see [parseCubeLutBinary] for the read side).
 *
 * Same "never throws, returns `null` on anything malformed" contract [parseCubeLut] already
 * established — a corrupted/truncated binary file should fail to load, not crash the caller.
 */
private const val Magic = 0x4C555442 // ASCII "LUTB"
private const val FormatVersion = 1
private const val HeaderBytes = 12 // Magic + FormatVersion + size, each a 4-byte int.

/** Inverse of [parseCubeLutBinary] — see this file's own doc. */
fun CubeLut.toBinary(): ByteArray {
    val buffer = ByteBuffer.allocate(HeaderBytes + values.size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
    buffer.putInt(Magic)
    buffer.putInt(FormatVersion)
    buffer.putInt(size)
    buffer.asFloatBuffer().put(values)
    return buffer.array()
}

/**
 * Parses [bytes] written by [CubeLut.toBinary] — `null` (never throws) on anything malformed: too
 * short for even the header, a wrong [Magic]/[FormatVersion] (e.g. a stray `.cube`-text file sitting
 * in the same directory under the wrong extension), a non-positive `size`, or a byte count that
 * doesn't exactly match `size³ * 3` floats worth of payload after the header.
 */
fun parseCubeLutBinary(bytes: ByteArray): CubeLut? {
    if (bytes.size < HeaderBytes) return null
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
    val magic = buffer.int
    val version = buffer.int
    val size = buffer.int
    if (magic != Magic || version != FormatVersion || size <= 0) return null

    val expectedFloatCount = size * size * size * 3
    val payloadFloatCount = (bytes.size - HeaderBytes) / Float.SIZE_BYTES
    if (payloadFloatCount != expectedFloatCount) return null

    val values = FloatArray(expectedFloatCount)
    buffer.asFloatBuffer().get(values)
    return CubeLut(size, values)
}
