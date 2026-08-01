package com.dragote.xcamera.feature.camera.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ShutterSpeedStopsTest {

    @Test
    fun `empty range yields no stops`() {
        assertEquals(emptyList<Long>(), shutterSpeedStopsInRange(500L..499L))
    }

    @Test
    fun `narrow range keeps only the stops it actually covers`() {
        // 1_000_000ns (1/1000) and 2_000_000ns (1/500) — the standard ladder's next two neighbors,
        // 500_000ns (1/2000) and 4_000_000ns (1/250), sit just outside this range.
        assertEquals(listOf(1_000_000L, 2_000_000L), shutterSpeedStopsInRange(1_000_000L..2_000_000L))
    }

    @Test
    fun `narrow range not aligned to any stop yields nothing`() {
        assertEquals(emptyList<Long>(), shutterSpeedStopsInRange(130_000L..180_000L))
    }

    @Test
    fun `wide flagship-tier range keeps the full standard ladder`() {
        assertEquals(
            listOf(
                125_000L, 250_000L, 500_000L, 1_000_000L, 2_000_000L, 4_000_000L, 8_000_000L,
                16_666_667L, 33_333_333L, 66_666_667L, 125_000_000L, 250_000_000L, 500_000_000L,
                1_000_000_000L, 2_000_000_000L, 4_000_000_000L, 8_000_000_000L, 15_000_000_000L,
                30_000_000_000L,
            ),
            shutterSpeedStopsInRange(100L..30_000_000_000L),
        )
    }

    @Test
    fun `range clipped below the ladder start still keeps values inside it`() {
        // Excludes the three fastest stops (1/8000, 1/4000, 1/2000) but keeps everything from
        // 1/1000 up through 1s.
        assertEquals(
            listOf(1_000_000L, 2_000_000L, 4_000_000L, 8_000_000L, 16_666_667L, 33_333_333L, 66_666_667L, 125_000_000L, 250_000_000L, 500_000_000L, 1_000_000_000L),
            shutterSpeedStopsInRange(1_000_000L..1_000_000_000L),
        )
    }

    @Test
    fun `formats an exact fractional stop as a fraction`() {
        assertEquals("1/125", formatShutterSpeed(8_000_000L))
        assertEquals("1/8000", formatShutterSpeed(125_000L))
    }

    @Test
    fun `formats a rounded fractional stop back to its nearest whole denominator`() {
        assertEquals("1/60", formatShutterSpeed(16_666_667L))
        assertEquals("1/30", formatShutterSpeed(33_333_333L))
        assertEquals("1/15", formatShutterSpeed(66_666_667L))
    }

    @Test
    fun `formats exactly 1 second as 1s`() {
        assertEquals("1s", formatShutterSpeed(1_000_000_000L))
    }

    @Test
    fun `formats multi-second stops with an s suffix`() {
        assertEquals("2s", formatShutterSpeed(2_000_000_000L))
        assertEquals("30s", formatShutterSpeed(30_000_000_000L))
    }

    @Test
    fun `nearestShutterStopIndex picks the closest stop to a raw auto-exposure value`() {
        val stops = listOf(4_000_000L, 8_000_000L, 16_666_667L, 33_333_333L)

        // Right between 8_000_000 (1/125) and 16_666_667 (1/60), slightly closer to 1/125.
        assertEquals(1, stops.nearestShutterStopIndex(11_000_000L))
        // Well past the slowest stop — still clamps to the nearest (last) one, not out of bounds.
        assertEquals(3, stops.nearestShutterStopIndex(1_000_000_000L))
        // Exact match.
        assertEquals(2, stops.nearestShutterStopIndex(16_666_667L))
    }

    @Test
    fun `nearestShutterStopIndex on an empty list returns 0`() {
        assertEquals(0, emptyList<Long>().nearestShutterStopIndex(1_000_000L))
    }
}
