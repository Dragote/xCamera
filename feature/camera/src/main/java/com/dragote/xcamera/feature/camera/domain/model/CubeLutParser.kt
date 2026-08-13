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
 * a data row that isn't exactly three parseable floats, a data row before the `LUT_3D_SIZE` header has
 * been seen (the normal/valid case — and every real `.cube` export — always puts the size header
 * first; this parser doesn't try to support an out-of-order file, see this function's own perf-rewrite
 * doc below), or a final row count that doesn't match `size^3` exactly — a corrupt/unsupported file
 * should just fail to import, not crash the caller.
 *
 * Writes directly into a [FloatArray] preallocated the moment `LUT_3D_SIZE` is parsed, rather than
 * accumulating into a growable `List<Float>` — a 64-size `.cube` file is `64*64*64*3` ≈ 800k values,
 * and boxing every one of those into a `Float` object (what a `List<Float>`/`ArrayList<Float>` would
 * do) is real, avoidable allocation pressure on the parse path this file's own earlier history already
 * flagged as user-visibly slow. No growable fallback for a data row seen before the size header, on
 * purpose — the `.cube` format's own convention (and every real exporter) always writes the header
 * first, so a file that violates this is already malformed by this parser's own standing contract; a
 * growable-buffer fallback purely to still parse a header-comes-second file would be solving a problem
 * no real `.cube` file actually has.
 */
private val whitespaceRegex = Regex("\\s+")

fun parseCubeLut(content: String): CubeLut? {
    var size: Int? = null
    var values: FloatArray? = null
    var writeIndex = 0

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
                values = FloatArray(parsedSize * parsedSize * parsedSize * 3)
            }
            "TITLE", "DOMAIN_MIN", "DOMAIN_MAX" -> {
                // Recognized but unsupported/irrelevant to this parser — see this function's own doc.
            }
            "LUT_1D_SIZE" -> return null // a different file format entirely, not a 3D LUT.
            else -> {
                // Expected to be a data row: "r g b" — malformed if seen before LUT_3D_SIZE (values is
                // still null, nothing to write into yet) or once more rows have shown up than the
                // header promised (writeIndex already at capacity).
                val target = values ?: return null
                val components = line.split(whitespaceRegex)
                if (components.size != 3) return null
                for (component in components) {
                    if (writeIndex >= target.size) return null
                    target[writeIndex] = component.toFloatOrNull() ?: return null
                    writeIndex++
                }
            }
        }
    }

    val resolvedSize = size ?: return null
    val resolvedValues = values ?: return null
    if (writeIndex != resolvedValues.size) return null // fewer rows than the header promised

    return CubeLut(resolvedSize, resolvedValues)
}
