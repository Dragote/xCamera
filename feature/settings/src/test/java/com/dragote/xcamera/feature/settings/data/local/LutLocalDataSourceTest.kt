package com.dragote.xcamera.feature.settings.data.local

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
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

    private fun stubSourceContent(uri: Uri, bytes: ByteArray) {
        every { contentResolver.openInputStream(uri) } returns ByteArrayInputStream(bytes)
    }

    @Test
    fun `listLuts is empty before anything is imported`() {
        assertEquals(emptyList<Any>(), dataSource.listLuts())
    }

    @Test
    fun `importLut copies the source bytes into app-private storage`() {
        val uri = mockk<Uri>()
        val content = "LUT_3D_SIZE 2\n"
        stubSourceContent(uri, content.toByteArray())

        val preset = dataSource.importLut(uri, "My LUT")

        assertEquals("My LUT", preset.displayName)
        assertEquals(content, java.io.File(preset.filePath).readText())
        assertTrue(java.io.File(preset.filePath).exists())
    }

    @Test
    fun `imported LUTs are returned by a later listLuts call`() {
        val uri = mockk<Uri>()
        stubSourceContent(uri, "LUT_3D_SIZE 2\n".toByteArray())

        val preset = dataSource.importLut(uri, "My LUT")

        assertEquals(listOf(preset), dataSource.listLuts())
    }

    @Test
    fun `each import gets a distinct id even with the same display name`() {
        val uri = mockk<Uri>()
        stubSourceContent(uri, "LUT_3D_SIZE 2\n".toByteArray())

        val first = dataSource.importLut(uri, "My LUT")
        val second = dataSource.importLut(uri, "My LUT")

        assertTrue(first.id != second.id)
        assertEquals(2, dataSource.listLuts().size)
    }

    @Test
    fun `a display name with path-hostile characters is sanitized but still imports`() {
        val uri = mockk<Uri>()
        stubSourceContent(uri, "LUT_3D_SIZE 2\n".toByteArray())

        val preset = dataSource.importLut(uri, "My/LUT:2024*")

        assertEquals("My_LUT_2024_", preset.displayName)
        assertTrue(java.io.File(preset.filePath).exists())
        assertEquals(listOf(preset), dataSource.listLuts())
    }
}
