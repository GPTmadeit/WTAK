package com.atakwatch.minimap.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Emergency types have to match what the rest of the TAK ecosystem expects — an
 * alert nobody else recognises is worse than useless, because the sender
 * believes they have called for help.
 */
class EmergencyTest {

    @Test
    fun `alert types match the ATAK b-a-o branch`() {
        assertEquals("b-a-o-tbl", EmergencyType.NINE_ONE_ONE.cotType)
        assertEquals("b-a-o-pan", EmergencyType.RING_THE_BELL.cotType)
        assertEquals("b-a-o-opn", EmergencyType.IN_CONTACT.cotType)
        assertEquals("b-a-o-can", EmergencyType.CANCEL_TYPE)
    }

    @Test
    fun `every alert type is recognised as an emergency`() {
        EmergencyType.entries.forEach {
            assertTrue(it.cotType, EmergencyType.isEmergency(it.cotType))
        }
        assertTrue(EmergencyType.isEmergency(EmergencyType.CANCEL_TYPE))
    }

    @Test
    fun `ordinary contacts are not emergencies`() {
        assertFalse(EmergencyType.isEmergency("a-f-G-U-C"))
        assertFalse(EmergencyType.isEmergency("b-m-p-w"))
        assertFalse(EmergencyType.isEmergency("b-t-f"))
    }

    @Test
    fun `cancel is distinguished from a live alert`() {
        assertTrue(EmergencyType.isCancel("b-a-o-can"))
        assertFalse(EmergencyType.isCancel("b-a-o-tbl"))
    }

    @Test
    fun `a live alert is flagged on the event but a cancel is not`() {
        fun ev(type: String) = CotEvent(
            uid = "U", callsign = "ALPHA", type = CotType(type), lat = 1.0, lon = 2.0,
        )
        assertTrue(ev("b-a-o-tbl").isEmergency)
        // A cancel must not keep rendering as an active emergency.
        assertFalse(ev("b-a-o-can").isEmergency)
        assertFalse(ev("a-f-G").isEmergency)
    }

    @Test
    fun `alerts reach the map, chat and tasking do not`() {
        assertTrue(CotType("b-a-o-tbl").isRenderable)
        assertTrue(CotType("b-a-o-can").isRenderable)
        assertFalse(CotType("b-t-f").isRenderable)
        assertFalse(CotType("t-x-takp-v").isRenderable)
    }
}
