package com.atakwatch.minimap.model

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Great-circle range/bearing helpers plus tactical range/bearing formatting. */
object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return EARTH_RADIUS_M * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Initial true bearing from point 1 to point 2, degrees in [0, 360). */
    fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** Destination point given a start, a true bearing and a distance in metres. */
    fun destination(lat: Double, lon: Double, bearingDeg: Double, distM: Double): Pair<Double, Double> {
        val phi1 = Math.toRadians(lat)
        val lambda1 = Math.toRadians(lon)
        val theta = Math.toRadians(bearingDeg)
        val delta = distM / EARTH_RADIUS_M
        val phi2 = kotlin.math.asin(
            sin(phi1) * cos(delta) + cos(phi1) * sin(delta) * cos(theta)
        )
        val lambda2 = lambda1 + atan2(
            sin(theta) * sin(delta) * cos(phi1),
            cos(delta) - sin(phi1) * sin(phi2)
        )
        return Math.toDegrees(phi2) to Math.toDegrees(lambda2)
    }

    fun formatRange(meters: Double, imperial: Boolean): String = when {
        imperial -> {
            val ft = meters * 3.28084
            if (ft < 1000) "${ft.roundToInt()} ft" else "%.2f mi".format(ft / 5280.0)
        }
        meters < 1000 -> "${meters.roundToInt()} m"
        else -> "%.2f km".format(meters / 1000.0)
    }

    /** Compact age for a position report: "12s", "4m", "1h20m". */
    fun formatAge(millis: Long): String {
        val s = (millis / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m"
            else -> "${s / 3600}h${(s % 3600) / 60}m"
        }
    }

    /** e.g. "045°M" plus cardinal, ATAK-style magnetic-agnostic true bearing. */
    fun formatBearing(deg: Double): String {
        val d = ((deg % 360) + 360) % 360
        return "%03d° %s".format(d.roundToInt() % 360, cardinal(d))
    }

    private fun cardinal(deg: Double): String {
        val dirs = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return dirs[((deg + 22.5) / 45).toInt() % 8]
    }
}
