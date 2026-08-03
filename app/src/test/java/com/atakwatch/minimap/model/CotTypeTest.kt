package com.atakwatch.minimap.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The CoT type tree decides what a contact *is* — affiliation, domain, and
 * whether it belongs on the map at all. Getting this wrong paints a hostile
 * contact as friendly, so every branch is pinned.
 */
class CotTypeTest {

    @Test
    fun `parses the standard atoms-affiliation-dimension tree`() {
        val t = CotType("a-f-G-U-C")
        assertTrue(t.isAtom)
        assertEquals(Affiliation.FRIEND, t.affiliation)
        assertEquals('G', t.dimension)
        assertEquals("Ground", t.dimensionLabel)
    }

    @Test
    fun `maps every affiliation code`() {
        assertEquals(Affiliation.FRIEND, CotType("a-f-G").affiliation)
        assertEquals(Affiliation.HOSTILE, CotType("a-h-G").affiliation)
        assertEquals(Affiliation.NEUTRAL, CotType("a-n-G").affiliation)
        assertEquals(Affiliation.UNKNOWN, CotType("a-u-G").affiliation)
    }

    @Test
    fun `unrecognised affiliation degrades to unknown, never to friendly`() {
        // Mislabelling something friendly is the dangerous failure.
        assertEquals(Affiliation.UNKNOWN, CotType("a-x-G").affiliation)
        assertEquals(Affiliation.UNKNOWN, CotType("garbage").affiliation)
        assertEquals(Affiliation.UNKNOWN, CotType("").affiliation)
    }

    @Test
    fun `labels each battle dimension`() {
        assertEquals("Ground", CotType("a-f-G").dimensionLabel)
        assertEquals("Air", CotType("a-f-A").dimensionLabel)
        assertEquals("Surface", CotType("a-f-S").dimensionLabel)
        assertEquals("Subsurface", CotType("a-f-U").dimensionLabel)
        assertEquals("Space", CotType("a-f-P").dimensionLabel)
        assertEquals("—", CotType("a-f").dimensionLabel)
    }

    @Test
    fun `waypoints are the b-m-p branch, not atoms`() {
        val wp = CotType.waypoint()
        assertEquals("b-m-p-w", wp.raw)
        assertTrue(wp.isWaypoint)
        assertFalse(wp.isAtom)
        assertFalse(CotType("a-f-G").isWaypoint)
    }

    @Test
    fun `only atoms and map points are renderable`() {
        assertTrue(CotType("a-f-G-U-C").isRenderable)
        assertTrue(CotType("b-m-p-w").isRenderable)
        // Chat, tasking and TAK protocol control must never hit the map.
        assertFalse(CotType("b-t-f").isRenderable)
        assertFalse(CotType("t-x-takp-v").isRenderable)
        assertFalse(CotType("t-x-c-t").isRenderable)
    }

    @Test
    fun `self type carries the chosen affiliation`() {
        assertEquals("a-f-G-U-C", CotType.self(Affiliation.FRIEND).raw)
        assertEquals("a-h-G-U-C", CotType.self(Affiliation.HOSTILE).raw)
        assertEquals("a-n-G", CotType.ground(Affiliation.NEUTRAL).raw)
    }

    @Test
    fun `team colours round-trip case-insensitively and reject junk`() {
        assertEquals(TeamColor.CYAN, TeamColor.fromLabel("Cyan"))
        assertEquals(TeamColor.CYAN, TeamColor.fromLabel("cyan"))
        assertEquals(TeamColor.DARK_BLUE, TeamColor.fromLabel("Dark Blue"))
        assertEquals(null, TeamColor.fromLabel("Chartreuse"))
        assertEquals(null, TeamColor.fromLabel(null))
    }
}
