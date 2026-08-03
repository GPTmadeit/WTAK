package com.atakwatch.minimap.map

import com.atakwatch.minimap.data.CoordFormat
import mil.nga.grid.features.Point
import mil.nga.mgrs.MGRS

/** Formats a position as MGRS (ATAK default) or decimal Lat/Lon. */
object CoordinateFormatter {

    fun format(lat: Double, lon: Double, format: CoordFormat): String = when (format) {
        CoordFormat.MGRS -> toMgrs(lat, lon)
        CoordFormat.LATLON -> "%.5f, %.5f".format(lat, lon)
    }

    /** Grouped MGRS, e.g. "18T WL 83600 07039" — falls back to Lat/Lon on error. */
    fun toMgrs(lat: Double, lon: Double): String = runCatching {
        val raw = MGRS.from(Point.point(lon, lat)).coordinate() // e.g. 18TWL8360007039
        val m = Regex("^(\\d{1,2}[C-X])([A-Z]{2})(\\d+)$").find(raw)
        if (m != null) {
            val (gzd, sq, digits) = m.destructured
            val half = digits.length / 2
            "$gzd $sq ${digits.substring(0, half)} ${digits.substring(half)}"
        } else raw
    }.getOrElse { "%.5f, %.5f".format(lat, lon) }
}
