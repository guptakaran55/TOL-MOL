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
                planMode = done.planMode, campaigns = done.campaigns, chains = done.chains, outlooks = done.outlooks,
                orders = done.orders, trades = done.trades,
                log = (fresh.log + pass.newLog), lastRunMs = System.currentTimeMillis(),
                lastNote = pass.summary())
        } ?: return
        if (saved.notify) for (n in pass.notices) {
            try {
                if (n.note) LabNotifier.campaignNote(ctx, saved.name, saved.ns, n.symbol, n.text)
                else if (n.sold) LabNotifier.floorSold(ctx, saved.name, saved.ns, n.symbol, n.price, n.pct, n.text)
                else LabNotifier.floorBought(ctx, saved.name, saved.ns, n.symbol, n.price, n.text)
            } catch (e: Exception) { Log.w(TAG, "notice failed", e) }
        }
        Log.i(TAG, "${saved.name}: ${saved.lastNote} (${pass.book.fetches} downloads)")
    }

    /** [note]: a campaign's zone notice — neither a buy nor a sale. */
    private data class Notice(val sold: Boolean, val symbol: String, val price: Double,
                              val pct: Double, val text: String, val note: Boolean = false)

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
        /** Floor orders that wait in the weekly support zone instead of expiring. */
        val campaign = start.kind == FloorAlgoStore.KIND_CAMPAIGN
        /** The monthly-low chain: see [ChainRules]. */
        val chain = start.kind == FloorAlgoStore.KIND_CHAIN
        val weekMode = start.kind == FloorAlgoStore.KIND_FLOOR && start.settings.orderMode == FloorAlgoStore.MODE_WEEK
        val orderMode = if (weekMode) FloorAlgoStore.MODE_WEEK else FloorAlgoStore.MODE_DAY
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
            if (chain) {
                val live = a.chains.filter { it.state != "ENDED" }
                val counted = live.filter { it.counted }
                return "checked ${FloorAlgoStore.stamp(nowMs)} · ${counted.size}/${a.settings.seats} seats " +
                    "(${counted.count { it.state == "HOLDING" }} holding, ${counted.count { it.state == "WATCHING" }} watching)" +
                    " · ${live.size - counted.size} followed on the side"
            }
            if (campaign) {
                val live = a.campaigns.filter { it.state != "ENDED" }
                return "checked ${FloorAlgoStore.stamp(nowMs)} · ${live.size} campaigns running " +
                    "(${live.count { it.state == "HOLDING" || it.state == "RIDING" }} holding, " +
                    "${live.count { it.state == "OUT" }} between swings, ${live.count { it.state == "WAITING" }} waiting to start)"
            }
            val open = a.trades.count { it.status == "OPEN" }
            val head = "checked ${FloorAlgoStore.stamp(nowMs)} · $open held · "
            if (a.planDay != today.toString()) return head + (if (macd) "not watching today" else "no orders today")
            if (macd) {
                val held = a.trades.filter { it.status == "OPEN" }.map { it.symbol }.toSet()
                return head + "watching ${a.universe.count { passes(it) && it.symbol !in held }} for a turn"
            }
            return head + "${a.orders.count { it.status == "RESTING" }} with orders " + (if (weekMode) "waiting" else "resting")
        }

        /** From the open to an hour after the close, on a weekday — when prices move. */
        fun inSessionWindow(): Boolean {
            val t = local.toLocalTime()
            val closeAt = MarketHours.closeOf(space) ?: LocalTime.of(17, 30)
            return today.dayOfWeek.value <= 5 && !t.isBefore(openAt) && !t.isAfter(closeAt.plusHours(1))
        }

        fun isDay(o: Order) = o.mode != FloorAlgoStore.MODE_WEEK
        fun isWeek(o: Order) = o.mode == FloorAlgoStore.MODE_WEEK

        fun run() {
            if (campaign) { runCampaigns(); return }
            if (chain) { runChain(); return }
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
                val modeChanged = !macd && a.planMode != orderMode
                if (a.planDay == today.toString() && (a.planFilter != filterOn || modeChanged) && sessionLeft) {
                    if (!macd && !local.toLocalTime().isBefore(openAt)) {
                        if (a.orders.any { it.status == "RESTING" && isDay(it) }) fillPass(today)
                        if (a.orders.any { it.status == "RESTING" && isWeek(it) }) weekPass()
                    }
                    a = a.copy(planDay = "")
                    note(if (modeChanged) "order type switched to ${if (weekMode) "weekly zone" else "daily"} — planning today again"
                         else "trend filter switched ${if (filterOn) "on" else "off"} — planning today again")
                }
                // 1. The previous plan day: one last look, then what is left of
                //    its DAILY orders expires. Weekly orders wait on.
                if (!macd && a.planDay.isNotEmpty() && a.planDay != today.toString() &&
                    a.orders.any { it.status == "RESTING" && isDay(it) }) {
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
                    else if (a.orders.any { it.status == "RESTING" && isDay(it) }) {
                        fillPass(today)
                        if (todayOver()) endDay()
                    }
                }
                // Weekly orders: every bar since each was placed, whatever day —
                // so a day the phone slept through is caught up on, not lost.
                if (!macd && inSessionWindow() && a.orders.any { it.status == "RESTING" && isWeek(it) }) weekPass()
            }
            // 5–7.
            checkHeldTrend()
            if (macd) fillTurns()
            chargeOldSales()
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
            // a small one — so those are left out of a TOP list. ALL keeps every
            // name the scan kept (it has already applied the market's volume ×
            // price floor), those with no figure last.
            val all = s.universeMode == "all"
            val top = scan.allStocks
                .filter { all || (it.rocPct.isFinite() && it.rocPct > 0.0) }
                .sortedByDescending { if (it.rocPct.isFinite() && it.rocPct > 0.0) it.rocPct else -1.0 }
                .let { if (all) it else it.take(s.universeSize.coerceIn(1, 200)) }
                .map { Member(MacdLadder.bare(it.symbol), it.name, roc = it.rocPct.takeIf { r -> r.isFinite() && r > 0.0 }) }
                .distinctBy { it.symbol }
            if (top.isEmpty()) { note("the Discovery scan has no ROC figures"); return }
            val changed = top.map { it.symbol } != a.universe.map { it.symbol }
            a = a.copy(universe = top, universeDay = today.toString(), universeFrom = scan.timestamp)
            if (changed) note("universe: " + (if (all) "all ${top.size} stocks that passed the volume × price floor"
                else "top ${top.size} by ROC") + " from the Discovery scan of " + FloorAlgoStore.stamp(scan.timestamp))
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
            // Weekly orders still waiting from earlier days carry over at their
            // own prices — that is the point of them. One that has filled a half
            // keeps its other half waiting even though the stock is now held.
            val waiting = if (weekMode) a.orders.filter { isWeek(it) && it.status == "RESTING" }.associateBy { it.symbol }
                          else emptyMap()
            val inUniverse = a.universe.map { it.symbol }.toSet()
            val keep = HashSet<String>()
            waiting.values.forEach { o ->
                if (o.symbol !in inUniverse || o.first.filledSec > 0L || o.second.filledSec > 0L) keep.add(o.symbol)
            }
            val members = a.universe.map { m ->
                val (w, d) = trendOf(daily[m.symbol.uppercase()], smoothing)
                val base = m.copy(week = w, day = d, h4 = null, looks = 0, h4Up = 0, bothUp = 0,
                    turnSec = 0L, turnPx = 0.0, turnBlocked = false)
                if (m.symbol in held) return@map base.copy(note =
                    if (m.symbol in keep) "held — 2nd half still waiting" else "held — nothing new")
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
                if (weekMode) {
                    waiting[m.symbol]?.let { o ->
                        keep.add(m.symbol)
                        return@map base.copy(note = "order waiting since ${LocalDate.parse(o.placedDay).format(dayFmt)}")
                    }
                    val z = days?.let { FloorOrders.weekZone(it, today, s.zonePct) }
                        ?: return@map base.copy(note = "no recent prices")
                    // Already up in its resistance zone: an order would be cancelled
                    // by the first bar. Tomorrow's week is read afresh.
                    val lastClose = days.headMap(today, false).lastEntry()?.value?.lastOrNull()?.close ?: 0.0
                    if (lastClose >= z.cancelAt) return@map base.copy(note = "at its weekly resistance — no order")
                    orders.add(Order(m.symbol, m.name, z.buyAt,
                        first = Leg(z.buyAt, half), second = Leg(FloorOrders.secondPrice(z.buyAt, s.secondPct), half),
                        fromSec = fromSec, mode = FloorAlgoStore.MODE_WEEK, placedDay = today.toString(),
                        cancelAt = z.cancelAt))
                    return@map base.copy(note = "order placed in its weekly support zone")
                }
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
            // Waiting orders the trend filter now keeps out are cancelled: the
            // rest carry over beside today's new ones.
            val carried = waiting.values.filter { it.symbol in keep }
            val dropped = waiting.size - carried.size
            a = a.copy(universe = members, planDay = today.toString(), planFilter = filterOn, planMode = orderMode,
                orders = carried + orders)
            val rising = members.count { FloorOrders.inUptrend(it.week, it.day) && it.symbol !in held }
            val open = members.count { it.symbol !in held && passes(it) }
            note("${today.format(dayFmt)}: " +
                (if (filterOn) "$rising of ${members.size} rising on week and day"
                 else "trend filter off — $open of ${members.size} in play ($rising rising on week and day)") +
                (if (held.isNotEmpty()) " (${held.size} already held)" else "") +
                when {
                    macd -> " → watching them for a turn"
                    weekMode -> " → ${orders.size} new weekly orders, ${carried.size} still waiting" +
                        (if (dropped > 0) ", $dropped cancelled by the trend filter" else "")
                    else -> " → ${orders.size * 2} orders resting"
                })
        }

        // ── FLOOR ORDERS ─────────────────────────────────────────────────────

        private data class Hit(val symbol: String, val second: Boolean, val fill: FloorOrders.Fill, val rank: Int)

        private fun fillPass(day: LocalDate) {
            val hits = ArrayList<Hit>()
            for (o in a.orders) {
                if (o.status != "RESTING" || !isDay(o)) continue
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

        /**
         * WAITING WEEKLY ORDERS, through every bar since each was placed: the
         * halves that dipped into the support zone fill — in the order they
         * happened, across all of them, so the earliest gets the free seat — and
         * an order whose price reached the resistance zone first is cancelled
         * (a half still waiting with it). Worked out afresh from the prices each
         * time, so a day the phone slept through is caught up, not lost.
         */
        private fun weekPass() {
            val hits = ArrayList<Hit>()
            val cancels = HashMap<String, Long>()
            for (o in a.orders) {
                if (o.status != "RESTING" || !isWeek(o)) continue
                val days = book.days(o.symbol) ?: continue
                val bars = days.values.flatten().filter { it.timestamp >= o.fromSec }
                if (bars.isEmpty()) continue
                val f = FloorOrders.weekFate(bars, o.first.price, o.second.price, o.cancelAt)
                val rank = a.universe.indexOfFirst { it.symbol == o.symbol }.let { if (it < 0) 999 else it }
                if (o.first.filledSec == 0L) f.first?.let { hits.add(Hit(o.symbol, false, it, rank)) }
                if (o.second.filledSec == 0L) f.second?.let { hits.add(Hit(o.symbol, true, it, rank)) }
                if (f.cancelSec > 0L) cancels[o.symbol] = f.cancelSec
            }
            for (h in hits.sortedWith(compareBy({ it.fill.sec }, { it.rank }, { it.second })))
                applyFill(h, Instant.ofEpochSecond(h.fill.sec).atZone(zone).toLocalDate())
            // Both halves in: the order is done.
            a = a.copy(orders = a.orders.map {
                if (isWeek(it) && it.status == "RESTING" && it.first.filledSec > 0L && it.second.filledSec > 0L)
                    it.copy(status = "ENDED") else it })
            for ((sym, sec) in cancels) {
                val o = a.orders.firstOrNull { it.symbol == sym && isWeek(it) && it.status == "RESTING" } ?: continue
                a = a.copy(orders = a.orders.map {
                    if (it.symbol == sym && isWeek(it) && it.status == "RESTING") it.copy(status = "CANCELLED", cancelSec = sec) else it })
                val partly = o.first.filledSec > 0L || o.second.filledSec > 0L
                note("$sym: reached its weekly resistance zone (${px(o.cancelAt)}) at ${clock(sec)} on " +
                    "${Instant.ofEpochSecond(sec).atZone(zone).toLocalDate().format(dayFmt)} — " +
                    (if (partly) "the half still waiting is cancelled"
                     else "it never dipped, so the order is cancelled; a new one is placed from the next week's range"))
            }
        }

        private fun endOrder(symbol: String) {
            a = a.copy(orders = a.orders.map {
                if (it.symbol == symbol && it.status == "RESTING") it.copy(status = "ENDED") else it })
        }

        private fun applyFill(h: Hit, day: LocalDate) {
            val s = a.settings
            val o = a.orders.firstOrNull { it.symbol == h.symbol && it.status == "RESTING" } ?: return
            val leg = if (h.second) o.second else o.first
            if (leg.filledSec > 0L) return
            val qty = leg.amount / h.fill.price
            val dayStr = day.toString()
            // The trade this order's first half went into — for a weekly order the
            // second half may come days later. Daily orders saved before orders
            // remembered their trade are matched by the day, as they always were.
            val existing = (if (o.tradeId.isNotEmpty()) a.trades.firstOrNull { it.id == o.tradeId } else null)
                ?: if (o.tradeId.isEmpty() && isDay(o)) a.trades.firstOrNull { it.symbol == h.symbol && it.day == dayStr } else null
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
                // One trade per stock per day as a rule — but a weekly order can fill
                // on a day another of the stock's trades already carries.
                var id = "${h.symbol}|$dayStr"
                if (a.trades.any { it.id == id }) id += "|${o.fromSec}"
                trade = Trade(id = id, symbol = h.symbol, name = o.name, day = dayStr,
                    firstSec = h.fill.sec, qty = qty, cost = leg.amount, halves = 1,
                    firstPrice = h.fill.price,
                    status = if (free) "OPEN" else "MISSED",
                    lastPrice = h.fill.price, lastSec = h.fill.sec)
                if (free) {
                    note("${h.symbol}: $which filled at ${px(h.fill.price)} — bought (seat ${busy + 1} of ${s.seats})")
                    notices.add(Notice(false, h.symbol, h.fill.price, 0.0,
                        "$which filled at ${cur}${px(h.fill.price)}, " +
                            (if (isWeek(o)) (if (h.second) "${s.secondPct}% under " else "at ") +
                                "the top of its weekly support zone, ${cur}${px(o.low)} — the order waited since " +
                                LocalDate.parse(o.placedDay).format(dayFmt)
                             else (if (h.second) "${s.secondPct}% under" else "at") +
                                " its ${s.lowDays}-day low of ${cur}${px(o.low)}")))
                } else {
                    note("${h.symbol}: filled at ${px(h.fill.price)} but all ${s.seats} seats were taken — followed on the side, not bought")
                }
            }
            val newLeg = leg.copy(filledSec = h.fill.sec, fillPrice = h.fill.price)
            a = a.copy(
                orders = a.orders.map {
                    if (it.symbol != h.symbol || it.status != "RESTING") it
                    else if (h.second) it.copy(second = newLeg, tradeId = trade.id)
                    else it.copy(first = newLeg, tradeId = trade.id)
                },
                trades = a.trades.filterNot { it.id == trade.id } + trade)
        }

        /** Has today's session finished, going by the prices already downloaded for
         *  a stock with orders? One stock answers for the market. */
        private fun todayOver(): Boolean {
            val sym = a.orders.firstOrNull { it.status == "RESTING" && isDay(it) }?.symbol ?: return false
            val days = book.days(sym) ?: return false
            return FloorOrders.dayComplete(days, today, today, lastBar, zone, nowSec)
        }

        /** The plan day is over: every DAILY half still unfilled has expired.
         *  Weekly orders are not touched — they wait on. */
        private fun endDay() {
            val all = a.orders.filter { isDay(it) }
            if (all.isEmpty()) return
            var filled = 0
            for (o in all) {
                if (o.first.filledSec > 0L) filled++
                if (o.second.filledSec > 0L) filled++
            }
            val total = all.size * 2
            a = a.copy(orders = a.orders.map { if (it.status == "RESTING" && isDay(it)) it.copy(status = "ENDED") else it })
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
        // ── THE BROKER'S CHARGES ─────────────────────────────────────────────
        // The live algos' own rate card (TradingCosts), so a paper result and a
        // real one are charged alike: Trading 212 — nothing on German shares, its
        // currency conversion on both legs of a US one; Zerodha — the statutory
        // charges, a flat depository fee per sale, GST on the services, and the
        // intraday card when bought and sold the same day.
        private val config = com.signalscope.app.data.ConfigManager(ctx)
        private val accountCurrency = try { config.accountCurrency } catch (e: Exception) { "EUR" }
        private val fxPct = try { config.fxConversionPct } catch (e: Exception) { TradingCosts.T212_FX_PCT_PER_SIDE }

        /** The charges on a round trip bought on [buyDay] for [buyValue] and sold on
         *  [sellDay] for [sellValue], in the market's currency. */
        fun feesFor(buyDay: String, buyValue: Double, sellValue: Double, sellDay: String): Double = try {
            TradingCosts.chargesFor(space, buyValue, sellValue, accountCurrency, fxPct,
                intraday = buyDay == sellDay).cashRoundTrip
        } catch (e: Exception) { 0.0 }

        /** Half the spread, in percent, per market execution. */
        private val spreadPct = (start.settings.spreadPct ?: FloorAlgoStore.defaultSpreadPct(space)).coerceAtLeast(0.0)

        /**
         * The spread a round trip pays: on the buy if it was at the market (a MACD
         * turn — a floor buy is a limit order), and on the sale unless it was the
         * target (a limit order too). Held or followed, the sale is assumed to be
         * at the market — the cautious guess, since only the target avoids it.
         */
        fun slipFor(buyValue: Double, sellValue: Double, sellReason: String?): Double {
            val buy = if (macd || chain) buyValue else 0.0
            val sell = if (sellReason == "target") 0.0 else sellValue
            return spreadPct / 100.0 * (buy + sell)
        }

        /** Sales booked before charges or the spread were modelled get them now —
         *  once — so the booked total never mixes results with and without them. */
        private fun chargeOldSales() {
            if (a.trades.none { it.status == "SOLD" && (!it.feesIn || !it.slipIn) }) return
            a = a.copy(trades = a.trades.map { t ->
                if (t.status != "SOLD" || (t.feesIn && t.slipIn)) t
                else {
                    val sell = t.qty * t.sellPrice
                    val fees = if (t.feesIn) t.fees else feesFor(t.day, t.cost, sell, t.sellDay ?: t.day)
                    val slip = slipFor(t.cost, sell, t.exitReason)
                    t.copy(fees = fees, slip = slip, pnl = sell - t.cost - fees - slip, feesIn = true, slipIn = true)
                }
            })
        }

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
                n = n.copy(stop = pos.stop, target = pos.target,
                    fees = feesFor(t.day, n.cost, n.qty * n.lastPrice, today.toString()),
                    slip = slipFor(n.cost, n.qty * n.lastPrice, null))
                val ex = pos.exit
                if (ex != null) {
                    // What was held AT the exit — a half the replay says came after
                    // it is not part of the sale.
                    val qty = if (pos.qty > 0.0) pos.qty else n.qty
                    val cost = if (pos.qty > 0.0) pos.cost else n.cost
                    val fees = feesFor(t.day, cost, qty * ex.price, ex.day.toString())
                    val slip = slipFor(cost, qty * ex.price, ex.reason)
                    val pnl = qty * ex.price - cost - fees - slip
                    val pct = if (cost > 0) pnl / cost * 100.0 else 0.0
                    val entry = if (qty > 0) cost / qty else 0.0
                    n = n.copy(status = "SOLD", sellDay = ex.day.toString(), sellPrice = ex.price, sellSec = ex.sec,
                        exitReason = ex.reason, qty = qty, cost = cost, pnl = pnl, fees = fees, feesIn = true,
                        slip = slip, slipIn = true, lastPrice = ex.price, lastSec = ex.sec)
                    val why = reasonText(ex.reason, s.holdDays)
                    val pctStr = String.format(Locale.US, "%+.2f", pct)
                    val moneyStr = (if (pnl >= 0) "+" else "−") + money(Math.abs(pnl))
                    note("${t.symbol} sold at ${px(ex.price)} on ${ex.day.format(dayFmt)} — $why ($pctStr%, $moneyStr" +
                        (if (fees > 0.0) " after ${money(fees)} charges" else "") +
                        (if (slip > 0.0) (if (fees > 0.0) " and " else " after ") + "${money(slip)} spread" else "") + ")")
                    notices.add(Notice(true, t.symbol, ex.price, pct,
                        "$why at ${cur}${px(ex.price)} — bought at ${cur}${px(entry)} on ${bought.format(dayFmt)}"))
                    // A sale on the buying day takes what is left of that day's orders with it.
                    // A weekly order's half still waiting goes with the sale too,
                    // whatever day the first half filled.
                    a = a.copy(orders = a.orders.map {
                        if (it.status == "RESTING" && (it.tradeId == t.id || (isDay(it) && it.symbol == t.symbol && a.planDay == t.day)))
                            it.copy(status = "ENDED") else it })
                }
                a = a.copy(trades = a.trades.map { if (it.id == t.id) n else it })
            }
            followedPrices()
        }

        /**
         * The latest price of the buys FOLLOWED ON THE SIDE — filled or turned up
         * while every seat was taken — so the card can show how each is doing,
         * not only the ones that got a seat. Only while their outcome is still
         * being tracked, and only from the open to an hour after the close: after
         * that the prices do not move, and asking again all night is waste.
         */
        private fun followedPrices() {
            if (!inSessionWindow()) return
            var trades = a.trades
            for (f in a.trades.filter { it.status == "MISSED" && it.firstSec > 0L && !it.done }) {
                val last = book.days(f.symbol)?.lastEntry()?.value?.lastOrNull() ?: continue
                if (last.timestamp <= f.lastSec) continue
                trades = trades.map {
                    if (it.id == f.id) it.copy(lastPrice = last.close, lastSec = last.timestamp,
                        fees = feesFor(f.day, f.cost, f.qty * last.close, today.toString()),
                        slip = slipFor(f.cost, f.qty * last.close, null))
                    else it }
            }
            a = a.copy(trades = trades)
        }

        // ── CAMPAIGNS ────────────────────────────────────────────────────────
        // The rules are in CampaignRules; this is the bookkeeping: the morning's
        // levels and exits, new campaigns while seats are free, and each finished
        // fifteen-minute bar through every campaign running.

        /** News outlooks still allowed this morning — each is a paid call to the
         *  language model. Set from the algo's daily cap when the morning starts. */
        private var outlookBudget = 0

        private fun runCampaigns() {
            if (a.enabled && a.universeDay != today.toString()) refreshUniverse()
            if (today.dayOfWeek.value <= 5 && a.planDay != today.toString() && !local.toLocalTime().isBefore(PLAN_FROM))
                campaignMorning()
            if (a.planDay == today.toString() && inSessionWindow()) campaignBars()
            chargeOldSales()
            outcomes()
        }

        private fun applyLevels(c: FloorAlgoStore.Campaign, L: CampaignRules.Levels, negDays: Int) = c.copy(
            levelsDay = today.toString(), support = L.support, weekRes = L.weekRes, monthRes = L.monthRes,
            prevHigh = L.prevHigh, prevLow = L.prevLow, atr = L.atr, bbHalf = L.bbHalf,
            bufDay = L.bufDay, bufWeek = L.bufWeek, bufMonth = L.bufMonth, negDays = negDays, inWeekZone = false)

        /**
         * Once a day from 06:00: today's levels for every campaign running; the
         * exits the daily MACD calls for; waiting entries re-priced (or dropped if
         * the stock has left its support zone); and new campaigns, in the order of
         * the list, while seats are free — each asked of the news outlook first.
         */
        private fun campaignMorning() {
            val s = a.settings
            val smoothing = MacdLadder.smoothingFor(ctx, space)
            val fromSec = ((nowSec + 899) / 900) * 900
            val todayStr = today.toString()
            val syms = (a.universe.map { it.symbol } + a.campaigns.filter { it.state != "ENDED" }.map { it.symbol }).distinct()
            if (syms.isEmpty()) { a = a.copy(planDay = todayStr); return }
            val daily = dailyBars(syms)?.takeIf { it.isNotEmpty() } ?: return
            fun slopes(bars: List<DailyBar>) =
                MacdSlopes.series(bars.map { it.close }.toDoubleArray(), smoothing) ?: DoubleArray(0)

            val kept = ArrayList<FloorAlgoStore.Campaign>()
            for (c in a.campaigns) {
                if (c.state == "ENDED") { kept.add(c); continue }
                val bars = daily[c.symbol.uppercase()]
                val L = bars?.let { CampaignRules.levels(it, s.atrFrac, s.levelSource) }
                if (bars == null || L == null) { kept.add(c); continue }
                val ser = slopes(bars)
                var n = applyLevels(c, L, CampaignRules.negDays(ser))
                if (c.state == "WAITING") {
                    val falling = CampaignRules.negDays(ser) > 3
                    if (!a.enabled || !CampaignRules.isCandidate(L, L.prevClose) || falling) {
                        note("${c.symbol}: " + (when {
                            !a.enabled -> "algo paused"
                            falling -> "daily MACD now falling ${CampaignRules.negDays(ser)} days in a row"
                            else -> "left its monthly support zone" }) +
                            " before the campaign's first buy — dropped, seat freed")
                        continue
                    }
                    // Started without a verdict (the outlook failed or was unread):
                    // asked again, and dropped if it now says avoid.
                    if (s.useNews && c.verdict.isEmpty()) {
                        val v = outlookFor(Member(c.symbol, c.name), L.prevClose)
                        if (v != null && CampaignRules.blocks(v)) {
                            note("${c.symbol}: news outlook now $v — waiting campaign dropped, seat freed")
                            continue
                        }
                        if (!v.isNullOrEmpty()) n = n.copy(verdict = v)
                    }
                    kept.add(n.copy(orderPrice = CampaignRules.entryPrice(L, CampaignRules.turnedUp(ser), c.verdict),
                        orderFromSec = fromSec))
                    continue
                }
                val inMonth = c.inMonthZone || (L.monthRes > 0.0 && L.prevClose >= L.monthRes - L.bufMonth)
                if (inMonth && !c.inMonthZone) {
                    val t = "closed in its monthly resistance zone (${px(L.monthRes - L.bufMonth)}–${px(L.monthRes)}) — swings stop; it now ends when the daily or 4-hour MACD turns down"
                    note("${c.symbol}: $t"); notices.add(Notice(false, c.symbol, L.prevClose, 0.0, t, note = true))
                }
                val daysRun = bars.count { it.date.toString() > c.startDay }
                val exit = when {
                    c.exitPending.isNotEmpty() -> c.exitPending
                    inMonth && CampaignRules.turnedDown(ser) -> "daily MACD turned down at its monthly resistance"
                    !inMonth && n.negDays > 3 -> "daily MACD down ${n.negDays} days in a row"
                    daysRun >= s.maxDays -> "ran ${s.maxDays} trading days"
                    else -> ""
                }
                if (exit.isNotEmpty() && c.exitPending.isEmpty()) note("${c.symbol}: $exit — the campaign ends at the next price")
                n = n.copy(inMonthZone = inMonth, exitPending = exit)
                if (c.state == "OUT") n = n.copy(orderPrice = CampaignRules.rebuyPrice(L.prevLow, L.bufDay, c.verdict),
                    orderFromSec = fromSec)
                kept.add(n)
            }

            // New campaigns while seats are free, in the order of the list. Seats
            // at 0: no limit — each campaign simply gets its own allocation.
            val running = kept.filter { it.state != "ENDED" }
            var free = if (s.seats <= 0) Int.MAX_VALUE else s.seats - running.size
            val busy = running.map { it.symbol }.toSet()
            val seat = if (s.seatAmount > 0.0) s.seatAmount else s.capital / s.seats.coerceAtLeast(1)
            outlookBudget = if (s.useNews) s.newsPerDay.coerceAtLeast(0) else 0
            var started = 0
            val members = a.universe.map { m ->
                val bars = daily[m.symbol.uppercase()] ?: return@map m.copy(note = "no daily prices")
                val (w, d) = trendOf(bars, smoothing)
                val base = m.copy(week = w, day = d)
                if (m.symbol in busy) return@map base.copy(note = "campaign running")
                val L = CampaignRules.levels(bars, s.atrFrac, s.levelSource) ?: return@map base.copy(note = "not enough history")
                if (!CampaignRules.isCandidate(L, L.prevClose)) return@map base.copy(note =
                    if (L.prevClose > L.support) "${String.format(Locale.US, "%.1f", (L.prevClose / L.support - 1) * 100)}% above its support (${px(L.support)})"
                    else "broken below its support (${px(L.support)}) — not a buy")
                if (!a.enabled) return@map base.copy(note = "in its support zone — algo paused")
                // Already past the rule that ends a campaign: starting one only to
                // end it at the next look is churn, not a trade.
                val down = CampaignRules.negDays(slopes(bars))
                if (down > 3) return@map base.copy(note = "in its support zone — daily MACD falling $down days, not started")
                if (free <= 0) return@map base.copy(note = "in its support zone — every seat taken")
                val verdict = if (s.useNews) outlookFor(m, L.prevClose) ?: return@map base.copy(
                    note = "in its support zone — today's news checks are used up; asked tomorrow") else ""
                if (CampaignRules.blocks(verdict)) return@map base.copy(note = "in its support zone — news outlook $verdict, skipped")
                val ser = slopes(bars)
                val turned = CampaignRules.turnedUp(ser)
                val price = CampaignRules.entryPrice(L, turned, verdict)
                kept.add(applyLevels(FloorAlgoStore.Campaign(id = "${m.symbol}|$todayStr", symbol = m.symbol, name = m.name,
                    state = "WAITING", startDay = todayStr, cash = seat, seat = seat, orderPrice = price,
                    orderFromSec = fromSec, verdict = verdict, lastBarSec = fromSec - 1), L, CampaignRules.negDays(ser)))
                free--; started++
                note("${m.symbol}: in its monthly support zone (${px(L.support)} ± ${px(L.bufMonth)})" +
                    (if (verdict.isNotEmpty()) ", news outlook $verdict" else "") +
                    " — campaign buy order at ${px(price)}" +
                    (if (turned) ", the day's low, as the daily MACD has just turned up" else ""))
                base.copy(note = "campaign buy order at ${px(price)}")
            }
            a = a.copy(campaigns = kept, universe = members, planDay = todayStr)
            note("${today.format(dayFmt)}: ${running.size} campaigns running, $started new" +
                (if (free <= 0) " — every seat taken" else ""))
        }

        /** The news outlook's verdict for a stock, remembered for three days. "" when
         *  there is none (no key, an error, no verdict) — that does not hold a
         *  campaign back. NULL when today's checks are used up: the stock waits for
         *  tomorrow rather than starting unasked. */
        private fun outlookFor(m: Member, price: Double): String? {
            val cached = a.outlooks[m.symbol]?.split("|")
            if (cached != null && cached.size == 2) {
                val age = try { java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(cached[1]), today) } catch (e: Exception) { 99L }
                // A failed or unread answer is asked again the next day, not in three.
                if (age < (if (cached[0] == "UNKNOWN") 1 else 3)) return if (cached[0] == "UNKNOWN") "" else cached[0]
            }
            if (outlookBudget <= 0) return null
            outlookBudget--
            var why = ""
            val text = try {
                when (val r = com.signalscope.app.network.StockAiClient.analyzeOutlook(config, m.symbol, price,
                    0, 0, 0, "", "", 0.0, null, null, 0.0, 0.0, 0.0, false,
                    com.signalscope.app.network.StockAiClient.MarketContext.fromSpace(space.uppercase()),
                    m.name.ifBlank { null })) {
                    is com.signalscope.app.network.StockAiClient.AiResult.Success -> r.text
                    is com.signalscope.app.network.StockAiClient.AiResult.Error -> { why = r.message; null }
                }
            } catch (e: Exception) { why = e.message ?: e.javaClass.simpleName; null }
            // The verdict field of the outlook's checked JSON — already held to
            // STRONG BUY / BUY / WATCH / AVOID / STRONG AVOID; "" when it gave none.
            val v = text?.let { com.signalscope.app.network.OutlookReply.verdictIn(it) }?.ifEmpty { null } ?: "UNKNOWN"
            // Say WHY there is no verdict — a failed call and a reply without one
            // need different fixes, and both look the same otherwise.
            if (v == "UNKNOWN") note("${m.symbol}: news outlook gave no verdict — " +
                (if (text == null) "the call failed: ${why.take(120)}"
                 else "the reply ended: “${com.signalscope.app.network.OutlookReply.tail(text, 120).replace('\n', ' ')}”"))
            // Old answers fall away after ten days.
            val keep = a.outlooks.filterValues { val p = it.split("|")
                p.size == 2 && try { java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(p[1]), today) < 10 } catch (e: Exception) { false } }
            a = a.copy(outlooks = keep + (m.symbol to "$v|$today"))
            if (v != "UNKNOWN") note("${m.symbol}: news outlook $v")
            return if (v == "UNKNOWN") "" else v
        }

        /** Each finished fifteen-minute bar of today through every campaign running. */
        private fun campaignBars() {
            val s = a.settings
            val smoothing = MacdLadder.smoothingFor(ctx, space)
            val delay = FeedDelay.secFor(space)
            val todayStr = today.toString()
            for (c0 in a.campaigns) {
                if (c0.state == "ENDED" || c0.levelsDay != todayStr) continue
                val days = book.days(c0.symbol) ?: continue
                val bars = (days[today] ?: emptyList())
                    .filter { it.timestamp > c0.lastBarSec && FeedDelay.isComplete(it.timestamp, 900L, nowSec, 0L, delay) }
                var c = c0
                for (b in bars) {
                    val r = CampaignRules.onBar(c, b, s.stopUnit, s.stopPct)
                    var n = r.c
                    for (e in r.events) n = campaignEvent(c, n, e, b)
                    c = n
                    if (c.state == "ENDED") break
                }
                // In the monthly resistance zone the 4-hour MACD turning down ends it —
                // read after the bars, so it acts on the NEXT one, never an earlier.
                if (c.state != "ENDED" && c.inMonthZone && c.exitPending.isEmpty() &&
                    (c.state == "HOLDING" || c.state == "RIDING")) {
                    val s4 = MacdSlopes.series(MacdSlopes.resample(days.values.flatten(), 4 * 3600L, nowSec, delay), smoothing)
                    val sign = MacdSlopes.sign(s4?.lastOrNull()) ?: 0
                    if (c.h4Sign == 1 && sign == -1) {
                        c = c.copy(exitPending = "4-hour MACD turned down at its monthly resistance")
                        note("${c.symbol}: ${c.exitPending} — the campaign ends at the next price")
                    }
                    c = c.copy(h4Sign = sign)
                }
                // The swing held now, priced: its P&L as a sale at the market would leave it.
                if (c.legId.isNotEmpty()) {
                    val last = days[today]?.lastOrNull() ?: days.lastEntry()?.value?.lastOrNull()
                    if (last != null) a = a.copy(trades = a.trades.map {
                        if (it.id != c.legId) it
                        else it.copy(lastPrice = last.close, lastSec = last.timestamp,
                            fees = feesFor(it.day, it.cost, it.qty * last.close, todayStr),
                            slip = spreadPct / 100.0 * it.qty * last.close)
                    })
                }
                val done = c
                a = a.copy(campaigns = a.campaigns.map { if (it.id == done.id) done else it })
            }
        }

        /** One buy, sale, notice or end, booked: [before] is the campaign as the bar
         *  found it (its swing's size and cost), [after] as the rules left it. */
        private fun campaignEvent(before: FloorAlgoStore.Campaign, after: FloorAlgoStore.Campaign,
                                  e: CampaignRules.Event, b: CandleData): FloorAlgoStore.Campaign {
            val sym = before.symbol
            val todayStr = Instant.ofEpochSecond(b.timestamp).atZone(zone).toLocalDate().toString()
            if (e.notice.isNotEmpty()) {
                note("$sym: ${e.notice}")
                notices.add(Notice(false, sym, b.close, 0.0, e.notice, note = true))
            }
            if (e.buy) {
                val amount = before.cash
                val qty = amount / e.price
                val legNo = before.legs + 1
                val tid = "${before.id}|$legNo"
                a = a.copy(trades = a.trades + Trade(id = tid, symbol = sym, name = before.name, day = todayStr,
                    firstSec = b.timestamp, qty = qty, cost = amount, halves = 1, firstPrice = e.price,
                    status = "OPEN", lastPrice = e.price, lastSec = b.timestamp))
                val what = if (legNo == 1) "campaign started — bought at ${cur}${px(e.price)} in its monthly support zone"
                           else "swing ${legNo}: bought back at ${cur}${px(e.price)}, below the day's low"
                note("$sym: $what"); notices.add(Notice(false, sym, e.price, 0.0, what))
                return after.copy(legId = tid, qty = qty, cost = amount, legs = legNo)
            }
            if (e.price > 0.0 && before.qty > 0.0 && before.legId.isNotEmpty()) {
                val leg = a.trades.firstOrNull { it.id == before.legId }
                val proceeds = before.qty * e.price
                val fees = feesFor(leg?.day ?: todayStr, before.cost, proceeds, todayStr)
                val slip = if (e.market) spreadPct / 100.0 * proceeds else 0.0
                val pnl = proceeds - before.cost - fees - slip
                val pct = if (before.cost > 0.0) pnl / before.cost * 100.0 else 0.0
                a = a.copy(trades = a.trades.map {
                    if (it.id != before.legId) it
                    else it.copy(status = "SOLD", sellDay = todayStr, sellPrice = e.price, sellSec = b.timestamp,
                        exitReason = e.reason, pnl = pnl, fees = fees, feesIn = true, slip = slip, slipIn = true,
                        lastPrice = e.price, lastSec = b.timestamp)
                })
                val pctStr = String.format(Locale.US, "%+.2f", pct)
                val what = (if (e.ends) "campaign over — " else "swing sold — ") + "${e.reason}, at ${cur}${px(e.price)} ($pctStr%)"
                note("$sym: $what"); notices.add(Notice(true, sym, e.price, pct, what))
                var n = after.copy(cash = proceeds - fees - slip, qty = 0.0, cost = 0.0, legId = "", pnl = after.pnl + pnl)
                n = if (e.ends) n.copy(endDay = todayStr)
                    else n.copy(orderPrice = CampaignRules.rebuyPrice(n.prevLow, n.bufDay, n.verdict), orderFromSec = b.timestamp + 900)
                if (e.ends) note("$sym: campaign ended after ${n.legs} swing${if (n.legs == 1) "" else "s"}, " +
                    "${(if (n.pnl >= 0) "+" else "−")}${money(Math.abs(n.pnl))} in all")
                return n
            }
            if (e.ends) {
                note("$sym: campaign ended — ${e.reason}" + (if (before.legs > 0) ", ${money(after.pnl)} in all" else ", before any buy"))
                return after.copy(endDay = todayStr)
            }
            return after
        }

        // ── THE MONTHLY-LOW CHAIN ────────────────────────────────────────────
        // Rules in ChainRules; this is the bookkeeping. Mornings: who touched the
        // monthly low yesterday, the news outlook on each, watches extended or
        // run out. Every pass in session: the timeframes turn and the champion's
        // buy for halves still waiting, fills at the next price, and the
        // champion's exits for halves held. Stocks the outlook turned down (or
        // that found every seat taken) run the same way, followed on the side.

        private val chainCfg: StrategyLabConfig? by lazy { ChainRules.championConfig(space) }
        /** Engine bars fetched this pass, per stock — the champion's buy and exits share them. */
        private val chainFeeds = HashMap<String, Pair<List<CandleData>, Long>?>()
        private fun chainFeed(sym: String): Pair<List<CandleData>, Long>? {
            val cfg = chainCfg ?: return null
            return chainFeeds.getOrPut(sym) { StrategyLabRunner.engineFeed(ctx, space, sym, cfg) }
        }


        private fun runChain() {
            if (a.enabled && a.universeDay != today.toString()) refreshUniverse()
            if (today.dayOfWeek.value <= 5 && a.planDay != today.toString() && !local.toLocalTime().isBefore(PLAN_FROM))
                chainMorning()
            if (inSessionWindow()) chainBars()
            chargeOldSales()
            outcomes()
        }

        private fun chainSeatsBusy(chains: List<FloorAlgoStore.Chain>) =
            chains.count { it.counted && it.state != "ENDED" }

        /** Whether a chain gets a seat, once its verdict is known. */
        private fun chainDecide(c: FloorAlgoStore.Chain, chains: List<FloorAlgoStore.Chain>): FloorAlgoStore.Chain {
            val s = a.settings
            if (s.useNews && c.verdict.isEmpty()) return c.copy(counted = false, why = "news outlook not asked yet")
            if (s.useNews && !ChainRules.positive(c.verdict)) return c.copy(counted = false, why = "news outlook ${c.verdict}")
            if (!a.enabled) return c.copy(counted = false, why = "algo paused")
            val busy = chainSeatsBusy(chains.filter { it.id != c.id })
            return if (busy < s.seats) c.copy(counted = true, why = "") else c.copy(counted = false, why = "every seat taken")
        }

        private fun chainMorning() {
            val s = a.settings
            val todayStr = today.toString()
            val live = a.chains.filter { it.state != "ENDED" }
            val syms = (a.universe.map { it.symbol } + live.map { it.symbol }).distinct()
            if (syms.isEmpty()) { a = a.copy(planDay = todayStr); return }
            val daily = dailyBars(syms)?.takeIf { it.isNotEmpty() } ?: return
            outlookBudget = if (s.useNews) s.newsPerDay.coerceAtLeast(0) else 0
            val fromSec = ChainRules.ceil15(nowSec)
            var chains = a.chains

            // 1. Chains already running.
            chains = chains.map { c0 ->
                if (c0.state == "ENDED") return@map c0
                var c = c0
                val bars = daily[c.symbol.uppercase()]
                val judged = bars?.lastOrNull()?.date?.toString()
                // A fresh touch keeps a watch open while nothing has bought from it yet.
                if (bars != null && judged != null && c.state == "WATCHING" && judged > c.lastTouchDay &&
                    ChainRules.touchedMonthlyLow(bars)) {
                    c = c.copy(lastTouchDay = judged,
                        expiryDay = ChainRules.addTradingDays(LocalDate.parse(judged), s.watchDays).toString())
                }
                // Started after the day's checks ran out: asked now.
                if (s.useNews && c.verdict.isEmpty()) {
                    val v = outlookFor(Member(c.symbol, c.name), bars?.lastOrNull()?.close ?: 0.0)
                    if (v != null) {
                        c = chainDecide(c.copy(verdict = v.ifEmpty { "NO VERDICT" }), chains)
                        note("${c.symbol}: news outlook ${c.verdict} — " +
                            (if (c.counted) "takes a seat" else "followed on the side (${c.why})"))
                    }
                }
                // The watch ran out: a half that never bought never will.
                if (c.expiryDay.isNotEmpty() && todayStr > c.expiryDay) {
                    if (c.dots.isEmpty()) c = c.copy(dots = "VOID")
                    if (c.champ.isEmpty()) c = c.copy(champ = "VOID")
                }
                if (!a.enabled && c.state == "WATCHING") c = c.copy(dots = "VOID", champ = "VOID")
                chainMaybeEnd(c, if (!a.enabled) "algo paused before any buy" else "watch ran out")
            }

            // 2. New touches of the monthly low.
            val busySyms = chains.filter { it.state != "ENDED" }.map { it.symbol }.toSet()
            val cap = s.seats.coerceAtLeast(1) * 3       // followed chains are bounded: each costs downloads
            val seat = s.capital / s.seats.coerceAtLeast(1)
            var started = 0
            val members = a.universe.map { m ->
                val bars = daily[m.symbol.uppercase()] ?: return@map m.copy(note = "no daily prices")
                if (m.symbol in busySyms) return@map m.copy(note = "chain running")
                if (!ChainRules.touchedMonthlyLow(bars)) {
                    val w = bars.takeLast(ChainRules.MONTH_DAYS)
                    val lo = w.minOfOrNull { it.low } ?: 0.0
                    val hi = w.maxOfOrNull { it.high } ?: 0.0
                    val pos = if (hi > lo) (bars.last().low - lo) / (hi - lo) * 100.0 else 100.0
                    return@map m.copy(note = "day's low at ${String.format(Locale.US, "%.0f", pos)}% of its monthly range")
                }
                if (!a.enabled) return@map m.copy(note = "at its monthly low — algo paused")
                if (chains.count { it.state != "ENDED" } >= cap)
                    return@map m.copy(note = "at its monthly low — too many chains followed already")
                val judged = bars.last().date
                val verdict = if (s.useNews) (outlookFor(m, bars.last().close)?.ifEmpty { "NO VERDICT" } ?: "") else "NOT ASKED"
                val c = chainDecide(FloorAlgoStore.Chain(id = "${m.symbol}|$todayStr", symbol = m.symbol, name = m.name,
                    touchDay = judged.toString(), lastTouchDay = judged.toString(),
                    expiryDay = ChainRules.addTradingDays(judged, s.watchDays).toString(),
                    watchFromSec = fromSec, verdict = verdict, seat = seat, scannedSec = fromSec), chains)
                chains = chains + c
                started++
                note("${m.symbol}: at its monthly low on ${judged.format(dayFmt)}" +
                    (if (verdict.isNotEmpty()) ", news outlook $verdict" else ", news outlook asked tomorrow") +
                    " — " + (if (c.counted) "watching for the buys (seat ${chainSeatsBusy(chains)} of ${s.seats})"
                             else "followed on the side (${c.why})"))
                m.copy(note = if (c.counted) "chain started — watching" else "chain followed — ${c.why}")
            }
            a = a.copy(chains = chains, universe = members, planDay = todayStr)
            note("${today.format(dayFmt)}: $started new chains; ${chainSeatsBusy(chains)}/${s.seats} seats, " +
                "${chains.count { it.state != "ENDED" && !it.counted }} followed on the side")
        }

        /** Ended once no half is waiting, pending or held. */
        private fun chainMaybeEnd(c: FloorAlgoStore.Chain, why: String): FloorAlgoStore.Chain {
            if (c.state == "ENDED") return c
            val pending = a.trades.any { it.id.startsWith(c.id + "|") && it.sellDay == null }
            val waiting = c.dots.isEmpty() || c.champ.isEmpty()
            if (waiting || pending) return c
            val legs = a.trades.filter { it.id.startsWith(c.id + "|") }
            val reason = if (legs.isEmpty()) why else "both halves done"
            val pnl = legs.sumOf { it.pnl }
            if (legs.isNotEmpty() || c.counted)
                note("${c.symbol}: chain ended — $reason" + (if (legs.isNotEmpty())
                    ", ${(if (pnl >= 0) "+" else "−")}${money(Math.abs(pnl))} in all" +
                    (if (!c.counted) " (followed on the side, not counted)" else "") else ""))
            return c.copy(state = "ENDED", endDay = today.toString(), endReason = reason)
        }

        private fun chainBars() {
            val s = a.settings
            val cfg = chainCfg
            if (cfg == null) { note("the built-in champion config did not load — chain idle"); return }
            val smoothing = MacdLadder.smoothingFor(ctx, space)
            val delay = FeedDelay.secFor(space)
            val todayStr = today.toString()
            val live = a.chains.filter { it.state != "ENDED" && (!s.useNews || it.verdict.isNotEmpty()) }
            if (live.isEmpty()) return
            val daily = dailyBars(live.map { it.symbol }.distinct()) ?: emptyMap()
            val sessionOn = MarketHours.isRegularSession(space, nowMs)
            for (c0 in live) {
                var c = a.chains.firstOrNull { it.id == c0.id } ?: continue
                val days = book.days(c.symbol) ?: continue
                val c15 = days.values.flatten()
                val open = c.expiryDay.isEmpty() || todayStr <= c.expiryDay
                val status = if (c.counted) "OPEN" else "MISSED"
                val half = c.seat / 2.0

                // 1. Halves whose signal was seen: bought at the next price.
                for (t in a.trades.filter { it.id.startsWith(c.id + "|") && it.firstSec == 0L }) {
                    val f = ChainRules.nextOpen(c15, t.fromSec) ?: continue
                    val n = t.copy(firstSec = f.sec, firstPrice = f.price, qty = half / f.price, cost = half, halves = 1,
                        lastPrice = f.price, lastSec = f.sec,
                        day = Instant.ofEpochSecond(f.sec).atZone(zone).toLocalDate().toString())
                    a = a.copy(trades = a.trades.map { if (it.id == t.id) n else it })
                    val which = if (t.id.endsWith("|A")) "timeframes half" else "champion half"
                    note("${c.symbol}: $which bought at ${px(f.price)} (${clock(f.sec)} open)" +
                        (if (!c.counted) " — followed on the side" else ""))
                    if (c.counted) notices.add(Notice(false, c.symbol, f.price, 0.0,
                        "monthly-low chain: $which bought at ${cur}${px(f.price)}"))
                }

                // 2. The timeframes half: daily, 4-hour, hourly and 15-minute all turning up.
                if (c.dots.isEmpty() && open && sessionOn) {
                    val bars = daily[c.symbol.uppercase()]
                    if (bars != null) {
                        val after = maxOf(c.watchFromSec, c.scannedSec)
                        val turn = ChainRules.firstGreenTurn(c15, ChainRules.dailySlopeBefore(bars, smoothing),
                            zone, after, nowSec, delay, smoothing)
                        val newest = c15.filter { FeedDelay.isComplete(it.timestamp, 900L, nowSec, 0L, delay) }
                            .maxOfOrNull { it.timestamp } ?: after
                        if (turn != null) {
                            val t = Trade(id = "${c.id}|A", symbol = c.symbol, name = c.name, day = todayStr,
                                signalSec = turn.endSec, fromSec = ChainRules.ceil15(maxOf(turn.endSec, nowSec)), status = status)
                            a = a.copy(trades = a.trades.filterNot { it.id == t.id } + t)
                            c = c.copy(dots = "OPEN", state = "HOLDING", scannedSec = turn.barSec + 900L)
                            note("${c.symbol}: daily, 4-hour, hourly and 15-minute MACD all turned up at " +
                                "${clock(turn.endSec)} — timeframes half buys at the next price")
                        } else c = c.copy(scannedSec = maxOf(c.scannedSec, newest + 1))
                    }
                }

                // 3. The champion half, and 4. the champion's exits for halves held.
                val held = a.trades.filter { it.id.startsWith(c.id + "|") && it.firstSec > 0L && it.sellDay == null }
                val wantChamp = c.champ.isEmpty() && open
                // Asked once per finished two-hour bar — the only moment its answer can change.
                val visible = ChainRules.frameVisibleAt(nowSec,
                    StrategyLabEngine.timeframeSeconds(cfg.exitBars).coerceAtLeast(900L), cfg.barAnchorSec, delay)
                if ((wantChamp || held.isNotEmpty()) && c.champCheckSec < visible) {
                    c = c.copy(champCheckSec = nowSec)
                    val feed = chainFeed(c.symbol)
                    if (feed != null) {
                        val (candles, src) = feed
                        if (wantChamp) {
                            val run = StrategyLabEngine.runLiveClosed(c.symbol, candles, cfg, src, c.watchFromSec)
                            val tr = run.trades.firstOrNull { it.entryTs >= c.watchFromSec }
                            if (tr != null) {
                                val sigEnd = tr.entryTs + (if (run.exitTfSec > 0) run.exitTfSec else src)
                                val sigDay = Instant.ofEpochSecond(sigEnd).atZone(zone).toLocalDate().toString()
                                if (c.expiryDay.isEmpty() || sigDay <= c.expiryDay) {
                                    val t = Trade(id = "${c.id}|B", symbol = c.symbol, name = c.name, day = sigDay,
                                        signalSec = sigEnd, fromSec = ChainRules.ceil15(maxOf(sigEnd, nowSec)), status = status)
                                    a = a.copy(trades = a.trades.filterNot { it.id == t.id } + t)
                                    c = c.copy(champ = "OPEN", state = "HOLDING")
                                    note("${c.symbol}: the conservative champion bought at ${clock(sigEnd)} " +
                                        "(${tr.entryReason.take(60)}) — champion half buys at the next price")
                                }
                            }
                        }
                        for (t in held.filter { it.exitReason.isEmpty() }) {
                            val ex = StrategyLabEngine.adoptedExit(candles, cfg, t.firstPrice, t.firstSec, src)
                            if (!ex.ok || !ex.fires) continue
                            val exitTf = StrategyLabEngine.timeframeSeconds(cfg.exitBars).coerceAtLeast(src)
                            a = a.copy(trades = a.trades.map { if (it.id == t.id) it.copy(exitReason = ex.reason,
                                sellSec = ChainRules.ceil15(maxOf(ex.barTs + exitTf, nowSec))) else it })
                            note("${c.symbol}: champion exit for the " +
                                "${if (t.id.endsWith("|A")) "timeframes" else "champion"} half — ${ex.reason}")
                        }
                    }
                }

                // 5. Exits decided: sold at the next price.
                for (t in a.trades.filter { it.id.startsWith(c.id + "|") && it.firstSec > 0L &&
                        it.sellDay == null && it.exitReason.isNotEmpty() }) {
                    val f = ChainRules.nextOpen(c15, t.sellSec) ?: continue
                    val sellDay = Instant.ofEpochSecond(f.sec).atZone(zone).toLocalDate().toString()
                    val proceeds = t.qty * f.price
                    val fees = feesFor(t.day, t.cost, proceeds, sellDay)
                    val slip = slipFor(t.cost, proceeds, "champion exit")
                    val pnl = proceeds - t.cost - fees - slip
                    val pct = if (t.cost > 0) pnl / t.cost * 100.0 else 0.0
                    a = a.copy(trades = a.trades.map { if (it.id != t.id) it else it.copy(
                        status = if (it.status == "MISSED") "MISSED" else "SOLD", sellDay = sellDay, sellPrice = f.price,
                        sellSec = f.sec, pnl = pnl, fees = fees, feesIn = true, slip = slip, slipIn = true,
                        lastPrice = f.price, lastSec = f.sec) })
                    val which = if (t.id.endsWith("|A")) "timeframes" else "champion"
                    c = if (which == "timeframes") c.copy(dots = "SOLD") else c.copy(champ = "SOLD")
                    val pctStr = String.format(Locale.US, "%+.2f", pct)
                    note("${c.symbol}: $which half sold at ${px(f.price)} — ${t.exitReason.take(60)} ($pctStr%)" +
                        (if (!c.counted) ", followed on the side" else ""))
                    if (c.counted) notices.add(Notice(true, c.symbol, f.price, pct,
                        "monthly-low chain: $which half sold — ${t.exitReason.take(60)}"))
                }

                // 6. Priced, as a sale at the market would leave it.
                val last = c15.maxByOrNull { it.timestamp }
                if (last != null) a = a.copy(trades = a.trades.map {
                    if (!it.id.startsWith(c.id + "|") || it.firstSec == 0L || it.sellDay != null) it
                    else it.copy(lastPrice = last.close, lastSec = last.timestamp,
                        fees = feesFor(it.day, it.cost, it.qty * last.close, todayStr),
                        slip = slipFor(it.cost, it.qty * last.close, null))
                })
                val done = chainMaybeEnd(c, "watch ran out")
                a = a.copy(chains = a.chains.map { if (it.id == done.id) done else it })
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
