package com.atakwatch.minimap.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Range and bearing are tactical outputs — someone may walk on them, so the
 * maths is pinned against independently known values rather than against
 * whatever the implementation happens to return.
 */
class GeoTest {

    // Times Square, NYC — used as the reference origin throughout.
    private val lat = 40.7580
    private val lon = -73.9855

    @Test
    fun `distance between known landmarks matches published value`() {
        // Times Square → Empire State Building is ~1.6 km.
        val d = Geo.distanceMeters(lat, lon, 40.7484, -73.9857)
        assertTrue("expected ~1067 m, got $d", d in 1000.0..1150.0)
    }

    @Test
    fun `distance to self is zero`() {
        assertEquals(0.0, Geo.distanceMeters(lat, lon, lat, lon), 0.001)
    }

    @Test
    fun `distance is symmetric`() {
        val a = Geo.distanceMeters(lat, lon, 41.0, -74.5)
        val b = Geo.distanceMeters(41.0, -74.5, lat, lon)
        assertEquals(a, b, 0.001)
    }

    @Test
    fun `cardinal bearings are correct`() {
        // Due north: same longitude, higher latitude.
        assertEquals(0.0, Geo.bearingDegrees(lat, lon, lat + 0.1, lon), 0.5)
        // Due east: same latitude, higher longitude.
        assertEquals(90.0, Geo.bearingDegrees(lat, lon, lat, lon + 0.1), 0.5)
        // Due south.
        assertEquals(180.0, Geo.bearingDegrees(lat, lon, lat - 0.1, lon), 0.5)
        // Due west.
        assertEquals(270.0, Geo.bearingDegrees(lat, lon, lat, lon - 0.1), 0.5)
    }

    @Test
    fun `bearing is always normalised to 0-360`() {
        for (dLat in -1..1) for (dLon in -1..1) {
            if (dLat == 0 && dLon == 0) continue
            val b = Geo.bearingDegrees(lat, lon, lat + dLat * 0.5, lon + dLon * 0.5)
            assertTrue("bearing $b out of range", b >= 0.0 && b < 360.0)
        }
    }

    @Test
    fun `destination round-trips through distance and bearing`() {
        // Project out 500 m on 037°, then measure back: both must agree.
        val (dLat, dLon) = Geo.destination(lat, lon, 37.0, 500.0)
        assertEquals(500.0, Geo.distanceMeters(lat, lon, dLat, dLon), 1.0)
        assertEquals(37.0, Geo.bearingDegrees(lat, lon, dLat, dLon), 0.5)
    }

    @Test
    fun `range formatting switches units at the right thresholds`() {
        assertEquals("999 m", Geo.formatRange(999.0, imperial = false))
        assertEquals("1.00 km", Geo.formatRange(1000.0, imperial = false))
        assertEquals("2.50 km", Geo.formatRange(2500.0, imperial = false))
        // 1 mile ≈ 1609 m
        assertEquals("1.00 mi", Geo.formatRange(1609.34, imperial = true))
        assertTrue(Geo.formatRange(100.0, imperial = true).endsWith(" ft"))
    }

    @Test
    fun `bearing formatting pads and names the cardinal`() {
        assertEquals("000° N", Geo.formatBearing(0.0))
        assertEquals("045° NE", Geo.formatBearing(45.0))
        assertEquals("090° E", Geo.formatBearing(90.0))
        assertEquals("315° NW", Geo.formatBearing(315.0))
        // 360 must render as 000, not 360.
        assertEquals("000° N", Geo.formatBearing(360.0))
    }

    @Test
    fun `age formatting is compact across magnitudes`() {
        assertEquals("0s", Geo.formatAge(0))
        assertEquals("45s", Geo.formatAge(45_000))
        assertEquals("2m", Geo.formatAge(120_000))
        assertEquals("1h1m", Geo.formatAge(3_660_000))
        // Clock skew must not produce negative ages.
        assertEquals("0s", Geo.formatAge(-5_000))
    }
}
