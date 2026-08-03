package com.atakwatch.minimap.ui.radar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarGeometryTest {

    @Test
    fun `auto range keeps the furthest contact inside the outer ring`() {
        // 190 m needs headroom, so the 200 m rung would put it on the rim where
        // it flickers off-scope; the next rung up is the honest choice.
        assertEquals(500.0, RadarGeometry.autoRange(190.0), 0.0)
        assertEquals(200.0, RadarGeometry.autoRange(150.0), 0.0)
        assertEquals(2_000.0, RadarGeometry.autoRange(900.0), 0.0)
        assertEquals(1_000.0, RadarGeometry.autoRange(850.0), 0.0)
    }

    @Test
    fun `auto range falls back sensibly with nothing to show`() {
        assertEquals(RadarGeometry.DEFAULT_RANGE_M, RadarGeometry.autoRange(null), 0.0)
        assertEquals(RadarGeometry.DEFAULT_RANGE_M, RadarGeometry.autoRange(0.0), 0.0)
        assertEquals(RadarGeometry.DEFAULT_RANGE_M, RadarGeometry.autoRange(Double.NaN), 0.0)
    }

    @Test
    fun `a contact past the top of the ladder pins to the widest scope`() {
        val widest = RadarGeometry.LADDER_METERS.last()
        assertEquals(widest, RadarGeometry.autoRange(1_000_000.0), 0.0)
    }

    @Test
    fun `stepping walks the ladder and stops at both ends`() {
        assertEquals(500.0, RadarGeometry.step(1_000.0, zoomIn = true), 0.0)
        assertEquals(2_000.0, RadarGeometry.step(1_000.0, zoomIn = false), 0.0)

        val narrowest = RadarGeometry.LADDER_METERS.first()
        assertEquals(narrowest, RadarGeometry.step(narrowest, zoomIn = true), 0.0)
        val widest = RadarGeometry.LADDER_METERS.last()
        assertEquals(widest, RadarGeometry.step(widest, zoomIn = false), 0.0)
    }

    @Test
    fun `contacts are clamped to the rim rather than drawn off the scope`() {
        assertEquals(0.5f, RadarGeometry.radiusFraction(100.0, 200.0), 1e-4f)
        assertEquals(1f, RadarGeometry.radiusFraction(5_000.0, 200.0), 1e-4f)
        assertTrue(RadarGeometry.isOffScope(201.0, 200.0))
        assertFalse(RadarGeometry.isOffScope(199.0, 200.0))
    }

    @Test
    fun `north up ignores heading and track up counter-rotates by it`() {
        assertEquals(90f, RadarGeometry.screenAngle(90.0, 45f, trackUp = false), 1e-3f)
        assertEquals(45f, RadarGeometry.screenAngle(90.0, 45f, trackUp = true), 1e-3f)
        // A contact behind you in track-up belongs at the bottom of the display.
        assertEquals(180f, RadarGeometry.screenAngle(0.0, 180f, trackUp = true), 1e-3f)
        // And the result always lands in [0, 360).
        assertEquals(350f, RadarGeometry.screenAngle(10.0, 20f, trackUp = true), 1e-3f)
    }

    @Test
    fun `the sweep brightens a blip it just passed and never blanks one out`() {
        val justPainted = RadarGeometry.sweepIntensity(blipAngleDeg = 90f, sweepDeg = 91f)
        val longAgo = RadarGeometry.sweepIntensity(blipAngleDeg = 90f, sweepDeg = 80f)
        assertTrue("a freshly painted blip should be brighter", justPainted > longAgo)
        assertTrue("a blip must never disappear entirely", longAgo >= 0.45f)
        assertTrue(justPainted <= 1f)
    }
}
