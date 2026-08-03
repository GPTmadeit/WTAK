package com.atakwatch.minimap.net.meshtastic

import com.atakwatch.minimap.model.CotEvent
import com.atakwatch.minimap.model.CotType
import com.atakwatch.minimap.model.TeamColor
import com.atakwatch.minimap.model.TeamRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-format tests for the Meshtastic codec.
 *
 * These build the bytes a real radio would hand us — by hand, from the published
 * schema — and assert we read them the way the firmware wrote them. A codec that
 * only agrees with itself is a codec that will silently fail in the field.
 */
class MeshtasticProtoTest {

    // ---- helpers to assemble protobuf by hand -------------------------------

    private fun varint(value: Long): ByteArray {
        val out = ArrayList<Byte>()
        var v = value
        while (true) {
            if (v and 0x7FL.inv() == 0L) { out.add(v.toByte()); break }
            out.add(((v and 0x7F) or 0x80).toByte()); v = v ushr 7
        }
        return out.toByteArray()
    }

    private fun key(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())

    private fun fixed32(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte(),
    )

    private fun lenField(field: Int, body: ByteArray) =
        key(field, 2) + varint(body.size.toLong()) + body

    private fun varField(field: Int, value: Long) = key(field, 0) + varint(value)

    private fun f32Field(field: Int, value: Int) = key(field, 5) + fixed32(value)

    private fun str(field: Int, s: String) = lenField(field, s.toByteArray(Charsets.UTF_8))

    // ---- decode -------------------------------------------------------------

    @Test
    fun `decodes a TAK position report from a mesh packet`() {
        val contact = str(1, "ALPHA") + str(2, "ANDROID-abc123")
        val group = varField(1, 2) + varField(2, 10)          // TeamLead, Cyan
        val status = varField(1, 87)
        val pli = f32Field(1, 386_898_000) + f32Field(2, -770_367_000) +
            varField(3, 42) + varField(4, 3) + varField(5, 271)
        val takPacket = lenField(2, contact) + lenField(3, group) +
            lenField(4, status) + lenField(5, pli)

        val data = varField(1, MeshtasticProto.PORT_ATAK_PLUGIN.toLong()) + lenField(2, takPacket)
        val meshPacket = f32Field(1, 0x1234ABCD) + lenField(4, data) +
            f32Field(6, 0x55AA55AA) + f32Field(7, 1_700_000_000)
        val fromRadio = lenField(2, meshPacket)

        val inbound = MeshtasticProto.decodeFromRadio(fromRadio)
        assertTrue("expected a TAK PLI, got $inbound", inbound is MeshtasticProto.Inbound.TakPli)
        inbound as MeshtasticProto.Inbound.TakPli

        assertEquals(0x1234ABCD, inbound.nodeNum)
        assertEquals("ALPHA", inbound.callsign)
        assertEquals("ANDROID-abc123", inbound.deviceCallsign)
        assertEquals(TeamRole.TEAM_LEAD, inbound.role)
        assertEquals(TeamColor.CYAN, inbound.team)
        assertEquals(87, inbound.battery)
        assertEquals(38.6898, inbound.lat, 1e-6)
        assertEquals(-77.0367, inbound.lon, 1e-6)
        assertEquals(42.0, inbound.alt, 0.001)
        assertEquals(271.0, inbound.course!!, 0.001)
        assertEquals(1_700_000_000_000L, inbound.timeMillis)
    }

    @Test
    fun `southern and western hemispheres survive the round trip`() {
        // sfixed32 is two's complement; a sign error here puts the team in the
        // wrong hemisphere, which is exactly the kind of bug that is invisible
        // during testing at home.
        val pli = f32Field(1, (-33.8688 * 1e7).toInt()) + f32Field(2, (151.2093 * 1e7).toInt())
        val takPacket = lenField(5, pli)
        val data = varField(1, MeshtasticProto.PORT_ATAK_PLUGIN.toLong()) + lenField(2, takPacket)
        val inbound = MeshtasticProto.decodeFromRadio(lenField(2, lenField(4, data)))

        inbound as MeshtasticProto.Inbound.TakPli
        assertEquals(-33.8688, inbound.lat, 1e-6)
        assertEquals(151.2093, inbound.lon, 1e-6)
    }

    @Test
    fun `decodes a plain Meshtastic position from a node with no TAK plugin`() {
        val position = f32Field(1, 511_234_000) + f32Field(2, -1_234_000) + varField(3, 15)
        val data = varField(1, MeshtasticProto.PORT_POSITION.toLong()) + lenField(2, position)
        val meshPacket = f32Field(1, 0x0A0B0C0D) + lenField(4, data)

        val inbound = MeshtasticProto.decodeFromRadio(lenField(2, meshPacket))
        assertTrue(inbound is MeshtasticProto.Inbound.Position)
        inbound as MeshtasticProto.Inbound.Position
        assertEquals(51.1234, inbound.lat, 1e-6)
        assertEquals(-0.1234, inbound.lon, 1e-6)
    }

    @Test
    fun `a node reporting zero-zero has no fix, not a position in the Atlantic`() {
        val position = f32Field(1, 0) + f32Field(2, 0)
        val data = varField(1, MeshtasticProto.PORT_POSITION.toLong()) + lenField(2, position)
        assertNull(MeshtasticProto.decodeFromRadio(lenField(2, lenField(4, data))))
    }

    @Test
    fun `decodes node info and my node number`() {
        val user = str(1, "!a1b2c3d4") + str(2, "Bravo Six") + str(3, "B6")
        val nodeInfo = varField(1, 0xA1B2C3D4L) + lenField(2, user) + f32Field(5, 1_700_000_500)
        val node = MeshtasticProto.decodeFromRadio(lenField(4, nodeInfo))
        assertTrue(node is MeshtasticProto.Inbound.Node)
        node as MeshtasticProto.Inbound.Node
        assertEquals("Bravo Six", node.longName)
        assertEquals("B6", node.shortName)

        val myInfo = MeshtasticProto.decodeFromRadio(lenField(3, varField(1, 0x11223344L)))
        assertEquals(MeshtasticProto.Inbound.MyInfo(0x11223344), myInfo)
    }

    @Test
    fun `decodes TAK GeoChat and plain text messages`() {
        val chat = lenField(2, str(1, "ALPHA")) + lenField(6, str(1, "moving to phase line"))
        val takData = varField(1, MeshtasticProto.PORT_ATAK_PLUGIN.toLong()) + lenField(2, chat)
        val takPacket = lenField(2, f32Field(1, 7) + lenField(4, takData) + f32Field(6, 99))
        val geoChat = MeshtasticProto.decodeFromRadio(takPacket)
        assertTrue(geoChat is MeshtasticProto.Inbound.Text)
        geoChat as MeshtasticProto.Inbound.Text
        assertEquals("ALPHA", geoChat.callsign)
        assertEquals("moving to phase line", geoChat.text)
        assertEquals(99, geoChat.packetId)

        val textData = varField(1, MeshtasticProto.PORT_TEXT_MESSAGE.toLong()) +
            lenField(2, "plain radio text".toByteArray(Charsets.UTF_8))
        val plain = MeshtasticProto.decodeFromRadio(lenField(2, lenField(4, textData)))
        assertTrue(plain is MeshtasticProto.Inbound.Text)
        assertEquals("plain radio text", (plain as MeshtasticProto.Inbound.Text).text)
    }

    @Test
    fun `compressed callsigns are dropped rather than shown as garbage`() {
        // is_compressed means the strings use a unishox2 dictionary we don't
        // carry; the position is still good, the names are not.
        val takPacket = varField(1, 1) +
            lenField(2, str(1, "garbage")) +
            lenField(5, f32Field(1, 100_000_000) + f32Field(2, 200_000_000))
        val data = varField(1, MeshtasticProto.PORT_ATAK_PLUGIN.toLong()) + lenField(2, takPacket)
        val inbound = MeshtasticProto.decodeFromRadio(lenField(2, lenField(4, data)))

        inbound as MeshtasticProto.Inbound.TakPli
        assertNull(inbound.callsign)
        assertEquals(10.0, inbound.lat, 1e-6)
    }

    @Test
    fun `unknown ports and malformed frames decode to null instead of throwing`() {
        val telemetry = varField(1, 67L) + lenField(2, byteArrayOf(1, 2, 3))
        assertNull(MeshtasticProto.decodeFromRadio(lenField(2, lenField(4, telemetry))))

        assertNull(MeshtasticProto.decodeFromRadio(ByteArray(0)))
        assertNull(MeshtasticProto.decodeFromRadio(byteArrayOf(0xFF.toByte(), 0xFF.toByte())))
        // Truncated length prefix — claims 40 bytes of body, supplies none.
        assertNull(MeshtasticProto.decodeFromRadio(key(2, 2) + varint(40)))
    }

    // ---- encode -------------------------------------------------------------

    private val self = CotEvent(
        uid = "ANDROID-watch1",
        callsign = "WATCH-1",
        type = CotType("a-f-G-U-C"),
        lat = 38.8895,
        lon = -77.0353,
        hae = 17.0,
        teamName = "Green",
        teamRole = "Medic",
        battery = 64,
        course = 91.4,
        speed = 2.0,
        isSelf = true,
    )

    @Test
    fun `an encoded position report decodes back to the same position`() {
        val takPacket = MeshtasticProto.takPliPacket(self, "ANDROID-watch1")
        val packet = MeshtasticProto.meshPacket(
            takPacket, MeshtasticProto.PORT_ATAK_PLUGIN, packetId = 0x0BADF00D,
        )
        // The radio echoes what we sent as a FromRadio.packet, so decoding our
        // own encoder's output is exactly what a peer will do.
        val inbound = MeshtasticProto.decodeFromRadio(lenField(2, packet))
        assertTrue(inbound is MeshtasticProto.Inbound.TakPli)
        inbound as MeshtasticProto.Inbound.TakPli

        assertEquals("WATCH-1", inbound.callsign)
        assertEquals("ANDROID-watch1", inbound.deviceCallsign)
        assertEquals(TeamColor.GREEN, inbound.team)
        assertEquals(TeamRole.MEDIC, inbound.role)
        assertEquals(64, inbound.battery)
        assertEquals(38.8895, inbound.lat, 1e-6)
        assertEquals(-77.0353, inbound.lon, 1e-6)
        assertEquals(91.0, inbound.course!!, 1.0)
    }

    @Test
    fun `an encoded chat message decodes back to the same text`() {
        val packet = MeshtasticProto.meshPacket(
            MeshtasticProto.takChatPacket("contact front", "WATCH-1", "ANDROID-watch1"),
            MeshtasticProto.PORT_ATAK_PLUGIN, packetId = 7,
        )
        val inbound = MeshtasticProto.decodeFromRadio(lenField(2, packet))
        inbound as MeshtasticProto.Inbound.Text
        assertEquals("contact front", inbound.text)
        assertEquals("WATCH-1", inbound.callsign)
    }

    @Test
    fun `mesh packets are addressed to the broadcast node`() {
        val packet = MeshtasticProto.meshPacket(byteArrayOf(1), 72, packetId = 5)
        // to = fixed32 field 2, value 0xFFFFFFFF
        val expected = key(2, 5) + fixed32(-1)
        assertTrue(
            "broadcast address missing from ${packet.joinToString(" ") { "%02x".format(it) }}",
            packet.toList().windowed(expected.size).any { it == expected.toList() },
        )
    }

    @Test
    fun `want_config carries the nonce even when the low bits are zero`() {
        // want_config_id is a proto3 scalar, so a naive encoder drops a zero —
        // and the radio then never echoes a config_complete we can match.
        val frame = MeshtasticProto.toRadioWantConfig(0)
        assertEquals(listOf<Byte>(0x18, 0x00), frame.toList())
    }

    @Test
    fun `team and role codes map both ways`() {
        TeamColor.entries.forEach { colour ->
            val code = MeshtasticProto.teamColorCode(colour.label)
            assertNotNull("no wire code for ${colour.label}", code)
            assertEquals(colour, MeshtasticProto.teamFromCode(code!!))
        }
        TeamRole.entries.forEach { role ->
            val code = MeshtasticProto.teamRoleCode(role.label)
            assertNotNull("no wire code for ${role.label}", code)
            assertEquals(role, MeshtasticProto.roleFromCode(code!!))
        }
        assertNull(MeshtasticProto.teamFromCode(0))
        assertNull(MeshtasticProto.roleFromCode(99))
    }

    // ---- radio administration -----------------------------------------------

    @Test
    fun `decodes the radio's own config from the replay`() {
        // Config { device { role = TAK } }
        val device = varField(1, MeshtasticProto.DEVICE_ROLE_TAK.toLong())
        val deviceConfig = MeshtasticProto.decodeFromRadio(lenField(5, lenField(1, device)))
        assertEquals(
            MeshtasticProto.Inbound.DeviceConfig(MeshtasticProto.DEVICE_ROLE_TAK),
            deviceConfig,
        )

        // Config { lora { modem_preset=0 region=1(US) hop_limit=3 tx_enabled } }
        val lora = varField(2, 0) + varField(7, 1) + varField(8, 3) + varField(9, 1)
        val loraConfig = MeshtasticProto.decodeFromRadio(lenField(5, lenField(6, lora)))
        assertTrue(loraConfig is MeshtasticProto.Inbound.LoRaConfig)
        loraConfig as MeshtasticProto.Inbound.LoRaConfig
        assertEquals(1, loraConfig.region)
        assertEquals(3, loraConfig.hopLimit)
        assertTrue(loraConfig.txEnabled)
    }

    @Test
    fun `an unset region decodes as unset rather than as a valid region`() {
        // region is a proto3 scalar, so UNSET is simply absent from the wire.
        // Reading that as "some region" would hide the one fault that stops a
        // radio transmitting at all.
        val lora = varField(8, 3)
        val decoded = MeshtasticProto.decodeFromRadio(lenField(5, lenField(6, lora)))
        decoded as MeshtasticProto.Inbound.LoRaConfig
        assertEquals(MeshtasticProto.REGION_UNSET, decoded.region)
    }

    @Test
    fun `decodes the radio's TAK module identity`() {
        val tak = varField(1, 5) + varField(2, 12)   // Medic, Green
        val decoded = MeshtasticProto.decodeFromRadio(lenField(9, lenField(16, tak)))
        assertEquals(
            MeshtasticProto.Inbound.TakConfig(TeamColor.GREEN, TeamRole.MEDIC),
            decoded,
        )
    }

    @Test
    fun `decodes the primary channel and ignores disabled ones`() {
        val settings = str(3, "TeamNet")
        val primary = varField(1, 0) + lenField(2, settings) + varField(3, 1)
        val decoded = MeshtasticProto.decodeFromRadio(lenField(10, primary))
        assertEquals(
            MeshtasticProto.Inbound.ChannelInfo(0, "TeamNet", true),
            decoded,
        )

        // role 0 is DISABLED — an unused slot, not a channel.
        val disabled = varField(1, 3) + lenField(2, str(3, "unused"))
        assertNull(MeshtasticProto.decodeFromRadio(lenField(10, disabled)))
    }

    @Test
    fun `an unnamed primary channel is reported as the Meshtastic default`() {
        val primary = varField(1, 0) + varField(3, 1)
        val decoded = MeshtasticProto.decodeFromRadio(lenField(10, primary))
        decoded as MeshtasticProto.Inbound.ChannelInfo
        assertEquals("Default", decoded.name)
    }

    @Test
    fun `admin edits are wrapped in a begin and commit pair`() {
        // begin_edit_settings = 64, commit_edit_settings = 65, both bool.
        // Field 64 wire 0 -> tag 512 -> varint 0x80 0x04; value 1.
        assertEquals(
            listOf<Byte>(0x80.toByte(), 0x04, 0x01),
            MeshtasticProto.adminBeginEdit().toList(),
        )
        assertEquals(
            listOf<Byte>(0x88.toByte(), 0x04, 0x01),
            MeshtasticProto.adminCommitEdit().toList(),
        )
    }

    @Test
    fun `set_config carries the TAK device role`() {
        val frame = MeshtasticProto.adminSetDeviceRole(MeshtasticProto.DEVICE_ROLE_TAK)
        // AdminMessage.set_config = 34 (wire 2) -> tag 274 -> 0x92 0x02
        // Config.device = 1 (wire 2), DeviceConfig.role = 1 (varint) = 7
        assertEquals(
            listOf<Byte>(0x92.toByte(), 0x02, 0x04, 0x0A, 0x02, 0x08, 0x07),
            frame.toList(),
        )
    }

    @Test
    fun `set_module_config carries the TAK team and role`() {
        val frame = MeshtasticProto.adminSetTakModule(TeamColor.CYAN, TeamRole.HQ)
        // set_module_config = 35 (wire 2) -> tag 282 -> 0x9A 0x02, len 7
        // ModuleConfig.tak = 16 (wire 2) -> tag 130 -> 0x82 0x01, len 4
        // TAKConfig.role = 1 -> HQ = 3 ; team = 2 -> Cyan = 10
        assertEquals(
            listOf<Byte>(
                0x9A.toByte(), 0x02, 0x07,
                0x82.toByte(), 0x01, 0x04,
                0x08, 0x03, 0x10, 0x0A,
            ),
            frame.toList(),
        )
    }

    @Test
    fun `region and preset codes render as Meshtastic's own labels`() {
        assertEquals("UNSET", MeshtasticProto.regionName(MeshtasticProto.REGION_UNSET))
        assertEquals("EU_868", MeshtasticProto.regionName(3))
        assertEquals("Long Fast", MeshtasticProto.modemPresetName(0))
        assertEquals("TAK", MeshtasticProto.deviceRoleName(MeshtasticProto.DEVICE_ROLE_TAK))
        // An unknown code is reported, not guessed at.
        assertEquals("Region 99", MeshtasticProto.regionName(99))
    }

    @Test
    fun `node ids follow the Meshtastic bang-hex convention`() {
        assertEquals("!a1b2c3d4", MeshtasticProto.nodeId(0xA1B2C3D4.toInt()))
        assertEquals("!0000000f", MeshtasticProto.nodeId(15))
    }

    @Test
    fun `a TAK report keeps its sender uid so one teammate is one contact`() {
        val pli = MeshtasticProto.Inbound.TakPli(
            nodeNum = 0x11, callsign = "ALPHA", deviceCallsign = "ANDROID-abc",
            team = TeamColor.CYAN, role = TeamRole.HQ, battery = 50,
            lat = 1.0, lon = 2.0, alt = 3.0, speed = null, course = null,
            timeMillis = 1_000L,
        )
        val event = MeshtasticProto.toCotEvent(pli, staleAfterMs = 60_000)
        assertEquals("ANDROID-abc", event.uid)
        assertEquals("Cyan", event.teamName)
        assertEquals(61_000L, event.staleMillis)

        // A node that gave us no device callsign still gets a stable identity.
        val anonymous = MeshtasticProto.toCotEvent(pli.copy(deviceCallsign = null), 60_000)
        assertEquals("MESH-!00000011", anonymous.uid)
    }
}
