package com.dragote.xcamera.feature.camera.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class IsoStopsTest {

    @Test
    fun `empty range yields no stops`() {
        assertEquals(emptyList<Int>(), isoStopsInRange(500..499))
    }

    @Test
    fun `narrow range keeps only the stops it actually covers`() {
        assertEquals(listOf(100, 200), isoStopsInRange(100..200))
    }

    @Test
    fun `narrow range not aligned to any stop yields nothing`() {
        assertEquals(emptyList<Int>(), isoStopsInRange(120..180))
    }

    @Test
    fun `wide flagship-tier range keeps the full standard ladder`() {
        assertEquals(
            listOf(50, 100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600),
            isoStopsInRange(32..102400),
        )
    }

    @Test
    fun `range clipped below the ladder start still keeps values inside it`() {
        assertEquals(listOf(200, 400, 800), isoStopsInRange(150..1000))
    }
}
