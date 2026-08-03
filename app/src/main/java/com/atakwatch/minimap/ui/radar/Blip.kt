package com.atakwatch.minimap.ui.radar

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.atakwatch.minimap.model.CotEvent
import com.atakwatch.minimap.model.Geo
import com.atakwatch.minimap.ui.map.entityColor
import kotlin.math.cos
import kotlin.math.sin

/**
 * One contact reduced to what a scope actually needs: how far, which way, and
 * how much to trust it. Everything the radar draws comes from this, so the
 * plotting pass never touches the CoT model or does trigonometry twice.
 */
data class Blip(
    val uid: String,
    val callsign: String,
    val color: Color,
    val rangeMeters: Double,
    val bearingDeg: Double,
    /** Degrees clockwise from the top of the display, orientation applied. */
    val angleDeg: Float,
    /** Distance from centre as a fraction of the scope radius, clamped to the rim. */
    val fraction: Float,
    val offScope: Boolean,
    val emergency: Boolean,
    val isWaypoint: Boolean,
    val ageMillis: Long,
) {
    /** A contact that has stopped reporting fades rather than vanishing. */
    val stale: Boolean get() = ageMillis > STALE_AFTER_MS

    fun offset(centre: Offset, scopeRadius: Float): Offset {
        val radians = Math.toRadians(angleDeg.toDouble())
        val r = scopeRadius * fraction
        return Offset(
            centre.x + (r * sin(radians)).toFloat(),
            centre.y - (r * cos(radians)).toFloat(),
        )
    }

    companion object {
        const val STALE_AFTER_MS = 90_000L

        /**
         * Plot every entity relative to [self]. Sorted nearest-first so the
         * closest contacts are drawn last and end up on top — under load, the
         * thing standing next to you matters more than the thing 2 km away.
         */
        fun plot(
            self: CotEvent,
            entities: List<CotEvent>,
            scopeRangeMeters: Double,
            headingDeg: Float?,
            trackUp: Boolean,
            nowMillis: Long,
        ): List<Blip> = entities
            .map { e ->
                val range = Geo.distanceMeters(self.lat, self.lon, e.lat, e.lon)
                val bearing = Geo.bearingDegrees(self.lat, self.lon, e.lat, e.lon)
                Blip(
                    uid = e.uid,
                    callsign = e.callsign,
                    color = entityColor(e),
                    rangeMeters = range,
                    bearingDeg = bearing,
                    angleDeg = RadarGeometry.screenAngle(bearing, headingDeg, trackUp),
                    fraction = RadarGeometry.radiusFraction(range, scopeRangeMeters),
                    offScope = RadarGeometry.isOffScope(range, scopeRangeMeters),
                    emergency = e.isEmergency,
                    isWaypoint = e.type.isWaypoint,
                    ageMillis = (nowMillis - e.timeMillis).coerceAtLeast(0),
                )
            }
            .sortedByDescending { it.rangeMeters }
    }
}
