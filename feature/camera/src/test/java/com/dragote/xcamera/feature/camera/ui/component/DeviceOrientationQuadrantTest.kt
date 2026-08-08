package com.dragote.xcamera.feature.camera.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceOrientationQuadrantTest {

    @Test
    fun `smoothOrientationDegrees is a no-op once current already equals target`() {
        assertEquals(0f, smoothOrientationDegrees(current = 0f, target = 0f, factor = 0.2f), Tolerance)
        assertEquals(90f, smoothOrientationDegrees(current = 90f, target = 90f, factor = 0.2f), Tolerance)
    }

    @Test
    fun `smoothOrientationDegrees steps a fixed fraction of the way towards target`() {
        val stepped = smoothOrientationDegrees(current = 0f, target = 10f, factor = 0.2f)
        assertEquals(2f, stepped, Tolerance)
    }

    @Test
    fun `smoothOrientationDegrees steps backwards towards a smaller target`() {
        val stepped = smoothOrientationDegrees(current = 10f, target = 0f, factor = 0.2f)
        assertEquals(8f, stepped, Tolerance)
    }

    @Test
    fun `smoothOrientationDegrees converges towards target over repeated steps`() {
        var current = 0f
        repeat(50) { current = smoothOrientationDegrees(current = current, target = 10f, factor = 0.2f) }
        assertEquals(10f, current, Tolerance)
    }

    @Test
    fun `smoothOrientationDegrees steps forward through the 360 to 0 wraparound`() {
        // Current is just below the 360/0 boundary, target just above it — the short way there is
        // *forward* through the wrap (358 -> 360/0 -> 2, a 4-degree gap), not backward through 180
        // (a 356-degree gap the wrong way round).
        val stepped = smoothOrientationDegrees(current = 358f, target = 2f, factor = 0.2f)
        assertEquals(358.8f, stepped, Tolerance)
    }

    @Test
    fun `smoothOrientationDegrees steps backward through the 0 to 360 wraparound`() {
        val stepped = smoothOrientationDegrees(current = 2f, target = 358f, factor = 0.2f)
        assertEquals(1.2f, stepped, Tolerance)
    }

    @Test
    fun `smoothOrientationDegrees result always folds back into 0 until 360`() {
        val stepped = smoothOrientationDegrees(current = 359f, target = 1f, factor = 1f)
        assertEquals(1f, stepped, Tolerance)
    }

    companion object {
        private const val Tolerance = 0.001f
    }
}
