package com.atakwatch.minimap.bridge

import com.atakwatch.minimap.model.Affiliation
import com.atakwatch.minimap.model.TeamColor
import com.atakwatch.minimap.model.TeamRole
import org.json.JSONObject

/**
 * Wire contract between an EUD phone running ATAK and this watch, carried over
 * the Wearable Data Layer.
 *
 * Split by transport on purpose:
 *  - **DataClient** ([PATH_IDENTITY]) carries the operator's identity and server
 *    configuration. Data items persist and re-sync, so the watch can be
 *    onboarded even if it was off when the phone published.
 *  - **MessageClient** ([PATH_COT]) carries live CoT events. Messages are
 *    fire-and-forget and low latency, which is what position reports want.
 *
 * The payload deliberately mirrors ATAK's own preference keys
 * (`locationCallsign`, `locationTeam`, `atakRoleType`, `locationUnitType` in
 * `com.atakmap.app_preferences`) so the phone side is a direct read of what the
 * operator already configured in ATAK — no separate setup to keep in sync.
 */
object EudProtocol {

    /** Capability the phone advertises so the watch can find a usable EUD. */
    const val CAPABILITY = "atak_eud_bridge"

    /** DataItem path: operator identity + server config. */
    const val PATH_IDENTITY = "/atak/identity"

    /** Message path: a single CoT event, XML or TAK protobuf. */
    const val PATH_COT = "/atak/cot"

    /** Message path: watch asks the phone to publish a fresh identity item. */
    const val PATH_REQUEST_SYNC = "/atak/request-sync"

    const val KEY_PAYLOAD = "payload"

    /**
     * What the phone tells the watch about the operator. Every field is optional
     * so an older or partial phone build still onboards what it can.
     */
    data class Identity(
        val callsign: String? = null,
        val team: String? = null,
        val role: String? = null,
        val cotType: String? = null,
        val uid: String? = null,
        val serverHost: String? = null,
        val serverPort: Int? = null,
        val tlsPort: Int? = null,
        val enrollPort: Int? = null,
        val atakVersion: String? = null,
    ) {
        val teamColor: TeamColor? get() = TeamColor.fromLabel(team)

        val teamRole: TeamRole?
            get() = role?.let { r -> TeamRole.entries.firstOrNull { it.label.equals(r, true) } }

        /** Affiliation implied by the self CoT type, e.g. `a-f-G-U-C` -> friendly. */
        val affiliation: Affiliation?
            get() = cotType?.split("-")?.getOrNull(1)?.firstOrNull()?.let { Affiliation.fromCode(it) }

        /** "host:port" for the plain streaming input, or null if unset. */
        val hostPort: String?
            get() = serverHost?.takeIf { it.isNotBlank() }?.let { "$it:${serverPort ?: 8087}" }

        fun toJson(): String = JSONObject().apply {
            callsign?.let { put("callsign", it) }
            team?.let { put("team", it) }
            role?.let { put("role", it) }
            cotType?.let { put("cotType", it) }
            uid?.let { put("uid", it) }
            serverHost?.let { put("serverHost", it) }
            serverPort?.let { put("serverPort", it) }
            tlsPort?.let { put("tlsPort", it) }
            enrollPort?.let { put("enrollPort", it) }
            atakVersion?.let { put("atakVersion", it) }
        }.toString()

        companion object {
            fun fromJson(json: String): Identity? = runCatching {
                val o = JSONObject(json)
                fun str(k: String) = if (o.has(k)) o.optString(k).takeIf { it.isNotBlank() } else null
                fun int(k: String) = if (o.has(k)) o.optInt(k).takeIf { it > 0 } else null
                Identity(
                    callsign = str("callsign"),
                    team = str("team"),
                    role = str("role"),
                    cotType = str("cotType"),
                    uid = str("uid"),
                    serverHost = str("serverHost"),
                    serverPort = int("serverPort"),
                    tlsPort = int("tlsPort"),
                    enrollPort = int("enrollPort"),
                    atakVersion = str("atakVersion"),
                )
            }.getOrNull()
        }
    }
}
