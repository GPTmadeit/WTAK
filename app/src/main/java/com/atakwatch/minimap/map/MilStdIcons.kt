package com.atakwatch.minimap.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.ui.graphics.toArgb
import com.atakwatch.minimap.model.Affiliation
import java.util.concurrent.ConcurrentHashMap

/**
 * Draws MIL-STD-2525-style marker icons at runtime (no PNG assets), matching the
 * ATAK affiliation conventions: friendly rectangle, hostile diamond, neutral
 * square, unknown quatrefoil. Bitmaps are cached by key so a busy map never
 * re-rasterises the same icon — the roster can grow without per-marker allocation.
 */
object MilStdIcons {

    private val cache = ConcurrentHashMap<String, Bitmap>()

    val waypointColor = androidx.compose.ui.graphics.Color(0xFF00E0C0)

    fun affiliationIcon(context: Context, aff: Affiliation, dp: Float = 26f): Bitmap =
        cache.getOrPut("aff:${aff.name}:$dp") { buildAffiliation(context, aff, dp) }

    fun selfIcon(context: Context, dp: Float = 30f): Bitmap =
        cache.getOrPut("self:$dp") { buildSelf(context, dp) }

    fun waypointIcon(context: Context, dp: Float = 24f): Bitmap =
        cache.getOrPut("wp:$dp") { buildWaypoint(context, dp) }

    /**
     * ATAK-style teammate marker: a filled circle in the contact's team color
     * with a white ring — how ATAK renders team members (team color trumps the
     * generic friendly frame).
     */
    fun teamIcon(context: Context, argb: Int, dp: Float = 24f): Bitmap =
        cache.getOrPut("team:$argb:$dp") { buildTeam(context, argb, dp) }

    private fun buildTeam(context: Context, argb: Int, dp: Float): Bitmap {
        val p = px(context, dp, 12)
        val bmp = Bitmap.createBitmap(p, p, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = p / 2f; val cy = p / 2f
        val r = p * 0.34f
        c.drawCircle(cx, cy, r + p * 0.10f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL; color = 0xCC0B0B0B.toInt()
        })
        c.drawCircle(cx, cy, r + p * 0.05f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL; color = 0xFFFFFFFF.toInt()
        })
        c.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL; color = argb
        })
        return bmp
    }

    private fun px(context: Context, dp: Float, min: Int) =
        (dp * context.resources.displayMetrics.density).toInt().coerceAtLeast(min)

    private fun buildAffiliation(context: Context, aff: Affiliation, dp: Float): Bitmap {
        val p = px(context, dp, 12)
        val bmp = Bitmap.createBitmap(p, p, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val color = aff.color.toArgb()
        val pad = p * 0.16f
        val r = RectF(pad, pad, p - pad, p - pad)

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color; alpha = 70 }
        val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; this.color = 0xCC0B0B0B.toInt(); strokeWidth = p * 0.10f }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; this.color = color; strokeWidth = p * 0.07f }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }

        val path = framePath(aff.frame, r)
        c.drawPath(path, halo)
        c.drawPath(path, fill)
        c.drawPath(path, stroke)
        c.drawCircle(p / 2f, p / 2f, p * 0.06f, dot)
        return bmp
    }

    private fun framePath(frame: Affiliation.Frame, r: RectF): Path {
        val p = Path()
        when (frame) {
            Affiliation.Frame.RECTANGLE -> { val rad = r.width() * 0.14f; p.addRoundRect(r, rad, rad, Path.Direction.CW) }
            Affiliation.Frame.SQUARE -> p.addRect(r, Path.Direction.CW)
            Affiliation.Frame.DIAMOND -> {
                val cx = r.centerX(); val cy = r.centerY()
                p.moveTo(cx, r.top); p.lineTo(r.right, cy); p.lineTo(cx, r.bottom); p.lineTo(r.left, cy); p.close()
            }
            Affiliation.Frame.QUATREFOIL -> {
                val cx = r.centerX(); val cy = r.centerY()
                val lobe = r.width() * 0.30f; val off = r.width() * 0.20f
                val union = Path()
                val centers = listOf(cx to (cy - off), (cx + off) to cy, cx to (cy + off), (cx - off) to cy)
                for ((i, ct) in centers.withIndex()) {
                    val lp = Path().apply { addCircle(ct.first, ct.second, lobe, Path.Direction.CW) }
                    if (i == 0) union.set(lp) else union.op(lp, Path.Op.UNION)
                }
                p.set(union)
            }
        }
        return p
    }

    private fun buildSelf(context: Context, dp: Float): Bitmap {
        val p = px(context, dp, 16)
        val bmp = Bitmap.createBitmap(p, p, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val blue = Affiliation.FRIEND.color.toArgb()
        val cx = p / 2f; val cy = p / 2f

        val wedge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = blue }
        Path().apply { moveTo(cx, p * 0.06f); lineTo(cx + p * 0.20f, cy); lineTo(cx - p * 0.20f, cy); close(); c.drawPath(this, wedge) }
        c.drawCircle(cx, cy, p * 0.24f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFFFFFFFF.toInt() })
        c.drawCircle(cx, cy, p * 0.18f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = blue })
        return bmp
    }

    private fun buildWaypoint(context: Context, dp: Float): Bitmap {
        val p = px(context, dp, 12)
        val bmp = Bitmap.createBitmap(p, p, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val wpColor = waypointColor.toArgb()
        val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xCC0B0B0B.toInt(); strokeWidth = p * 0.10f }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = wpColor; strokeWidth = p * 0.08f }
        // A downward pin/teardrop.
        val path = Path().apply {
            val cx = p / 2f
            moveTo(cx, p * 0.90f)
            cubicTo(p * 0.18f, p * 0.55f, p * 0.24f, p * 0.16f, cx, p * 0.16f)
            cubicTo(p * 0.76f, p * 0.16f, p * 0.82f, p * 0.55f, cx, p * 0.90f)
            close()
        }
        c.drawPath(path, halo)
        c.drawPath(path, stroke)
        c.drawCircle(p / 2f, p * 0.38f, p * 0.10f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = colorInt() })
        return bmp
    }

    private fun colorInt() = waypointColor.toArgb()
}
