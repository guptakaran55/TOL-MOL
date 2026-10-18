package com.signalscope.app.trading

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface

/**
 * THE MACD CHART AS A PICTURE, for the notification shade.
 *
 * Drawn to Discovery's large-chart recipe — red dashed zero line, a fill that
 * fades from the line to zero, the curve in its phase colour, a bold dot on
 * today and a faint one on the first day — so the shade and the app show the
 * same shape. The geometry is [MacdChartGeometry]; this only paints it.
 *
 * Two sizes: the wide chart for the expanded notification, and a small square
 * for the collapsed one, where the system shows a picture beside the title.
 */
object MacdChartBitmap {

    private fun isDark(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /**
     * The wide chart, with a one-line caption so the picture explains itself —
     * a notification is read without the screen around it that would otherwise.
     *
     * [velPct] is the short-term price velocity (see [PriceVelocity]), shown as a
     * coloured dot with its figure; [velNote] says which candle it is — "15m to
     * 20:45" — so an evening reading of the close never passes for "now".
     */
    fun wide(ctx: Context, curve: List<Double>, phase: String?, days: Int,
             velPct: Double? = null, velNote: String? = null,
             slopes: List<MacdSlopes.Reading>? = null): Bitmap? = try {
        // A row for the five slope dots under the plot when there are any.
        val rowH = if (slopes.isNullOrEmpty()) 0 else 64
        val w = 960; val h = 300 + rowH
        val dark = isDark(ctx)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (dark) 0xFF1E293B.toInt() else 0xFFF1F5F9.toInt()
        }
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 18f, 18f, bg)

        val captionH = 52f
        val col = MacdChartGeometry.phaseColor(phase)
        val cap = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 30f
            color = if (dark) 0xFF94A3B8.toInt() else 0xFF64748B.toInt()
        }
        c.drawText("Daily MACD · ${days}d", 22f, 38f, cap)
        var right = w - 22f
        if (!phase.isNullOrBlank()) {
            val ph = Paint(cap).apply { color = col; typeface = Typeface.DEFAULT_BOLD }
            right -= ph.measureText(phase.uppercase())
            c.drawText(phase.uppercase(), right, 38f, ph)
            right -= 30f
        }
        // THE PRICE DOT: which way the stock is moving right now, beside the
        // MACD's longer story. Omitted, not greyed, when there is no figure.
        if (velPct != null) {
            val vc = MacdChartGeometry.velocityColor(velPct)
            val label = "%+.2f%%".format(velPct) + (if (!velNote.isNullOrBlank()) " · $velNote" else "")
            val vp = Paint(cap).apply { color = vc; typeface = Typeface.DEFAULT_BOLD }
            right -= vp.measureText(label)
            c.drawText(label, right, 38f, vp)
            c.drawCircle(right - 20f, 28f, 11f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = vc })
        }

        // The plot sits under the caption; the geometry is laid out in its own
        // box and shifted down, so the zero line stays centred in the plot.
        c.save()
        c.translate(0f, captionH)
        val ok = paintCurve(c, curve, col, w.toFloat(), h - rowH - captionH, 16f, 5f, 8f, 5f)
        c.restore()

        // ── MACD SLOPE ON FIVE TIMESCALES, longest first ────────────────────
        if (!slopes.isNullOrEmpty()) {
            val y0 = (h - rowH).toFloat()
            c.drawLine(22f, y0 + 4f, w - 22f, y0 + 4f, Paint().apply {
                color = if (dark) 0xFF334155.toInt() else 0xFFE2E8F0.toInt(); strokeWidth = 2f })
            val base = y0 + 44f
            c.drawText("MACD slope", 22f, base, cap)
            val left = 22f + cap.measureText("MACD slope") + 30f
            val cell = (w - 22f - left) / slopes.size
            val lab = Paint(cap).apply { typeface = Typeface.DEFAULT_BOLD
                color = if (dark) 0xFFE2E8F0.toInt() else 0xFF334155.toInt() }
            slopes.forEachIndexed { i, r ->
                val x = left + i * cell
                c.drawCircle(x + 13f, base - 10f, 13f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = MacdSlopes.color(r.sign) })
                c.drawText(r.label, x + 34f, base, lab)
            }
        }
        if (ok) bmp else null
    } catch (e: Exception) { null }

    /** The small square beside the title while the notification is collapsed —
     *  with the price dot in its corner, so direction reads without expanding. */
    fun square(ctx: Context, curve: List<Double>, phase: String?,
               velPct: Double? = null, slopes: List<MacdSlopes.Reading>? = null): Bitmap? = try {
        val s = 192
        val dark = isDark(ctx)
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawRoundRect(RectF(0f, 0f, s.toFloat(), s.toFloat()), 28f, 28f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (dark) 0xFF1E293B.toInt() else 0xFFF1F5F9.toInt()
            })
        val drawn = paintCurve(c, curve, MacdChartGeometry.phaseColor(phase),
                s.toFloat(), s.toFloat(), 20f, 6f, 9f, 5f)
        if (drawn && !slopes.isNullOrEmpty()) {
            // FIVE DOTS ACROSS THE TOP, month on the left to fifteen minutes on
            // the right — too small to label, so always in that order. Ringed in
            // the background colour so the curve cannot run through them.
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (dark) 0xFF1E293B.toInt() else 0xFFF1F5F9.toInt() }
            val gap = (s - 2 * 26f) / (slopes.size - 1)
            slopes.forEachIndexed { i, r ->
                val cx = 26f + i * gap; val cy = 26f
                c.drawCircle(cx, cy, 17f, ring)
                c.drawCircle(cx, cy, 13f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MacdSlopes.color(r.sign) })
            }
        } else if (drawn && velPct != null) {
            // Ringed in the background colour so it stays a distinct dot even
            // where the curve runs underneath it.
            val cx = s - 30f; val cy = 30f
            c.drawCircle(cx, cy, 24f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (dark) 0xFF1E293B.toInt() else 0xFFF1F5F9.toInt()
            })
            c.drawCircle(cx, cy, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = MacdChartGeometry.velocityColor(velPct)
            })
        }
        if (drawn) bmp else null
    } catch (e: Exception) { null }

    internal fun paintCurve(c: Canvas, curve: List<Double>, col: Int, w: Float, h: Float,
                           pad: Float, stroke: Float, endDot: Float, startDot: Float): Boolean {
        val g = MacdChartGeometry.layout(curve, w, h, pad) ?: return false
        val n = g.xs.size

        // Zero line: red, dashed, slightly transparent — Discovery's own.
        c.drawLine(pad, g.zeroY, w - pad, g.zeroY, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xB3DC2626.toInt(); strokeWidth = stroke * 0.8f
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(stroke * 3f, stroke * 2.2f), 0f)
        })

        // Fill between the curve and zero, fading downward.
        val line = Path().apply {
            moveTo(g.xs[0], g.ys[0]); for (i in 1 until n) lineTo(g.xs[i], g.ys[i])
        }
        val fill = Path(line).apply {
            lineTo(g.xs[n - 1], g.zeroY); lineTo(g.xs[0], g.zeroY); close()
        }
        val top = g.ys.minOrNull() ?: 0f
        c.drawPath(fill, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(0f, minOf(top, g.zeroY), 0f, h,
                withAlpha(col, 0x40), withAlpha(col, 0x05), Shader.TileMode.CLAMP)
        })

        c.drawPath(line, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = col; style = Paint.Style.STROKE; strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        })
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col }
        c.drawCircle(g.xs[n - 1], g.ys[n - 1], endDot, dot)
        c.drawCircle(g.xs[0], g.ys[0], startDot, Paint(dot).apply { alpha = 0x66 })
        return true
    }

    private fun withAlpha(color: Int, a: Int): Int = (color and 0x00FFFFFF) or (a shl 24)
}
