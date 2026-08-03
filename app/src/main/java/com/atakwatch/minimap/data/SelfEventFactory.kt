package com.atakwatch.minimap.data

import android.content.Context
import android.location.Location
import android.os.BatteryManager
import com.atakwatch.minimap.model.CotEvent
import com.atakwatch.minimap.model.CotType
import com.atakwatch.minimap.net.DeviceIdentity

/**
 * Builds the self PLI event exactly the way an ATAK EUD does: ANDROID-<id> uid,
 * GPS-derived accuracy in ce/le, battery in `status`, course/speed in `track`
 * when moving, and team color + role in `__group`.
 */
object SelfEventFactory {

    fun build(context: Context, loc: Location, s: Settings): CotEvent {
        val acc = if (loc.hasAccuracy()) loc.accuracy.toDouble() else 9_999_999.0
        return CotEvent(
            uid = DeviceIdentity.uid,
            callsign = s.callsign,
            type = CotType.self(s.selfAffiliation),
            lat = loc.latitude,
            lon = loc.longitude,
            hae = loc.altitude,
            ce = acc,
            le = if (loc.hasVerticalAccuracy()) loc.verticalAccuracyMeters.toDouble() else acc,
            staleMillis = System.currentTimeMillis() + 120_000,
            isSelf = true,
            endpoint = "*:-1:stcp",
            teamName = s.teamColor.label,
            teamRole = s.teamRole.label,
            battery = batteryPercent(context),
            course = if (loc.hasBearing()) loc.bearing.toDouble() else null,
            speed = if (loc.hasSpeed()) loc.speed.toDouble() else null,
        )
    }

    private fun batteryPercent(context: Context): Int? {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (pct in 1..100) pct else null
    }
}
