package com.atakwatch.minimap.ui.radar

import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas

/**
 * The scope's rendering. Split out from the screen so the drawing reads as one
 * continuous piece and the composable stays about state, gestures and layout.
 *
 * Colours follow the map's language — team colour for you, MIL-STD affiliation
 * colours for everyone else — on the near-black background a round OLED renders
 * as genuinely off, which is both the classic scope look and the cheapest thing
 * a watch can display.
 */
object RadarPalette {
    val Background = Color(0xFF04070A)
    val RingOuter = Color(0xFF44566A)
    val RingInner = Color(0x8848627A)
    val Cardinal = Color(0xFF6E8296)
    val CardinalNorth = Color(0xFFB8C9D9)
    val Label = Color(0xFFC7D4E0)
    val Dim = Color(0xFF7E8C9A)
}

/** Concentric range rings: dashed references inside a solid outer boundary. */
fun DrawScope.drawScopeRings(centre: Offset, scopeRadius: Float, minimal: Boolean) {
    val dash = PathEffect.dashPathEffect(floatArrayOf(3f, 9f), 0f)
    RadarGeometry.RING_FRACTIONS.forEachIndexed { index, fraction ->
        val outer = index == RadarGeometry.RING_FRACTIONS.lastIndex
        // Always-on draws the boundary only: fewer lit pixels, less burn-in.
        if (minimal && !outer) return@forEachIndexed
        drawCircle(
            color = if (outer) RadarPalette.RingOuter else RadarPalette.RingInner,
            radius = scopeRadius * fraction,
            center = centre,
            style = Stroke(
                width = if (outer) 1.6f else 1.4f,
                pathEffect = if (outer) null else dash,
            ),
        )
    }
}

/**
 * Cardinal ticks just outside the boundary, with north called out. In track-up
 * these rotate, which is the only cue that the scope is no longer north-aligned.
 */
fun DrawScope.drawCardinals(centre: Offset, scopeRadius: Float, rotationDeg: Float) {
    for (i in 0 until 4) {
        val angle = i * 90f - rotationDeg
        val north = i == 0
        rotate(degrees = angle, pivot = centre) {
            val top = centre.y - scopeRadius
            drawLine(
                color = if (north) RadarPalette.CardinalNorth else RadarPalette.Cardinal,
                start = Offset(centre.x, top - (if (north) 9f else 5f)),
                end = Offset(centre.x, top - 1f),
                strokeWidth = if (north) 2.5f else 1.6f,
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * The sweep arm: a quarter-turn of accent fading to nothing behind it, so the
 * eye reads direction of travel without an arrowhead.
 */
fun DrawScope.drawSweep(centre: Offset, scopeRadius: Float, sweepDeg: Float, accent: Color) {
    val topLeft = Offset(centre.x - scopeRadius, centre.y - scopeRadius)
    val box = Size(scopeRadius * 2, scopeRadius * 2)
    val segmentSpan = TRAIL_DEGREES / TRAIL_SEGMENTS

    // Built from stacked wedges rather than a sweep gradient: a sweepGradient is
    // anchored to the layer, not to the arc, so rotating it drags the ramp out
    // of alignment and the trail renders as a flat opaque quarter-circle.
    // drawArc measures from 3 o'clock, so a bearing is that angle minus 90°.
    for (i in 0 until TRAIL_SEGMENTS) {
        val fade = 1f - i.toFloat() / TRAIL_SEGMENTS
        val alpha = TRAIL_ALPHA * fade * fade
        drawArc(
            color = accent.copy(alpha = alpha),
            startAngle = sweepDeg - 90f - (i + 1) * segmentSpan,
            // Butted, never overlapped: translucent wedges that overlap compound
            // their alpha and the trail reads as a set of bright radial stripes.
            sweepAngle = segmentSpan,
            useCenter = true,
            topLeft = topLeft,
            size = box,
        )
    }

    // The arm itself, brightest at the rim where the newest contacts appear.
    rotate(degrees = sweepDeg, pivot = centre) {
        drawLine(
            brush = Brush.verticalGradient(
                colors = listOf(accent.copy(alpha = 0.55f), accent.copy(alpha = 0.03f)),
                startY = centre.y - scopeRadius,
                endY = centre.y,
            ),
            start = Offset(centre.x, centre.y - scopeRadius),
            end = centre,
            strokeWidth = 1.6f,
        )
    }
}

private const val TRAIL_DEGREES = 90f
private const val TRAIL_SEGMENTS = 30
private const val TRAIL_ALPHA = 0.17f

/** You, at the centre, with a wedge showing which way you're facing. */
fun DrawScope.drawSelf(
    centre: Offset,
    color: Color,
    headingDeg: Float?,
    trackUp: Boolean,
    minimal: Boolean,
) {
    if (headingDeg != null && !minimal) {
        // In track-up the scope already rotated, so forward is always up.
        val facing = if (trackUp) 0f else headingDeg
        rotate(degrees = facing, pivot = centre) {
            val path = Path().apply {
                moveTo(centre.x, centre.y - 17f)
                lineTo(centre.x - 5.5f, centre.y - 4f)
                lineTo(centre.x + 5.5f, centre.y - 4f)
                close()
            }
            drawPath(path, color = color.copy(alpha = 0.55f))
        }
    }
    if (!minimal) drawCircle(color.copy(alpha = 0.18f), radius = 11f, center = centre)
    drawCircle(color, radius = 4.5f, center = centre)
    drawCircle(RadarPalette.Background, radius = 1.6f, center = centre)
}

/**
 * One contact. Live contacts are filled and painted by the sweep; stale ones go
 * hollow, which reads as "last known" at a glance instead of hiding the fact.
 * Off-scope contacts become a chevron on the rim pointing the way out.
 */
fun DrawScope.drawBlip(
    blip: Blip,
    centre: Offset,
    scopeRadius: Float,
    sweepDeg: Float,
    sweeping: Boolean,
    selected: Boolean,
    pulse: Float,
    /** 0 while a contact is still arriving, 1 once it has settled. */
    emergence: Float = 1f,
) {
    val at = blip.offset(centre, scopeRadius)
    val intensity = if (sweeping) RadarGeometry.sweepIntensity(blip.angleDeg, sweepDeg) else 1f
    val alpha = (if (blip.stale) 0.45f else 1f) * intensity * emergence
    val colour = blip.color.copy(alpha = alpha.coerceIn(0f, 1f))
    // Overshoots slightly before settling, so a contact joining the net reads
    // as an arrival rather than as a redraw.
    val grow = emergence.coerceIn(0f, 1.15f)

    if (blip.emergency) {
        // An alert can't wait for the sweep to come round to it.
        drawCircle(
            color = Color(0xFFE23B3B).copy(alpha = (0.55f * (1f - pulse)).coerceIn(0f, 1f)),
            radius = 7f + pulse * 16f,
            center = at,
            style = Stroke(width = 2f),
        )
    }

    if (blip.offScope) {
        rotate(degrees = blip.angleDeg, pivot = centre) {
            val tip = Offset(centre.x, centre.y - scopeRadius - 2f)
            val path = Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(tip.x - 4.5f, tip.y + 6.5f)
                lineTo(tip.x + 4.5f, tip.y + 6.5f)
                close()
            }
            drawPath(path, color = colour)
        }
        return
    }

    when {
        blip.isWaypoint -> {
            // Waypoints are places, not people — a diamond keeps them separable
            // from contacts at 5 px.
            val r = 4.5f * grow
            val path = Path().apply {
                moveTo(at.x, at.y - r); lineTo(at.x + r, at.y)
                lineTo(at.x, at.y + r); lineTo(at.x - r, at.y); close()
            }
            if (blip.stale) drawPath(path, colour, style = Stroke(width = 1.6f))
            else drawPath(path, colour)
        }
        blip.stale ->
            drawCircle(colour, radius = 4f * grow, center = at, style = Stroke(width = 1.6f))
        else -> {
            drawCircle(colour.copy(alpha = alpha * 0.25f), radius = 8.5f * grow, center = at)
            drawCircle(colour, radius = 4f * grow, center = at)
        }
    }

    if (selected) {
        // Lock brackets, so the navigated contact is unmistakable. They close in
        // from outside as the target is taken.
        val r = 10f + (1f - emergence.coerceIn(0f, 1f)) * 10f
        val arm = 4f
        listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f).forEach { (sx, sy) ->
            drawLine(Color.White, Offset(at.x + sx * r, at.y + sy * r),
                Offset(at.x + sx * (r - arm), at.y + sy * r), strokeWidth = 1.6f)
            drawLine(Color.White, Offset(at.x + sx * r, at.y + sy * r),
                Offset(at.x + sx * r, at.y + sy * (r - arm)), strokeWidth = 1.6f)
        }
    }
}

/** A hairline from you to the contact you're navigating to. */
fun DrawScope.drawNavLine(centre: Offset, blip: Blip, scopeRadius: Float, accent: Color) {
    drawLine(
        color = accent.copy(alpha = 0.5f),
        start = centre,
        end = blip.offset(centre, scopeRadius),
        strokeWidth = 1.4f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f),
    )
}

/**
 * Callsign beside a blip.
 *
 * A label that lands on the range readout, on your own marker, or on another
 * callsign is worse than no label — it makes both unreadable. Candidate
 * positions are tried in order and the label is simply dropped if none is
 * clear; the blip itself is always drawn, and tapping it still identifies it.
 *
 * [reserved] accumulates what has been claimed, so callers pass the same list
 * through every blip and seed it with the HUD's own regions.
 */
fun DrawScope.drawBlipLabel(
    blip: Blip,
    centre: Offset,
    scopeRadius: Float,
    paint: Paint,
    reserved: MutableList<Rect>,
) {
    val at = blip.offset(centre, scopeRadius)
    val text = blip.callsign.take(10)
    val width = paint.measureText(text)
    val height = paint.textSize

    // Right of the blip, then left, then below — whichever is clear first.
    val candidates = listOf(
        Offset(at.x + 9f, at.y + height * 0.35f),
        Offset(at.x - 9f - width, at.y + height * 0.35f),
        Offset(at.x - width / 2f, at.y + 9f + height),
    )
    val spot = candidates.firstOrNull { c ->
        val box = Rect(c.x - 2f, c.y - height, c.x + width + 2f, c.y + 3f)
        box.left >= 2f && box.right <= size.width - 2f &&
            box.top >= 0f && box.bottom <= size.height &&
            reserved.none { it.overlaps(box) }
    } ?: return

    reserved.add(Rect(spot.x - 2f, spot.y - height, spot.x + width + 2f, spot.y + 3f))
    paint.alpha = if (blip.stale) 110 else 215
    drawContext.canvas.nativeCanvas.drawText(text, spot.x, spot.y, paint)
}
