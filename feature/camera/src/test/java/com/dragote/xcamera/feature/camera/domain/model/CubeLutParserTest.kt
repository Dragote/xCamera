package com.dragote.xcamera.feature.camera.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CubeLutParserTest {

    /** A trivial identity-ish 2x2x2 LUT — 8 rows, blue fastest per the format's own row order. */
    private fun validCubeContent(): String = """
        TITLE "Test LUT"
        LUT_3D_SIZE 2

        0.0 0.0 0.0
        0.0 0.0 1.0
        0.0 1.0 0.0
        0.0 1.0 1.0
        1.0 0.0 0.0
        1.0 0.0 1.0
        1.0 1.0 0.0
        1.0 1.0 1.0
    """.trimIndent()

    @Test
    fun `parses a well-formed cube file`() {
        val lut = parseCubeLut(validCubeContent())

        assertEquals(2, lut?.size)
        assertEquals(
            floatArrayOf(
                0f, 0f, 0f,
                0f, 0f, 1f,
                0f, 1f, 0f,
                0f, 1f, 1f,
                1f, 0f, 0f,
                1f, 0f, 1f,
                1f, 1f, 0f,
                1f, 1f, 1f,
            ).toList(),
            lut?.values?.toList(),
        )
    }

    @Test
    fun `ignores comment and blank lines anywhere in the file`() {
        val content = """
            # a leading comment
            LUT_3D_SIZE 2
            # a comment between the header and the data

            0.0 0.0 0.0
            # a comment between data rows
            0.0 0.0 1.0
            0.0 1.0 0.0
            0.0 1.0 1.0
            1.0 0.0 0.0
            1.0 0.0 1.0
            1.0 1.0 0.0
            1.0 1.0 1.0
            # trailing comment
        """.trimIndent()

        val lut = parseCubeLut(content)

        assertEquals(2, lut?.size)
        assertEquals(24, lut?.values?.size)
    }

    @Test
    fun `ignores TITLE DOMAIN_MIN and DOMAIN_MAX header lines`() {
        val content = """
            TITLE "My LUT"
            LUT_3D_SIZE 2
            DOMAIN_MIN 0.0 0.0 0.0
            DOMAIN_MAX 1.0 1.0 1.0
            0.0 0.0 0.0
            0.0 0.0 1.0
            0.0 1.0 0.0
            0.0 1.0 1.0
            1.0 0.0 0.0
            1.0 0.0 1.0
            1.0 1.0 0.0
            1.0 1.0 1.0
        """.trimIndent()

        val lut = parseCubeLut(content)

        assertEquals(2, lut?.size)
    }

    @Test
    fun `returns null when LUT_3D_SIZE header is missing`() {
        val content = """
            0.0 0.0 0.0
            1.0 1.0 1.0
        """.trimIndent()

        assertNull(parseCubeLut(content))
    }

    @Test
    fun `returns null when LUT_3D_SIZE is duplicated`() {
        val content = """
            LUT_3D_SIZE 2
            LUT_3D_SIZE 2
            0.0 0.0 0.0
            0.0 0.0 1.0
            0.0 1.0 0.0
            0.0 1.0 1.0
            1.0 0.0 0.0
            1.0 0.0 1.0
            1.0 1.0 0.0
            1.0 1.0 1.0
        """.trimIndent()

        assertNull(parseCubeLut(content))
    }

    @Test
    fun `returns null when LUT_3D_SIZE is not a positive integer`() {
        assertNull(parseCubeLut("LUT_3D_SIZE 0\n"))
        assertNull(parseCubeLut("LUT_3D_SIZE -2\n"))
        assertNull(parseCubeLut("LUT_3D_SIZE abc\n"))
    }

    @Test
    fun `returns null when the data row count doesn't match size cubed`() {
        val content = """
            LUT_3D_SIZE 2
            0.0 0.0 0.0
            0.0 0.0 1.0
        """.trimIndent()

        assertNull(parseCubeLut(content))
    }

    @Test
    fun `returns null when a data row doesn't have exactly three components`() {
        val content = """
            LUT_3D_SIZE 2
            0.0 0.0
            0.0 0.0 1.0
            0.0 1.0 0.0
            0.0 1.0 1.0
            1.0 0.0 0.0
            1.0 0.0 1.0
            1.0 1.0 0.0
            1.0 1.0 1.0
        """.trimIndent()

        assertNull(parseCubeLut(content))
    }

    @Test
    fun `returns null when a data row has a non-numeric component`() {
        val content = """
            LUT_3D_SIZE 2
            0.0 0.0 x
            0.0 0.0 1.0
            0.0 1.0 0.0
            0.0 1.0 1.0
            1.0 0.0 0.0
            1.0 0.0 1.0
            1.0 1.0 0.0
            1.0 1.0 1.0
        """.trimIndent()

        assertNull(parseCubeLut(content))
    }

    @Test
    fun `returns null for a 1D LUT file`() {
        val content = """
            LUT_1D_SIZE 2
            0.0 0.0 0.0
            1.0 1.0 1.0
        """.trimIndent()

        assertNull(parseCubeLut(content))
    }

    @Test
    fun `CubeLut equality is structural, not by array reference`() {
        val a = CubeLut(2, floatArrayOf(0f, 1f))
        val b = CubeLut(2, floatArrayOf(0f, 1f))
        val c = CubeLut(2, floatArrayOf(1f, 0f))

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(false, a == c)
    }
}
