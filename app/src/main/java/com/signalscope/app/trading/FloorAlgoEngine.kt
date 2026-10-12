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
import com.signalscope.app.data.FloorJournalStore
import com.signalscope.app.network.YahooFinanceClient
import com.signalscope.app.screener.DailyBar
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * RUNS THE BUILT-IN PAPER ALGOS — floor orders and MACD turns — on paper only;
 * nothing here can reach a broker.
 *
 * One pass, every fifteen minutes with the other algos, does in order:
 *   1. floor orders: finishes yesterday — a last look for fills, then whatever
 *      is left expires;
 *   2. once a day, takes the universe from the Discovery scan — the top names by
 *      ROC, the room to the nearest resistance;
 *   3. once a day, from 06:00 on the exchange's clock, reads which of them have
 *      both the weekly and daily MACD slope rising. Floor orders rest two orders
 *      on each: half a seat at the recent low, half a little below. MACD turns
 *      watch them through the day;
 *   4. floor orders: looks for fills in today's fifteen-minute prices. MACD
 *      turns: looks for the 15-minute and hourly MACD turning up together with
 *      the 4-hour rising, and buys at the next price;
 *   5. once a day, checks the weekly and daily trend of what is held;
 *   6. sells on the stop, the target, the time limit or a broken trend;
 *   7. keeps filling in what happened after each buy (1, 3 and 5 days on, and
 *      the deepest dip).
 *
 * Floor orders take prices as they are — the feed's delay is ignored, since an
 * order resting at the broker fills whether or not the app has seen the price.
 * A MACD turn cannot be acted on before the app sees it, so it buys at the next
 * bar's opening price after the pass that saw it. No fees, as the test was asked.
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
        return "paper algos $open held · $resting resting"
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
                trendCheckDay = done.trendCheckDay, journalDay = done.journalDay, planFilter = done.planFilter,
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

    /** Fifteen-minute prices by trading day for one pass, from [Intraday]. */
    private class Book(val space: String, val zone: ZoneId) {
        private val got = HashMap<String, TreeMap<LocalDate, List<CandleData>>>()
        /** Downloads this pass actually made — the rest came from the shared copy. */
        var fetches = 0

        fun days(sym: String): TreeMap<LocalDate, List<CandleData>>? {
            got[sym]?.let { return it }
            val (bars, downloaded) = Intraday.load(space, sym)
            if (downloaded) fetches++
            val d = bars?.let { FloorOrders.byDay(it, zone) } ?: return null
            got[sym] = d
            return d
        }
    }

    /**
     * Fifteen-minute prices KEPT BETWEEN PASSES AND SHARED BY EVERY PAPER ALGO.
     * Sixty days once a day — enough for the 4-hour MACD, a 20-day hold and the
     * journal — then only today's bars, merged in, at most once every few
     * minutes per stock however many algos ask. With the trend filter off, a
     * floor algo and a MACD algo on the same market each watch the same thirty
     * stocks all day; asking Yahoo separately for each, every pass, would crowd
     * out the passes that manage real money, which download from it too.
     */
    private object Intraday {
        private data class Entry(val day: String, val bars: TreeMap<Long, CandleData>, val atMs: Long)
        private val got = ConcurrentHashMap<String, Entry>()
        /** A copy this young is reused rather than asked for again — one pass's worth. */
        private const val FRESH_MS = 5 * 60 * 1000L

        fun bars(space: String, sym: String): List<CandleData>? = load(space, sym).first

        /** The bars, and whether the network was asked this time. */
        fun load(space: String, sym: String): Pair<List<CandleData>?, Boolean> {
            val key = "$space|$sym"
            val day = LocalDate.now(ZoneOffset.UTC).toString()
            val nowMs = System.currentTimeMillis()
            val cur = got[key]?.takeIf { it.day == day }
            if (cur != null && nowMs - cur.atMs < FRESH_MS) return cur.bars.values.toList() to false
            // The refresh asks for five days, NOT one: the reader refuses any answer
            // under fifty bars, and a single day of fifteen-minute bars is at most
            // thirty-four — so a "1d" refresh failed every time, the morning copy
            // was served all day, and after forty-five minutes every stock read as
            // "not current". Five days is 125–170 bars: accepted, and still small.
            val r = try {
                YahooFinanceClient.fetchCandles15mRange(sym, Spaces.exchangeOf(space), if (cur == null) "60d" else "5d")
            } catch (e: Exception) { null }
            try { Thread.sleep(250) } catch (_: InterruptedException) {}
            val c = (r as? YahooFinanceClient.CandleResult.Success)?.candles
                ?: return cur?.bars?.values?.toList() to true
            // A fresh map each time, never the one a caller may still be reading.
            val map = TreeMap<Long, CandleData>()
            cur?.bars?.let { map.putAll(it) }
            // Keyed by start time: the bar still forming at the last look is
            // replaced by the newer copy of itself.
            //
            // ONLY BARS ON THE FIFTEEN-MINUTE GRID. Yahoo ends each answer with a
            // "last trade" bar stamped at the trade's own second (09:35:10), open
            // equal to close. Kept, those piled up through the day — one per
            // download — and each won its slot in the MACD's bars over the slot's
            // real close; and a floor order read one as a bar that OPENED below it
            // and filled at that price instead of its own (OHB, 9 Oct: 156.60, not
            // 156.70). The slot's own bar, forming or finished, says all of it.
            for (b in c) if (b.close > 0.0 && b.close.isFinite() && b.timestamp % 900L == 0L) map[b.timestamp] = b
            got.entries.removeIf { it.value.day != day }
            got[key] = Entry(day, map, nowMs)
            return map.values.toList() to true
        }
    }

    private class Pass(val ctx: Context, start: Algo) {
        var a: Algo = start
        val space = start.space
        val macd = start.kind == FloorAlgoStore.KIND_MACD
        /** The weekly-and-daily trend filter, on or off for this algo. */
        val filterOn = start.settings.trendFilter
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
        private val clockFmt = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)

        fun note(s: String) { newLog.add(FloorAlgoStore.stamp(nowMs) + " · " + s) }
        fun money(v: Double) = cur + String.format(Locale.US, "%.2f", v)
        fun px(v: Double) = String.format(Locale.US, if (v >= 1000) "%.1f" else "%.2f", v)
        fun clock(sec: Long) = Instant.ofEpochSecond(sec).atZone(zone).format(clockFmt)

        /** May this stock be bought today, as far as the trend goes? Always, with the filter off. */
        fun passes(m: Member) = !filterOn || FloorOrders.inUptrend(m.week, m.day)

        fun summary(): String {
            val open = a.trades.count { it.status == "OPEN" }
            val head = "checked ${FloorAlgoStore.stamp(nowMs)} · $open held · "
            if (a.planDay != today.toString()) return head + (if (macd) "not watching today" else "no orders today")
            if (macd) {
                val held = a.trades.filter { it.status == "OPEN" }.map { it.symbol }.toSet()
                return head + "watching ${a.universe.count { passes(it) && it.symbol !in held }} for a turn"
            }
            return head + "${a.orders.count { it.status == "RESTING" }} with orders resting"
        }

        fun run() {
            // A PAUSED algo places nothing new, but still sells what it holds on
            // schedule and keeps its record up to date — pausing must never strand
            // a position with nothing to close it.
            if (a.enabled) {
                // 0. Today was planned under the other setting of the trend filter:
                //    book any fills of the old orders up to now, then plan again.
                //    Not once the session is over: there is nothing left to trade,
                //    and the new plan could not be made until tomorrow anyway — it
                //    needs a daily bar for today that is not final yet.
                val sessionLeft = local.toLocalTime().isBefore(MarketHours.closeOf(space) ?: LocalTime.of(17, 30))
                if (a.planDay == today.toString() && a.planFilter != filterOn && sessionLeft) {
                    if (!macd && !local.toLocalTime().isBefore(openAt) && a.orders.any { it.status == "RESTING" })
                        fillPass(today)
                    a = a.copy(planDay = "")
                    note("trend filter switched ${if (filterOn) "on" else "off"} — planning today again")
                }
                // 1. The previous plan day: one last look, then what is left expires.
                if (!macd && a.planDay.isNotEmpty() && a.planDay != today.toString() &&
                    a.orders.any { it.status == "RESTING" }) {
                    fillPass(LocalDate.parse(a.planDay))
                    endDay()
                }
                // The finished plan day goes into the journal BEFORE the universe
                // and its notes are replaced. Until its prices are in, the new day
                // waits — it would need the same prices to plan anyway.
                if (journalPrevious()) {
                    // 2. The universe, once a day.
                    // A list still picked by return on capital (saved before it
                    // switched to ROC) is re-picked now rather than tomorrow.
                    if (a.universeDay != today.toString() ||
                        (a.universe.isNotEmpty() && a.universe.all { it.roc == null })) refreshUniverse()
                    // 3. Today's trend read, and the floor orders.
                    val weekday = today.dayOfWeek.value <= 5
                    if (weekday && a.planDay != today.toString() && !local.toLocalTime().isBefore(PLAN_FROM)) plan()
                }
                // 4. Fills, or turns, once the market has opened.
                if (a.planDay == today.toString() && !local.toLocalTime().isBefore(openAt)) {
                    if (macd) watchTurns()
                    else if (a.orders.any { it.status == "RESTING" }) {
                        fillPass(today)
                        if (todayOver()) endDay()
                    }
                }
            }
            // 5–7.
            checkHeldTrend()
            if (macd) fillTurns()
            manageOpen()
            outcomes()
        }

        /**
         * Write the last plan day to the journal, once, from its finished daily
         * bars — and fill in the returns of earlier rows while those bars are in
         * hand. False only while the prices are not in yet: asked again next pass.
         */
        private fun journalPrevious(): Boolean {
            val pd = a.planDay
            if (pd.isEmpty() || pd == today.toString() || a.journalDay == pd) return true
            if (a.universe.isEmpty()) { a = a.copy(journalDay = pd); return true }
            val day = LocalDate.parse(pd)
            val pending = try {
                FloorJournalStore.load(ctx, a.id).rows.filter { !it.done }.map { it.symbol }
            } catch (e: Exception) { emptyList() }
            val daily = dailyBars((a.universe.map { it.symbol } + pending).distinct()) ?: return false
            // Nothing came back at all: a download failure, not a verdict.
            if (daily.isEmpty()) return false
            val rows = FloorJournal.rows(a, day, daily)
            FloorJournalStore.mutate(ctx, a.id) { old ->
                FloorJournal.withOutcomes(old.filterNot { it.day == pd } + rows, daily, today)
            }
            a = a.copy(journalDay = pd)
            note("journal for ${day.format(dayFmt)}: ${FloorJournal.summary(rows, macd)}")
            return true
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
            // ROC: room to the nearest resistance, the Discovery table's own column.
            // Zero means no resistance was found above the price — no figure, not
            // a small one — so those are left out.
            val top = scan.allStocks
                .filter { it.rocPct.isFinite() && it.rocPct > 0.0 }
                .sortedByDescending { it.rocPct }
                .take(s.universeSize.coerceIn(1, 200))
                .map { Member(MacdLadder.bare(it.symbol), it.name, roc = it.rocPct) }
            if (top.isEmpty()) { note("the Discovery scan has no ROC figures"); return }
            val changed = top.map { it.symbol } != a.universe.map { it.symbol }
            a = a.copy(universe = top, universeDay = today.toString(), universeFrom = scan.timestamp)
            if (changed) note("universe: top ${top.size} by ROC from the Discovery scan of " +
                FloorAlgoStore.stamp(scan.timestamp))
        }

        /** Daily bars up to yesterday for these stocks — null when the series
         *  does not yet hold the last finished session, so the caller waits. */
        private fun dailyBars(symbols: List<String>): Map<String, List<DailyBar>>? {
            val daily = try {
                com.signalscope.app.screener.ScreenerRunner.fetchUniverseBars(
                    Spaces.dataSpaceOf(space), symbols, minBars = MacdSlopes.MIN_BARS).first
            } catch (e: Exception) { LinkedHashMap() }
            // The daily trend must include the last finished session. A series
            // missing it is a day behind; the read waits for the next pass.
            val newest = daily.values.mapNotNull { b -> b.lastOrNull { it.date.isBefore(today) }?.date }.maxOrNull()
            if (daily.isNotEmpty() &&
                !com.signalscope.app.screener.ScreenerRunner.dailyDataCurrent(space, newest)) {
                Log.w(TAG, "${a.name}: daily prices end at $newest — last session missing, retried next pass")
                return null
            }
            return daily.mapValues { (_, b) -> b.filter { it.date.isBefore(today) } }
        }

        /** Weekly and daily slope signs from daily bars up to yesterday. */
        private fun trendOf(bars: List<DailyBar>?, smoothing: Int): Pair<Int?, Int?> {
            if (bars.isNullOrEmpty()) return null to null
            val d = MacdSlopes.sign(MacdSlopes.latest(bars.map { it.close }.toDoubleArray(), smoothing))
            val w = MacdSlopes.sign(MacdSlopes.latest(FloorOrders.completedWeeks(bars, today), smoothing))
            return w to d
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
            val daily = dailyBars(a.universe.map { it.symbol }) ?: return
            val orders = ArrayList<Order>()
            val members = a.universe.map { m ->
                val (w, d) = trendOf(daily[m.symbol.uppercase()], smoothing)
                val base = m.copy(week = w, day = d, h4 = null, looks = 0, h4Up = 0, bothUp = 0,
                    turnSec = 0L, turnPx = 0.0, turnBlocked = false)
                if (m.symbol in held) return@map base.copy(note = "held — nothing new")
                // With the filter off the slopes are still read — for the dots
                // and the journal — but they decide nothing.
                if (filterOn) {
                    if (d == null) return@map base.copy(note = "no daily prices")
                    if (d != 1) return@map base.copy(note = "daily slope not rising")
                    if (w == null) return@map base.copy(note = "not enough weekly history")
                    if (w != 1) return@map base.copy(note = "weekly slope not rising")
                }
                if (macd) return@map base.copy(note = "watching for a turn")
                val days = book.days(m.symbol)
                val low = days?.let { FloorOrders.priorLow(it, today, s.lowDays) }
                    ?: return@map base.copy(note = "no recent prices")
                orders.add(Order(m.symbol, m.name, low,
                    first = Leg(low, half), second = Leg(FloorOrders.secondPrice(low, s.secondPct), half),
                    fromSec = fromSec))
                base.copy(note = "orders resting")
            }
            // A download failure is not a verdict. If most of the universe could not
            // be priced, the day is not planned yet — the next pass tries again,
            // rather than one bad minute at dawn leaving the whole day without orders.
            val unpriced = members.count { it.note == "no daily prices" || it.note == "no recent prices" }
            if (unpriced * 2 > members.size) {
                Log.w(TAG, "${a.name}: $unpriced of ${members.size} unpriced — plan retried next pass")
                return
            }
            a = a.copy(universe = members, planDay = today.toString(), planFilter = filterOn, orders = orders)
            val rising = members.count { FloorOrders.inUptrend(it.week, it.day) && it.symbol !in held }
            val open = members.count { it.symbol !in held && passes(it) }
            note("${today.format(dayFmt)}: " +
                (if (filterOn) "$rising of ${members.size} rising on week and day"
                 else "trend filter off — $open of ${members.size} in play ($rising rising on week and day)") +
                (if (held.isNotEmpty()) " (${held.size} already held)" else "") +
                (if (macd) " → watching them for a turn" else " → ${orders.size * 2} orders resting"))
        }

        // ── FLOOR ORDERS ─────────────────────────────────────────────────────

        private data class Hit(val symbol: String, val second: Boolean, val fill: FloorOrders.Fill, val rank: Int)

        private fun fillPass(day: LocalDate) {
            val hits = ArrayList<Hit>()
            for (o in a.orders) {
                if (o.status != "RESTING") continue
                if (o.first.filledSec > 0L && o.second.filledSec > 0L) continue
                val days = book.days(o.symbol) ?: continue
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

        private fun endOrder(symbol: String) {
            a = a.copy(orders = a.orders.map {
                if (it.symbol == symbol && it.status == "RESTING") it.copy(status = "ENDED") else it })
        }

        private fun applyFill(h: Hit, day: LocalDate) {
            val s = a.settings
            val o = a.orders.firstOrNull { it.symbol == h.symbol } ?: return
            if (o.status != "RESTING") return
            val leg = if (h.second) o.second else o.first
            if (leg.filledSec > 0L) return
            val qty = leg.amount / h.fill.price
            val dayStr = day.toString()
            val existing = a.trades.firstOrNull { it.symbol == h.symbol && it.day == dayStr }
            val which = if (h.second) "2nd half" else "1st half"
            val trade: Trade
            if (existing != null) {
                // Already sold — by the stop or the target, earlier in the day —
                // before this half could fill: what was left of the order went
                // with the sale.
                if (existing.status == "SOLD") { endOrder(h.symbol); return }
                if (existing.status == "OPEN" && existing.firstPrice > 0.0) {
                    val days = book.days(h.symbol)
                    if (days != null && position(existing, days, until = h.fill.sec).exit != null) {
                        endOrder(h.symbol); return
                    }
                }
                trade = existing.copy(qty = existing.qty + qty, cost = existing.cost + leg.amount,
                    halves = existing.halves + 1, secondSec = h.fill.sec, secondPrice = h.fill.price)
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
                    firstPrice = h.fill.price,
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
            val days = book.days(sym) ?: return false
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

        // ── MACD TURNS ───────────────────────────────────────────────────────

        /**
         * Through the session, each stock rising on week and day — and not held,
         * and not already bought or followed today — is checked for the turn:
         * the 4-hour MACD rising, and the 15-minute and hourly MACD both rising
         * now when an hour ago they were not. The same turn the watcher's
         * "turning up" alert uses, so the two can be compared.
         */
        private fun watchTurns() {
            if (!MarketHours.isRegularSession(space, nowMs)) return
            val smoothing = MacdLadder.smoothingFor(ctx, space)
            val delay = FeedDelay.secFor(space)
            val todayStr = today.toString()
            val skip = a.trades.filter { it.status == "OPEN" || it.day == todayStr }.map { it.symbol }.toSet()
            val members = a.universe.map { m ->
                if (!passes(m) || m.symbol in skip) return@map m
                val c15 = Intraday.bars(space, m.symbol)
                    ?: return@map m.copy(note = "no intraday prices this pass")
                val last = c15.lastOrNull()
                if (last == null || !MarketHours.barIsCurrent(last.timestamp, 900L, nowSec, delay))
                    return@map m.copy(note = "intraday prices not current")
                val s15 = MacdSlopes.series(MacdSlopes.resample(c15, 15 * 60L, nowSec, delay), smoothing)
                val s1h = MacdSlopes.series(MacdSlopes.resample(c15, 3600L, nowSec, delay), smoothing)
                val s4h = MacdSlopes.series(MacdSlopes.resample(c15, 4 * 3600L, nowSec, delay), smoothing)
                val h4 = MacdSlopes.sign(s4h?.lastOrNull())
                if (s15 == null || s1h == null || h4 == null)
                    return@map m.copy(h4 = h4, note = "not enough intraday history")
                // The day so far, for the journal.
                val both = s15.last() > 0.0 && s1h.last() > 0.0
                val turned = MacdSlopes.turnedUp(s15, s1h)
                val seen = m.copy(h4 = h4, looks = m.looks + 1, h4Up = m.h4Up + (if (h4 == 1) 1 else 0),
                    bothUp = m.bothUp + (if (both) 1 else 0))
                if (h4 != 1) {
                    // A turn the 4-hour kept out — the near miss of this strategy.
                    if (turned && m.turnSec == 0L) {
                        note("${m.symbol}: 15m and 1h MACD turned up, but the 4-hour is not rising — not bought")
                        return@map seen.copy(turnSec = nowSec, turnPx = last.close, turnBlocked = true,
                            note = "turned up ${clock(nowSec)}, but 4-hour not rising")
                    }
                    return@map seen.copy(note = if (m.turnBlocked) m.note else "4-hour slope not rising")
                }
                if (!turned) return@map seen.copy(note =
                    if (m.turnBlocked) m.note else "waiting for 15m and 1h to turn up together")
                seen.copy(turnSec = nowSec, turnPx = last.close, turnBlocked = false, note = signal(m))
            }
            a = a.copy(universe = members)
        }

        /** A turn seen: a seat is taken now, and filled at the next bar's open. */
        private fun signal(m: Member): String {
            val s = a.settings
            val spans = a.trades.filter { it.status != "MISSED" }
                .map { LocalDate.parse(it.day) to it.sellDay?.let { d -> LocalDate.parse(d) } }
            val busy = FloorOrders.seatsBusy(spans, today)
            val free = busy < s.seats
            val t = Trade(id = "${m.symbol}|$today", symbol = m.symbol, name = m.name, day = today.toString(),
                signalSec = nowSec, fromSec = ((nowSec + 899) / 900) * 900,
                status = if (free) "OPEN" else "MISSED")
            a = a.copy(trades = a.trades.filterNot { it.id == t.id } + t)
            val at = clock(nowSec)
            return if (free) {
                note("${m.symbol}: 15m and 1h MACD turned up (4h rising) — buying at the next price (seat ${busy + 1} of ${s.seats})")
                "turned up $at — buying"
            } else {
                note("${m.symbol}: turned up, but all ${s.seats} seats were taken — followed on the side, not bought")
                "turned up $at — seats full, followed"
            }
        }

        /** Turns seen on an earlier pass, filled at the first bar from then on. */
        private fun fillTurns() {
            val s = a.settings
            val amount = s.capital / s.seats.coerceAtLeast(1)
            for (t in a.trades.filter { it.firstSec == 0L && it.signalSec > 0L && it.status != "SOLD" }) {
                val day = LocalDate.parse(t.day)
                val days = book.days(t.symbol) ?: continue
                val f = FloorOrders.nextOpen(days, day, t.fromSec)
                if (f == null) {
                    // The session ended before a price came: nothing was bought.
                    if (FloorOrders.dayComplete(days, day, today, lastBar, zone, nowSec)) {
                        a = a.copy(trades = a.trades.filterNot { it.id == t.id })
                        note("${t.symbol}: turned up too late in the session to buy — dropped")
                    }
                    continue
                }
                val n = t.copy(firstSec = f.sec, firstPrice = f.price, qty = amount / f.price, cost = amount,
                    halves = 1, lastPrice = f.price, lastSec = f.sec)
                a = a.copy(trades = a.trades.map { if (it.id == t.id) n else it })
                if (t.status == "OPEN") {
                    note("${t.symbol}: bought at ${px(f.price)} (${clock(f.sec)} open)")
                    notices.add(Notice(false, t.symbol, f.price, 0.0,
                        "15m and 1h MACD turned up at ${clock(t.signalSec)} with the 4h rising — " +
                            "bought at the next price, ${cur}${px(f.price)}"))
                }
            }
        }

        // ── HELD POSITIONS ───────────────────────────────────────────────────

        /**
         * Once a day from 06:00, paused or not: is each held stock still rising
         * on the week and the day? One that is not is sold at today's close,
         * unless the stop or the target sells it first. A stock bought today was
         * checked this very morning before buying, so it waits for tomorrow.
         */
        private fun checkHeldTrend() {
            if (a.trendCheckDay == today.toString()) return
            if (today.dayOfWeek.value > 5 || local.toLocalTime().isBefore(PLAN_FROM)) return
            val todayStr = today.toString()
            // Bought without the filter, so never sold by it.
            if (!filterOn) { a = a.copy(trendCheckDay = todayStr); return }
            val held = a.trades.filter {
                it.status == "OPEN" && it.firstPrice > 0.0 && it.trendDay == null && it.day != todayStr
            }
            if (held.isNotEmpty()) {
                val smoothing = MacdLadder.smoothingFor(ctx, space)
                // Not current yet, or nothing came back at all: asked again next pass.
                val daily = dailyBars(held.map { it.symbol }.distinct())?.takeIf { it.isNotEmpty() } ?: return
                var trades = a.trades
                for (t in held) {
                    val (w, d) = trendOf(daily[t.symbol.uppercase()], smoothing)
                    if (!FloorOrders.trendBroken(w, d)) continue
                    trades = trades.map { if (it.id == t.id) it.copy(trendDay = todayStr) else it }
                    val what = listOfNotNull(if (w != 1) "weekly" else null, if (d != 1) "daily" else null).joinToString(" and ")
                    note("${t.symbol}: $what MACD no longer rising — sold at today's close unless the stop or target comes first")
                }
                a = a.copy(trades = trades)
            }
            a = a.copy(trendCheckDay = todayStr)
        }

        /** Where a position stands: replayed from its buys through every price since. */
        private fun position(t: Trade, days: TreeMap<LocalDate, List<CandleData>>,
                             until: Long = Long.MAX_VALUE): FloorOrders.Position {
            val s = a.settings
            // Bought before stops and targets existed: kept to the rule it was
            // bought under — sold on time only.
            val legacy = t.firstPrice <= 0.0
            val buys = when {
                legacy -> listOf(FloorOrders.Buy(t.firstSec, t.entry, t.cost))
                t.halves >= 2 && t.secondSec > 0L -> listOf(
                    FloorOrders.Buy(t.firstSec, t.firstPrice, t.cost / 2.0),
                    FloorOrders.Buy(t.secondSec, t.secondPrice, t.cost / 2.0))
                else -> listOf(FloorOrders.Buy(t.firstSec, t.firstPrice, t.cost))
            }
            return FloorOrders.walk(days, buys,
                stopPct = if (legacy) 0.0 else s.stopPct, targetPct = if (legacy) 0.0 else s.targetPct,
                // A break found while the filter was on no longer sells once it is
                // switched off.
                holdDays = s.holdDays, trendDay = if (legacy || !filterOn) null else t.trendDay?.let { LocalDate.parse(it) },
                today = today, lastBar = lastBar, zone = zone, nowSec = nowSec, until = until)
        }

        private fun reasonText(reason: String, holdDays: Int) = when (reason) {
            "stop" -> "stop-loss hit"
            "target" -> "target reached"
            "trend" -> "trend broke — sold at the close"
            else -> "held $holdDays trading days — sold at the close"
        }

        private fun manageOpen() {
            val s = a.settings
            for (t in a.trades.filter { it.status == "OPEN" && it.firstSec > 0L }) {
                val bought = LocalDate.parse(t.day)
                val days = book.days(t.symbol) ?: continue
                var n = t
                days.lastEntry()?.value?.lastOrNull()?.let { n = n.copy(lastPrice = it.close, lastSec = it.timestamp) }
                val pos = position(t, days)
                n = n.copy(stop = pos.stop, target = pos.target)
                val ex = pos.exit
                if (ex != null) {
                    // What was held AT the exit — a half the replay says came after
                    // it is not part of the sale.
                    val qty = if (pos.qty > 0.0) pos.qty else n.qty
                    val cost = if (pos.qty > 0.0) pos.cost else n.cost
                    val pnl = qty * ex.price - cost
                    val pct = if (cost > 0) pnl / cost * 100.0 else 0.0
                    val entry = if (qty > 0) cost / qty else 0.0
                    n = n.copy(status = "SOLD", sellDay = ex.day.toString(), sellPrice = ex.price, sellSec = ex.sec,
                        exitReason = ex.reason, qty = qty, cost = cost, pnl = pnl,
                        lastPrice = ex.price, lastSec = ex.sec)
                    val why = reasonText(ex.reason, s.holdDays)
                    val pctStr = String.format(Locale.US, "%+.2f", pct)
                    val moneyStr = (if (pnl >= 0) "+" else "−") + money(Math.abs(pnl))
                    note("${t.symbol} sold at ${px(ex.price)} on ${ex.day.format(dayFmt)} — $why ($pctStr%, $moneyStr)")
                    notices.add(Notice(true, t.symbol, ex.price, pct,
                        "$why at ${cur}${px(ex.price)} — bought at ${cur}${px(entry)} on ${bought.format(dayFmt)}"))
                    // A sale on the buying day takes what is left of that day's orders with it.
                    if (a.planDay == t.day) endOrder(t.symbol)
                }
                a = a.copy(trades = a.trades.map { if (it.id == t.id) n else it })
            }
        }

        private fun outcomes() {
            var trades = a.trades
            for (t in a.trades) {
                if (t.done || t.firstSec == 0L) continue
                val bought = LocalDate.parse(t.day)
                if (bought.plusDays(GIVE_UP_DAYS).isBefore(today)) {
                    trades = trades.map { if (it.id == t.id) it.copy(done = true) else it }
                    continue
                }
                if (t.status != "OPEN" && nowMs - t.checkedMs < RECHECK_MS) continue
                val days = book.days(t.symbol) ?: continue
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
