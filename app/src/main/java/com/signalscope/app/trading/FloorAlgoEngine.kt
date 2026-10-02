package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.signalscope.app.data.CandleData
import com.signalscope.app.data.DiscoveryResultStore
import com.signalscope.app.data.FloorAlgoStore
import com.signalscope.app.data.FloorAlgoStore.Algo
import com.signalscope.app.data.FloorAlgoStore.Leg
import com.signalscope.app.data.FloorAlgoStore.Member
import com.signalscope.app.data.FloorAlgoStore.Order
import com.signalscope.app.data.FloorAlgoStore.Trade
import com.signalscope.app.network.YahooFinanceClient
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TreeMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * RUNS THE FLOOR-ORDER ALGOS — on paper only; nothing here can reach a broker.
 *
 * One pass, every fifteen minutes with the other algos, does in order:
 *   1. finishes yesterday: a last look for fills, then whatever is left expires;
 *   2. once a day, takes the universe from the Discovery scan — the top names by
 *      return on capital;
 *   3. once a day, from 06:00 on the exchange's clock, checks which of them have
 *      both the monthly and daily MACD slope rising, and rests two orders on
 *      each: half a seat at the recent low, half a little below;
 *   4. looks for fills in today's fifteen-minute prices;
 *   5. prices what is held, and sells at the close on the planned day;
 *   6. keeps filling in what happened after each buy (1, 3 and 5 days on, and
 *      the deepest dip).
 *
 * Prices are taken as they are — the feed's delay is ignored, fills happen at
 * exactly the order price, and there are no fees, as the test was asked to be.
 *
 * WHY 06:00 AND NOT MIDNIGHT: daily prices are cached by the UTC day, which on
 * a German clock changes at one or two in the morning. Before that, the cache
 * can still hold yesterday's bar as it looked in mid-session, and a trend read
 * from it would be from a half-finished day.
 */
object FloorAlgoEngine {
    private const val TAG = "FloorAlgo"
    private val runLock = ReentrantLock()
    private val PLAN_FROM: LocalTime = LocalTime.of(6, 0)
    private const val RECHECK_MS = 3 * 60 * 60 * 1000L
    private const val GIVE_UP_DAYS = 30L

    fun currencyMark(space: String) = when (space) { "de" -> "€"; "in" -> "₹"; else -> "$" }

    private fun marketFor(space: String) = when (space) {
        "de" -> "DAX 100"; "us" -> "NASDAQ 100"; else -> "NIFTY 500"
    }

    /** Every algo with work to do, one after another. For the fifteen-minute pass. */
    fun runAll(ctx: Context): String {
        val ids = FloorAlgoStore.load(ctx).algos.filter { FloorAlgoStore.hasWork(it) }.map { it.id }
        if (ids.isEmpty()) return ""
        if (!runLock.tryLock(3, TimeUnit.MINUTES)) return ""
        try { ids.forEach { runLocked(ctx, it) } } finally { runLock.unlock() }
        val all = FloorAlgoStore.load(ctx).algos.filter { it.id in ids }
        val open = all.sumOf { a -> a.trades.count { it.status == "OPEN" } }
        val resting = all.sumOf { a -> a.orders.count { it.status == "RESTING" } }
        return "floor $open held · $resting resting"
    }

    /** One algo, now — the card's "check now". False when another pass held it too long. */
    fun runOne(ctx: Context, id: String): Boolean {
        if (!runLock.tryLock(2, TimeUnit.MINUTES)) return false
        try { runLocked(ctx, id) } finally { runLock.unlock() }
        return true
    }

    private fun runLocked(ctx: Context, id: String) {
        val snap = FloorAlgoStore.get(ctx, id) ?: return
        val pass = Pass(ctx, snap)
        try { pass.run() } catch (e: Exception) {
            Log.e(TAG, "pass failed for ${snap.name}", e)
            pass.note("stopped by an error: ${e.message ?: e.javaClass.simpleName}")
        }
        val done = pass.a
        // Merged onto the CURRENT record, not the one read at the start: a pause,
        // rename or settings change made while prices were downloading must win.
        val saved = FloorAlgoStore.update(ctx, id) { fresh ->
            fresh.copy(universe = done.universe, universeDay = done.universeDay,
                universeFrom = done.universeFrom, planDay = done.planDay,
                orders = done.orders, trades = done.trades,
                log = (fresh.log + pass.newLog), lastRunMs = System.currentTimeMillis(),
                lastNote = pass.summary())
        } ?: return
        if (saved.notify) for (n in pass.notices) {
            try {
                if (n.sold) LabNotifier.floorSold(ctx, saved.name, saved.ns, n.symbol, n.price, n.pct, n.text)
                else LabNotifier.floorBought(ctx, saved.name, saved.ns, n.symbol, n.price, n.text)
            } catch (e: Exception) { Log.w(TAG, "notice failed", e) }
        }
        Log.i(TAG, "${saved.name}: ${saved.lastNote} (${pass.book.fetches} downloads)")
    }

    private data class Notice(val sold: Boolean, val symbol: String, val price: Double,
                              val pct: Double, val text: String)

    /** Fifteen-minute prices by trading day, downloaded at most once a pass per stock
     *  and widened only when a question reaches further back than what is in hand. */
    private class Book(val space: String, val zone: ZoneId) {
        private val RANGES = listOf("5d", "1mo", "60d")
        private val got = HashMap<String, Pair<Int, TreeMap<LocalDate, List<CandleData>>>>()
        var fetches = 0

        fun days(sym: String, enough: (TreeMap<LocalDate, List<CandleData>>) -> Boolean):
                TreeMap<LocalDate, List<CandleData>>? {
            var cur = got[sym]
            while (true) {
                if (cur != null && enough(cur.second)) return cur.second
                val next = (cur?.first ?: -1) + 1
                if (next >= RANGES.size) return cur?.second
                val fresh = fetch(sym, RANGES[next]) ?: return cur?.second
                cur = next to fresh
                got[sym] = cur
            }
        }

        private fun fetch(sym: String, range: String): TreeMap<LocalDate, List<CandleData>>? {
            fetches++
            val r = try {
                YahooFinanceClient.fetchCandles15mRange(sym, Spaces.exchangeOf(space), range)
            } catch (e: Exception) { null }
            try { Thread.sleep(250) } catch (_: InterruptedException) {}
            val c = (r as? YahooFinanceClient.CandleResult.Success)?.candles ?: return null
            return FloorOrders.byDay(c, zone)
        }
    }

    private class Pass(val ctx: Context, start: Algo) {
        var a: Algo = start
        val space = start.space
        val zone: ZoneId = MarketHours.zoneOf(space) ?: ZoneId.of("Europe/Berlin")
        val lastBar: LocalTime = MarketHours.lastBarStart(space) ?: LocalTime.of(17, 15)
        val openAt: LocalTime = MarketHours.openOf(space) ?: LocalTime.of(9, 0)
        val nowMs = System.currentTimeMillis()
        val nowSec = nowMs / 1000L
        val local = Instant.ofEpochMilli(nowMs).atZone(zone)
        val today: LocalDate = local.toLocalDate()
        val book = Book(space, zone)
        val newLog = ArrayList<String>()
        val notices = ArrayList<Notice>()
        val cur = currencyMark(space)
        private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

        fun note(s: String) { newLog.add(FloorAlgoStore.stamp(nowMs) + " · " + s) }
        fun money(v: Double) = cur + String.format(Locale.US, "%.2f", v)
        fun px(v: Double) = String.format(Locale.US, if (v >= 1000) "%.1f" else "%.2f", v)

        fun summary(): String {
            val resting = a.orders.count { it.status == "RESTING" }
            val open = a.trades.count { it.status == "OPEN" }
            return "checked ${FloorAlgoStore.stamp(nowMs)} · $open held · " +
                (if (a.planDay == today.toString()) "$resting with orders resting" else "no orders today")
        }

        fun run() {
            // A PAUSED algo places nothing new, but still sells what it holds on
            // schedule and keeps its record up to date — pausing must never strand
            // a position with nothing to close it.
            if (a.enabled) {
                // 1. The previous plan day: one last look, then what is left expires.
                if (a.planDay.isNotEmpty() && a.planDay != today.toString() &&
                    a.orders.any { it.status == "RESTING" }) {
                    fillPass(LocalDate.parse(a.planDay))
                    endDay()
                }
                // 2. The universe, once a day.
                if (a.universeDay != today.toString()) refreshUniverse()
                // 3. Today's orders.
                val weekday = today.dayOfWeek.value <= 5
                if (weekday && a.planDay != today.toString() && !local.toLocalTime().isBefore(PLAN_FROM)) plan()
                // 4. Fills, once the market has opened.
                if (a.planDay == today.toString() && !local.toLocalTime().isBefore(openAt) &&
                    a.orders.any { it.status == "RESTING" }) {
                    fillPass(today)
                    if (todayOver()) endDay()
                }
            }
            // 5–6.
            manageOpen()
            outcomes()
        }

        private fun refreshUniverse() {
            val s = a.settings
            val market = marketFor(space)
            val scan = try {
                DiscoveryResultStore.loadLatestComplete(ctx, market) ?: DiscoveryResultStore.load(ctx, market)
            } catch (e: Exception) { null }
            if (scan == null) {
                if (a.universe.isEmpty()) note("no Discovery scan for this market yet — run one, then check again")
                return
            }
            val top = scan.allStocks
                .filter { it.roce != null && it.roce.isFinite() }
                .sortedByDescending { it.roce }
                .take(s.universeSize.coerceIn(1, 200))
                .map { Member(MacdLadder.bare(it.symbol), it.name, it.roce) }
            if (top.isEmpty()) { note("the Discovery scan has no return-on-capital figures"); return }
            val changed = top.map { it.symbol } != a.universe.map { it.symbol }
            a = a.copy(universe = top, universeDay = today.toString(), universeFrom = scan.timestamp)
            if (changed) note("universe: top ${top.size} by return on capital from the Discovery scan of " +
                FloorAlgoStore.stamp(scan.timestamp))
        }

        private fun plan() {
            val s = a.settings
            if (a.universe.isEmpty()) return
            val smoothing = MacdLadder.smoothingFor(ctx, space)
            val held = a.trades.filter { it.status == "OPEN" }.map { it.symbol }.toSet()
            val half = s.capital / s.seats.coerceAtLeast(1) / 2.0
            // Orders count from the next bar after they exist — never on a price
            // that traded before the order was placed.
            val fromSec = ((nowSec + 899) / 900) * 900
            val daily = try {
                com.signalscope.app.screener.ScreenerRunner.fetchUniverseBars(
                    Spaces.dataSpaceOf(space), a.universe.map { it.symbol }, minBars = MacdSlopes.MIN_BARS).first
            } catch (e: Exception) { LinkedHashMap() }
            // The daily trend must include the last finished session. A series
            // missing it is a day behind; the plan waits for the next pass.
            val newest = daily.values.mapNotNull { b -> b.lastOrNull { it.date.isBefore(today) }?.date }.maxOrNull()
            if (daily.isNotEmpty() &&
                !com.signalscope.app.screener.ScreenerRunner.dailyDataCurrent(space, newest)) {
                Log.w(TAG, "${a.name}: daily prices end at $newest — last session missing, plan retried next pass")
                return
            }
            val orders = ArrayList<Order>()
            val members = a.universe.map { m ->
                if (m.symbol in held) return@map m.copy(month = null, day = null, note = "held — no new orders")
                val closes = daily[m.symbol.uppercase()]?.filter { it.date.isBefore(today) }
                    ?.map { it.close }?.toDoubleArray()
                val d = MacdSlopes.sign(closes?.let { MacdSlopes.latest(it, smoothing) })
                if (d != 1) return@map m.copy(month = null, day = d,
                    note = if (d == null) "no daily prices" else "daily slope not rising")
                val mo = MacdLadder.monthlyBars(space, m.symbol, allowFetch = false)
                    ?: MacdLadder.monthlyBars(space, m.symbol).also {
                        try { Thread.sleep(150) } catch (_: InterruptedException) {}
                    }
                val mm = MacdSlopes.sign(mo?.let { MacdSlopes.latest(MacdSlopes.completedMonths(it, nowSec), smoothing) })
                if (mm != 1) return@map m.copy(month = mm, day = d,
                    note = if (mm == null) "no monthly prices" else "monthly slope not rising")
                val days = book.days(m.symbol) { it.headMap(today, false).size >= s.lowDays }
                val low = days?.let { FloorOrders.priorLow(it, today, s.lowDays) }
                    ?: return@map m.copy(month = mm, day = d, note = "no recent prices")
                orders.add(Order(m.symbol, m.name, low,
                    first = Leg(low, half), second = Leg(FloorOrders.secondPrice(low, s.secondPct), half),
                    fromSec = fromSec))
                m.copy(month = mm, day = d, note = "orders resting")
            }
            // A download failure is not a verdict. If most of the universe could not
            // be priced, the day is not planned yet — the next pass tries again,
            // rather than one bad minute at dawn leaving the whole day without orders.
            val unpriced = members.count { it.note == "no daily prices" || it.note == "no monthly prices" ||
                it.note == "no recent prices" }
            if (unpriced * 2 > members.size) {
                Log.w(TAG, "${a.name}: $unpriced of ${members.size} unpriced — plan retried next pass")
                return
            }
            a = a.copy(universe = members, planDay = today.toString(), orders = orders)
            val rising = members.count { FloorOrders.inUptrend(it.month, it.day) }
            note("${today.format(dayFmt)}: $rising of ${members.size} rising on month and day" +
                (if (held.isNotEmpty()) " (${held.size} already held)" else "") +
                " → ${orders.size * 2} orders resting")
        }

        private data class Hit(val symbol: String, val second: Boolean, val fill: FloorOrders.Fill, val rank: Int)

        private fun fillPass(day: LocalDate) {
            val hits = ArrayList<Hit>()
            for (o in a.orders) {
                if (o.status != "RESTING") continue
                if (o.first.filledSec > 0L && o.second.filledSec > 0L) continue
                val days = book.days(o.symbol) { it.isNotEmpty() && !it.firstKey().isAfter(day) } ?: continue
                val bars = days[day]?.filter { it.timestamp >= o.fromSec } ?: continue
                val rank = a.universe.indexOfFirst { it.symbol == o.symbol }.let { if (it < 0) 999 else it }
                if (o.first.filledSec == 0L)
                    FloorOrders.firstFill(bars, o.first.price)?.let { hits.add(Hit(o.symbol, false, it, rank)) }
                if (o.second.filledSec == 0L)
                    FloorOrders.firstFill(bars, o.second.price)?.let { hits.add(Hit(o.symbol, true, it, rank)) }
            }
            // In the order they happened — the earliest fill gets the free seat.
            for (h in hits.sortedWith(compareBy({ it.fill.sec }, { it.rank }, { it.second }))) applyFill(h, day)
        }

        private fun applyFill(h: Hit, day: LocalDate) {
            val s = a.settings
            val o = a.orders.firstOrNull { it.symbol == h.symbol } ?: return
            val leg = if (h.second) o.second else o.first
            if (leg.filledSec > 0L) return
            val qty = leg.amount / h.fill.price
            val dayStr = day.toString()
            val existing = a.trades.firstOrNull { it.symbol == h.symbol && it.day == dayStr }
            val which = if (h.second) "2nd half" else "1st half"
            val trade: Trade
            if (existing != null) {
                trade = existing.copy(qty = existing.qty + qty, cost = existing.cost + leg.amount,
                    halves = existing.halves + 1)
                note("${h.symbol}: $which filled at ${px(h.fill.price)}" +
                    (if (existing.status == "MISSED") " (followed on the side)" else ""))
                if (existing.status == "OPEN") {
                    // Both halves in one pass (one steep bar) are one event, not two alerts.
                    val same = notices.indexOfFirst { !it.sold && it.symbol == h.symbol }
                    val text = if (same >= 0) "both halves filled — full seat at an average of ${cur}${px(trade.entry)}"
                               else "2nd half filled at ${cur}${px(h.fill.price)} — now a full seat, average ${cur}${px(trade.entry)}"
                    val n = Notice(false, h.symbol, trade.entry, 0.0, text)
                    if (same >= 0) notices[same] = n else notices.add(n)
                }
            } else {
                val spans = a.trades.filter { it.status != "MISSED" }
                    .map { LocalDate.parse(it.day) to it.sellDay?.let { d -> LocalDate.parse(d) } }
                val busy = FloorOrders.seatsBusy(spans, day)
                val free = busy < s.seats
                trade = Trade(id = "${h.symbol}|$dayStr", symbol = h.symbol, name = o.name, day = dayStr,
                    firstSec = h.fill.sec, qty = qty, cost = leg.amount, halves = 1,
                    status = if (free) "OPEN" else "MISSED",
                    lastPrice = h.fill.price, lastSec = h.fill.sec)
                if (free) {
                    note("${h.symbol}: $which filled at ${px(h.fill.price)} — bought (seat ${busy + 1} of ${s.seats})")
                    notices.add(Notice(false, h.symbol, h.fill.price, 0.0,
                        "$which filled at ${cur}${px(h.fill.price)}, " +
                            (if (h.second) "${s.secondPct}% under" else "at") +
                            " its ${s.lowDays}-day low of ${cur}${px(o.low)}"))
                } else {
                    note("${h.symbol}: filled at ${px(h.fill.price)} but all ${s.seats} seats were taken — followed on the side, not bought")
                }
            }
            val newLeg = leg.copy(filledSec = h.fill.sec, fillPrice = h.fill.price)
            a = a.copy(
                orders = a.orders.map {
                    if (it.symbol != h.symbol) it
                    else if (h.second) it.copy(second = newLeg) else it.copy(first = newLeg)
                },
                trades = a.trades.filterNot { it.id == trade.id } + trade)
        }

        /** Has today's session finished, going by the prices already downloaded for
         *  a stock with orders? One stock answers for the market. */
        private fun todayOver(): Boolean {
            val sym = a.orders.firstOrNull { it.status == "RESTING" }?.symbol ?: return false
            val days = book.days(sym) { it.isNotEmpty() && !it.firstKey().isAfter(today) } ?: return false
            return FloorOrders.dayComplete(days, today, today, lastBar, zone, nowSec)
        }

        /** The plan day is over: every half still unfilled has expired. */
        private fun endDay() {
            val all = a.orders
            if (all.isEmpty()) return
            var filled = 0
            for (o in all) {
                if (o.first.filledSec > 0L) filled++
                if (o.second.filledSec > 0L) filled++
            }
            val total = all.size * 2
            a = a.copy(orders = all.map { if (it.status == "RESTING") it.copy(status = "ENDED") else it })
            note("orders for ${LocalDate.parse(a.planDay).format(dayFmt)} closed: " +
                "$filled of $total filled, ${total - filled} expired")
        }

        private fun manageOpen() {
            val s = a.settings
            var trades = a.trades
            for (t in a.trades.filter { it.status == "OPEN" }) {
                val bought = LocalDate.parse(t.day)
                val days = book.days(t.symbol) { it.isNotEmpty() && !it.firstKey().isAfter(bought) } ?: continue
                var n = t
                days.lastEntry()?.value?.lastOrNull()?.let { n = n.copy(lastPrice = it.close, lastSec = it.timestamp) }
                val sd = FloorOrders.sellDay(days, bought, s.holdDays)
                if (sd != null && FloorOrders.dayComplete(days, sd, today, lastBar, zone, nowSec)) {
                    val price = FloorOrders.closeOf(days, sd) ?: continue
                    val sec = (days[sd]?.lastOrNull()?.timestamp ?: nowSec) + 900
                    val pnl = n.qty * price - n.cost
                    val pct = if (n.cost > 0) pnl / n.cost * 100.0 else 0.0
                    n = n.copy(status = "SOLD", sellDay = sd.toString(), sellPrice = price, sellSec = sec,
                        pnl = pnl, lastPrice = price, lastSec = sec)
                    note("${t.symbol} sold at ${px(price)} at the close of ${sd.format(dayFmt)} " +
                        "(${String.format(Locale.US, "%+.2f", pct)}%, ${if (pnl >= 0) "+" else "−"}${money(Math.abs(pnl))})")
                    notices.add(Notice(true, t.symbol, price, pct,
                        "sold at the close, ${cur}${px(price)} — bought at ${cur}${px(n.entry)} on ${bought.format(dayFmt)}"))
                }
                trades = trades.map { if (it.id == t.id) n else it }
            }
            a = a.copy(trades = trades)
        }

        private fun outcomes() {
            var trades = a.trades
            for (t in a.trades) {
                if (t.done) continue
                val bought = LocalDate.parse(t.day)
                if (bought.plusDays(GIVE_UP_DAYS).isBefore(today)) {
                    trades = trades.map { if (it.id == t.id) it.copy(done = true) else it }
                    continue
                }
                if (t.status != "OPEN" && nowMs - t.checkedMs < RECHECK_MS) continue
                val days = book.days(t.symbol) { it.isNotEmpty() && !it.firstKey().isAfter(bought) } ?: continue
                val o = FloorOrders.outcome(days, bought, t.firstSec, t.entry, today, lastBar, zone, nowSec)
                trades = trades.map {
                    if (it.id != t.id) it
                    else it.copy(ret1 = o.ret1 ?: it.ret1, ret3 = o.ret3 ?: it.ret3, ret5 = o.ret5 ?: it.ret5,
                        dip = o.dip ?: it.dip, done = o.done, checkedMs = nowMs)
                }
            }
            a = a.copy(trades = trades)
        }
    }
}
