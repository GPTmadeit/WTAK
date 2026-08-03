package com.atakwatch.minimap.model

/**
 * A schema-faithful Cursor-on-Target event — the atomic unit of situational
 * awareness across the TAK ecosystem. Core fields mirror the CoT base schema
 * (uid, type, point, time/stale); the optional fields mirror the standard
 * detail elements real ATAK PLI messages carry (`contact` endpoint, `__group`
 * team color + role, `status` battery, `track` course/speed).
 *
 * Serialisation lives in [com.atakwatch.minimap.net.TakProtocol], which emits
 * both CoT XML and TAK Protocol v1 protobuf.
 */
data class CotEvent(
    val uid: String,
    val callsign: String,
    val type: CotType,
    val lat: Double,
    val lon: Double,
    val hae: Double = 0.0,            // height above ellipsoid, metres
    val ce: Double = 9_999_999.0,     // circular error, metres
    val le: Double = 9_999_999.0,     // linear error, metres
    val timeMillis: Long = System.currentTimeMillis(),
    val staleMillis: Long = System.currentTimeMillis() + 120_000,
    val isSelf: Boolean = false,
    // ---- standard ATAK detail elements (all optional) ----
    val endpoint: String? = null,     // contact endpoint, e.g. "*:-1:stcp"
    val teamName: String? = null,     // __group name: ATAK team color ("Cyan", …)
    val teamRole: String? = null,     // __group role: "Team Member", "HQ", …
    val battery: Int? = null,         // status battery, percent
    val course: Double? = null,       // track course, degrees true
    val speed: Double? = null,        // track speed, m/s
    /** `<emergency type="…">` label, set on b-a-o-* alert events. */
    val emergency: String? = null,
    /** True on a stand-down (`b-a-o-can`). */
    val emergencyCancel: Boolean = false,
) {
    /** An active alert — rendered and announced differently from a contact. */
    val isEmergency: Boolean
        get() = EmergencyType.isEmergency(type.raw) && !EmergencyType.isCancel(type.raw)

    val affiliation: Affiliation get() = type.affiliation

    val teamColor: TeamColor? get() = TeamColor.fromLabel(teamName)

    /** CoT XML wire format (see [com.atakwatch.minimap.net.TakProtocol.buildXml]). */
    fun toCotXml(): String = com.atakwatch.minimap.net.TakProtocol.buildXml(this)
}
