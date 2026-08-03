package com.atakwatch.minimap.net

import com.atakwatch.minimap.model.CotEvent
import com.atakwatch.minimap.model.CotType
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * TAK Protocol Version 1 codec + CoT XML builder/parser.
 *
 * Wire formats verified against the official schemas in
 * `AndroidTacticalAssaultKit-CIV/commoncommo/core/impl/protobuf/`:
 *
 *   TakMessage { takControl = 1; cotEvent = 2 }
 *   CotEvent   { type=1 access=2 qos=3 opex=4 uid=5 sendTime=6 startTime=7
 *                staleTime=8 how=9 lat=10 lon=11 hae=12 ce=13 le=14 detail=15 }
 *   Detail     { xmlDetail=1 contact=2 group=3 precisionLocation=4 status=5
 *                takv=6 track=7 }
 *   Contact    { endpoint=1 callsign=2 }        Group { name=1 role=2 }
 *   Status     { battery=1 }                    Takv  { device=1 platform=2 os=3 version=4 }
 *   Track      { speed=1 course=2 }             PrecisionLocation { geopointsrc=1 altsrc=2 }
 *
 * Mesh SA framing (UDP multicast): 0xBF 0x01 0xBF + TakMessage bytes.
 * Stream framing (TCP after negotiation): 0xBF + varint(length) + TakMessage.
 *
 * The codec is hand-rolled (protobuf wire format is tiny) so the app has zero
 * heavyweight dependencies and the byte layout is fully auditable. Everything
 * here is pure JVM — unit-testable off-device.
 */
object TakProtocol {

    const val MAGIC: Byte = 0xBF.toByte()
    private const val UNKNOWN_ERR = 9_999_999.0

    /** ATAK stales PLI a few minutes out; never emit an unparseable far-future stale. */
    private const val MAX_STALE_AHEAD_MS = 365L * 24 * 3600 * 1000

    // ---------------------------------------------------------------- framing

    /** Mesh SA datagram: 0xBF 0x01 0xBF + TakMessage. */
    fun encodeMeshFrame(event: CotEvent): ByteArray {
        val msg = encodeTakMessage(event)
        val out = ByteArray(3 + msg.size)
        out[0] = MAGIC; out[1] = 0x01; out[2] = MAGIC
        msg.copyInto(out, 3)
        return out
    }

    /** Stream frame: 0xBF + varint(len) + TakMessage. */
    fun encodeStreamFrame(event: CotEvent): ByteArray {
        val msg = encodeTakMessage(event)
        val head = ByteArrayOutputStream()
        head.write(MAGIC.toInt())
        writeRawVarint(head, msg.size.toLong())
        head.write(msg, 0, msg.size)
        return head.toByteArray()
    }

    /**
     * Decode one received datagram: auto-detects TAK proto mesh frames vs legacy
     * CoT XML, exactly like ATAK's mesh listener. Returns null on anything
     * unusable (never throws — hostile packets must not crash the app).
     */
    fun decodeDatagram(data: ByteArray, length: Int): CotEvent? {
        if (length < 4) return null
        // Legacy XML: first non-whitespace byte is '<'
        var i = 0
        while (i < length && data[i].toInt().toChar().isWhitespace()) i++
        if (i < length && data[i] == '<'.code.toByte()) {
            return runCatching { parseXml(String(data, 0, length, Charsets.UTF_8)) }.getOrNull()
        }
        // TAK Protocol v1 mesh frame
        if (data[0] == MAGIC && data[1].toInt() == 0x01 && data[2] == MAGIC) {
            return runCatching { decodeTakMessage(data, 3, length) }.getOrNull()
        }
        return null
    }

    // ------------------------------------------------------------ proto write

    private fun writeRawVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            if (v and 0x7F.inv().toLong() == 0L) { out.write(v.toInt()); return }
            out.write(((v and 0x7F) or 0x80).toInt()); v = v ushr 7
        }
    }

    private fun tag(field: Int, wireType: Int) = (field shl 3) or wireType

    private fun writeString(out: ByteArrayOutputStream, field: Int, s: String?) {
        if (s.isNullOrEmpty()) return
        val bytes = s.toByteArray(Charsets.UTF_8)
        writeRawVarint(out, tag(field, 2).toLong())
        writeRawVarint(out, bytes.size.toLong())
        out.write(bytes, 0, bytes.size)
    }

    private fun writeUInt64(out: ByteArrayOutputStream, field: Int, v: Long) {
        if (v == 0L) return
        writeRawVarint(out, tag(field, 0).toLong())
        writeRawVarint(out, v)
    }

    private fun writeUInt32(out: ByteArrayOutputStream, field: Int, v: Int) {
        if (v == 0) return
        writeRawVarint(out, tag(field, 0).toLong())
        writeRawVarint(out, v.toLong())
    }

    private fun writeDouble(out: ByteArrayOutputStream, field: Int, d: Double) {
        writeRawVarint(out, tag(field, 1).toLong())
        var bits = java.lang.Double.doubleToLongBits(d)
        repeat(8) { out.write((bits and 0xFF).toInt()); bits = bits ushr 8 }
    }

    private fun writeMessage(out: ByteArrayOutputStream, field: Int, msg: ByteArray) {
        if (msg.isEmpty()) return
        writeRawVarint(out, tag(field, 2).toLong())
        writeRawVarint(out, msg.size.toLong())
        out.write(msg, 0, msg.size)
    }

    /** Encode a [CotEvent] as a TakMessage (cotEvent field only, like a mesh PLI). */
    fun encodeTakMessage(e: CotEvent): ByteArray {
        val detail = ByteArrayOutputStream().apply {
            // Contact { endpoint=1 callsign=2 }
            val contact = ByteArrayOutputStream().apply {
                writeString(this, 1, e.endpoint)
                writeString(this, 2, e.callsign)
            }.toByteArray()
            writeMessage(this, 2, contact)
            // Group { name=1 role=2 }
            if (e.teamName != null) {
                val group = ByteArrayOutputStream().apply {
                    writeString(this, 1, e.teamName)
                    writeString(this, 2, e.teamRole)
                }.toByteArray()
                writeMessage(this, 3, group)
            }
            // PrecisionLocation { geopointsrc=1 altsrc=2 } — GPS-derived self only
            if (e.isSelf) {
                val pl = ByteArrayOutputStream().apply {
                    writeString(this, 1, "GPS"); writeString(this, 2, "GPS")
                }.toByteArray()
                writeMessage(this, 4, pl)
            }
            // Status { battery=1 }
            if (e.battery != null) {
                val status = ByteArrayOutputStream().apply { writeUInt32(this, 1, e.battery) }.toByteArray()
                writeMessage(this, 5, status)
            }
            // Takv { device=1 platform=2 os=3 version=4 }
            if (e.isSelf && DeviceIdentity.initialized) {
                val takv = ByteArrayOutputStream().apply {
                    writeString(this, 1, DeviceIdentity.device)
                    writeString(this, 2, DeviceIdentity.platform)
                    writeString(this, 3, DeviceIdentity.os)
                    writeString(this, 4, DeviceIdentity.version)
                }.toByteArray()
                writeMessage(this, 6, takv)
            }
            // Track { speed=1 course=2 }
            if (e.course != null || e.speed != null) {
                val track = ByteArrayOutputStream().apply {
                    writeDouble(this, 1, e.speed ?: 0.0)
                    writeDouble(this, 2, e.course ?: 0.0)
                }.toByteArray()
                writeMessage(this, 7, track)
            }
        }.toByteArray()

        val now = e.timeMillis
        val stale = minOf(e.staleMillis, now + MAX_STALE_AHEAD_MS)
        val cot = ByteArrayOutputStream().apply {
            writeString(this, 1, e.type.raw)
            writeString(this, 5, e.uid)
            writeUInt64(this, 6, now)
            writeUInt64(this, 7, now)
            writeUInt64(this, 8, stale)
            writeString(this, 9, if (e.isSelf) "m-g" else "h-g-i-g-o")
            writeDouble(this, 10, e.lat)
            writeDouble(this, 11, e.lon)
            writeDouble(this, 12, e.hae)
            writeDouble(this, 13, e.ce)
            writeDouble(this, 14, e.le)
            writeMessage(this, 15, detail)
        }.toByteArray()

        return ByteArrayOutputStream().apply { writeMessage(this, 2, cot) }.toByteArray()
    }

    // ------------------------------------------------------------- proto read

    private class Reader(val buf: ByteArray, var pos: Int, val end: Int) {
        fun hasMore() = pos < end
        fun readVarint(): Long {
            var result = 0L; var shift = 0
            while (true) {
                require(pos < end) { "varint overrun" }
                val b = buf[pos++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                require(shift < 64) { "varint too long" }
            }
        }
        fun readDouble(): Double {
            require(pos + 8 <= end) { "double overrun" }
            var bits = 0L
            repeat(8) { i -> bits = bits or ((buf[pos + i].toLong() and 0xFF) shl (8 * i)) }
            pos += 8
            return java.lang.Double.longBitsToDouble(bits)
        }
        fun readBytes(): Pair<Int, Int> { // (offset, length)
            val len = readVarint().toInt()
            require(len >= 0 && pos + len <= end) { "bytes overrun" }
            val off = pos; pos += len
            return off to len
        }
        fun readString(): String {
            val (off, len) = readBytes()
            return String(buf, off, len, Charsets.UTF_8)
        }
        fun skip(wireType: Int) {
            when (wireType) {
                0 -> readVarint()
                1 -> { require(pos + 8 <= end); pos += 8 }
                2 -> readBytes()
                5 -> { require(pos + 4 <= end); pos += 4 }
                else -> throw IllegalArgumentException("unsupported wire type $wireType")
            }
        }
    }

    /** Decode a TakMessage (no framing). Returns null if there's no usable CotEvent. */
    fun decodeTakMessage(buf: ByteArray, offset: Int, end: Int): CotEvent? {
        val r = Reader(buf, offset, end)
        while (r.hasMore()) {
            val key = r.readVarint().toInt()
            val field = key ushr 3
            val wt = key and 7
            if (field == 2 && wt == 2) {
                val (off, len) = r.readBytes()
                return decodeCotEvent(buf, off, off + len)
            }
            r.skip(wt)
        }
        return null
    }

    private fun decodeCotEvent(buf: ByteArray, offset: Int, end: Int): CotEvent? {
        val r = Reader(buf, offset, end)
        var type: String? = null; var uid: String? = null
        var stale = 0L; var time = 0L
        var lat = 0.0; var lon = 0.0; var hae = 0.0
        var ce = UNKNOWN_ERR; var le = UNKNOWN_ERR
        var callsign: String? = null; var endpoint: String? = null
        var team: String? = null; var role: String? = null
        var battery: Int? = null; var course: Double? = null; var speed: Double? = null

        while (r.hasMore()) {
            val key = r.readVarint().toInt()
            when (val field = key ushr 3) {
                1 -> type = r.readString()
                5 -> uid = r.readString()
                6 -> time = r.readVarint()
                8 -> stale = r.readVarint()
                10 -> lat = r.readDouble()
                11 -> lon = r.readDouble()
                12 -> hae = r.readDouble()
                13 -> ce = r.readDouble()
                14 -> le = r.readDouble()
                15 -> {
                    val (off, len) = r.readBytes()
                    val d = Reader(buf, off, off + len)
                    while (d.hasMore()) {
                        val dk = d.readVarint().toInt()
                        when (dk ushr 3) {
                            1 -> { // xmlDetail — salvage a callsign if one is embedded
                                val xml = d.readString()
                                if (callsign == null) {
                                    callsign = Regex("callsign=\"([^\"]+)\"").find(xml)?.groupValues?.get(1)
                                }
                            }
                            2 -> { // Contact
                                val (o2, l2) = d.readBytes()
                                val c = Reader(buf, o2, o2 + l2)
                                while (c.hasMore()) {
                                    val ck = c.readVarint().toInt()
                                    when (ck ushr 3) {
                                        1 -> endpoint = c.readString()
                                        2 -> callsign = c.readString()
                                        else -> c.skip(ck and 7)
                                    }
                                }
                            }
                            3 -> { // Group
                                val (o2, l2) = d.readBytes()
                                val g = Reader(buf, o2, o2 + l2)
                                while (g.hasMore()) {
                                    val gk = g.readVarint().toInt()
                                    when (gk ushr 3) {
                                        1 -> team = g.readString()
                                        2 -> role = g.readString()
                                        else -> g.skip(gk and 7)
                                    }
                                }
                            }
                            5 -> { // Status
                                val (o2, l2) = d.readBytes()
                                val s = Reader(buf, o2, o2 + l2)
                                while (s.hasMore()) {
                                    val sk = s.readVarint().toInt()
                                    if (sk ushr 3 == 1) battery = s.readVarint().toInt() else s.skip(sk and 7)
                                }
                            }
                            7 -> { // Track
                                val (o2, l2) = d.readBytes()
                                val t = Reader(buf, o2, o2 + l2)
                                while (t.hasMore()) {
                                    val tk = t.readVarint().toInt()
                                    when (tk ushr 3) {
                                        1 -> speed = t.readDouble()
                                        2 -> course = t.readDouble()
                                        else -> t.skip(tk and 7)
                                    }
                                }
                            }
                            else -> d.skip(dk and 7)
                        }
                    }
                }
                else -> r.skip(key and 7)
            }
        }

        if (uid == null || type == null) return null
        return CotEvent(
            uid = uid, callsign = callsign ?: uid, type = CotType(type),
            lat = lat, lon = lon, hae = hae, ce = ce, le = le,
            timeMillis = if (time > 0) time else System.currentTimeMillis(),
            staleMillis = if (stale > 0) stale else System.currentTimeMillis() + 120_000,
            endpoint = endpoint, teamName = team, teamRole = role,
            battery = battery, course = course, speed = speed,
        )
    }

    // -------------------------------------------------------------------- XML

    private val isoFmt: SimpleDateFormat
        get() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }

    fun iso(millis: Long): String = isoFmt.format(Date(millis))

    private fun parseIso(s: String?): Long? = s?.let {
        runCatching { isoFmt.parse(it)?.time }.getOrNull()
    }

    fun escapeXml(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;")
            '"' -> append("&quot;"); '\'' -> append("&apos;"); else -> append(c)
        }
    }

    /**
     * Serialise a CoT event as the XML wire format, with the detail block laid
     * out the way ATAK's own PLI messages are (takv, contact+endpoint, uid,
     * precisionlocation, __group, status, track).
     */
    fun buildXml(e: CotEvent): String {
        val t = iso(e.timeMillis)
        val stale = iso(minOf(e.staleMillis, e.timeMillis + MAX_STALE_AHEAD_MS))
        val how = if (e.isSelf) "m-g" else "h-g-i-g-o"
        val cs = escapeXml(e.callsign)
        val sb = StringBuilder(768)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        sb.append("<event version=\"2.0\" uid=\"").append(escapeXml(e.uid))
            .append("\" type=\"").append(e.type.raw)
            .append("\" how=\"").append(how)
            .append("\" time=\"").append(t)
            .append("\" start=\"").append(t)
            .append("\" stale=\"").append(stale).append("\">")
        sb.append("<point lat=\"").append("%.7f".format(Locale.US, e.lat))
            .append("\" lon=\"").append("%.7f".format(Locale.US, e.lon))
            .append("\" hae=\"").append("%.1f".format(Locale.US, e.hae))
            .append("\" ce=\"").append(fmtErr(e.ce))
            .append("\" le=\"").append(fmtErr(e.le)).append("\"/>")
        sb.append("<detail>")
        if (e.isSelf && DeviceIdentity.initialized) {
            sb.append("<takv device=\"").append(escapeXml(DeviceIdentity.device))
                .append("\" platform=\"").append(escapeXml(DeviceIdentity.platform))
                .append("\" os=\"").append(escapeXml(DeviceIdentity.os))
                .append("\" version=\"").append(escapeXml(DeviceIdentity.version)).append("\"/>")
        }
        sb.append("<contact callsign=\"").append(cs).append('"')
        e.endpoint?.let { sb.append(" endpoint=\"").append(escapeXml(it)).append('"') }
        sb.append("/>")
        if (e.isSelf) {
            sb.append("<uid Droid=\"").append(cs).append("\"/>")
            sb.append("<precisionlocation geopointsrc=\"GPS\" altsrc=\"GPS\"/>")
        }
        e.teamName?.let {
            sb.append("<__group name=\"").append(escapeXml(it))
                .append("\" role=\"").append(escapeXml(e.teamRole ?: "Team Member")).append("\"/>")
        }
        e.battery?.let { sb.append("<status battery=\"").append(it).append("\"/>") }
        // Alert payload — what makes ATAK treat this as an emergency rather
        // than another marker.
        e.emergency?.let {
            sb.append("<emergency type=\"").append(escapeXml(it)).append('"')
            if (e.emergencyCancel) sb.append(" cancel=\"true\"")
            sb.append("/>")
        }
        if (e.course != null || e.speed != null) {
            sb.append("<track course=\"").append("%.1f".format(Locale.US, e.course ?: 0.0))
                .append("\" speed=\"").append("%.2f".format(Locale.US, e.speed ?: 0.0)).append("\"/>")
        }
        sb.append("</detail></event>")
        return sb.toString()
    }

    private fun fmtErr(v: Double) =
        if (v >= UNKNOWN_ERR) "9999999.0" else "%.1f".format(Locale.US, v)

    /**
     * Parse one CoT XML event (defensive: returns null on anything malformed).
     * Uses a small regex-free scanner over the attributes we care about via
     * XmlPullParser on Android; falls back cleanly off-device.
     */
    fun parseXml(xml: String): CotEvent? = runCatching {
        val parser = android.util.Xml.newPullParser()
        parser.setInput(java.io.StringReader(xml))
        var uid: String? = null; var type: String? = null; var stale: String? = null
        var lat = 0.0; var lon = 0.0; var hae = 0.0; var hasPoint = false
        var ce = UNKNOWN_ERR; var le = UNKNOWN_ERR
        var callsign: String? = null; var endpoint: String? = null
        var team: String? = null; var role: String? = null; var battery: Int? = null
        var emergency: String? = null; var emergencyCancel = false

        var ev = parser.eventType
        while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (ev == org.xmlpull.v1.XmlPullParser.START_TAG) {
                when (parser.name) {
                    "event" -> {
                        uid = parser.getAttributeValue(null, "uid")
                        type = parser.getAttributeValue(null, "type")
                        stale = parser.getAttributeValue(null, "stale")
                    }
                    "point" -> {
                        lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                        lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                        hae = parser.getAttributeValue(null, "hae")?.toDoubleOrNull() ?: 0.0
                        ce = parser.getAttributeValue(null, "ce")?.toDoubleOrNull() ?: UNKNOWN_ERR
                        le = parser.getAttributeValue(null, "le")?.toDoubleOrNull() ?: UNKNOWN_ERR
                        hasPoint = true
                    }
                    "contact" -> {
                        callsign = parser.getAttributeValue(null, "callsign") ?: callsign
                        endpoint = parser.getAttributeValue(null, "endpoint") ?: endpoint
                    }
                    "__group" -> {
                        team = parser.getAttributeValue(null, "name")
                        role = parser.getAttributeValue(null, "role")
                    }
                    "status" -> battery = parser.getAttributeValue(null, "battery")?.toIntOrNull()
                    "emergency" -> {
                        emergency = parser.getAttributeValue(null, "type")
                        emergencyCancel = parser.getAttributeValue(null, "cancel")
                            ?.equals("true", ignoreCase = true) ?: false
                    }
                }
            }
            ev = parser.next()
        }
        if (uid == null || type == null || !hasPoint) return null
        CotEvent(
            uid = uid, callsign = callsign ?: uid, type = CotType(type),
            lat = lat, lon = lon, hae = hae, ce = ce, le = le,
            staleMillis = parseIso(stale) ?: (System.currentTimeMillis() + 120_000),
            endpoint = endpoint, teamName = team, teamRole = role, battery = battery,
            emergency = emergency, emergencyCancel = emergencyCancel,
        )
    }.getOrNull()
}

/**
 * Identity of this device as a TAK client — fills the `takv` detail exactly the
 * way ATAK does (device model, platform name, OS API level, app version), and
 * the ATAK-convention `ANDROID-<androidId>` uid. Initialised once at app start;
 * the codec skips takv until then (keeps this object JVM-test safe).
 */
object DeviceIdentity {
    @Volatile var initialized: Boolean = false; private set
    var uid: String = "ANDROID-WATCH"; private set
    var device: String = "WATCH"; private set
    var platform: String = "ATAK-Watch"; private set
    var os: String = "0"; private set
    var version: String = "0"; private set

    fun init(context: android.content.Context, appVersion: String) {
        val androidId = android.provider.Settings.Secure.getString(
            context.contentResolver, android.provider.Settings.Secure.ANDROID_ID
        ) ?: "watch"
        uid = "ANDROID-$androidId"
        device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".uppercase()
        platform = "ATAK-Watch"
        os = android.os.Build.VERSION.SDK_INT.toString()
        version = appVersion
        initialized = true
    }
}
