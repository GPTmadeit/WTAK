package com.atakwatch.minimap.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of the entity model that the UI depends on: team colour resolution,
 * staleness, and the copy-on-rename path used by waypoint editing.
 */
class CotEventTest {

    private fun event(
        uid: String = "U-1",
        callsign: String = "ALPHA",
        type: String = "a-f-G",
        team: String? = null,
        time: Long = 1_000_000L,
        stale: Long = 1_060_000L,
    ) = CotEvent(
        uid = uid, callsign = callsign, type = CotType(type),
        lat = 40.0, lon = -74.0, timeMillis = time, staleMillis = stale, teamName = team,
    )

    @Test
    fun `team colour resolves from the wire string`() {
        assertEquals(TeamColor.CYAN, event(team = "Cyan").teamColor)
        assertEquals(TeamColor.DARK_GREEN, event(team = "Dark Green").teamColor)
        // An unknown team must not crash or guess — the UI falls back to affiliation.
        assertNull(event(team = "Puce").teamColor)
        assertNull(event(team = null).teamColor)
    }

    @Test
    fun `affiliation comes from the type tree`() {
        assertEquals(Affiliation.FRIEND, event(type = "a-f-G").affiliation)
        assertEquals(Affiliation.HOSTILE, event(type = "a-h-G").affiliation)
    }

    @Test
    fun `renaming preserves identity and position`() {
        // Waypoint rename copies the event; the uid must survive so an active
        // nav target isn't silently dropped.
        val original = event(uid = "WP-7", callsign = "WP 1", type = "b-m-p-w")
        val renamed = original.copy(callsign = "RALLY POINT")

        assertEquals("WP-7", renamed.uid)
        assertEquals("RALLY POINT", renamed.callsign)
        assertEquals(original.lat, renamed.lat, 0.0)
        assertEquals(original.lon, renamed.lon, 0.0)
        assertTrue(renamed.type.isWaypoint)
        assertNotEquals(original.callsign, renamed.callsign)
    }

    @Test
    fun `stale time is independent of send time`() {
        val e = event(time = 1_000_000L, stale = 1_120_000L)
        assertEquals(120_000L, e.staleMillis - e.timeMillis)
    }

    @Test
    fun `self events are distinguishable from received ones`() {
        assertFalse(event().isSelf)
        assertTrue(event().copy(isSelf = true).isSelf)
    }

    @Test
    fun `unknown accuracy uses the CoT sentinel`() {
        // 9999999.0 is CoT's "unknown"; the UI keys off it to hide the readout.
        val e = event()
        assertEquals(9_999_999.0, e.ce, 0.0)
        assertEquals(9_999_999.0, e.le, 0.0)
    }
}
