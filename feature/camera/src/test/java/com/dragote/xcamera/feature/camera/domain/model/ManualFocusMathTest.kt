package com.dragote.xcamera.feature.camera.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI

class ManualFocusMathTest {

    private val Epsilon = 1e-4f

    @Test
    fun `displayFractionToSensorFraction is the identity at rotation 0`() {
        val (sx, sy) = displayFractionToSensorFraction(0.25f, 0.75f, 0)
        assertEquals(0.25f, sx, Epsilon)
        assertEquals(0.75f, sy, Epsilon)
    }

    @Test
    fun `displayFractionToSensorFraction at rotation 90 maps the top-left display corner correctly`() {
        // The common back-camera-in-portrait case: SENSOR_ORIENTATION = 90.
        val (sx, sy) = displayFractionToSensorFraction(0f, 0f, 90)
        assertEquals(0f, sx, Epsilon)
        assertEquals(1f, sy, Epsilon)
    }

    @Test
    fun `displayFractionToSensorFraction at rotation 90 maps center to center`() {
        val (sx, sy) = displayFractionToSensorFraction(0.5f, 0.5f, 90)
        assertEquals(0.5f, sx, Epsilon)
        assertEquals(0.5f, sy, Epsilon)
    }

    @Test
    fun `displayFractionToSensorFraction at rotation 180 mirrors both axes`() {
        val (sx, sy) = displayFractionToSensorFraction(0.2f, 0.8f, 180)
        assertEquals(0.8f, sx, Epsilon)
        assertEquals(0.2f, sy, Epsilon)
    }

    @Test
    fun `displayFractionToSensorFraction at rotation 270 maps the top-left display corner correctly`() {
        val (sx, sy) = displayFractionToSensorFraction(0f, 0f, 270)
        assertEquals(1f, sx, Epsilon)
        assertEquals(0f, sy, Epsilon)
    }

    @Test
    fun `displayFractionToSensorFraction 90 and 270 are mutual inverses`() {
        val (sx, sy) = displayFractionToSensorFraction(0.3f, 0.9f, 90)
        val (dx, dy) = displayFractionToSensorFraction(sx, sy, 270)
        assertEquals(0.3f, dx, Epsilon)
        assertEquals(0.9f, dy, Epsilon)
    }

    @Test
    fun `displayFractionToSensorFraction normalizes negative and over-360 rotation`() {
        val fromNegative = displayFractionToSensorFraction(0.1f, 0.6f, -90)
        val fromNormalized = displayFractionToSensorFraction(0.1f, 0.6f, 270)
        assertEquals(fromNormalized.first, fromNegative.first, Epsilon)
        assertEquals(fromNormalized.second, fromNegative.second, Epsilon)

        val fromOver360 = displayFractionToSensorFraction(0.1f, 0.6f, 450)
        val fromNinety = displayFractionToSensorFraction(0.1f, 0.6f, 90)
        assertEquals(fromNinety.first, fromOver360.first, Epsilon)
        assertEquals(fromNinety.second, fromOver360.second, Epsilon)
    }

    @Test
    fun `displayFractionToSensorFraction falls back to identity for a non-90-multiple rotation`() {
        val (sx, sy) = displayFractionToSensorFraction(0.4f, 0.6f, 45)
        assertEquals(0.4f, sx, Epsilon)
        assertEquals(0.6f, sy, Epsilon)
    }

    @Test
    fun `manualFocusDistanceForRotation with zero rotation returns the start distance`() {
        val distance = manualFocusDistanceForRotation(
            startDistanceDiopters = 2.5f,
            rotationRadians = 0f,
            maxFocusDistanceDiopters = 10f,
        )
        assertEquals(2.5f, distance, Epsilon)
    }

    @Test
    fun `manualFocusDistanceForRotation a full sweep worth of rotation reaches the max distance`() {
        val fullSweepRadians = (2.0 * PI * 1.5).toFloat()
        val distance = manualFocusDistanceForRotation(
            startDistanceDiopters = 0f,
            rotationRadians = fullSweepRadians,
            maxFocusDistanceDiopters = 8f,
        )
        assertEquals(8f, distance, Epsilon)
    }

    @Test
    fun `manualFocusDistanceForRotation clamps below zero`() {
        val distance = manualFocusDistanceForRotation(
            startDistanceDiopters = 0.1f,
            rotationRadians = -100f,
            maxFocusDistanceDiopters = 10f,
        )
        assertEquals(0f, distance, Epsilon)
    }

    @Test
    fun `manualFocusDistanceForRotation clamps above the lens's max`() {
        val distance = manualFocusDistanceForRotation(
            startDistanceDiopters = 9.9f,
            rotationRadians = 100f,
            maxFocusDistanceDiopters = 10f,
        )
        assertEquals(10f, distance, Epsilon)
    }

    @Test
    fun `manualFocusDistanceForRotation negative rotation decreases distance`() {
        val fullSweepRadians = (2.0 * PI * 1.5).toFloat()
        val distance = manualFocusDistanceForRotation(
            startDistanceDiopters = 5f,
            rotationRadians = -fullSweepRadians / 2f,
            maxFocusDistanceDiopters = 10f,
        )
        assertEquals(0f, distance, Epsilon)
    }

    @Test
    fun `manualFocusDistanceForRotation with a non-positive max distance always returns zero`() {
        val distance = manualFocusDistanceForRotation(
            startDistanceDiopters = 3f,
            rotationRadians = 2f,
            maxFocusDistanceDiopters = 0f,
        )
        assertEquals(0f, distance, Epsilon)
    }

    @Test
    fun `formatFocusDistance at zero diopters is optical infinity`() {
        assertEquals("∞", formatFocusDistance(0f))
    }

    @Test
    fun `formatFocusDistance below zero diopters is also optical infinity`() {
        assertEquals("∞", formatFocusDistance(-1f))
    }

    @Test
    fun `formatFocusDistance at 0_5 diopters is 2_0m`() {
        assertEquals("2.0m", formatFocusDistance(0.5f))
    }

    @Test
    fun `formatFocusDistance at 1 diopter is 1_0m`() {
        assertEquals("1.0m", formatFocusDistance(1f))
    }

    @Test
    fun `formatFocusDistance at 2 diopters (sub-meter) switches to centimeters`() {
        assertEquals("50cm", formatFocusDistance(2f))
    }

    @Test
    fun `formatFocusDistance at 10 diopters is 10cm`() {
        assertEquals("10cm", formatFocusDistance(10f))
    }
}
