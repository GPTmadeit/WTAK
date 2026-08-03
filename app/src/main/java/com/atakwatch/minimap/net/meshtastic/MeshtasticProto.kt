package com.atakwatch.minimap.net.meshtastic

import com.atakwatch.minimap.model.CotEvent
import com.atakwatch.minimap.model.CotType
import com.atakwatch.minimap.model.TeamColor
import com.atakwatch.minimap.model.TeamRole
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * Meshtastic wire protocol — the subset a TAK client needs.
 *
 * Field numbers verified against the official schemas in
 * `meshtastic/protobufs`:
 *
 *   ToRadio    { packet=1  want_config_id=3  disconnect=4  heartbeat=7 }
 *   FromRadio  { id=1  packet=2  my_info=3  node_info=4  config_complete_id=7 }
 *   MeshPacket { from=1(fixed32) to=2(fixed32) channel=3 decoded=4 encrypted=5
 *                id=6(fixed32) rx_time=7(fixed32) rx_snr=8(float) hop_limit=9
 *                want_ack=10 priority=11 rx_rssi=12 }
 *   Data       { portnum=1 payload=2 want_response=3 dest=4 source=5 }
 *   Position   { latitude_i=1(sfixed32) longitude_i=2(sfixed32) altitude=3
 *                time=4(fixed32) ground_speed=15 ground_track=16 }
 *   User       { id=1 long_name=2 short_name=3 }
 *   NodeInfo   { num=1 user=2 position=3 snr=4 last_heard=5 }
 *   TAKPacket  { is_compressed=1 contact=2 group=3 status=4 pli=5 chat=6 detail=7 }
 *   Contact    { callsign=1 device_callsign=2 }
 *   Group      { role=1(MemberRole) team=2(Team) }
 *   Status     { battery=1 }
 *   PLI        { latitude_i=1(sfixed32) longitude_i=2(sfixed32) altitude=3
 *                speed=4 course=5 }
 *   GeoChat    { message=1 to=2 to_callsign=3 }
 *
 * TAK traffic rides [PORT_ATAK_PLUGIN] (72), the same port Meshtastic's own
 * ATAK plugin uses, so a watch and a phone running that plugin interoperate
 * over LoRa with no server and no IP network at all.
 *
 * The codec is hand-rolled for the same reason [com.atakwatch.minimap.net.TakProtocol]
 * is: protobuf's wire format is small, the byte layout stays auditable, and the
 * watch APK doesn't grow a code-generation toolchain. Pure JVM — unit-testable
 * off-device.
 */
object MeshtasticProto {

    // ---- ports ---------------------------------------------------------------

    const val PORT_TEXT_MESSAGE = 1
    const val PORT_POSITION = 3
    const val PORT_NODEINFO = 4
    const val PORT_ADMIN = 6
    const val PORT_ATAK_PLUGIN = 72

    // ---- device roles --------------------------------------------------------

    /**
     * `Config.DeviceConfig.Role.TAK` — the role Meshtastic ships for exactly
     * this job: a radio acting as a TAK client's link, which suppresses the
     * routine chatter a general-purpose node emits.
     *
     * Deliberately not `TAK_TRACKER` (10): that role makes the *radio* generate
     * its own PLI, which would put a second, less accurate copy of this operator
     * on the mesh alongside the one the watch already sends.
     */
    const val DEVICE_ROLE_TAK = 7
    const val DEVICE_ROLE_CLIENT = 0

    /** `Config.LoRaConfig.RegionCode.UNSET` — a radio in this state cannot transmit. */
    const val REGION_UNSET = 0

    /** Meshtastic's broadcast address (0xFFFFFFFF). */
    const val BROADCAST_ADDR = -1

    /** Radios drop packets past this many hops; 3 is the firmware default. */
    private const val HOP_LIMIT = 3

    /** Degrees ↔ Meshtastic's fixed-point integer degrees. */
    private const val DEG_SCALE = 1e7

    // ---- outbound ------------------------------------------------------------

    /** `ToRadio { packet }` — one mesh packet handed to the radio for transmit. */
    fun toRadioPacket(packet: ByteArray): ByteArray =
        PbWriter().apply { message(1, packet) }.toByteArray()

    /**
     * `ToRadio { want_config_id }` — asks the radio to replay its config, node
     * database and identity. This is the handshake every Meshtastic client
     * performs on connect; the radio answers with a burst of FromRadio messages
     * terminated by `config_complete_id` carrying the same id back.
     */
    fun toRadioWantConfig(nonce: Int): ByteArray =
        PbWriter().apply { varintAlways(3, nonce.toLong() and 0xFFFFFFFFL) }.toByteArray()

    /** `ToRadio { heartbeat }` — keeps the radio from dropping an idle BLE link. */
    fun toRadioHeartbeat(): ByteArray =
        PbWriter().apply { messageAlways(7, ByteArray(0)) }.toByteArray()

    /** `ToRadio { disconnect }` — a clean goodbye, so the radio frees the slot. */
    fun toRadioDisconnect(): ByteArray =
        PbWriter().apply { bool(4, true) }.toByteArray()

    /** `MeshPacket` wrapping an application payload, addressed to the whole mesh. */
    fun meshPacket(
        payload: ByteArray,
        portNum: Int,
        packetId: Int,
        channel: Int = 0,
        to: Int = BROADCAST_ADDR,
        wantAck: Boolean = false,
    ): ByteArray {
        val data = PbWriter().apply {
            varint(1, portNum.toLong())
            bytes(2, payload)
        }.toByteArray()
        return PbWriter().apply {
            fixed32(2, to)
            varint(3, channel.toLong())
            message(4, data)
            fixed32(6, packetId)
            varint(9, HOP_LIMIT.toLong())
            bool(10, wantAck)
        }.toByteArray()
    }

    /**
     * A self position report as a `TAKPacket`.
     *
     * Strings are sent uncompressed (`is_compressed` omitted): the compressed
     * form uses a shared unishox2 dictionary that only buys a few bytes on a
     * callsign, and an uncompressed packet is readable by every client.
     */
    fun takPliPacket(e: CotEvent, deviceCallsign: String): ByteArray {
        val contact = PbWriter().apply {
            string(1, e.callsign)
            string(2, deviceCallsign)
        }.toByteArray()

        val group = PbWriter().apply {
            teamRoleCode(e.teamRole)?.let { varint(1, it.toLong()) }
            teamColorCode(e.teamName)?.let { varint(2, it.toLong()) }
        }.toByteArray()

        val status = PbWriter().apply {
            e.battery?.let { varint(1, it.coerceIn(0, 100).toLong()) }
        }.toByteArray()

        val pli = PbWriter().apply {
            fixed32Always(1, (e.lat * DEG_SCALE).roundToInt())
            fixed32Always(2, (e.lon * DEG_SCALE).roundToInt())
            varint(3, e.hae.roundToInt().toLong())
            // Course/speed are unsigned on the wire: a negative or absent value
            // means "not reported", which is what omitting the field says.
            e.speed?.let { if (it > 0) varint(4, it.roundToInt().toLong()) }
            e.course?.let { varint(5, (((it % 360) + 360) % 360).roundToInt().toLong()) }
        }.toByteArray()

        return PbWriter().apply {
            message(2, contact)
            message(3, group)
            message(4, status)
            messageAlways(5, pli)
        }.toByteArray()
    }

    // ---- admin: making the radio a TAK connector -----------------------------

    /**
     * Admin messages, addressed to the radio itself.
     *
     * Settings changes are wrapped in a begin/commit pair so the radio applies
     * them together and reboots once, rather than restarting after each field.
     * These go to our own node number, which is local administration — the
     * session-passkey handshake only guards *remote* admin over the mesh.
     */
    fun adminBeginEdit(): ByteArray = PbWriter().apply { bool(64, true) }.toByteArray()

    fun adminCommitEdit(): ByteArray = PbWriter().apply { bool(65, true) }.toByteArray()

    /** `AdminMessage { set_config { device { role } } }`. */
    fun adminSetDeviceRole(role: Int): ByteArray {
        val device = PbWriter().apply { varintAlways(1, role.toLong()) }.toByteArray()
        val config = PbWriter().apply { messageAlways(1, device) }.toByteArray()
        return PbWriter().apply { messageAlways(34, config) }.toByteArray()
    }

    /**
     * `AdminMessage { set_module_config { tak { team, role } } }`.
     *
     * This is the radio's own ATAK identity. Setting it from the watch is what
     * stops the two drifting apart — the operator picks a team once, on the
     * device they actually look at.
     */
    fun adminSetTakModule(team: TeamColor?, role: TeamRole?): ByteArray {
        val tak = PbWriter().apply {
            teamRoleCode(role?.label)?.let { varint(1, it.toLong()) }
            teamColorCode(team?.label)?.let { varint(2, it.toLong()) }
        }.toByteArray()
        val moduleConfig = PbWriter().apply { messageAlways(16, tak) }.toByteArray()
        return PbWriter().apply { messageAlways(35, moduleConfig) }.toByteArray()
    }

    /** `AdminMessage { get_config_request }` for one [ConfigType]. */
    fun adminGetConfig(type: Int): ByteArray =
        PbWriter().apply { varintAlways(5, type.toLong()) }.toByteArray()

    /** `AdminMessage { get_module_config_request }` for one ModuleConfigType. */
    fun adminGetModuleConfig(type: Int): ByteArray =
        PbWriter().apply { varintAlways(7, type.toLong()) }.toByteArray()

    /** ConfigType values we ask for. */
    const val CONFIG_DEVICE = 0
    const val CONFIG_LORA = 5

    /** ModuleConfigType.TAK_CONFIG. */
    const val MODULE_CONFIG_TAK = 15

    /** A broadcast chat message as a `TAKPacket`, matching ATAK's "All Chat Rooms". */
    fun takChatPacket(text: String, callsign: String, deviceCallsign: String): ByteArray {
        val contact = PbWriter().apply {
            string(1, callsign)
            string(2, deviceCallsign)
        }.toByteArray()
        val chat = PbWriter().apply { string(1, text) }.toByteArray()
        return PbWriter().apply {
            message(2, contact)
            messageAlways(6, chat)
        }.toByteArray()
    }

    // ---- inbound -------------------------------------------------------------

    /** What a decoded `FromRadio` turned out to be. Anything else decodes to null. */
    sealed interface Inbound {
        /** The radio told us its own node number — that is our address on the mesh. */
        data class MyInfo(val nodeNum: Int) : Inbound

        /** A node's identity from the radio's node database or a NODEINFO packet. */
        data class Node(
            val nodeNum: Int,
            val id: String?,
            val longName: String?,
            val shortName: String?,
            val lat: Double?,
            val lon: Double?,
            val alt: Double,
            val timeMillis: Long,
        ) : Inbound

        /** A plain Meshtastic position report from a node with no TAK plugin. */
        data class Position(
            val nodeNum: Int,
            val lat: Double,
            val lon: Double,
            val alt: Double,
            val timeMillis: Long,
        ) : Inbound

        /** A TAK position report. */
        data class TakPli(
            val nodeNum: Int,
            val callsign: String?,
            val deviceCallsign: String?,
            val team: TeamColor?,
            val role: TeamRole?,
            val battery: Int?,
            val lat: Double,
            val lon: Double,
            val alt: Double,
            val speed: Double?,
            val course: Double?,
            val timeMillis: Long,
        ) : Inbound

        /** A chat message — TAK GeoChat, or a plain Meshtastic text message. */
        data class Text(
            val nodeNum: Int,
            val callsign: String?,
            val text: String,
            val timeMillis: Long,
            /** Mesh packet id, so a rebroadcast can't show up twice in the log. */
            val packetId: Int = 0,
        ) : Inbound

        /** The radio finished replaying its state; the node database is current. */
        data class ConfigComplete(val id: Int) : Inbound

        /** `Config.DeviceConfig` — what job the radio thinks it is doing. */
        data class DeviceConfig(val role: Int) : Inbound

        /**
         * `Config.LoRaConfig`. Region matters most: a radio left on
         * [REGION_UNSET] is legally unable to transmit and will look, from the
         * watch, exactly like a mesh with nobody on it.
         */
        data class LoRaConfig(
            val region: Int,
            val modemPreset: Int,
            val hopLimit: Int,
            val txEnabled: Boolean,
        ) : Inbound

        /** `ModuleConfig.TAKConfig` — the radio's own ATAK team and role. */
        data class TakConfig(val team: TeamColor?, val role: TeamRole?) : Inbound

        /** The primary channel, which every node must share to hear each other. */
        data class ChannelInfo(val index: Int, val name: String, val isPrimary: Boolean) : Inbound

        /** `DeviceMetadata` — firmware version, for the settings readout. */
        data class Metadata(val firmwareVersion: String) : Inbound
    }

    /**
     * Decode one `FromRadio` message. Returns null for anything we don't act on
     * — config blocks, log records, telemetry — and never throws: a malformed or
     * hostile frame off the air must not take the app down.
     */
    fun decodeFromRadio(buf: ByteArray, length: Int = buf.size): Inbound? = runCatching {
        val r = PbReader(buf, 0, length)
        while (r.hasMore()) {
            val key = r.readVarint().toInt()
            val wire = key and 7
            when (key ushr 3) {
                2 -> if (wire == 2) return@runCatching decodeMeshPacket(r.sub())
                3 -> if (wire == 2) return@runCatching decodeMyInfo(r.sub())
                4 -> if (wire == 2) return@runCatching decodeNodeInfo(r.sub())
                5 -> if (wire == 2) return@runCatching decodeConfig(r.sub())
                7 -> if (wire == 0) return@runCatching Inbound.ConfigComplete(r.readVarint().toInt())
                9 -> if (wire == 2) return@runCatching decodeModuleConfig(r.sub())
                10 -> if (wire == 2) return@runCatching decodeChannel(r.sub())
                13 -> if (wire == 2) return@runCatching decodeMetadata(r.sub())
            }
            r.skip(wire)
        }
        null
    }.getOrNull()

    /** `Config` — only the two variants a TAK link actually depends on. */
    private fun decodeConfig(r: PbReader): Inbound? {
        while (r.hasMore()) {
            val key = r.readVarint().toInt()
            val wire = key and 7
            when (key ushr 3) {
                1 -> if (wire == 2) {                       // DeviceConfig
                    val d = r.sub()
                    var role = DEVICE_ROLE_CLIENT
                    while (d.hasMore()) {
                        val dk = d.readVarint().toInt()
                        if (dk ushr 3 == 1 && dk and 7 == 0) { role = d.readVarint().toInt(); continue }
                        d.skip(dk and 7)
                    }
                    return Inbound.DeviceConfig(role)
                }
                6 -> if (wire == 2) {                       // LoRaConfig
                    val l = r.sub()
                    var region = REGION_UNSET
                    var preset = 0
                    var hops = 0
                    var tx = true
                    while (l.hasMore()) {
                        val lk = l.readVarint().toInt()
                        when (lk ushr 3) {
                            2 -> if (lk and 7 == 0) { preset = l.readVarint().toInt(); continue }
                            7 -> if (lk and 7 == 0) { region = l.readVarint().toInt(); continue }
                            8 -> if (lk and 7 == 0) { hops = l.readVarint().toInt(); continue }
                            9 -> if (lk and 7 == 0) { tx = l.readVarint() != 0L; continue }
                        }
                        l.skip(lk and 7)
                    }
                    return Inbound.LoRaConfig(region, preset, hops, tx)
                }
            }
            r.skip(wire)
        }
        return null
    }

    /** `ModuleConfig` — only the TAK variant matters here. */
    private fun decodeModuleConfig(r: PbReader): Inbound? {
        while (r.hasMore()) {
            val key = r.readVarint().toInt()
            val wire = key and 7
            if (key ushr 3 == 16 && wire == 2) {
                val t = r.sub()
                var team: TeamColor? = null
                var role: TeamRole? = null
                while (t.hasMore()) {
                    val tk = t.readVarint().toInt()
                    when (tk ushr 3) {
                        1 -> if (tk and 7 == 0) { role = roleFromCode(t.readVarint().toInt()); continue }
                        2 -> if (tk and 7 == 0) { team = teamFromCode(t.readVarint().toInt()); continue }
                    }
                    t.skip(tk and 7)
                }
                return Inbound.TakConfig(team, role)
            }
            r.skip(wire)
        }
        return null
    }

    private fun decodeChannel(r: PbReader): Inbound? {
        var index = 0
        var name = ""
        var role = 0
        while (r.hasMore()) {
            val key = r.readVarint().toInt()
            val wire = key and 7
            when (key ushr 3) {
                1 -> if (wire == 0) { index = r.readVarint().toInt(); continue }
                2 -> if (wire == 2) {                       // ChannelSettings
                    val s = r.sub()
                    while (s.hasMore()) {
                        val sk = s.readVarint().toInt()
                        if (sk ushr 3 == 3 && sk and 7 == 2) { name = s.readString(); continue }
                        s.skip(sk and 7)
                    }
                    continue
                }
                3 -> if (wire == 0) { role = r.readVarint().toInt(); continue }
            }
            r.skip(wire)
        }
        // Role 1 is PRIMARY. An unnamed primary channel is Meshtastic's
        // default, which every stock radio shares — worth naming as such.
        if (role == 0) return null
        return Inbound.ChannelInfo(
            index = index,
            name = name.ifBlank { if (role == 1) "Default" else "Channel $index" },
            isPrimary = role == 1,
        )
    }

    private fun decodeMetadata(r: PbReader): Inbound? {
        var version = ""
        while (r.hasMore()) {
            val key = r.readVarint().toInt()
            val wire = key and 7
            if (key ushr 3 == 1 && wire == 2) { version = r.readString(); continue }
            r.skip(wire)
        }
        return if (version.isBlank()) null else Inbound.Metadata(version)
    }

    private fun decodeMyInfo(r: PbReader): Inbound? {
        while (r.hasMore()) {
            val key = r.readVarint().toInt()
            if (key ushr 3 == 1 && (key and 7) == 0) return Inbound.MyInfo(r.readVarint().toInt())
            r.skip(key and 7)
        }
        return null
    }

    private fun decodeNodeInfo(r: PbReader): Inbound? {
        var num = 0
        var id: String? = null
        var longName: String? = null
        var shortName: String? = null
        var lat: Double? = null
        var lon: Double? = null
        var alt = 0.0
        var lastHeard = 0L

        while (r.hasMore()) {
            val key = r.readVarint().toInt()
            val wire = key and 7
            when (key ushr 3) {
                1 -> if (wire == 0) { num = r.readVarint().toInt(); continue }
                2 -> if (wire == 2) {                       // User
                    val u = r.sub()
                    while (u.hasMore()) {
                        val uk = u.readVarint().toInt()
                        when (uk ushr 3) {
                            1 -> if (uk and 7 == 2) { id = u.readString(); continue }
                            2 -> if (uk and 7 == 2) { longName = u.readString(); continue }
                            3 -> if (uk and 7 == 2) { shortName = u.readString(); continue }
                        }
                        u.skip(uk and 7)
                    }
                    continue
                }
                3 -> if (wire == 2) {                       // Position
                    val p = decodePositionBody(r.sub())
                    lat = p.lat; lon = p.lon; alt = p.alt
                    continue
                }
                5 -> if (wire == 5) { lastHeard = r.readFixed32().toLong() and 0xFFFFFFFFL; continue }
            }
            r.skip(wire)
        }
        if (num == 0) return null
        return Inbound.Node(
            nodeNum = num, id = id, longName = longName, shortName = shortName,
            lat = lat, lon = lon, alt = alt,
            timeMillis = if (lastHeard > 0) lastHeard * 1000 else System.currentTimeMillis(),
        )
    }

    private fun decodeMeshPacket(r: PbReader): Inbound? {
        var from = 0
        var packetId = 0
        var rxTime = 0L
        var portNum = -1
        var payload: ByteArray? = null

        while (r.hasMore()) {
            val key = r.readVarint().toInt()
            val wire = key and 7
            when (key ushr 3) {
                1 -> if (wire == 5) { from = r.readFixed32(); continue }
                4 -> if (wire == 2) {                       // Data
                    val d = r.sub()
                    while (d.hasMore()) {
                        val dk = d.readVarint().toInt()
                        when (dk ushr 3) {
                            1 -> if (dk and 7 == 0) { portNum = d.readVarint().toInt(); continue }
                            2 -> if (dk and 7 == 2) { payload = d.readByteArray(); continue }
                        }
                        d.skip(dk and 7)
                    }
                    continue
                }
                6 -> if (wire == 5) { packetId = r.readFixed32(); continue }
                7 -> if (wire == 5) { rxTime = r.readFixed32().toLong() and 0xFFFFFFFFL; continue }
            }
            r.skip(wire)
        }

        val body = payload ?: return null
        // An encrypted packet (no `decoded`) means the channel key doesn't match;
        // there is nothing to be done with it and nothing to warn about.
        val time = if (rxTime > 0) rxTime * 1000 else System.currentTimeMillis()

        return when (portNum) {
            PORT_ATAK_PLUGIN -> decodeTakPacket(body, from, time, packetId)
            PORT_POSITION -> {
                val p = decodePositionBody(PbReader(body))
                if (p.lat == null || p.lon == null) null
                else Inbound.Position(from, p.lat, p.lon, p.alt, time)
            }
            PORT_NODEINFO -> {
                var id: String? = null; var longName: String? = null; var shortName: String? = null
                val u = PbReader(body)
                while (u.hasMore()) {
                    val uk = u.readVarint().toInt()
                    when (uk ushr 3) {
                        1 -> if (uk and 7 == 2) { id = u.readString(); continue }
                        2 -> if (uk and 7 == 2) { longName = u.readString(); continue }
                        3 -> if (uk and 7 == 2) { shortName = u.readString(); continue }
                    }
                    u.skip(uk and 7)
                }
                Inbound.Node(from, id, longName, shortName, null, null, 0.0, time)
            }
            PORT_TEXT_MESSAGE -> {
                val text = String(body, Charsets.UTF_8).trim()
                if (text.isEmpty()) null else Inbound.Text(from, null, text, time, packetId)
            }
            else -> null
        }
    }

    private class Pos(val lat: Double?, val lon: Double?, val alt: Double)

    /** Decode a `Position` body; lat/lon come back null when the node has no fix. */
    private fun decodePositionBody(p: PbReader): Pos {
        var latI: Int? = null; var lonI: Int? = null; var alt = 0.0
        while (p.hasMore()) {
            val key = p.readVarint().toInt()
            val wire = key and 7
            when (key ushr 3) {
                1 -> if (wire == 5) { latI = p.readFixed32(); continue }
                2 -> if (wire == 5) { lonI = p.readFixed32(); continue }
                3 -> if (wire == 0) { alt = p.readVarint().toDouble(); continue }
            }
            p.skip(wire)
        }
        // 0/0 is what a radio reports with no fix, not a position off West Africa.
        if (latI == null || lonI == null || (latI == 0 && lonI == 0)) return Pos(null, null, alt)
        return Pos(latI / DEG_SCALE, lonI / DEG_SCALE, alt)
    }

    private fun decodeTakPacket(body: ByteArray, from: Int, time: Long, packetId: Int): Inbound? {
        var compressed = false
        var callsign: String? = null
        var deviceCallsign: String? = null
        var team: TeamColor? = null
        var role: TeamRole? = null
        var battery: Int? = null
        var latI: Int? = null; var lonI: Int? = null
        var alt = 0.0; var speed: Double? = null; var course: Double? = null
        var chat: String? = null

        val r = PbReader(body)
        while (r.hasMore()) {
            val key = r.readVarint().toInt()
            val wire = key and 7
            when (key ushr 3) {
                1 -> if (wire == 0) { compressed = r.readVarint() != 0L; continue }
                2 -> if (wire == 2) {                       // Contact
                    val c = r.sub()
                    while (c.hasMore()) {
                        val ck = c.readVarint().toInt()
                        when (ck ushr 3) {
                            1 -> if (ck and 7 == 2) { callsign = c.readString(); continue }
                            2 -> if (ck and 7 == 2) { deviceCallsign = c.readString(); continue }
                        }
                        c.skip(ck and 7)
                    }
                    continue
                }
                3 -> if (wire == 2) {                       // Group
                    val g = r.sub()
                    while (g.hasMore()) {
                        val gk = g.readVarint().toInt()
                        when (gk ushr 3) {
                            1 -> if (gk and 7 == 0) { role = roleFromCode(g.readVarint().toInt()); continue }
                            2 -> if (gk and 7 == 0) { team = teamFromCode(g.readVarint().toInt()); continue }
                        }
                        g.skip(gk and 7)
                    }
                    continue
                }
                4 -> if (wire == 2) {                       // Status
                    val s = r.sub()
                    while (s.hasMore()) {
                        val sk = s.readVarint().toInt()
                        if (sk ushr 3 == 1 && sk and 7 == 0) { battery = s.readVarint().toInt(); continue }
                        s.skip(sk and 7)
                    }
                    continue
                }
                5 -> if (wire == 2) {                       // PLI
                    val p = r.sub()
                    while (p.hasMore()) {
                        val pk = p.readVarint().toInt()
                        when (pk ushr 3) {
                            1 -> if (pk and 7 == 5) { latI = p.readFixed32(); continue }
                            2 -> if (pk and 7 == 5) { lonI = p.readFixed32(); continue }
                            3 -> if (pk and 7 == 0) { alt = p.readVarint().toDouble(); continue }
                            4 -> if (pk and 7 == 0) { speed = p.readVarint().toDouble(); continue }
                            5 -> if (pk and 7 == 0) { course = p.readVarint().toDouble(); continue }
                        }
                        p.skip(pk and 7)
                    }
                    continue
                }
                6 -> if (wire == 2) {                       // GeoChat
                    val g = r.sub()
                    while (g.hasMore()) {
                        val gk = g.readVarint().toInt()
                        if (gk ushr 3 == 1 && gk and 7 == 2) { chat = g.readString(); continue }
                        g.skip(gk and 7)
                    }
                    continue
                }
            }
            r.skip(wire)
        }

        // Compressed callsigns use a shared unishox2 dictionary we deliberately
        // don't carry. The numeric payload is unaffected, so the report is still
        // usable — the sender is just identified by node number instead of a
        // string we can't decode.
        if (compressed) { callsign = null; deviceCallsign = null }

        chat?.let { return Inbound.Text(from, callsign, it, time, packetId) }

        if (latI == null || lonI == null) return null
        return Inbound.TakPli(
            nodeNum = from, callsign = callsign, deviceCallsign = deviceCallsign,
            team = team, role = role, battery = battery,
            lat = latI / DEG_SCALE, lon = lonI / DEG_SCALE, alt = alt,
            speed = speed, course = course, timeMillis = time,
        )
    }

    // ---- CoT bridging --------------------------------------------------------

    /** Meshtastic's node-id convention: `!` + lowercase hex of the node number. */
    fun nodeId(nodeNum: Int): String = "!%08x".format(nodeNum)

    /**
     * A TAK position report as a CoT event.
     *
     * The uid is the sender's own device callsign when it supplied one, so a
     * teammate seen over both LoRa and IP is one contact rather than two. With
     * no device callsign we fall back to the node id, which is stable per radio.
     */
    fun toCotEvent(p: Inbound.TakPli, staleAfterMs: Long): CotEvent {
        val id = p.deviceCallsign?.takeIf { it.isNotBlank() } ?: "MESH-${nodeId(p.nodeNum)}"
        return CotEvent(
            uid = id,
            callsign = p.callsign?.takeIf { it.isNotBlank() } ?: nodeId(p.nodeNum),
            type = CotType("a-f-G-U-C"),
            lat = p.lat, lon = p.lon, hae = p.alt,
            timeMillis = p.timeMillis,
            staleMillis = p.timeMillis + staleAfterMs,
            teamName = p.team?.label, teamRole = p.role?.label,
            battery = p.battery,
            speed = p.speed, course = p.course,
            endpoint = "*:-1:lora",
        )
    }

    /**
     * A plain Meshtastic node as a CoT event — a radio with no TAK plugin is
     * still something on the ground worth seeing, so it is rendered as a
     * friendly ground contact with whatever name the mesh knows it by.
     */
    fun toCotEvent(nodeNum: Int, name: String?, lat: Double, lon: Double, alt: Double,
                   timeMillis: Long, staleAfterMs: Long): CotEvent = CotEvent(
        uid = "MESH-${nodeId(nodeNum)}",
        callsign = name?.takeIf { it.isNotBlank() } ?: nodeId(nodeNum),
        type = CotType("a-f-G"),
        lat = lat, lon = lon, hae = alt,
        timeMillis = timeMillis,
        staleMillis = timeMillis + staleAfterMs,
        endpoint = "*:-1:lora",
    )

    // ---- enum mapping --------------------------------------------------------

    /** `Team` enum → the ATAK palette. Written out rather than derived from
     *  ordinals so reordering [TeamColor] can't silently change the wire. */
    fun teamFromCode(code: Int): TeamColor? = when (code) {
        1 -> TeamColor.WHITE
        2 -> TeamColor.YELLOW
        3 -> TeamColor.ORANGE
        4 -> TeamColor.MAGENTA
        5 -> TeamColor.RED
        6 -> TeamColor.MAROON
        7 -> TeamColor.PURPLE
        8 -> TeamColor.DARK_BLUE
        9 -> TeamColor.BLUE
        10 -> TeamColor.CYAN
        11 -> TeamColor.TEAL
        12 -> TeamColor.GREEN
        13 -> TeamColor.DARK_GREEN
        14 -> TeamColor.BROWN
        else -> null
    }

    fun teamColorCode(label: String?): Int? = when (TeamColor.fromLabel(label)) {
        TeamColor.WHITE -> 1
        TeamColor.YELLOW -> 2
        TeamColor.ORANGE -> 3
        TeamColor.MAGENTA -> 4
        TeamColor.RED -> 5
        TeamColor.MAROON -> 6
        TeamColor.PURPLE -> 7
        TeamColor.DARK_BLUE -> 8
        TeamColor.BLUE -> 9
        TeamColor.CYAN -> 10
        TeamColor.TEAL -> 11
        TeamColor.GREEN -> 12
        TeamColor.DARK_GREEN -> 13
        TeamColor.BROWN -> 14
        null -> null
    }

    fun roleFromCode(code: Int): TeamRole? = when (code) {
        1 -> TeamRole.TEAM_MEMBER
        2 -> TeamRole.TEAM_LEAD
        3 -> TeamRole.HQ
        4 -> TeamRole.SNIPER
        5 -> TeamRole.MEDIC
        6 -> TeamRole.FORWARD_OBSERVER
        7 -> TeamRole.RTO
        8 -> TeamRole.K9
        else -> null
    }

    /**
     * `RegionCode` as the label Meshtastic itself uses. Unknown codes come back
     * as the raw number rather than a guess — a region this app doesn't know
     * is still a region the radio is legally operating under.
     */
    fun regionName(code: Int): String = when (code) {
        0 -> "UNSET"
        1 -> "US"; 2 -> "EU_433"; 3 -> "EU_868"; 4 -> "CN"; 5 -> "JP"
        6 -> "ANZ"; 7 -> "KR"; 8 -> "TW"; 9 -> "RU"; 10 -> "IN"
        11 -> "NZ_865"; 12 -> "TH"; 13 -> "LORA_24"; 14 -> "UA_433"; 15 -> "UA_868"
        16 -> "MY_433"; 17 -> "MY_919"; 18 -> "SG_923"; 19 -> "PH_433"
        20 -> "PH_868"; 21 -> "PH_915"; 22 -> "ANZ_433"; 23 -> "KZ_433"
        24 -> "KZ_863"; 25 -> "NP_865"; 26 -> "BR_902"
        else -> "Region $code"
    }

    /** `ModemPreset` as its Meshtastic label. */
    fun modemPresetName(code: Int): String = when (code) {
        0 -> "Long Fast"; 1 -> "Long Slow"; 2 -> "V Long Slow"
        3 -> "Medium Slow"; 4 -> "Medium Fast"; 5 -> "Short Slow"
        6 -> "Short Fast"; 7 -> "Long Moderate"; 8 -> "Short Turbo"
        9 -> "Long Turbo"
        else -> "Preset $code"
    }

    /** `Config.DeviceConfig.Role` as its Meshtastic label. */
    fun deviceRoleName(code: Int): String = when (code) {
        0 -> "Client"; 1 -> "Client Mute"; 2 -> "Router"; 3 -> "Router Client"
        4 -> "Repeater"; 5 -> "Tracker"; 6 -> "Sensor"; 7 -> "TAK"
        8 -> "Client Hidden"; 9 -> "Lost and Found"; 10 -> "TAK Tracker"
        11 -> "Router Late"; 12 -> "Client Base"
        else -> "Role $code"
    }

    fun teamRoleCode(label: String?): Int? =
        when (TeamRole.entries.firstOrNull { it.label.equals(label, ignoreCase = true) }) {
            TeamRole.TEAM_MEMBER -> 1
            TeamRole.TEAM_LEAD -> 2
            TeamRole.HQ -> 3
            TeamRole.SNIPER -> 4
            TeamRole.MEDIC -> 5
            TeamRole.FORWARD_OBSERVER -> 6
            TeamRole.RTO -> 7
            TeamRole.K9 -> 8
            null -> null
        }
}

// ---- protobuf primitives -----------------------------------------------------

/**
 * Minimal protobuf writer. Scalar setters skip zero/empty values, matching
 * proto3's "default values are not on the wire"; the `Always` variants force a
 * field that is meaningfully zero (a nonce, an equator crossing).
 */
internal class PbWriter {
    private val out = ByteArrayOutputStream(64)

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun raw(value: Long) {
        var v = value
        while (true) {
            if (v and 0x7FL.inv() == 0L) { out.write(v.toInt()); return }
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
    }

    private fun key(field: Int, wire: Int) = raw(((field shl 3) or wire).toLong())

    fun varint(field: Int, v: Long) { if (v == 0L) return; varintAlways(field, v) }
    fun varintAlways(field: Int, v: Long) { key(field, 0); raw(v) }

    fun bool(field: Int, v: Boolean) { if (v) { key(field, 0); raw(1) } }

    fun fixed32(field: Int, v: Int) { if (v == 0) return; fixed32Always(field, v) }
    fun fixed32Always(field: Int, v: Int) {
        key(field, 5)
        out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF); out.write((v ushr 24) and 0xFF)
    }

    fun string(field: Int, s: String?) {
        if (s.isNullOrEmpty()) return
        bytes(field, s.toByteArray(Charsets.UTF_8))
    }

    fun bytes(field: Int, b: ByteArray) {
        key(field, 2); raw(b.size.toLong()); out.write(b, 0, b.size)
    }

    fun message(field: Int, m: ByteArray) { if (m.isNotEmpty()) bytes(field, m) }
    fun messageAlways(field: Int, m: ByteArray) = bytes(field, m)
}

/** Minimal protobuf reader over a byte range. Throws on truncation; callers catch. */
internal class PbReader(private val buf: ByteArray, private var pos: Int, private val end: Int) {

    constructor(buf: ByteArray) : this(buf, 0, buf.size)

    fun hasMore(): Boolean = pos < end

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            require(pos < end) { "varint overrun" }
            val b = buf[pos++].toInt()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            require(shift < 64) { "varint too long" }
        }
    }

    fun readFixed32(): Int {
        require(pos + 4 <= end) { "fixed32 overrun" }
        val v = (buf[pos].toInt() and 0xFF) or
            ((buf[pos + 1].toInt() and 0xFF) shl 8) or
            ((buf[pos + 2].toInt() and 0xFF) shl 16) or
            ((buf[pos + 3].toInt() and 0xFF) shl 24)
        pos += 4
        return v
    }

    private fun readLen(): Pair<Int, Int> {
        val len = readVarint().toInt()
        require(len >= 0 && pos + len <= end) { "length-delimited overrun" }
        val off = pos
        pos += len
        return off to len
    }

    fun readString(): String {
        val (off, len) = readLen()
        return String(buf, off, len, Charsets.UTF_8)
    }

    fun readByteArray(): ByteArray {
        val (off, len) = readLen()
        return buf.copyOfRange(off, off + len)
    }

    /** A reader scoped to the next length-delimited field, without copying. */
    fun sub(): PbReader {
        val (off, len) = readLen()
        return PbReader(buf, off, off + len)
    }

    fun skip(wire: Int) {
        when (wire) {
            0 -> readVarint()
            1 -> { require(pos + 8 <= end) { "fixed64 overrun" }; pos += 8 }
            2 -> readLen()
            5 -> { require(pos + 4 <= end) { "fixed32 overrun" }; pos += 4 }
            else -> throw IllegalArgumentException("unsupported wire type $wire")
        }
    }
}
