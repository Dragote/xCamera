package com.dragote.xcamera.feature.settings.data.local

import android.content.Context
import android.net.Uri
import com.dragote.xcamera.shared.common.domain.model.LutPreset
import com.dragote.xcamera.shared.common.domain.model.parseCubeLut
import com.dragote.xcamera.shared.common.domain.model.resampleCubeLut
import com.dragote.xcamera.shared.common.domain.model.toBinary
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject

/**
 * Plain app-private-storage backing for imported LUTs — deliberately no Room/DataStore here: the file
 * system itself (one file per import, under `context.filesDir/luts/`) is already the full source of
 * truth, so [listLuts] just scans that directory rather than maintaining a separate metadata store
 * that could drift out of sync with it. Per this project's minimal-infra preference, this is the
 * smallest working slice for a first import-and-list LUT store, not a placeholder for a "real"
 * database later.
 *
 * A [LutPreset]'s [LutPreset.id]/[LutPreset.displayName] are both encoded directly into its file name
 * (`<uuid>__<sanitized display name>.$LutFileExtension`) rather than a separate manifest — `__` is an
 * arbitrary but exceedingly unlikely delimiter to collide with a real display name, and sanitizing
 * (see [sanitizeForFileName]) means the display name recovered from a file name may differ
 * cosmetically from what the user originally typed (stripped punctuation, truncated) — an accepted
 * simplification, not a correctness issue for this feature's own scope.
 *
 * [importLut] (issue #43 follow-up) validates every picked file against [parseCubeLut] and resamples
 * it onto [CanonicalLutSize] before writing it to disk. **Stored on disk as [toBinary]'s compact
 * format, not ASCII `.cube` text** (a second follow-up) — every `.cube` file the user hands this class
 * only ever exists transiently in memory during import; what's actually persisted is always exactly
 * [CanonicalLutSize]³ *and* already in the format `CameraRepositoryImpl` reads back with a plain bulk
 * byte read, no text parsing at all. This is what keeps every LUT in the library uniformly small/fast
 * to load (not just uniformly *sized*, which the resample step alone would already guarantee) and lets
 * `feature:camera`'s GPU texture cache assume a fixed texture size across LUT switches (see that
 * module's `ui/gl/CameraPreviewRenderer` for the consumer of that assumption).
 */
class LutLocalDataSource @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val lutsDir: File by lazy { File(context.filesDir, "luts").apply { mkdirs() } }

    /** Synchronous directory scan — callers (see `LutRepositoryImpl`) are expected to run this off
     *  the main thread. Empty (not an error) when nothing's been imported yet or the directory can't
     *  be listed. */
    fun listLuts(): List<LutPreset> =
        lutsDir.listFiles { file -> file.isFile && file.extension.equals(LutFileExtension, ignoreCase = true) }
            ?.mapNotNull { file -> presetFromFile(file) }
            ?.sortedBy { it.displayName.lowercase() }
            ?: emptyList()

    /**
     * Reads [sourceUri]'s bytes (the SAF `ACTION_OPEN_DOCUMENT` result) entirely into memory, validates
     * it's an actual parseable `.cube` file via [parseCubeLut], resamples it onto [CanonicalLutSize]
     * (issue #43 follow-up — see this class's own doc for why a uniform on-disk size matters), and
     * writes *only* the resulting [toBinary] bytes to app-private storage under a fresh id — the picked
     * file's own raw bytes are never themselves written to disk, so there's nothing left over to clean
     * up on a validation failure and no risk of a stray non-canonical-format file surviving a failed
     * import. Returns the resulting [LutPreset]. Throws [IOException] on any read/write failure *or* on
     * content that fails [parseCubeLut] (an unsupported/corrupt `.cube`) — `LutRepositoryImpl` is what
     * translates either failure into a [com.dragote.xcamera.shared.common.domain.result.Result], this
     * data source stays exception-based like `CameraController`'s own hardware-adjacent calls do.
     *
     * This is now the *primary* validation gate for import — `LutResolutionRepository`'s
     * resolve-failure auto-cleanup (`feature:camera`, wired in `SettingsViewModel`) is a secondary,
     * defensive fallback for a file that goes missing/corrupts on disk *after* a valid import (e.g.
     * external interference), not the first line of defense it used to be before the parser moved to
     * `shared:common`.
     *
     * The returned [LutPreset.displayName] is already run through [sanitizeForFileName] (not the raw
     * [displayName] as typed/picked) — deliberately, so it's identical to what a later [listLuts] scan
     * (which can only ever recover the sanitized name off the file name) would report for this same
     * preset after a process restart; returning the raw name here would mean the same LUT's display
     * name visibly changes the moment the app process restarts.
     */
    fun importLut(sourceUri: Uri, displayName: String): LutPreset {
        val pickedContent = context.contentResolver.openInputStream(sourceUri)
            ?.use { stream -> stream.bufferedReader().readText() }
            ?: throw IOException("Couldn't open an input stream for $sourceUri")

        val parsed = parseCubeLut(pickedContent)
            ?: throw IOException("$sourceUri did not contain a valid .cube file")

        val id = UUID.randomUUID().toString()
        val sanitizedDisplayName = sanitizeForFileName(displayName)
        val file = File(lutsDir, "$id$FileNameSeparator$sanitizedDisplayName.$LutFileExtension")
        file.writeBytes(resampleCubeLut(parsed, CanonicalLutSize).toBinary())

        return LutPreset(id = id, displayName = sanitizedDisplayName, filePath = file.absolutePath)
    }

    /**
     * Deletes the backing file at [filePath] (see [LutPreset.filePath]). Returns whether the file was
     * actually deleted — `false` (not an exception) for a missing/already-gone file or a plain
     * OS-level delete failure, matching [File.delete]'s own contract; `LutRepositoryImpl` is what turns
     * that into a [com.dragote.xcamera.shared.common.domain.result.Result.Error]. [importLut] never
     * writes more than this one file per LUT (see its own doc), so deleting it is always the complete
     * cleanup — nothing else to chase down.
     */
    fun deleteLut(filePath: String): Boolean = File(filePath).delete()

    private fun presetFromFile(file: File): LutPreset? {
        val nameWithoutExtension = file.nameWithoutExtension
        val separatorIndex = nameWithoutExtension.indexOf(FileNameSeparator)
        if (separatorIndex < 0) return null
        val id = nameWithoutExtension.substring(0, separatorIndex)
        val displayName = nameWithoutExtension.substring(separatorIndex + FileNameSeparator.length)
        if (id.isEmpty() || displayName.isEmpty()) return null
        return LutPreset(id = id, displayName = displayName, filePath = file.absolutePath)
    }

    /** Strips path separators/control characters a raw display name could contain (a `.cube` file
     *  picked via SAF can be named almost anything) and caps length — this is a file name component,
     *  not free text, but otherwise preserves the name as typed/picked. */
    private fun sanitizeForFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F]"), "_")
            .trim()
            .ifEmpty { "LUT" }
            .take(60)

    private companion object {
        const val FileNameSeparator = "__"

        /** The standard professional `.cube` grid size (DaVinci Resolve/Lightroom's own default
         *  export size) — small and fast enough to parse/upload while still visually indistinguishable
         *  from a larger grid for real-time preview grading. See this class's own doc for why every
         *  imported LUT is normalized onto this one size. */
        const val CanonicalLutSize = 33

        /** Deliberately not `cube` — every file this class stores is [CubeLut.toBinary]'s internal
         *  binary format, never ASCII `.cube` text (see this class's own doc), so a different extension
         *  keeps [listLuts] from ever mistaking one for the other on disk. */
        const val LutFileExtension = "lutbin"
    }
}
