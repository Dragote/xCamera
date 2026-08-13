package com.dragote.xcamera.shared.common.domain.model

/**
 * A user-imported 3D color-grading LUT (`.cube` file), copied into app-private storage on import so
 * it persists across app restarts and doesn't depend on a `content://` SAF grant remaining valid.
 * [filePath] is a plain absolute file-system path (never the original SAF `Uri`) — both
 * `feature:settings` (list/import UI) and `feature:camera` (parses the file's content into a
 * `GL_TEXTURE_3D` via its own `CubeLutParser`) can read it directly with plain `java.io.File` APIs,
 * no Android platform type needed once import has copied the bytes locally.
 */
data class LutPreset(
    val id: String,
    val displayName: String,
    val filePath: String,
)
