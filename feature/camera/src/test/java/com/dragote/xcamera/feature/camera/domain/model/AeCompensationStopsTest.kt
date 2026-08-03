package com.dragote.xcamera.feature.camera.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AeCompensationStopsTest {

    @Test
    fun `aeCompensationSteps returns every step in the range, ascending`() {
        assertEquals(listOf(-2, -1, 0, 1, 2), aeCompensationSteps(-2..2))
    }

    @Test
    fun `aeCompensationSteps on a single-value range yields just that value`() {
        assertEquals(listOf(0), aeCompensationSteps(0..0))
    }

    @Test
    fun `formatEvCompensation renders 0 without a sign`() {
        assertEquals("0", formatEvCompensation(0, stepEv = 1f / 3f))
    }

    @Test
    fun `formatEvCompensation renders positive steps with a leading plus`() {
        assertEquals("+1.0", formatEvCompensation(3, stepEv = 1f / 3f))
        assertEquals("+0.3", formatEvCompensation(1, stepEv = 1f / 3f))
    }

    @Test
    fun `formatEvCompensation renders negative steps with a leading minus`() {
        assertEquals("-1.0", formatEvCompensation(-2, stepEv = 0.5f))
        assertEquals("-0.5", formatEvCompensation(-1, stepEv = 0.5f))
    }

    @Test
    fun `formatEvCompensation rounds to one decimal place`() {
        assertEquals("+0.7", formatEvCompensation(1, stepEv = 2f / 3f))
    }
}
