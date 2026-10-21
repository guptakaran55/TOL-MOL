package com.signalscope.app.trading

import com.signalscope.app.data.CandleData
import com.signalscope.app.data.FloorAlgoStore.Campaign
import com.signalscope.app.data.ManualWatchStore
import com.signalscope.app.screener.DailyBar
import com.signalscope.app.util.Indicators
import kotlin.math.sqrt

/**
 * THE CAMPAIGN STRATEGY'S RULES — pure, so each one is tested on numbers.
 *
 * The user's design (16 Oct 2026), in one breath: start a campaign on a stock
 * sitting in the buffer zone of its monthly support, with a limit buy at a price
 * likely to fill (at the day's low instead if the daily MACD has just turned up;
 * higher still if the news outlook says buy). While it runs and the price is
 * below its monthly resistance zone, sell the swing near the day's high — or, if
 * the price breaks the high, once it turns down — and buy back below the day's
 * low. In the weekly resistance zone the swing target is the weekly resistance
 * itself, with a notice when it is crossed. Entering the monthly resistance zone
 * is notified too, and stops the swinging. The campaign ends:
 *   • below the monthly resistance zone — the daily MACD slope down for more
 *     than three days in a row;
 *   • in it — the daily or the 4-hour MACD slope turning down;
 *   • on the stop (the watcher's kind: % / ATR / Bollinger, off the high since
 *     each buy), or after a set number of days.
 *
 * BUFFER ZONES ARE A FRACTION OF THE ATR OF THEIR OWN TIMESCALE. A day's ATR
 * for the daily zones; for a week and a month, the daily ATR grown by the square
 * root of the days in them (5, 21) — how a price's typical range widens with
 * time. With the default tenth, that is about 0.3–0.4% of the price a day, 0.8%
 * a week and 1.5–1.8% a month on the stocks these algos watch.
 */
object CampaignRules {

    const val WEEK_DAYS = 5
    const val MONTH_DAYS = 21

    /** The 14-day average true range, from daily bars oldest first. */
    fun atr14(bars: List<DailyBar>): Double? {
        if (bars.size < 15) return null
        val tr = (1 until bars.size).map { i ->
            val b = bars[i]; val pc = bars[i - 1].close
            maxOf(b.high - b.low, kotlin.math.abs(b.high - pc), kotlin.math.abs(b.low - pc))
        }
        return tr.takeLast(14).average().takeIf { it > 0.0 }
    }

    /** Half the 20-day Bollinger width, (upper − lower) / 2 = two standard deviations. */
    fun bbHalf20(bars: List<DailyBar>): Double? {
        if (bars.size < 20) return null
        val c = bars.takeLast(20).map { it.close }
        val m = c.average()
        return (2.0 * sqrt(c.sumOf { (it - m) * (it - m) } / c.size)).takeIf { it > 0.0 }
    }

    data class Levels(
        val atr: Double, val bbHalf: Double,
        val bufDay: Double, val bufWeek: Double, val bufMonth: Double,
        /** Monthly support, weekly and monthly resistance. */
        val support: Double, val weekRes: Double, val monthRes: Double,
        /** Yesterday's high, low and close. */
        val prevHigh: Double, val prevLow: Double, val prevClose: Double,
        /** Which way the levels were read: "projected" or "range". */
        val source: String
    )

    /**
     * Today's levels from daily bars up to yesterday, oldest first.
     *
     * "projected": the trend channel the app's wave projection draws — its floor
     * a day ahead for the support, its ceiling five and twenty-one days ahead for
     * the weekly and monthly resistance — so on a rising or falling stock they
     * move with it. "range" (and the fallback when the projection cannot be
     * drawn): the plain low of the month, high of the week, high of the month.
     */
    fun levels(bars: List<DailyBar>, atrFrac: Double, source: String): Levels? {
        val atr = atr14(bars) ?: return null
        val last = bars.last()
        val f = atrFrac.coerceIn(0.01, 2.0)
        val closes = bars.map { it.close }
        val p1 = if (source == "projected") Indicators.projectWaveRange(closes, 1) else null
        val p5 = if (p1 != null) Indicators.projectWaveRange(closes, WEEK_DAYS) else null
        val p21 = if (p1 != null) Indicators.projectWaveRange(closes, MONTH_DAYS) else null
        val usedProjection = p1 != null && p5 != null && p21 != null &&
            p1.projectedFloor > 0.0 && p5.projectedCeiling > 0.0 && p21.projectedCeiling > 0.0
        val month = bars.takeLast(MONTH_DAYS)
        val week = bars.takeLast(WEEK_DAYS)
        return Levels(
            atr = atr, bbHalf = bbHalf20(bars) ?: 0.0,
            bufDay = atr * f, bufWeek = atr * sqrt(WEEK_DAYS.toDouble()) * f,
            bufMonth = atr * sqrt(MONTH_DAYS.toDouble()) * f,
            support = if (usedProjection) p1!!.projectedFloor else month.minOf { it.low },
            weekRes = if (usedProjection) p5!!.projectedCeiling else week.maxOf { it.high },
            // On a FALLING channel the ceiling a month ahead sits below the one a
            // week ahead; a monthly resistance under the weekly one would stop the
            // swings before the weekly target could be met. Never below it.
            monthRes = if (usedProjection) maxOf(p21!!.projectedCeiling, p5!!.projectedCeiling) else month.maxOf { it.high },
            prevHigh = last.high, prevLow = last.low, prevClose = last.close,
            source = if (usedProjection) "projected" else "range")
    }

    /** In the buffer zone of its monthly support: within one monthly buffer of it,
     *  either side. Further below, it has broken down — not a buy. */
    fun isCandidate(l: Levels, price: Double): Boolean =
        price > 0.0 && price >= l.support - l.bufMonth && price <= l.support + l.bufMonth

    /**
     * The entry's limit price. At the top of the support zone — likelier to fill
     * than the support itself; at the top of yesterday's low zone if the daily
     * MACD has just turned up. A BUY from the news outlook bids one monthly
     * buffer higher, a STRONG BUY two — surer to enter. Never above yesterday's
     * close: higher than that is not a limit, it is a market order.
     */
    fun entryPrice(l: Levels, macdTurnedUp: Boolean, verdict: String): Double {
        val base = if (macdTurnedUp) l.prevLow + l.bufDay else l.support + l.bufMonth
        val bump = when (verdict) { "STRONG BUY" -> 2.0 * l.bufMonth; "BUY" -> l.bufMonth; else -> 0.0 }
        return minOf(base + bump, l.prevClose)
    }

    /** The buy-back price after a swing sale: below yesterday's low — a lower low.
     *  A BUY outlook raises it to the top of the low's buffer zone. */
    fun rebuyPrice(prevLow: Double, bufDay: Double, verdict: String): Double =
        if (verdict == "BUY" || verdict == "STRONG BUY") prevLow + bufDay else prevLow

    /** An outlook that keeps a campaign from starting. (The verdict itself is a
     *  field of the outlook's JSON answer — never read out of its prose.) */
    fun blocks(verdict: String): Boolean =
        verdict == "AVOID" || verdict == "STRONG AVOID" || verdict == "SELL" || verdict == "REDUCE"

    /** Trailing days in a row the slope series has been below zero. */
    fun negDays(series: DoubleArray): Int {
        var n = 0
        for (i in series.indices.reversed()) { if (series[i] < 0.0) n++ else break }
        return n
    }

    /** Rising now, after not rising at some point in the [within] days before. */
    fun turnedUp(series: DoubleArray, within: Int = 2): Boolean {
        if (series.size < within + 1 || series.last() <= 0.0) return false
        return (1..within).any { series[series.size - 1 - it] <= 0.0 }
    }

    /** Falling today after rising yesterday. */
    fun turnedDown(series: DoubleArray): Boolean =
        series.size >= 2 && series.last() < 0.0 && series[series.size - 2] >= 0.0

    /** A buy or a sale a bar caused. [market]: at the market price (pays the spread). */
    data class Event(val buy: Boolean, val price: Double, val reason: String, val market: Boolean,
                     val notice: String = "", val ends: Boolean = false)

    data class Result(val c: Campaign, val events: List<Event>)

    /** The stop's width below the peak, by the watcher's own rule. */
    fun stopWidth(unit: String, size: Double, peak: Double, atr: Double, bbHalf: Double): Double =
        ManualTrailingStop.widthFor(
            try { ManualWatchStore.TrailUnit.valueOf(unit) } catch (e: Exception) { ManualWatchStore.TrailUnit.PCT },
            size, peak, atr, bbHalf)

    /**
     * ONE FIFTEEN-MINUTE BAR through a campaign. Buys and sales are limit orders
     * filled as the floor orders are — the price must trade through, and a bar
     * that opens beyond takes its opening price — except the stop, a sale after
     * a new high has turned down, and an exit: those are at the market.
     */
    fun onBar(c0: Campaign, b: CandleData, stopUnit: String, stopSize: Double): Result {
        var c = c0
        val ev = ArrayList<Event>()
        val holding = c.state == "HOLDING" || c.state == "RIDING"
        if (c.exitPending.isNotEmpty()) {
            if (holding) ev.add(Event(false, b.open, c.exitPending, market = true, ends = true))
            else ev.add(Event(false, 0.0, c.exitPending, market = false, ends = true))
            return Result(c.copy(state = "ENDED", endReason = c.exitPending, lastBarSec = b.timestamp), ev)
        }
        when (c.state) {
            "WAITING", "OUT" -> {
                if (c.orderPrice > 0.0 && b.timestamp >= c.orderFromSec && b.low < c.orderPrice) {
                    val px = if (b.open > 0.0 && b.open < c.orderPrice) b.open else c.orderPrice
                    ev.add(Event(true, px, if (c.state == "WAITING") "campaign start" else "buy back", market = false))
                    c = c.copy(state = "HOLDING", peak = px, rideLast = 0.0)
                }
            }
            "HOLDING", "RIDING" -> {
                val width = stopWidth(stopUnit, stopSize, c.peak, c.atr, c.bbHalf)
                val stop = if (width > 0.0) c.peak - width else 0.0
                if (stop > 0.0 && b.low <= stop) {
                    val px = if (b.open > 0.0 && b.open < stop) b.open else stop
                    ev.add(Event(false, px, "stop", market = true, ends = true))
                    return Result(c.copy(state = "ENDED", endReason = "stop", lastBarSec = b.timestamp), ev)
                }
                // The monthly resistance zone: noticed once; no more swinging.
                if (!c.inMonthZone && c.monthRes > 0.0 && b.high >= c.monthRes - c.bufMonth) {
                    c = c.copy(inMonthZone = true)
                    ev.add(Event(false, 0.0, "", market = false,
                        notice = "entered its monthly resistance zone (${fmt(c.monthRes - c.bufMonth)}–${fmt(c.monthRes)}) — swings stop; it now ends when the daily or 4-hour MACD turns down"))
                }
                if (c.inMonthZone) return Result(c.copy(peak = maxOf(c.peak, b.high), lastBarSec = b.timestamp), ev)
                if (c.state == "RIDING") {
                    if (c.rideLast > 0.0 && b.close < c.rideLast) {
                        ev.add(Event(false, b.close, "turned down after a new high", market = true))
                        c = c.copy(state = "OUT")
                    } else c = c.copy(rideLast = b.close, peak = maxOf(c.peak, b.high))
                    return Result(c.copy(lastBarSec = b.timestamp), ev)
                }
                if (!c.inWeekZone && c.weekRes > 0.0 && b.high >= c.weekRes - c.bufWeek) c = c.copy(inWeekZone = true)
                if (c.inWeekZone) {
                    if (b.high > c.weekRes) {
                        val px = if (b.open > c.weekRes) b.open else c.weekRes
                        ev.add(Event(false, px, "weekly resistance", market = false,
                            notice = "crossed its weekly resistance of ${fmt(c.weekRes)} — swing sold"))
                        c = c.copy(state = "OUT", weekCrosses = c.weekCrosses + 1)
                    }
                } else if (c.prevHigh > 0.0) {
                    val level = c.prevHigh - c.bufDay
                    if (b.high >= level) {
                        if (b.high > c.prevHigh) c = c.copy(state = "RIDING", rideLast = b.close)
                        else {
                            val px = if (b.open > level) b.open else level
                            ev.add(Event(false, px, "near the day's high", market = false))
                            c = c.copy(state = "OUT")
                        }
                    }
                }
                if (c.state != "OUT") c = c.copy(peak = maxOf(c.peak, b.high))
            }
        }
        return Result(c.copy(lastBarSec = b.timestamp), ev)
    }

    private fun fmt(v: Double) = String.format(java.util.Locale.US, if (v >= 1000) "%.1f" else "%.2f", v)
}
