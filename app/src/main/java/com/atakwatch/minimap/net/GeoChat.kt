package com.atakwatch.minimap.net

import android.util.Xml
import com.atakwatch.minimap.model.ChatMessage
import com.atakwatch.minimap.model.CotEvent
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.util.UUID

/**
 * GeoChat on the wire.
 *
 * ATAK carries chat as CoT type `b-t-f` with a `__chat` detail block and a
 * `<remarks>` body:
 *
 * ```
 * <event type="b-t-f" uid="GeoChat.<sender>.All Chat Rooms.<msgId>" …>
 *   <point …/>
 *   <detail>
 *     <__chat parent="RootContactGroup" groupOwner="false"
 *             messageId="<msgId>" chatroom="All Chat Rooms"
 *             id="All Chat Rooms" senderCallsign="ALPHA">
 *       <chatgrp uid0="<senderUid>" id="All Chat Rooms"/>
 *     </__chat>
 *     <link uid="<senderUid>" type="a-f-G-U-C" relation="p-p"/>
 *     <remarks source="BAO.F.ATAK.<senderUid>" to="All Chat Rooms">text</remarks>
 *   </detail>
 * </event>
 * ```
 *
 * Chat is deliberately *not* renderable on the map — it is a message, not a
 * contact — so it is filtered out of the entity pipeline and routed here.
 */
object GeoChat {

    const val COT_TYPE = "b-t-f"

    fun isChat(cotType: String): Boolean = cotType == COT_TYPE

    /**
     * Build a broadcast chat event. Position is included because ATAK expects a
     * point on every event; it also lets a recipient see where a message came
     * from, which is genuinely useful in the field.
     */
    fun build(text: String, self: CotEvent): String {
        val msgId = UUID.randomUUID().toString()
        val senderUid = DeviceIdentity.uid
        val room = ChatMessage.ALL_ROOMS
        val now = System.currentTimeMillis()
        val t = TakProtocol.iso(now)
        val stale = TakProtocol.iso(now + 86_400_000)   // chat lingers a day
        val esc = TakProtocol::escapeXml

        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
            append("<event version=\"2.0\" uid=\"GeoChat.$senderUid.$room.$msgId\"")
            append(" type=\"$COT_TYPE\" how=\"h-g-i-g-o\"")
            append(" time=\"$t\" start=\"$t\" stale=\"$stale\">")
            append("<point lat=\"${"%.7f".format(self.lat)}\" lon=\"${"%.7f".format(self.lon)}\"")
            append(" hae=\"${"%.1f".format(self.hae)}\" ce=\"9999999.0\" le=\"9999999.0\"/>")
            append("<detail>")
            append("<__chat parent=\"RootContactGroup\" groupOwner=\"false\"")
            append(" messageId=\"$msgId\" chatroom=\"$room\" id=\"$room\"")
            append(" senderCallsign=\"${esc(self.callsign)}\">")
            append("<chatgrp uid0=\"$senderUid\" id=\"$room\"/>")
            append("</__chat>")
            append("<link uid=\"$senderUid\" type=\"${self.type.raw}\" relation=\"p-p\"/>")
            append("<remarks source=\"BAO.F.ATAK.$senderUid\" to=\"$room\" time=\"$t\">")
            append(esc(text))
            append("</remarks>")
            append("</detail></event>")
        }
    }

    /**
     * Parse an inbound chat event. Returns null for anything that isn't a
     * usable chat message — never throws on malformed input.
     */
    fun parse(xml: String): ChatMessage? = runCatching {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))

        var type: String? = null
        var uid: String? = null
        var sender: String? = null
        var room = ChatMessage.ALL_ROOMS
        var messageId: String? = null
        var remarks: String? = null
        var senderUid: String? = null

        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "event" -> {
                        type = parser.getAttributeValue(null, "type")
                        uid = parser.getAttributeValue(null, "uid")
                    }
                    "__chat" -> {
                        sender = parser.getAttributeValue(null, "senderCallsign")
                        parser.getAttributeValue(null, "chatroom")?.let { room = it }
                        messageId = parser.getAttributeValue(null, "messageId")
                    }
                    "chatgrp" -> senderUid = parser.getAttributeValue(null, "uid0")
                    "link" -> if (senderUid == null) senderUid = parser.getAttributeValue(null, "uid")
                    "remarks" -> {
                        // source is "BAO.F.ATAK.<uid>" — a fallback when the
                        // sender omitted chatgrp/link.
                        if (senderUid == null) {
                            senderUid = parser.getAttributeValue(null, "source")
                                ?.substringAfterLast('.')?.takeIf { it.isNotBlank() }
                        }
                        remarks = parser.nextText()
                    }
                }
            }
            ev = parser.next()
        }

        if (type != COT_TYPE) return null
        val body = remarks?.trim().orEmpty()
        if (body.isEmpty()) return null

        ChatMessage(
            id = messageId ?: uid ?: UUID.randomUUID().toString(),
            senderCallsign = sender ?: "UNKNOWN",
            text = body,
            timeMillis = System.currentTimeMillis(),
            room = room,
            senderUid = senderUid,
        )
    }.getOrNull()
}
