package com.signalscope.app.trading

import com.signalscope.app.data.FloorAlgoStore
import com.signalscope.app.data.FloorAlgoStore.Algo
import com.signalscope.app.data.FloorJournalStore
import com.signalscope.app.data.FloorJournalStore.Row
import com.signalscope.app.screener.DailyBar
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * WHAT GOES IN THE SCAN JOURNAL — pure, no network, no files, so it is tested.
 *
 * One row per universe stock for a finished plan day, saying what the day came
 * to for it and the price an entry had, or would have had:
 *   • bought / seats full — the buy's own average price;
 *   • floor orders that did not fill, and stocks the trend kept out — the
 *     better of the floor and the day's low: the floor where the price traded
 *     below it (an order there would have filled), else the low (the nearest an
 *     order could have been and still filled);
 *   • MACD turns blocked by the 4-hour — the price when the turn was seen;
 *   • anything else — the day's close.
 * The 1, 3 and 5-day returns are filled in from that price on later mornings.
 */
object FloorJournal {

    /** Give up on returns that have not arrived after this long. */
    private const val GIVE_UP_DAYS = 30L

    /** The lowest daily low of the [n] trading days before [day]; null without that many. */
    fun priorDailyLow(bars: List<DailyBar>, day: LocalDate, n: Int): Double? {
        if (n < 1) return null
        val prior = bars.filter { it.date.isBefore(day) }.sortedBy { it.date }.takeLast(n)
        if (prior.size < n) return null
        return prior.minOf { it.low }.takeIf { it > 0.0 }
    }

    /** The journal rows for [day] — [a] as it stood at the end of that day. */
    fun rows(a: Algo, day: LocalDate, daily: Map<String, List<DailyBar>>): List<Row> {
        val dayStr = day.toString()
        val floorKind = a.kind != FloorAlgoStore.KIND_MACD
        val orders = if (a.planDay == dayStr) a.orders.associateBy { it.symbol } else emptyMap()
        val trades = a.trades.filter { it.day == dayStr }.associateBy { it.symbol }
        return a.universe.mapIndexed { i, m ->
            val bars = daily[m.symbol.uppercase()]
            val bar = bars?.firstOrNull { it.date == day }
            val low = bar?.low ?: 0.0
            val close = bar?.close ?: 0.0
            val o = orders[m.symbol]
            val t = trades[m.symbol]
            val floor = if (!floorKind) 0.0
                else o?.low ?: bars?.let { priorDailyLow(it, day, a.settings.lowDays) } ?: 0.0
            val gap = if (floor > 0.0 && low > 0.0) (low / floor - 1.0) * 100.0 else null
            val held = m.note.startsWith("held")
            // Kept out by the trend only when the filter is on; with it off the
            // slopes are recorded but decided nothing.
            val filter = a.settings.trendFilter
            val result = when {
                t != null && t.firstSec > 0L && t.status != "MISSED" -> FloorJournalStore.BOUGHT
                t != null && t.status == "MISSED" -> FloorJournalStore.SEATS_FULL
                held -> FloorJournalStore.HELD
                floorKind && o != null -> FloorJournalStore.NO_FILL
                filter && (m.week == null || m.day == null) -> FloorJournalStore.NO_DATA
                filter && !FloorOrders.inUptrend(m.week, m.day) -> FloorJournalStore.TREND_BLOCKED
                floorKind -> FloorJournalStore.NO_DATA
                m.turnBlocked -> FloorJournalStore.H4_BLOCKED
                m.looks == 0 -> FloorJournalStore.NO_DATA
                else -> FloorJournalStore.NO_TURN
            }
            val ref = when {
                t != null && t.firstSec > 0L && t.qty > 0.0 -> t.entry
                floorKind && floor > 0.0 && low > 0.0 -> maxOf(floor, low)
                !floorKind && m.turnPx > 0.0 -> m.turnPx
                else -> close
            }
            Row(day = dayStr, symbol = m.symbol, name = m.name, rank = i + 1, roce = m.roce, roc = m.roc,
                week = m.week, dayTrend = m.day, h4 = m.h4, result = result,
                floor = floor, second = o?.second?.price ?: 0.0, low = low, close = close, gap = gap,
                halves = (if ((o?.first?.filledSec ?: 0L) > 0L) 1 else 0) + (if ((o?.second?.filledSec ?: 0L) > 0L) 1 else 0),
                looks = m.looks, h4Up = m.h4Up, bothUp = m.bothUp, turnSec = m.turnSec, ref = ref)
        }
    }

    /** Fill in the 1, 3 and 5-day returns that the bars now hold. Bars are up to yesterday. */
    fun withOutcomes(rows: List<Row>, daily: Map<String, List<DailyBar>>, today: LocalDate): List<Row> =
        rows.map { r ->
            if (r.done) return@map r
            val tooOld = try {
                ChronoUnit.DAYS.between(LocalDate.parse(r.day), today) > GIVE_UP_DAYS
            } catch (e: Exception) { true }
            val bars = daily[r.symbol.uppercase()]?.sortedBy { it.date }
            val idx = bars?.indexOfFirst { it.date.toString() == r.day } ?: -1
            if (bars == null || idx < 0 || r.ref <= 0.0) return@map if (tooOld) r.copy(done = true) else r
            fun ret(k: Int) = bars.getOrNull(idx + k)?.close?.takeIf { it > 0.0 }?.let { (it / r.ref - 1.0) * 100.0 }
            val r5 = ret(5) ?: r.ret5
            r.copy(ret1 = ret(1) ?: r.ret1, ret3 = ret(3) ?: r.ret3, ret5 = r5, done = r5 != null || tooOld)
        }

    /** One line for the algo's log about a journaled day. */
    fun summary(rows: List<Row>, macd: Boolean): String {
        fun c(res: String) = rows.count { it.result == res }
        val bought = c(FloorJournalStore.BOUGHT) + c(FloorJournalStore.SEATS_FULL)
        return if (!macd) {
            val noFill = rows.filter { it.result == FloorJournalStore.NO_FILL }
            val near = noFill.count { (it.gap ?: 99.0) <= 1.0 }
            val blockedDips = rows.count { it.result == FloorJournalStore.TREND_BLOCKED && (it.gap ?: 1.0) < 0.0 }
            "${bought + noFill.size} with orders, $bought filled, $near more within 1% · " +
                "$blockedDips dipped below their floor but the trend kept them out"
        } else {
            val watched = bought + c(FloorJournalStore.NO_TURN) + c(FloorJournalStore.H4_BLOCKED)
            "$watched watched, $bought turned up and bought or followed · " +
                "${c(FloorJournalStore.H4_BLOCKED)} turned up while the 4-hour was not rising"
        }
    }
}
