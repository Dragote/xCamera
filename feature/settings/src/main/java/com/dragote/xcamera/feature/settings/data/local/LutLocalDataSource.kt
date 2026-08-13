package com.dragote.xcamera.feature.settings.data.local

import android.content.Context
import android.net.Uri
import com.dragote.xcamera.shared.common.domain.model.LutPreset
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject

/**
 * Plain app-private-storage backing for imported `.cube` LUTs — deliberately no Room/DataStore here:
 * the file system itself (one `.cube` file per import, under `context.filesDir/luts/`) is already the
 * full source of truth, so [listLuts] just scans that directory rather than maintaining a separate
 * metadata store that could drift out of sync with it. Per this project's minimal-infra preference,
 * this is the smallest working slice for a first import-and-list LUT store, not a placeholder for a
 * "real" database later.
 *
 * A [LutPreset]'s [LutPreset.id]/[LutPreset.displayName] are both encoded directly into its file name
 * (`<uuid>__<sanitized display name>.cube`) rather than a separate manifest — `__` is an arbitrary but
 * exceedingly unlikely delimiter to collide with a real display name, and sanitizing (see
 * [sanitizeForFileName]) means the display name recovered from a file name may differ cosmetically
 * from what the user originally typed (stripped punctuation, truncated) — an accepted simplification,
 * not a correctness issue for this feature's own scope.
 */
class LutLocalDataSource @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val lutsDir: File by lazy { File(context.filesDir, "luts").apply { mkdirs() } }

    /** Synchronous directory scan — callers (see `LutRepositoryImpl`) are expected to run this off
     *  the main thread. Empty (not an error) when nothing's been imported yet or the directory can't
     *  be listed. */
    fun listLuts(): List<LutPreset> =
        lutsDir.listFiles { file -> file.isFile && file.extension.equals("cube", ignoreCase = true) }
            ?.mapNotNull { file -> presetFromFile(file) }
            ?.sortedBy { it.displayName.lowercase() }
            ?: emptyList()

    /**
     * Copies [sourceUri]'s bytes (the SAF `ACTION_OPEN_DOCUMENT` result) into app-private storage
     * under a fresh id, returning the resulting [LutPreset]. Throws [IOException] on any read/write
     * failure — `LutRepositoryImpl` is what translates that into a [com.dragote.xcamera.shared.common
     * .domain.result.Result], this data source stays exception-based like `CameraController`'s own
     * hardware-adjacent calls do.
     *
     * The returned [LutPreset.displayName] is already run through [sanitizeForFileName] (not the raw
     * [displayName] as typed/picked) — deliberately, so it's identical to what a later [listLuts] scan
     * (which can only ever recover the sanitized name off the file name) would report for this same
     * preset after a process restart; returning the raw name here would mean the same LUT's display
     * name visibly changes the moment the app process restarts.
     */
    fun importLut(sourceUri: Uri, displayName: String): LutPreset {
        val id = UUID.randomUUID().toString()
        val sanitizedDisplayName = sanitizeForFileName(displayName)
        val file = File(lutsDir, "$id$FileNameSeparator$sanitizedDisplayName.cube")
        val input = context.contentResolver.openInputStream(sourceUri)
            ?: throw IOException("Couldn't open an input stream for $sourceUri")
        input.use { stream -> file.outputStream().use { output -> stream.copyTo(output) } }
        return LutPreset(id = id, displayName = sanitizedDisplayName, filePath = file.absolutePath)
    }

    /**
     * Deletes the backing `.cube` file at [filePath] (see [LutPreset.filePath]). Returns whether the
     * file was actually deleted — `false` (not an exception) for a missing/already-gone file or a
     * plain OS-level delete failure, matching [File.delete]'s own contract; `LutRepositoryImpl` is what
     * turns that into a [com.dragote.xcamera.shared.common.domain.result.Result.Error].
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
    }
}
