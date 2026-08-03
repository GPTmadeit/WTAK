package com.atakwatch.minimap.data

import com.atakwatch.minimap.model.Geo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The track you have walked, for backtracking.
 *
 * Points are only kept when you have actually moved [MIN_SPACING_M], so standing
 * still doesn't fill the buffer with GPS jitter, and the trail is capped at
 * [MAX_POINTS] — on a watch this has to stay cheap to hold and cheap to draw.
 * The cap is a ring: the oldest point is dropped, so the trail always shows the
 * most recent stretch rather than stopping dead when it fills.
 */
object Breadcrumbs {

    private const val MIN_SPACING_M = 12.0
    private const val MAX_POINTS = 500

    private val _points = MutableStateFlow<List<Pair<Double, Double>>>(emptyList())
    val points: StateFlow<List<Pair<Double, Double>>> = _points.asStateFlow()

    /** @return true when the trail actually changed, so callers can skip redraws. */
    fun record(lat: Double, lon: Double): Boolean {
        val current = _points.value
        val last = current.lastOrNull()
        if (last != null && Geo.distanceMeters(last.first, last.second, lat, lon) < MIN_SPACING_M) {
            return false
        }
        val next = if (current.size >= MAX_POINTS) {
            current.drop(current.size - MAX_POINTS + 1) + (lat to lon)
        } else {
            current + (lat to lon)
        }
        _points.value = next
        return true
    }

    fun clear() { _points.value = emptyList() }

    /** Total distance walked along the recorded trail, in metres. */
    fun trackLengthMeters(): Double {
        val pts = _points.value
        if (pts.size < 2) return 0.0
        var total = 0.0
        for (i in 1 until pts.size) {
            total += Geo.distanceMeters(pts[i - 1].first, pts[i - 1].second, pts[i].first, pts[i].second)
        }
        return total
    }
}
