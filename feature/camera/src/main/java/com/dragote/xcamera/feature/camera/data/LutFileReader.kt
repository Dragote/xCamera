package com.dragote.xcamera.feature.camera.data

import java.io.File
import javax.inject.Inject

/**
 * Thin, mockable seam around reading a LUT file's raw text content off disk. Extracted purely so
 * `CameraRepositoryImplTest` can verify `CameraRepositoryImpl.setLut`'s in-memory resolve cache
 * (issue #43 follow-up) actually skips file IO on a cache hit, without needing to fake
 * `java.io.File` itself — a real `File`/`IOException` failure mode has no interesting branching logic
 * of its own worth unit testing beyond "returns null instead of throwing", which this class's own
 * [readText] already covers by construction.
 *
 * `@Inject constructor()` with no parameters is enough for Hilt to provide this automatically — no
 * `di/CameraModule` wiring needed, same as any other zero-dependency `@Inject`-constructed class.
 */
class LutFileReader @Inject constructor() {

    /** Returns [filePath]'s full text content, or `null` on any read failure (missing file,
     *  permission error, etc.) — never throws, mirroring `CameraRepositoryImpl.setLut`'s own "any
     *  resolve failure just means no LUT" contract. */
    fun readText(filePath: String): String? = runCatching { File(filePath).readText() }.getOrNull()
}
