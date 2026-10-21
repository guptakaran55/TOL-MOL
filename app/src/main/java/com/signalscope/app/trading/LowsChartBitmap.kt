package com.signalscope.app.trading

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.signalscope.app.data.CandleData

/**
 * THE NEAR-LOW ALERT'S PICTURE — price on three timescales, the MACD slope on
 * five, and the daily MACD, in the room one large MACD chart used to take.
 *
 * The alert is about PRICE sitting at a low, and the old picture showed no price
 * at all: a large daily MACD and a row of slope dots. Android caps an expanded
 * notification at roughly 256dp, so the price charts could only come in by
 * re-splitting the same height — the slope dots move to a thin strip at the top,
 * and a 2×2 grid takes the rest: a year and a month on the first row, five days
 * and the daily MACD on the second. Each price chart has that window's low dashed
 * and today's price marked, in its own colour, so which low it is sitting on
 * reads at a glance.
 *
 * Drawn from bars the watchlist has already downloaded for the stock on this
 * pass. Nothing is fetched to make it.
 */
object LowsChartBitmap {

    /** One price chart: [closes] oldest first; [low] that window's lowest low. */
    class Panel(val label: String, val closes: List<Double>, val low: Double, val now: Double)

    private fun isDark(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /**
     * The three panels from the watchlist's own bars: the last year as daily
     * closes, the last month and the last five trading days as the bars
     * themselves. A window the history does not reach is labelled with the days
     * it actually covers rather than passed off as a year.
     */
    fun panels(candles: List<CandleData>, now: Double): List<Panel> {
        if (candles.size < 10 || !(now > 0.0)) return emptyList()
        fun win(days: Int) = IntradayLevels.recentDays(candles, days)
        val out = ArrayList<Panel>()
        val year = win(252)
        val yearDays = year.map { it.timestamp / 86_400L }.distinct()
        if (yearDays.size >= 20) {
            // One close per trading day — a year of hourly bars is thousands of
            // points for a chart a few dozen pixels wide.
            val daily = year.groupBy { it.timestamp / 86_400L }.toSortedMap().values.map { it.last().close }
            out.add(Panel(if (yearDays.size >= 240) "1Y" else "${yearDays.size}d", daily,
                year.minOf { it.low }, now))
        }
        val month = win(21)
        if (month.size >= 2) out.add(Panel("1M", month.map { it.close }, month.minOf { it.low }, now))
        val five = win(5)
        if (five.size >= 2) out.add(Panel("5D", five.map { it.close }, five.minOf { it.low }, now))
        return out
    }

    private class Ink(val dark: Boolean) {
        val bg = if (dark) 0xFF1E293B.toInt() else 0xFFF1F5F9.toInt()
        val rule = if (dark) 0xFF334155.toInt() else 0xFFE2E8F0.toInt()
        val muted = if (dark) 0xFF94A3B8.toInt() else 0xFF64748B.toInt()
        val strong = if (dark) 0xFFE2E8F0.toInt() else 0xFF334155.toInt()
        val low = 0xFFEF4444.toInt()
        val now = 0xFF3B82F6.toInt()
        val up = 0xFF16A34A.toInt()
        val down = 0xFFDC2626.toInt()

        /** ONE COLOUR PER TIMESCALE, so the three price charts read apart without
         *  spending width on gaps between them: a year violet, a month teal, five
         *  days amber — the label in the same colour as its line. */
        fun lineFor(label: String): Int = when {
            label == "1M" -> if (dark) 0xFF2DD4BF.toInt() else 0xFF0D9488.toInt()
            label == "5D" -> if (dark) 0xFFFBBF24.toInt() else 0xFFD97706.toInt()
            else -> if (dark) 0xFFA78BFA.toInt() else 0xFF7C3AED.toInt()
        }
    }

    /**
     * The expanded picture: the slope strip across the top, then a 2×2 grid —
     * the year and the month on the first row, five days and the daily MACD on
     * the second. Two charts to a row give each price line half the width
     * instead of a third, and the MACD sits at the same half width as its
     * neighbours instead of being stretched across the whole card.
     *
     * Any part without data is left out and the rest close up; null when there
     * is nothing to draw at all, and the alert then goes out as text, as it
     * always could.
     */
    fun wide(ctx: Context, panels: List<Panel>, curve: List<Double>, phase: String?,
             slopes: List<MacdSlopes.Reading>?, velPct: Double?, velNote: String?): Bitmap? = try {
        val hasMacd = curve.size >= 2
        val cells = panels.size + (if (hasMacd) 1 else 0)
        if (cells == 0) null else {
            val w = 960
            val stripH = if (!slopes.isNullOrEmpty() || velPct != null) 58 else 0
            val rows = (cells + 1) / 2
            val rowH = 193
            val h = stripH + rows * rowH + 8
            val ink = Ink(isDark(ctx))
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 18f, 18f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink.bg })
            val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 28f; color = ink.muted }
            val bold = Paint(txt).apply { typeface = Typeface.DEFAULT_BOLD; color = ink.strong }
            val rule = Paint().apply { color = ink.rule; strokeWidth = 2f }

            // ── STRIP: the MACD slope on five timescales, and the price move now ──
            if (stripH > 0) {
                val base = 38f
                var x = 22f
                slopes.orEmpty().forEach { r ->
                    c.drawCircle(x + 11f, base - 9f, 11f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MacdSlopes.color(r.sign) })
                    c.drawText(r.label, x + 28f, base, bold)
                    x += 28f + bold.measureText(r.label) + 26f
                }
                if (velPct != null) {
                    val label = "%+.2f%%".format(velPct) + (if (!velNote.isNullOrBlank()) " · $velNote" else "")
                    val vp = Paint(bold).apply { color = MacdChartGeometry.velocityColor(velPct) }
                    c.drawText(label, w - 22f - vp.measureText(label), base, vp)
                }
                c.drawLine(22f, stripH - 4f, w - 22f, stripH - 4f, rule)
            }

            // ── THE GRID ──
            val gap = 28f
            val cellW = (w - 44f - gap) / 2f
            for (i in 0 until cells) {
                val row = i / 2; val col = i % 2
                val x0 = 22f + col * (cellW + gap)
                val y0 = stripH + row * rowH.toFloat()
                if (col == 1) c.drawLine(x0 - gap / 2f, y0 + 10f, x0 - gap / 2f, y0 + rowH - 10f, rule)
                if (row > 0 && col == 0) c.drawLine(22f, y0, w - 22f, y0, rule)
                if (i < panels.size) {
                    // A price chart: its window and change on top, the line below.
                    val p = panels[i]
                    val line = ink.lineFor(p.label)
                    val first = p.closes.firstOrNull() ?: p.now
                    val chg = if (first > 0.0) (p.now / first - 1.0) * 100.0 else 0.0
                    c.drawText(p.label, x0, y0 + 36f, Paint(bold).apply { color = line })
                    val cp = Paint(bold).apply { color = if (chg >= 0) ink.up else ink.down }
                    val cs = "%+.1f%%".format(chg)
                    c.drawText(cs, x0 + cellW - cp.measureText(cs), y0 + 36f, cp)
                    drawPrice(c, p, RectF(x0, y0 + 52f, x0 + cellW, y0 + rowH - 16f), ink, line)
                } else {
                    // The daily MACD, at the same size as its neighbours.
                    c.drawText("MACD ${curve.size}d", x0, y0 + 36f, txt)
                    if (!phase.isNullOrBlank()) {
                        val ph = Paint(bold).apply { color = MacdChartGeometry.phaseColor(phase) }
                        c.drawText(phase.uppercase(), x0 + cellW - ph.measureText(phase.uppercase()), y0 + 36f, ph)
                    }
                    c.save()
                    c.translate(x0, y0 + 44f)
                    MacdChartBitmap.paintCurve(c, curve, MacdChartGeometry.phaseColor(phase),
                        cellW, rowH - 54f, 10f, 4f, 7f, 4f)
                    c.restore()
                }
            }
            bmp
        }
    } catch (e: Exception) { null }

    /** One price line in [box]: the low dashed, today's price as a dot. */
    private fun drawPrice(c: Canvas, p: Panel, box: RectF, ink: Ink, line: Int) {
        val vals = p.closes + p.now
        val lo = minOf(vals.minOrNull() ?: return, if (p.low > 0.0) p.low else Double.MAX_VALUE)
        val hi = vals.maxOrNull() ?: return
        val span = (hi - lo).takeIf { it > 1e-9 } ?: (hi * 0.01).coerceAtLeast(1e-6)
        fun yOf(v: Double) = box.bottom - ((v - lo) / span * box.height()).toFloat()
        val n = p.closes.size
        val path = Path()
        p.closes.forEachIndexed { i, v ->
            val x = box.left + (if (n <= 1) 0f else i * box.width() / (n - 1))
            if (i == 0) path.moveTo(x, yOf(v)) else path.lineTo(x, yOf(v))
        }
        if (p.low > 0.0)
            c.drawLine(box.left, yOf(p.low), box.right, yOf(p.low), Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ink.low; strokeWidth = 2.5f; style = Paint.Style.STROKE
                pathEffect = DashPathEffect(floatArrayOf(8f, 7f), 0f)
            })
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = line; strokeWidth = 3.5f; style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        })
        c.drawCircle(box.right, yOf(p.now), 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink.now })
    }
}
