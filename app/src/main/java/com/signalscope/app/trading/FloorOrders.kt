package com.signalscope.app.trading

import com.signalscope.app.data.CandleData
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.TreeMap

/**
 * THE RULES OF THE FLOOR-ORDER ALGO, with nothing else in the way — no network,
 * no files — so each one can be checked on its own.
 *
 * The strategy, in one breath: only on a stock whose monthly and daily MACD
 * slopes are both rising, rest two buy orders for the day — half a seat at the
 * lowest price of the last few trading days, half a little below it — count a
 * fill only if the price trades BELOW the order, cancel whatever is left at the
 * close, and sell at the close a fixed number of trading days later.
 *
 * Why resting orders rather than a signal: the replay on the watchlist showed
 * every signal-driven entry arriving well after the turn, while an order already
 * waiting at the floor is there when the price comes to it. It also makes the
 * data delay irrelevant — an order at the broker fills when the price gets
 * there, whether or not the app has seen it yet.
 *
 * Everything runs on the EXCHANGE's calendar: a German stock's day is Berlin's.
 */
object FloorOrders {

    /** Both long timescales rising — the only stocks that get orders at all. */
    fun inUptrend(month: Int?, day: Int?): Boolean = month == 1 && day == 1

    /** Regular-session fifteen-minute bars grouped by the exchange's calendar day,
     *  each day's bars in time order. Bars without a usable price are dropped. */
    fun byDay(bars: List<CandleData>, zone: ZoneId): TreeMap<LocalDate, List<CandleData>> {
        val out = TreeMap<LocalDate, MutableList<CandleData>>()
        for (b in bars) {
            if (!(b.low > 0.0) || !(b.close > 0.0) || !b.low.isFinite() || !b.close.isFinite()) continue
            val d = Instant.ofEpochSecond(b.timestamp).atZone(zone).toLocalDate()
            out.getOrPut(d) { ArrayList() }.add(b)
        }
        val sorted = TreeMap<LocalDate, List<CandleData>>()
        for ((d, l) in out) sorted[d] = l.sortedBy { it.timestamp }
        return sorted
    }

    /** The lowest price of the last [n] trading days before [day] — the floor the
     *  first order rests at. Null without that many days of data. */
    fun priorLow(days: TreeMap<LocalDate, List<CandleData>>, day: LocalDate, n: Int): Double? {
        val prior = days.headMap(day, false)
        if (n < 1 || prior.size < n) return null
        return prior.values.toList().takeLast(n).flatten().minOfOrNull { it.low }
    }

    /** The second order's price: [pct] percent under the floor. */
    fun secondPrice(low: Double, pct: Double): Double = low * (1.0 - pct / 100.0)

    data class Fill(val sec: Long, val price: Double)

    /**
     * The first bar that traded BELOW [limit] — touching it is not enough, since
     * an order sitting at exactly that price may be behind others in the queue.
     *
     * Filled at the limit, or at the bar's opening price when it opened below the
     * limit already (a gap down): a resting buy order takes the better price.
     */
    fun firstFill(dayBars: List<CandleData>, limit: Double): Fill? {
        if (!(limit > 0.0)) return null
        for (b in dayBars.sortedBy { it.timestamp }) {
            if (b.low < limit) {
                val px = if (b.open > 0.0 && b.open < limit) b.open else limit
                return Fill(b.timestamp, px)
            }
        }
        return null
    }

    /**
     * Has [day] finished trading, as far as the data shows? Any earlier day has —
     * even one with no bars at all (a holiday). Today has only once its final bar
     * is in AND over: the feed shows a bar while it is still forming, and its
     * price then is not yet the close.
     */
    fun dayComplete(days: TreeMap<LocalDate, List<CandleData>>, day: LocalDate,
                    today: LocalDate, lastBar: LocalTime, zone: ZoneId, nowSec: Long): Boolean {
        if (day.isBefore(today)) return true
        if (day.isAfter(today)) return false
        val bars = days[day] ?: return false
        return bars.any {
            !Instant.ofEpochSecond(it.timestamp).atZone(zone).toLocalTime().isBefore(lastBar) &&
                nowSec >= it.timestamp + 900
        }
    }

    /** The trading day a position bought on [bought] is sold at the close of —
     *  the [hold]-th day with trading after it — or null while not yet in the data.
     *  Counted from the data, so holidays never count as a day held. */
    fun sellDay(days: TreeMap<LocalDate, List<CandleData>>, bought: LocalDate, hold: Int): LocalDate? =
        days.tailMap(bought, false).keys.elementAtOrNull(hold.coerceAtLeast(1) - 1)

    /** A day's closing price: its last bar's close. */
    fun closeOf(days: TreeMap<LocalDate, List<CandleData>>, day: LocalDate): Double? =
        days[day]?.lastOrNull()?.close

    /**
     * How many seats are taken on [day]. A position holds its seat from the day it
     * was bought to the day it is sold — INCLUDING that day, because it is sold at
     * the close and so still in hand while the day's orders can fill.
     */
    fun seatsBusy(spans: List<Pair<LocalDate, LocalDate?>>, day: LocalDate): Int =
        spans.count { (b, s) -> !b.isAfter(day) && (s == null || !s.isBefore(day)) }

    data class Outcome(val ret1: Double?, val ret3: Double?, val ret5: Double?,
                       val dip: Double?, val done: Boolean)

    /**
     * What the price did after a buy at [entry]: the return to the close one,
     * three and five trading days later, and the deepest dip below the buying
     * price from the moment of the fill ([fromSec]) to the fifth day's close.
     * A figure is filled in only once its day has finished trading.
     */
    fun outcome(days: TreeMap<LocalDate, List<CandleData>>, bought: LocalDate, fromSec: Long,
                entry: Double, today: LocalDate, lastBar: LocalTime, zone: ZoneId,
                nowSec: Long): Outcome {
        if (!(entry > 0.0) || !days.containsKey(bought)) return Outcome(null, null, null, null, false)
        val after = days.tailMap(bought, false).keys.toList()
        fun ret(k: Int): Double? = after.getOrNull(k - 1)
            ?.takeIf { dayComplete(days, it, today, lastBar, zone, nowSec) }
            ?.let { d -> closeOf(days, d)?.let { (it / entry - 1.0) * 100.0 } }
        val window = listOf(bought) + after.take(5)
        val low = window.flatMap { days[it].orEmpty() }.filter { it.timestamp >= fromSec }
            .minOfOrNull { it.low }
        val r5 = ret(5)
        return Outcome(ret(1), ret(3), r5, low?.let { (it / entry - 1.0) * 100.0 }, r5 != null)
    }
}
