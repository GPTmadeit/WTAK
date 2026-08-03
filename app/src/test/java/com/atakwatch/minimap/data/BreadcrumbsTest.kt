package com.atakwatch.minimap.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trail has to stay cheap on a watch: no point-per-GPS-jitter, and a hard
 * ceiling on how much is retained.
 */
class BreadcrumbsTest {

    @After fun tearDown() = Breadcrumbs.clear()

    @Test
    fun `first point is always recorded`() {
        Breadcrumbs.clear()
        assertTrue(Breadcrumbs.record(40.7580, -73.9855))
        assertEquals(1, Breadcrumbs.points.value.size)
    }

    @Test
    fun `standing still does not fill the trail`() {
        Breadcrumbs.clear()
        Breadcrumbs.record(40.7580, -73.9855)
        // ~1 m away — GPS noise, not movement.
        assertFalse(Breadcrumbs.record(40.75801, -73.9855))
        assertEquals(1, Breadcrumbs.points.value.size)
    }

    @Test
    fun `real movement is recorded`() {
        Breadcrumbs.clear()
        Breadcrumbs.record(40.7580, -73.9855)
        // ~20 m north, past the spacing threshold.
        assertTrue(Breadcrumbs.record(40.75818, -73.9855))
        assertEquals(2, Breadcrumbs.points.value.size)
    }

    @Test
    fun `trail is capped and keeps the most recent stretch`() {
        Breadcrumbs.clear()
        // Walk far enough to overflow the 500-point cap.
        repeat(600) { i -> Breadcrumbs.record(40.7580 + i * 0.0002, -73.9855) }
        val pts = Breadcrumbs.points.value
        assertTrue("expected cap, got ${pts.size}", pts.size <= 500)
        // The newest point must survive — the trail should not stop dead when full.
        assertEquals(40.7580 + 599 * 0.0002, pts.last().first, 1e-9)
    }

    @Test
    fun `track length accumulates along the trail`() {
        Breadcrumbs.clear()
        assertEquals(0.0, Breadcrumbs.trackLengthMeters(), 0.001)
        Breadcrumbs.record(40.7580, -73.9855)
        assertEquals(0.0, Breadcrumbs.trackLengthMeters(), 0.001)
        Breadcrumbs.record(40.75818, -73.9855)   // ~20 m
        assertTrue(Breadcrumbs.trackLengthMeters() > 15.0)
    }
}
