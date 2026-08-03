package com.atakwatch.minimap.ui.radar

/**
 * Scope maths for the radar view. Pure functions, kept out of the composable so
 * the ranging behaviour can be unit-tested off-device — a scope that silently
 * picks the wrong range is a scope that lies about how far away your team is.
 */
object RadarGeometry {

    /**
     * Selectable scope ranges in metres. Chosen as a 1–2–5 ladder so each step
     * is a recognisable jump rather than an arbitrary number, from a room-sized
     * scope out to a 50 km one (well past LoRa's practical reach, but a TAK
     * server can put a contact anywhere).
     */
    val LADDER_METERS: List<Double> = listOf(
        50.0, 100.0, 200.0, 500.0,
        1_000.0, 2_000.0, 5_000.0,
        10_000.0, 20_000.0, 50_000.0,
    )

    /** Scope range used before anything has been heard from. */
    const val DEFAULT_RANGE_M = 200.0

    /**
     * Smallest ladder rung that keeps the furthest contact inside the outer ring
     * with a little headroom, so a contact drifting outward doesn't flick on and
     * off the rim. With nothing to show, stays at [DEFAULT_RANGE_M].
     */
    fun autoRange(furthestMeters: Double?): Double {
        val d = furthestMeters ?: return DEFAULT_RANGE_M
        if (!d.isFinite() || d <= 0) return DEFAULT_RANGE_M
        val needed = d * 1.15
        return LADDER_METERS.firstOrNull { it >= needed } ?: LADDER_METERS.last()
    }

    /** One rung in or out, clamped at the ends of the ladder. */
    fun step(current: Double, zoomIn: Boolean): Double {
        val index = LADDER_METERS.indexOfFirst { it >= current - 0.001 }
            .let { if (it < 0) LADDER_METERS.lastIndex else it }
        val next = if (zoomIn) index - 1 else index + 1
        return LADDER_METERS[next.coerceIn(0, LADDER_METERS.lastIndex)]
    }

    /**
     * Ring radii as fractions of the scope radius: two dashed references inside
     * a solid outer ring, matching how a range scope is conventionally read.
     */
    val RING_FRACTIONS: List<Float> = listOf(0.34f, 0.67f, 1.0f)

    /**
     * Where a contact sits on the scope, as a fraction of the scope radius.
     * Anything beyond the outer ring is pinned to the rim — an off-scope contact
     * still has a bearing worth showing, and pretending it isn't there is worse
     * than showing it clamped.
     */
    fun radiusFraction(rangeMeters: Double, scopeRangeMeters: Double): Float {
        if (scopeRangeMeters <= 0) return 0f
        return (rangeMeters / scopeRangeMeters).coerceIn(0.0, 1.0).toFloat()
    }

    fun isOffScope(rangeMeters: Double, scopeRangeMeters: Double): Boolean =
        rangeMeters > scopeRangeMeters

    /**
     * Screen angle for a contact, in degrees clockwise from straight up.
     * In track-up the whole scope counter-rotates by your heading, so what is in
     * front of you is at the top of the display.
     */
    fun screenAngle(bearingDeg: Double, headingDeg: Float?, trackUp: Boolean): Float {
        val rotation = if (trackUp) (headingDeg ?: 0f).toDouble() else 0.0
        return (((bearingDeg - rotation) % 360.0 + 360.0) % 360.0).toFloat()
    }

    /**
     * Radar-scope brightness for a blip that was last painted by the sweep at
     * [sweepDeg]: brightest just behind the arm, fading to a floor over one
     * revolution. Never reaches zero — a contact you can't see is a contact you
     * will forget about.
     */
    fun sweepIntensity(blipAngleDeg: Float, sweepDeg: Float, floor: Float = 0.45f): Float {
        val behind = ((sweepDeg - blipAngleDeg) % 360f + 360f) % 360f
        val fresh = 1f - (behind / 360f)
        return floor + (1f - floor) * fresh * fresh
    }
}
