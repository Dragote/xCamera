package com.dragote.xcamera.feature.camera.domain.model

/**
 * Parses a standard ASCII `.cube` 3D LUT file (Adobe/Iridas format, the same one DaVinci Resolve/
 * Lightroom export) into a [CubeLut] — a plain, Android/GL-type-free domain function per this
 * project's camera conventions (`.claude/agents/camera-engineer.md`: "LUT math/parsing should stay
 * in plain testable classes, GL/shader plumbing thin"), so it's unit-testable directly against real
 * `.cube` file content with no GL context or Android framework involved at all.
 *
 * Supported subset (this project's own non-goals explicitly exclude anything beyond a shippable
 * import-and-apply slice): `LUT_3D_SIZE N` header, then exactly `N*N*N` data rows of three
 * whitespace-separated floats (`r g b`, each expected in `[0,1]` per the format's own convention —
 * not clamped/validated here, an out-of-range value just uploads as-is). `#`-prefixed comment lines
 * and blank lines are skipped anywhere. `TITLE`/`DOMAIN_MIN`/`DOMAIN_MAX` header lines are recognized
 * and skipped (this parser doesn't support a non-default `[0,1]` domain remap) rather than
 * misinterpreted as data rows. `LUT_1D_SIZE` (a different, 1D-LUT file) and any other unrecognized
 * keyword line are treated as unsupported input.
 *
 * Data rows are read in the file's own order — the `.cube` spec's own convention is the *blue*
 * coordinate varies fastest (then green, then red), i.e. row index `r*size*size + g*size + b` for
 * the LUT entry at [r, g, b] — callers building a `GL_TEXTURE_3D` from [CubeLut.values] must upload
 * with that same axis order for the result to sample correctly.
 *
 * Returns `null` (never throws) on anything malformed: missing/duplicate/non-positive `LUT_3D_SIZE`,
 * a data row that isn't exactly three parseable floats, or a final row count that doesn't match
 * `size^3` exactly — a corrupt/unsupported file should just fail to import, not crash the caller.
 */
private val whitespaceRegex = Regex("\\s+")

fun parseCubeLut(content: String): CubeLut? {
    var size: Int? = null
    val values = ArrayList<Float>()

    for (rawLine in content.lineSequence()) {
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("#")) continue

        val upperFirstToken = line.substringBefore(' ').uppercase()
        when (upperFirstToken) {
            "LUT_3D_SIZE" -> {
                if (size != null) return null // duplicate header — malformed
                val parsedSize = line.substringAfter(' ').trim().toIntOrNull() ?: return null
                if (parsedSize <= 0) return null
                size = parsedSize
            }
            "TITLE", "DOMAIN_MIN", "DOMAIN_MAX" -> {
                // Recognized but unsupported/irrelevant to this parser — see this function's own doc.
            }
            "LUT_1D_SIZE" -> return null // a different file format entirely, not a 3D LUT.
            else -> {
                // Expected to be a data row: "r g b".
                val components = line.split(whitespaceRegex)
                if (components.size != 3) return null
                val row = components.map { it.toFloatOrNull() ?: return null }
                values.addAll(row)
            }
        }
    }

    val resolvedSize = size ?: return null
    val expectedFloatCount = resolvedSize * resolvedSize * resolvedSize * 3
    if (values.size != expectedFloatCount) return null

    return CubeLut(resolvedSize, values.toFloatArray())
}
