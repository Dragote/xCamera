package com.dragote.xcamera.feature.settings.data.local

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.dragote.xcamera.shared.common.domain.model.parseCubeLutBinary
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LutLocalDataSourceTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var contentResolver: ContentResolver
    private lateinit var context: Context
    private lateinit var dataSource: LutLocalDataSource

    @Before
    fun setUp() {
        contentResolver = mockk()
        context = mockk()
        every { context.filesDir } returns temporaryFolder.root
        every { context.contentResolver } returns contentResolver
        dataSource = LutLocalDataSource(context)
    }

    /** A fresh [ByteArrayInputStream] per call (not a single shared instance `every { } returns ...`
     *  would give) — [LutLocalDataSource.importLut] fully reads the stream once to validate/
     *  resample it, so a test that imports the same [uri] twice (e.g. distinct-id checks) needs a
     *  genuinely rewindable source, matching what a real `ContentResolver.openInputStream` call
     *  gives on each invocation. */
    private fun stubSourceContent(uri: Uri, bytes: ByteArray) {
        every { contentResolver.openInputStream(uri) } answers { ByteArrayInputStream(bytes) }
    }

    /** A well-formed `.cube` file at [size] — every grid point set to its own normalized coordinate,
     *  so it's trivially valid input for [com.dragote.xcamera.shared.common.domain.model.parseCubeLut]. */
    private fun validCubeContent(size: Int = 2): String = buildString {
        appendLine("LUT_3D_SIZE $size")
        val maxIndex = (size - 1).coerceAtLeast(1)
        for (r in 0 until size) {
            for (g in 0 until size) {
                for (b in 0 until size) {
                    appendLine("${r / maxIndex.toFloat()} ${g / maxIndex.toFloat()} ${b / maxIndex.toFloat()}")
                }
            }
        }
    }

    @Test
    fun `listLuts is empty before anything is imported`() {
        assertEquals(emptyList<Any>(), dataSource.listLuts())
    }

    @Test
    fun `importLut writes a resampled binary file, not the raw source bytes verbatim`() {
        val uri = mockk<Uri>()
        stubSourceContent(uri, validCubeContent(size = 2).toByteArray())

        val preset = dataSource.importLut(uri, "My LUT")

        assertEquals("My LUT", preset.displayName)
        assertTrue(java.io.File(preset.filePath).exists())
        val storedLut = parseCubeLutBinary(java.io.File(preset.filePath).readBytes())
        assertEquals(33, storedLut?.size) // canonical size, regardless of the size-2 input
    }

    @Test
    fun `importLut resamples to the canonical size regardless of the input's own size`() {
        val uri = mockk<Uri>()
        stubSourceContent(uri, validCubeContent(size = 5).toByteArray())

        val preset = dataSource.importLut(uri, "Five")

        val storedLut = parseCubeLutBinary(java.io.File(preset.filePath).readBytes())
        assertEquals(33, storedLut?.size)
    }

    @Test
    fun `importLut rejects malformed cube content and leaves no file behind`() {
        val uri = mockk<Uri>()
        stubSourceContent(uri, "not a cube file at all".toByteArray())

        assertThrows(IOException::class.java) { dataSource.importLut(uri, "Bad LUT") }

        assertTrue(dataSource.listLuts().isEmpty())
    }

    @Test
    fun `imported LUTs are returned by a later listLuts call`() {
        val uri = mockk<Uri>()
        stubSourceContent(uri, validCubeContent().toByteArray())

        val preset = dataSource.importLut(uri, "My LUT")

        assertEquals(listOf(preset), dataSource.listLuts())
    }

    @Test
    fun `each import gets a distinct id even with the same display name`() {
        val uri = mockk<Uri>()
        stubSourceContent(uri, validCubeContent().toByteArray())

        val first = dataSource.importLut(uri, "My LUT")
        val second = dataSource.importLut(uri, "My LUT")

        assertTrue(first.id != second.id)
        assertEquals(2, dataSource.listLuts().size)
    }

    @Test
    fun `a display name with path-hostile characters is sanitized but still imports`() {
        val uri = mockk<Uri>()
        stubSourceContent(uri, validCubeContent().toByteArray())

        val preset = dataSource.importLut(uri, "My/LUT:2024*")

        assertEquals("My_LUT_2024_", preset.displayName)
        assertTrue(java.io.File(preset.filePath).exists())
        assertEquals(listOf(preset), dataSource.listLuts())
    }

    @Test
    fun `deleteLut removes the backing file and reports success`() {
        val uri = mockk<Uri>()
        stubSourceContent(uri, validCubeContent().toByteArray())
        val preset = dataSource.importLut(uri, "My LUT")

        val deleted = dataSource.deleteLut(preset.filePath)

        assertTrue(deleted)
        assertTrue(dataSource.listLuts().isEmpty())
        assertTrue(java.io.File(preset.filePath).exists().not())
    }

    @Test
    fun `deleteLut on a missing file reports failure without throwing`() {
        val deleted = dataSource.deleteLut(java.io.File(temporaryFolder.root, "luts/nonexistent.cube").absolutePath)

        assertTrue(deleted.not())
    }
}
