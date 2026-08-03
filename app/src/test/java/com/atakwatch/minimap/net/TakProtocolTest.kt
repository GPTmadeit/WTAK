package com.atakwatch.minimap.net

import com.atakwatch.minimap.model.CotEvent
import com.atakwatch.minimap.model.CotType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * JVM tests for the TAK Protocol v1 codec. The golden-bytes test builds a frame
 * with an INDEPENDENT inline encoder (not the code under test) laid out per the
 * official schemas, proving the decoder reads real ATAK-shaped bytes.
 */
class TakProtocolTest {

    // ---- independent minimal protobuf writer (test-local, not the CUT) ----

    private fun ByteArrayOutputStream.varint(v: Long) {
        var x = v
        while (true) {
            if (x and 0x7F.inv().toLong() == 0L) { write(x.toInt()); return }
            write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7
        }
    }
    private fun ByteArrayOutputStream.str(field: Int, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        varint(((field shl 3) or 2).toLong()); varint(b.size.toLong()); write(b)
    }
    private fun ByteArrayOutputStream.dbl(field: Int, d: Double) {
        varint(((field shl 3) or 1).toLong())
        var bits = java.lang.Double.doubleToLongBits(d)
        repeat(8) { write((bits and 0xFF).toInt()); bits = bits ushr 8 }
    }
    private fun ByteArrayOutputStream.u64(field: Int, v: Long) {
        varint(((field shl 3) or 0).toLong()); varint(v)
    }
    private fun ByteArrayOutputStream.msg(field: Int, m: ByteArray) {
        varint(((field shl 3) or 2).toLong()); varint(m.size.toLong()); write(m)
    }

    /** Frame shaped exactly like an ATAK EUD mesh PLI. */
    private fun goldenAtakPli(): ByteArray {
        val contact = ByteArrayOutputStream().apply {
            str(1, "*:-1:stcp"); str(2, "EUD-ALPHA")
        }.toByteArray()
        val group = ByteArrayOutputStream().apply {
            str(1, "Red"); str(2, "Team Lead")
        }.toByteArray()
        val status = ByteArrayOutputStream().apply { u64(1, 87) }.toByteArray()
        val takv = ByteArrayOutputStream().apply {
            str(1, "SAMSUNG SM-G990"); str(2, "ATAK-CIV"); str(3, "34"); str(4, "5.2.0")
        }.toByteArray()
        val track = ByteArrayOutputStream().apply { dbl(1, 1.2); dbl(2, 118.0) }.toByteArray()
        val detail = ByteArrayOutputStream().apply {
            msg(2, contact); msg(3, group); msg(5, status); msg(6, takv); msg(7, track)
        }.toByteArray()
        val cot = ByteArrayOutputStream().apply {
            str(1, "a-f-G-U-C")
            str(5, "ANDROID-abc123")
            u64(6, 1_700_000_000_000)
            u64(7, 1_700_000_000_000)
            u64(8, 1_700_000_075_000)
            str(9, "m-g")
            dbl(10, 40.75950)
            dbl(11, -73.98310)
            dbl(12, 15.0)
            dbl(13, 8.0)
            dbl(14, 12.0)
            msg(15, detail)
        }.toByteArray()
        val takMessage = ByteArrayOutputStream().apply { msg(2, cot) }.toByteArray()
        return ByteArrayOutputStream().apply {
            write(0xBF); write(0x01); write(0xBF); write(takMessage)
        }.toByteArray()
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun `decodes a golden ATAK-shaped mesh PLI`() {
        val frame = goldenAtakPli()
        val e = TakProtocol.decodeDatagram(frame, frame.size)
        assertNotNull(e)
        e!!
        assertEquals("ANDROID-abc123", e.uid)
        assertEquals("EUD-ALPHA", e.callsign)
        assertEquals("a-f-G-U-C", e.type.raw)
        assertEquals(40.75950, e.lat, 1e-9)
        assertEquals(-73.98310, e.lon, 1e-9)
        assertEquals(15.0, e.hae, 1e-9)
        assertEquals(8.0, e.ce, 1e-9)
        assertEquals("Red", e.teamName)
        assertEquals("Team Lead", e.teamRole)
        assertEquals(87, e.battery)
        assertEquals(1.2, e.speed!!, 1e-9)
        assertEquals(118.0, e.course!!, 1e-9)
        assertEquals("*:-1:stcp", e.endpoint)
        assertEquals(1_700_000_075_000, e.staleMillis)
    }

    @Test
    fun `round-trips an event through encode and decode`() {
        val original = CotEvent(
            uid = "ANDROID-watch1", callsign = "WATCH-1", type = CotType("a-f-G-U-C"),
            lat = 40.758, lon = -73.9855, hae = 10.5, ce = 5.0, le = 7.5,
            timeMillis = 1_700_000_000_000, staleMillis = 1_700_000_120_000,
            isSelf = true, endpoint = "*:-1:stcp",
            teamName = "Cyan", teamRole = "Team Member",
            battery = 64, course = 270.5, speed = 1.4,
        )
        val frame = TakProtocol.encodeMeshFrame(original)
        assertEquals(0xBF.toByte(), frame[0]); assertEquals(0x01.toByte(), frame[1]); assertEquals(0xBF.toByte(), frame[2])

        val decoded = TakProtocol.decodeDatagram(frame, frame.size)
        assertNotNull(decoded)
        decoded!!
        assertEquals(original.uid, decoded.uid)
        assertEquals(original.callsign, decoded.callsign)
        assertEquals(original.type.raw, decoded.type.raw)
        assertEquals(original.lat, decoded.lat, 1e-9)
        assertEquals(original.lon, decoded.lon, 1e-9)
        assertEquals(original.hae, decoded.hae, 1e-9)
        assertEquals(original.ce, decoded.ce, 1e-9)
        assertEquals(original.le, decoded.le, 1e-9)
        assertEquals(original.staleMillis, decoded.staleMillis)
        assertEquals(original.teamName, decoded.teamName)
        assertEquals(original.teamRole, decoded.teamRole)
        assertEquals(original.battery, decoded.battery)
        assertEquals(original.course!!, decoded.course!!, 1e-9)
        assertEquals(original.speed!!, decoded.speed!!, 1e-9)
        assertEquals(original.endpoint, decoded.endpoint)
    }

    @Test
    fun `stream frame carries a length-prefixed TakMessage`() {
        val e = CotEvent(
            uid = "X", callsign = "X", type = CotType("a-f-G"),
            lat = 1.0, lon = 2.0,
        )
        val frame = TakProtocol.encodeStreamFrame(e)
        assertEquals(0xBF.toByte(), frame[0])
        // varint length must equal remaining byte count
        var len = 0L; var shift = 0; var i = 1
        while (true) {
            val b = frame[i++].toInt()
            len = len or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        assertEquals(len, (frame.size - i).toLong())
        val decoded = TakProtocol.decodeTakMessage(frame, i, frame.size)
        assertNotNull(decoded)
        assertEquals("X", decoded!!.uid)
    }

    @Test
    fun `rejects garbage without crashing`() {
        assertNull(TakProtocol.decodeDatagram(ByteArray(0), 0))
        assertNull(TakProtocol.decodeDatagram(byteArrayOf(1, 2, 3, 4, 5), 5))
        val junk = ByteArray(64) { (it * 37).toByte() }
        junk[0] = 0xBF.toByte(); junk[1] = 0x01; junk[2] = 0xBF.toByte()
        // Arbitrary bytes after a valid magic header must not throw.
        TakProtocol.decodeDatagram(junk, junk.size)
    }

    @Test
    fun `skips unknown fields for forward compatibility`() {
        // A TakMessage with an unknown field 9 (varint) before the cotEvent.
        val cot = ByteArrayOutputStream().apply {
            str(1, "a-n-G"); str(5, "U-1")
            dbl(10, 3.0); dbl(11, 4.0)
        }.toByteArray()
        val frame = ByteArrayOutputStream().apply {
            write(0xBF); write(0x01); write(0xBF)
            u64(9, 12345)   // unknown field — must be skipped
            msg(2, cot)
        }.toByteArray()
        val e = TakProtocol.decodeDatagram(frame, frame.size)
        assertNotNull(e)
        assertEquals("U-1", e!!.uid)
        assertEquals("a-n-G", e.type.raw)
    }

    @Test
    fun `xml escaping handles special characters`() {
        assertEquals("A&amp;B &lt;C&gt; &quot;D&quot;", TakProtocol.escapeXml("A&B <C> \"D\""))
    }
}
