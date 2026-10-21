package com.signalscope.app.trading

import com.signalscope.app.data.CandleData
import com.signalscope.app.screener.DailyBar
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.TreeMap

/**
 * THE RULES OF THE BUILT-IN PAPER ALGOS, with nothing else in the way — no
 * network, no files — so each one can be checked on its own.
 *
 * The floor strategy, in one breath: only on a stock whose weekly and daily MACD
 * slopes are both rising, rest two buy orders for the day — half a seat at the
 * lowest price of the last few trading days, half a little below it — count a
 * fill only if the price trades BELOW the order, and cancel whatever is left at
 * the close. The MACD-turn strategy shares the trend filter but buys at the next
 * price once the short MACDs turn up.
 *
 * Both sell on whichever comes first: the stop, the target, the close a set
 * number of trading days on, or the close of a day the morning check found the
 * weekly or daily trend no longer rising. See [walk].
 *
 * WHY WEEKLY AND NOT MONTHLY: a monthly slope gets one new reading a month, so a
 * stock that turned up five weeks ago can still read as falling. Finished weeks
 * only — the week in progress would flicker, and the filter is meant to hold.
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
    fun inUptrend(week: Int?, day: Int?): Boolean = week == 1 && day == 1

    /** Has a held stock's trend broken? Known and no longer rising on either the
     *  week or the day. Missing data is not a verdict. */
    fun trendBroken(week: Int?, day: Int?): Boolean =
        (week != null && week != 1) || (day != null && day != 1)

    /**
     * Weekly closes from daily bars, oldest first: each finished week's last
     * close. Only days before [today] count, and the week [today] falls in is
     * left out — on a Wednesday, Monday and Tuesday are half a week.
     */
    fun completedWeeks(bars: List<DailyBar>, today: LocalDate): DoubleArray {
        val wf = WeekFields.ISO
        fun key(d: LocalDate) = d.get(wf.weekBasedYear()) * 100 + d.get(wf.weekOfWeekBasedYear())
        val now = key(today)
        val out = TreeMap<Int, Double>()
        for (b in bars.sortedBy { it.date }) {
            if (!b.date.isBefore(today) || !(b.close > 0.0) || !b.close.isFinite()) continue
            val k = key(b.date)
            if (k != now) out[k] = b.close
        }
        return out.values.toDoubleArray()
    }

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

    /** Trading days in "the week" the weekly zones are read from. */
    const val WEEK_DAYS = 5

    /** The weekly zones for an order placed on [day]: the last week's low and
     *  high, the price at the top of the support zone (where it buys) and the
     *  price at the bottom of the resistance zone (where it gives up). */
    data class WeekZone(val low: Double, val high: Double, val buyAt: Double, val cancelAt: Double)

    /**
     * From the [WEEK_DAYS] trading days before [day]: buy at the low plus
     * [zonePct] of the range, give up at the high minus [zonePct] of it.
     * RANGE-RELATIVE ON PURPOSE, as the watcher's own floor zone is: a fixed
     * percentage of the price means something different on a quiet stock than a
     * wild one — and "within 10% of the price" of its weekly low is where most
     * stocks already are. Null without a full week, or with no range at all.
     */
    fun weekZone(days: TreeMap<LocalDate, List<CandleData>>, day: LocalDate, zonePct: Double): WeekZone? {
        val prior = days.headMap(day, false)
        if (prior.size < WEEK_DAYS) return null
        val bars = prior.values.toList().takeLast(WEEK_DAYS).flatten()
        val lo = bars.minOfOrNull { it.low } ?: return null
        val hi = bars.maxOfOrNull { it.high } ?: return null
        if (!(lo > 0.0) || !(hi > lo)) return null
        val z = (zonePct / 100.0).coerceIn(0.0, 0.49) * (hi - lo)
        return WeekZone(lo, hi, lo + z, hi - z)
    }

    /** What has become of a waiting weekly order: when each half filled (null =
     *  not yet) and when the price reached the resistance zone (0 = not yet). */
    data class WeekFate(val first: Fill?, val second: Fill?, val cancelSec: Long)

    /**
     * A waiting order through [bars] — every bar since it was placed, across
     * days. It is CANCELLED by the first bar that reaches [cancelAt] (touching
     * the zone is enough), and a half fills only in a bar strictly BEFORE that
     * one: a single bar that spans both says nothing about which came first, so
     * the order is taken to have given up rather than bought.
     */
    fun weekFate(bars: List<CandleData>, firstPrice: Double, secondPrice: Double, cancelAt: Double): WeekFate {
        val sorted = bars.sortedBy { it.timestamp }
        val cancelSec = if (cancelAt > 0.0) sorted.firstOrNull { it.high >= cancelAt }?.timestamp ?: 0L else 0L
        val live = if (cancelSec > 0L) sorted.filter { it.timestamp < cancelSec } else sorted
        return WeekFate(firstFill(live, firstPrice), firstFill(live, secondPrice), cancelSec)
    }

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
     * A MACD turn's buy: the opening price of the first bar on [day] that starts
     * at or after [fromSec] — the next price after the turn was seen. Not the
     * price where it turned: the app only sees a turn once the bar has closed
     * and the feed has delivered it, and a buy placed then gets the price then.
     */
    fun nextOpen(days: TreeMap<LocalDate, List<CandleData>>, day: LocalDate, fromSec: Long): Fill? {
        val b = days[day]?.firstOrNull { it.timestamp >= fromSec && it.open > 0.0 && it.open.isFinite() }
            ?: return null
        return Fill(b.timestamp, b.open)
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

    /** One buy into a position: the start of the bar it filled in, its price, the money. */
    data class Buy(val sec: Long, val price: Double, val amount: Double)

    /** How a position ended: the day, the moment (the bar's start, or the close),
     *  the price, and why — stop | target | time | trend. */
    data class Exit(val day: LocalDate, val sec: Long, val price: Double, val reason: String)

    /** The position as the prices leave it: the stop and target in force, what is
     *  held, and the exit if there has been one. */
    data class Position(val stop: Double, val target: Double, val qty: Double,
                        val cost: Double, val exit: Exit?)

    /**
     * REPLAY A POSITION THROUGH ITS PRICES, bar by bar from the first buy, and
     * find the first thing that sells it. Worked out afresh from the data every
     * time, so a pass that ran late, a restart or a sleeping phone can neither
     * miss an exit nor invent one.
     *
     * In each fifteen-minute bar, in this order:
     *   1. buys that filled in it join the position, and the stop and target
     *      move with the average price;
     *   2. THE STOP — touching it is enough, since a stop is a trigger, not a
     *      place in a queue. Sold at the stop, or at the opening price if the bar
     *      opened below it;
     *   3. THE TARGET — like the buys, the price must trade ABOVE it. Sold at the
     *      target, or at the opening price if the bar opened above it. Not checked
     *      in a bar that also bought: its high may have come before the fill.
     * A bar that reaches both counts as the stop. Its prices cannot say which
     * came first, so the worse is assumed.
     *
     * After a finished day's last bar: sold at the close if it is the [holdDays]-th
     * trading day after the buy, or on or after [trendDay].
     *
     * Step 1 before step 2 is only right if a falling price meets the second
     * order before the first half's stop — so the settings keep the second
     * order above the stop.
     *
     * Only bars starting before [until] are read — "had it already sold by this
     * moment?" — which is how a second half filling after an exit is refused.
     */
    fun walk(days: TreeMap<LocalDate, List<CandleData>>, buys: List<Buy>,
             stopPct: Double, targetPct: Double, holdDays: Int, trendDay: LocalDate?,
             today: LocalDate, lastBar: LocalTime, zone: ZoneId, nowSec: Long,
             until: Long = Long.MAX_VALUE): Position {
        val sorted = buys.filter { it.price > 0.0 && it.amount > 0.0 }.sortedBy { it.sec }
        if (sorted.isEmpty()) return Position(0.0, 0.0, 0.0, 0.0, null)
        var qty = 0.0; var cost = 0.0; var next = 0
        var stop = 0.0; var target = 0.0
        fun take(upTo: Long): Boolean {
            var any = false
            while (next < sorted.size && sorted[next].sec <= upTo) {
                val b = sorted[next++]
                qty += b.amount / b.price; cost += b.amount; any = true
            }
            if (any) {
                val avg = cost / qty
                stop = if (stopPct > 0.0) avg * (1.0 - stopPct / 100.0) else 0.0
                target = if (targetPct > 0.0) avg * (1.0 + targetPct / 100.0) else 0.0
            }
            return any
        }
        val firstSec = sorted[0].sec
        val bought = Instant.ofEpochSecond(firstSec).atZone(zone).toLocalDate()
        val sellDay = sellDay(days, bought, holdDays)
        fun held() = Position(stop, target, qty, cost, null)
        for ((day, bars) in days.tailMap(bought, true)) {
            for (b in bars) {
                if (b.timestamp < firstSec) continue
                if (b.timestamp >= until) return held()
                val boughtHere = take(b.timestamp)
                if (qty <= 0.0) continue
                if (stop > 0.0 && b.low <= stop) {
                    val px = if (b.open > 0.0 && b.open < stop) b.open else stop
                    return Position(stop, target, qty, cost, Exit(day, b.timestamp, px, "stop"))
                }
                if (!boughtHere && target > 0.0 && b.high > target) {
                    val px = if (b.open > target) b.open else target
                    return Position(stop, target, qty, cost, Exit(day, b.timestamp, px, "target"))
                }
            }
            if (qty <= 0.0) continue
            val timeUp = sellDay != null && day == sellDay
            val trendUp = trendDay != null && !day.isBefore(trendDay)
            if ((timeUp || trendUp) && dayComplete(days, day, today, lastBar, zone, nowSec)) {
                val last = bars.lastOrNull() ?: continue
                val sec = last.timestamp + 900
                if (sec > until) return held()
                return Position(stop, target, qty, cost,
                    Exit(day, sec, last.close, if (timeUp) "time" else "trend"))
            }
        }
        return held()
    }

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
