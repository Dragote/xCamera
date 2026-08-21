package com.dragote.xcamera.feature.camera.data

import java.io.File
import javax.inject.Inject

/**
 * Thin, mockable seam around reading a LUT file's raw bytes off disk. Extracted purely so
 * `CameraRepositoryImplTest` can verify `CameraRepositoryImpl.setLut`'s in-memory resolve cache
 * actually skips file IO on a cache hit, without needing to fake `java.io.File` itself — a real
 * `File`/`IOException` failure mode has no interesting branching logic of its own worth unit testing
 * beyond "returns null instead of throwing", which this class's own [readBytes] already covers by
 * construction.
 *
 * Bytes, not text — `feature:settings`' `LutLocalDataSource` stores every LUT in
 * `com.dragote.xcamera.shared.common.domain.model.CubeLut.toBinary`'s compact binary format, so
 * there's no text content to read here.
 *
 * `@Inject constructor()` with no parameters is enough for Hilt to provide this automatically — no
 * `di/CameraModule` wiring needed, same as any other zero-dependency `@Inject`-constructed class.
 */
class LutFileReader @Inject constructor() {

    /** Returns [filePath]'s full raw byte content, or `null` on any read failure (missing file,
     *  permission error, etc.) — never throws, mirroring `CameraRepositoryImpl.setLut`'s own "any
     *  resolve failure just means no LUT" contract. */
    fun readBytes(filePath: String): ByteArray? = runCatching { File(filePath).readBytes() }.getOrNull()
}
