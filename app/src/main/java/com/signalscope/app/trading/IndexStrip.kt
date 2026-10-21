package com.signalscope.app.trading

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.Log
import com.google.gson.Gson
import com.signalscope.app.data.CandleData
import com.signalscope.app.network.YahooFinanceClient
import java.io.File
import java.time.Instant
import java.time.ZoneId

// ════════════════════════════════════════════════════════════════════════════
// THE MARKETS TODAY — DAX, Sensex and Nasdaq-100 on the positions widget
// ════════════════════════════════════════════════════════════════════════════
// One glance at the home screen should say which way each market is leaning
// today, beside what is held in it. Each index gets a tiny line of its latest
// session and the change from the session before.
//
// FETCHED BY THE BACKGROUND PASS, NOT THE WIDGET: three small requests at most
// every ten minutes (or on the widget's ↻), kept on disk so a restarted app
// draws the last reading at once instead of a blank strip.
// ════════════════════════════════════════════════════════════════════════════
object IndexStrip {
    private const val TAG = "IndexStrip"
    private const val FILE = "index_strip.json"
    private const val MIN_GAP_MS = 10 * 60 * 1000L

    /** [space] gives the exchange clock its sessions are split on. */
    data class Index(val key: String, val label: String, val yahoo: String, val space: String)

    val INDICES = listOf(
        Index("dax", "DAX", "^GDAXI", "de"),
        Index("sensex", "Sensex", "^BSESN", "in"),
        // "NAS100", not the ticker "NDX": the Nasdaq-100 — the 100 largest Nasdaq
        // companies, the list the US algos pick from — not the whole Nasdaq Composite.
        Index("ndx", "NAS100", "^NDX", "us"))

    /**
     * The latest session of one index: its closes through the day, the close of
     * the session before (the line the day is measured from), and the change.
     * [day] is the session's own date on the exchange clock.
     */
    data class Reading(
        val key: String = "",
        val label: String = "",
        val day: String = "",
        val prevClose: Double = 0.0,
        val last: Double = 0.0,
        val pct: Double = 0.0,
        val closes: List<Double> = emptyList(),
        val atMs: Long = 0L
    )

    data class State(val readings: List<Reading> = emptyList(), val atMs: Long = 0L)

    private val gson = Gson()
    @Volatile private var cached: State? = null
    @Volatile private var loaded = false

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    fun latest(ctx: Context): State? {
        if (loaded) return cached
        cached = try {
            file(ctx).takeIf { it.exists() }?.readText()?.let { gson.fromJson(it, State::class.java) }
        } catch (e: Exception) { null }
        loaded = true
        return cached
    }

    /**
     * Ask for the three indices again, unless the last answer is under ten
     * minutes old and [force] is off. An index that fails keeps its previous
     * reading — a stale line is better than a hole, and its date says how old.
     */
    fun refresh(ctx: Context, force: Boolean = false): State? {
        val old = latest(ctx)
        val now = System.currentTimeMillis()
        if (!force && old != null && now - old.atMs < MIN_GAP_MS) return old
        val readings = INDICES.map { ix ->
            val fresh = try {
                val r = YahooFinanceClient.fetchCandlesAt(ix.yahoo, "", "5m", "5d")
                val zone = MarketHours.zoneOf(ix.space) ?: ZoneId.of("UTC")
                (r as? YahooFinanceClient.CandleResult.Success)?.let { reading(ix, it.candles, zone, now) }
            } catch (e: Exception) { Log.w(TAG, "${ix.label} fetch failed", e); null }
            fresh ?: old?.readings?.firstOrNull { it.key == ix.key }
        }.filterNotNull()
        if (readings.isEmpty()) return old
        val s = State(readings, now)
        cached = s; loaded = true
        try {
            val f = file(ctx)
            val tmp = File(f.parentFile, "$FILE.tmp")
            tmp.writeText(gson.toJson(s))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (e: Exception) { Log.w(TAG, "save failed", e) }
        return s
    }

    /**
     * The latest session out of a few days of bars, measured from the close of
     * the session before it. Null without two sessions to compare. Pure.
     */
    fun reading(ix: Index, candles: List<CandleData>, zone: ZoneId, nowMs: Long): Reading? {
        val bars = candles.filter { it.close > 0.0 && it.close.isFinite() }.sortedBy { it.timestamp }
        if (bars.isEmpty()) return null
        val byDay = bars.groupBy { Instant.ofEpochSecond(it.timestamp).atZone(zone).toLocalDate() }.toSortedMap()
        if (byDay.size < 2) return null
        val days = byDay.keys.toList()
        val today = byDay.getValue(days.last())
        val prev = byDay.getValue(days[days.size - 2]).last().close
        val last = today.last().close
        return Reading(ix.key, ix.label, days.last().toString(), prev, last,
            (last / prev - 1.0) * 100.0, today.map { it.close }, nowMs)
    }

    /**
     * Where the line's points and the previous close sit inside a [w] × [h] box,
     * with [pad] kept clear at the edges. The previous close is always inside the
     * scale, so a day that never crossed it still shows how far away it stayed.
     * Pure; returns the points and the baseline's y.
     */
    fun geometry(closes: List<Double>, base: Double, w: Float, h: Float, pad: Float): Pair<List<Pair<Float, Float>>, Float> {
        val lo = minOf(closes.minOrNull() ?: base, base)
        val hi = maxOf(closes.maxOrNull() ?: base, base)
        val span = (hi - lo).takeIf { it > 0.0 } ?: 1.0
        fun y(v: Double) = (pad + (h - 2 * pad) * (1.0 - (v - lo) / span)).toFloat()
        val n = closes.size
        val pts = closes.mapIndexed { i, v ->
            val x = if (n <= 1) w / 2f else pad + (w - 2 * pad) * i / (n - 1).toFloat()
            x to y(v)
        }
        return pts to y(base)
    }

    /** The day's line as a small picture for the widget: green above the
     *  previous close at the last price, red below, the close itself dashed. */
    fun sparkline(r: Reading, wPx: Int, hPx: Int, density: Float): Bitmap {
        val bmp = Bitmap.createBitmap(wPx.coerceAtLeast(8), hPx.coerceAtLeast(8), Bitmap.Config.ARGB_8888)
        if (r.closes.isEmpty() || r.prevClose <= 0.0) return bmp
        val c = Canvas(bmp)
        val (pts, baseY) = geometry(r.closes, r.prevClose, bmp.width.toFloat(), bmp.height.toFloat(), 1.5f * density)
        val base = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x66FFFFFF; strokeWidth = 1f * density; style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(3f * density, 3f * density), 0f)
        }
        c.drawLine(0f, baseY, bmp.width.toFloat(), baseY, base)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (r.pct >= 0.0) 0xFF34D399.toInt() else 0xFFF87171.toInt()
            strokeWidth = 1.5f * density; style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        }
        val path = Path()
        pts.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
        c.drawPath(path, line)
        return bmp
    }
}
