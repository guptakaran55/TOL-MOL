package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.signalscope.app.data.CandleData
import com.signalscope.app.data.FloorAlgoStore
import com.signalscope.app.data.WhatIfStore
import com.signalscope.app.network.YahooFinanceClient
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.TreeMap
import java.util.TreeSet
import java.util.concurrent.atomic.AtomicBoolean

// ════════════════════════════════════════════════════════════════════════════
// THE 💡 REPLAY FOR THE BUILT-IN ALGOS (the user's ask, 25 Oct 2026)
// ════════════════════════════════════════════════════════════════════════════
// The config algos always had a 💡 replay; the built-in ones — floor orders and
// MACD turns — had none, so the compare table could not rank them at all. This
// replays their own rules over the history there is, through the very pieces
// the live engine trades with: the fill rule, the seat count and the position
// walk (FloorOrders), the turn test (MacdSlopes), the charges (TradingCosts).
//
//   FLOOR ORDERS — two years of DAILY prices. Each morning, from prices before
//   the day: the trend rule if it is on (monthly and daily), half a seat at the
//   3-day low and half under it; a fill when the day trades below; the stop,
//   target, time limit and (if this algo sells on it) the trend. A daily price
//   cannot say whether the stop or the target came first in a day that reached
//   both — the stop is assumed, as the live walk does in a fifteen-minute bar.
//
//   MACD TURNS — every fifteen-minute bar the data provider keeps, about sixty
//   days: the 15-minute and hourly MACD turning up together with the 4-hour
//   rising, bought at the next bar's open. Short, and labelled so.
//
// Both replay TODAY's universe backwards — the list the app picks afresh each
// morning cannot be rebuilt for past days. Not replayed: the news-outlook algos
// (the outlook knows what happened next), the campaigns and the monthly-low
// chain (no replay built), and weekly-zone floor orders.
// ════════════════════════════════════════════════════════════════════════════

object FloorReplay {
    private const val TAG = "FloorReplay"
    const val FLOOR_YEARS = 2L

    data class Trip(val symbol: String, val buyDay: LocalDate, val sellDay: LocalDate,
                    val cost: Double, val value: Double, val charges: Double, val reason: String) {
        val pnl: Double get() = value - cost - charges
    }

    data class Result(val ok: Boolean, val note: String = "", val trips: List<Trip> = emptyList(),
                      val from: LocalDate? = null, val to: LocalDate? = null, val symbols: Int = 0,
                      val compoundedPct: Double = 0.0, val cagrPct: Double = 0.0,
                      val maxDrawdownPct: Double = 0.0, val wins: Int = 0)

    /** Why this algo has no 💡 replay, or null when it has one. */
    fun unsupported(a: FloorAlgoStore.Algo): String? = when (a.kind) {
        FloorAlgoStore.KIND_FLOOR ->
            if (a.settings.orderMode == FloorAlgoStore.MODE_WEEK) "weekly-zone floor orders have no replay yet" else null
        FloorAlgoStore.KIND_MACD ->
            if (a.settings.universeMode == FloorAlgoStore.UNI_MONTHLY_LOW)
                "picks by the news outlook, which cannot be replayed honestly — it knows what happened next" else null
        FloorAlgoStore.KIND_CHAIN -> "picks by the news outlook, which cannot be replayed honestly — it knows what happened next"
        else -> "campaigns have no replay yet"
    }

    // ── the trend, as the morning check saw it on any past day ───────────────

    private fun weekKey(d: LocalDate): Int =
        d.get(WeekFields.ISO.weekBasedYear()) * 100 + d.get(WeekFields.ISO.weekOfWeekBasedYear())

    /** How many of [sorted] are below [x]. */
    private fun <T : Comparable<T>> countBelow(sorted: List<T>, x: T): Int {
        var lo = 0; var hi = sorted.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (sorted[mid] < x) lo = mid + 1 else hi = mid }
        return lo
    }

    /**
     * The monthly, weekly and daily MACD slope signs on any day, from prices
     * STRICTLY before it — what the 06:00 check would have read. One series per
     * timeframe, computed once: the slope is built only from earlier prices, so
     * its value at a point is the value the check would have computed then.
     * Pure — tested.
     */
    class TrendBook(daily: List<CandleData>, monthly: List<CandleData>?, zone: ZoneId, smoothing: Int) {
        private val dDays: List<LocalDate>
        private val dS: DoubleArray?
        private val wKeys: List<Int>
        private val wS: DoubleArray?
        private val mKeys: List<YearMonth>
        private val mS: DoubleArray?
        init {
            val byDay = TreeMap<LocalDate, Double>()
            for (c in daily) if (c.close > 0.0 && c.close.isFinite())
                byDay[Instant.ofEpochSecond(c.timestamp).atZone(zone).toLocalDate()] = c.close
            dDays = byDay.keys.toList(); dS = MacdSlopes.series(byDay.values.toDoubleArray(), smoothing)
            val wk = TreeMap<Int, Double>()
            for ((d, c) in byDay) wk[weekKey(d)] = c          // a week's last close
            wKeys = wk.keys.toList(); wS = MacdSlopes.series(wk.values.toDoubleArray(), smoothing)
            val mo = TreeMap<YearMonth, Double>()
            monthly?.forEach { c ->
                if (c.close > 0.0 && c.close.isFinite())
                    mo[YearMonth.from(Instant.ofEpochSecond(c.timestamp + 12 * 3600L).atZone(ZoneOffset.UTC))] = c.close
            }
            mKeys = mo.keys.toList(); mS = MacdSlopes.series(mo.values.toDoubleArray(), smoothing)
        }
        private fun signAt(s: DoubleArray?, count: Int): Int? =
            if (s == null || count < MacdSlopes.MIN_BARS) null else MacdSlopes.sign(s[count - 1])
        fun day(d: LocalDate): Int? = signAt(dS, countBelow(dDays, d))
        fun week(d: LocalDate): Int? = signAt(wS, countBelow(wKeys, weekKey(d)))
        fun month(d: LocalDate): Int? = signAt(mS, countBelow(mKeys, YearMonth.from(d)))
    }

    // ── the two replays, on prices already fetched ──────────────────────────

    /** Nothing in a replay is "today" — every day it reads has finished. */
    private fun walkAll(days: TreeMap<LocalDate, List<CandleData>>, buys: List<FloorOrders.Buy>,
                        s: FloorAlgoStore.Settings, trendDay: LocalDate?, to: LocalDate, zone: ZoneId) =
        FloorOrders.walk(days, buys, s.stopPct, s.targetPct, s.holdDays, trendDay,
            to.plusDays(1), LocalTime.of(23, 59), zone, Long.MAX_VALUE / 4)

    /** A trip from a walked position; one still held at the end is valued at the last close, "open". */
    private fun trip(sym: String, day: LocalDate, days: TreeMap<LocalDate, List<CandleData>>,
                     p: FloorOrders.Position, to: LocalDate,
                     charges: (Double, Double, Boolean, String) -> Double): Trip {
        val exit = p.exit
        val px = exit?.price ?: (days.floorEntry(to)?.value?.lastOrNull()?.close ?: (p.cost / p.qty))
        val reason = exit?.reason ?: "open"
        val sell = exit?.day ?: to
        val value = p.qty * px
        return Trip(sym, day, sell, p.cost, value, charges(p.cost, value, sell == day, reason), reason)
    }

    /**
     * FLOOR ORDERS over daily prices. [days]: one daily candle per day and stock,
     * nothing after [to]. [order]: the universe in its ROC order, which breaks
     * ties between fills of the same day after the nearer-to-the-floor open
     * (a daily price cannot say which order filled first). Pure — tested.
     */
    fun floorOrders(days: Map<String, TreeMap<LocalDate, List<CandleData>>>, order: List<String>,
                    passes: (String, LocalDate) -> Boolean, broken: ((String, LocalDate) -> Boolean)?,
                    s: FloorAlgoStore.Settings, from: LocalDate, to: LocalDate, zone: ZoneId,
                    charges: (Double, Double, Boolean, String) -> Double): List<Trip> {
        val half = s.capital / s.seats.coerceAtLeast(1) / 2.0
        val calendar = TreeSet<LocalDate>()
        days.values.forEach { m -> m.keys.forEach { if (!it.isBefore(from) && !it.isAfter(to)) calendar.add(it) } }
        val spans = ArrayList<Pair<LocalDate, LocalDate?>>()
        val heldTo = HashMap<String, LocalDate>()
        val out = ArrayList<Trip>()
        data class Cand(val gap: Double, val rank: Int, val sym: String, val buys: List<FloorOrders.Buy>)
        for (day in calendar) {
            val cands = ArrayList<Cand>()
            order.forEachIndexed { rank, sym ->
                val d = days[sym] ?: return@forEachIndexed
                val bar = d[day]?.firstOrNull() ?: return@forEachIndexed
                // Held through its sale day: sold at the close, it gets no order that morning.
                heldTo[sym]?.let { if (!day.isAfter(it)) return@forEachIndexed }
                if (!passes(sym, day)) return@forEachIndexed
                val floor = FloorOrders.priorLow(d, day, s.lowDays) ?: return@forEachIndexed
                val f1 = FloorOrders.firstFill(listOf(bar), floor) ?: return@forEachIndexed
                val buys = arrayListOf(FloorOrders.Buy(f1.sec, f1.price, half))
                FloorOrders.firstFill(listOf(bar), FloorOrders.secondPrice(floor, s.secondPct))
                    ?.let { buys.add(FloorOrders.Buy(it.sec, it.price, half)) }
                cands.add(Cand((bar.open - floor) / floor, rank, sym, buys))
            }
            for (c in cands.sortedWith(compareBy<Cand>({ it.gap }, { it.rank }))) {
                if (FloorOrders.seatsBusy(spans, day) >= s.seats) break
                val d = days[c.sym] ?: continue
                val trendDay = broken?.let { br -> d.tailMap(day, false).keys.firstOrNull { !it.isAfter(to) && br(c.sym, it) } }
                val t = trip(c.sym, day, d, walkAll(d, c.buys, s, trendDay, to, zone), to, charges)
                out.add(t); spans.add(day to t.sellDay); heldTo[c.sym] = t.sellDay
            }
        }
        return out
    }

    /**
     * MACD TURNS over fifteen-minute prices. A stock is looked at once a bar, at
     * the bar's close: its 15-minute and hourly slopes rising now and not both an
     * hour ago, the 4-hour rising, the trend rule passed that morning — then a
     * seat if one is free and a buy at the next bar's open. One signal a stock a
     * day, held or not. Pure — tested.
     */
    fun macdTurns(bars15: Map<String, List<CandleData>>, order: List<String>,
                  passes: (String, LocalDate) -> Boolean, broken: ((String, LocalDate) -> Boolean)?,
                  s: FloorAlgoStore.Settings, to: LocalDate, zone: ZoneId, smoothing: Int,
                  charges: (Double, Double, Boolean, String) -> Double): List<Trip> {
        val amount = s.capital / s.seats.coerceAtLeast(1)
        data class Ev(val sec: Long, val rank: Int, val sym: String, val day: LocalDate)
        val dayMaps = HashMap<String, TreeMap<LocalDate, List<CandleData>>>()
        val events = ArrayList<Ev>()
        fun buckets(bars: List<CandleData>, period: Long): Pair<LongArray, DoubleArray?> {
            val m = TreeMap<Long, Double>()
            for (b in bars) m[(b.timestamp / period) * period] = b.close
            return m.keys.toLongArray() to MacdSlopes.series(m.values.toDoubleArray(), smoothing)
        }
        fun done(starts: LongArray, period: Long, endSec: Long): Int {   // buckets finished by endSec
            var lo = 0; var hi = starts.size
            while (lo < hi) { val mid = (lo + hi) ushr 1; if (starts[mid] + period <= endSec) lo = mid + 1 else hi = mid }
            return lo
        }
        order.forEachIndexed { rank, sym ->
            val bars = (bars15[sym] ?: return@forEachIndexed)
                .filter { it.close > 0.0 && it.close.isFinite() && it.open > 0.0 }.sortedBy { it.timestamp }
                .filter { !Instant.ofEpochSecond(it.timestamp).atZone(zone).toLocalDate().isAfter(to) }
            if (bars.size < MacdSlopes.MIN_BARS) return@forEachIndexed
            dayMaps[sym] = FloorOrders.byDay(bars, zone)
            val s15 = MacdSlopes.series(DoubleArray(bars.size) { bars[it].close }, smoothing) ?: return@forEachIndexed
            val (h1, s1h) = buckets(bars, 3600L)
            val (h4, s4h) = buckets(bars, 4 * 3600L)
            if (s1h == null || s4h == null) return@forEachIndexed
            for (i in 4 until bars.size) {
                if (i + 1 < MacdSlopes.MIN_BARS) continue
                val end = bars[i].timestamp + 900
                val k1 = done(h1, 3600L, end); val k4 = done(h4, 4 * 3600L, end)
                if (k1 < MacdSlopes.MIN_BARS || k1 < 2 || k4 < MacdSlopes.MIN_BARS) continue
                val n15 = s15[i]; val p15 = s15[i - 4]; val n1h = s1h[k1 - 1]; val p1h = s1h[k1 - 2]
                val turned = n15 > 0.0 && n1h > 0.0 && !(p15 > 0.0 && p1h > 0.0)
                if (turned && MacdSlopes.sign(s4h[k4 - 1]) == 1)
                    events.add(Ev(end, rank, sym, Instant.ofEpochSecond(bars[i].timestamp).atZone(zone).toLocalDate()))
            }
        }
        val spans = ArrayList<Pair<LocalDate, LocalDate?>>()
        val heldTo = HashMap<String, LocalDate>()
        val signalled = HashMap<String, LocalDate>()
        val out = ArrayList<Trip>()
        for (e in events.sortedWith(compareBy<Ev>({ it.sec }, { it.rank }))) {
            if (signalled[e.sym] == e.day) continue
            val held = heldTo[e.sym]
            if (held != null && !e.day.isAfter(held)) continue
            if (!passes(e.sym, e.day)) continue
            signalled[e.sym] = e.day
            if (FloorOrders.seatsBusy(spans, e.day) >= s.seats) continue
            val d = dayMaps[e.sym] ?: continue
            val f = FloorOrders.nextOpen(d, e.day, e.sec) ?: continue      // too late in the session
            val trendDay = broken?.let { br -> d.tailMap(e.day, false).keys.firstOrNull { !it.isAfter(to) && br(e.sym, it) } }
            val t = trip(e.sym, e.day, d, walkAll(d, listOf(FloorOrders.Buy(f.sec, f.price, amount)), s, trendDay, to, zone), to, charges)
            out.add(t); spans.add(e.day to t.sellDay); heldTo[e.sym] = t.sellDay
        }
        return out
    }

    /** The figures the compare table shows, from the trips. Pure — tested. */
    fun summarize(trips: List<Trip>, capital: Double, from: LocalDate, to: LocalDate, symbols: Int): Result {
        val total = trips.sumOf { it.pnl }
        val span = ChronoUnit.DAYS.between(from, to).toDouble().coerceAtLeast(1.0)
        val growth = (1.0 + total / capital).coerceAtLeast(1e-9)
        val cagr = (Math.pow(growth, 365.0 / span) - 1.0) * 100.0
        var eq = capital; var peak = capital; var dd = 0.0
        for (t in trips.sortedBy { it.sellDay }) {
            eq += t.pnl; peak = maxOf(peak, eq)
            if (peak > 0.0) dd = maxOf(dd, (peak - eq) / peak * 100.0)
        }
        return Result(true, trips = trips, from = from, to = to, symbols = symbols,
            compoundedPct = total / capital * 100.0, cagrPct = cagr, maxDrawdownPct = dd,
            wins = trips.count { it.pnl > 0.0 })
    }

    // ── fetching, and the result kept for the compare table ─────────────────

    private val busy = AtomicBoolean(false)

    fun run(ctx: Context, a: FloorAlgoStore.Algo): Result {
        unsupported(a)?.let { return Result(false, it) }
        if (!busy.compareAndSet(false, true)) return Result(false, "a built-in replay is already running")
        try {
            val space = a.space
            val s = a.settings
            val zone = MarketHours.zoneOf(space) ?: ZoneId.of("Europe/Berlin")
            val exch = Spaces.exchangeOf(space)
            val smoothing = MacdLadder.smoothingFor(ctx, space)
            val floor = a.kind == FloorAlgoStore.KIND_FLOOR
            val syms = a.universe.map { it.symbol }.distinct()
            if (syms.isEmpty()) return Result(false, "no universe yet — it is set at the next morning plan")
            val to = LocalDate.now(zone).minusDays(1)
            fun candles(r: YahooFinanceClient.CandleResult?) = (r as? YahooFinanceClient.CandleResult.Success)?.candles
            val daily = HashMap<String, List<CandleData>>()
            for (sym in syms) {
                candles(try { YahooFinanceClient.fetchCandlesAt(sym, exch, "1d", if (floor) "5y" else "2y") } catch (e: Exception) { null })
                    ?.let { daily[sym] = it }
            }
            if (daily.size * 2 < syms.size) return Result(false, "daily prices came back for only ${daily.size} of ${syms.size} stocks")
            val books = if (!s.trendFilter) emptyMap() else syms.mapNotNull { sym ->
                val d = daily[sym] ?: return@mapNotNull null
                val m = if (floor) MacdLadder.monthlyBars(space, sym) else null
                sym to TrendBook(d, m, zone, smoothing)
            }.toMap()
            val long: (TrendBook, LocalDate) -> Int? = if (floor) { b, d -> b.month(d) } else { b, d -> b.week(d) }
            val passes: (String, LocalDate) -> Boolean = { sym, d ->
                !s.trendFilter || books[sym]?.let { b -> FloorOrders.inUptrend(long(b, d), b.day(d)) } == true
            }
            val broken: ((String, LocalDate) -> Boolean)? = if (s.trendFilter && s.trendExit != false)
                { sym, d -> books[sym]?.let { b -> FloorOrders.trendBroken(long(b, d), b.day(d)) } == true } else null
            val acct = try { com.signalscope.app.data.ConfigManager(ctx).accountCurrency } catch (e: Exception) { "EUR" }
            val fx = try { com.signalscope.app.data.ConfigManager(ctx).fxConversionPct } catch (e: Exception) { TradingCosts.T212_FX_PCT_PER_SIDE }
            val spread = (s.spreadPct ?: FloorAlgoStore.defaultSpreadPct(space)).coerceAtLeast(0.0) / 100.0
            val charges: (Double, Double, Boolean, String) -> Double = { buy, sell, sameDay, reason ->
                val fees = try { TradingCosts.chargesFor(space, buy, sell, acct, fx, intraday = sameDay).cashRoundTrip } catch (e: Exception) { 0.0 }
                fees + spread * ((if (floor) 0.0 else buy) + (if (reason == "target") 0.0 else sell))
            }
            return if (floor) {
                val from = to.minusYears(FLOOR_YEARS)
                val days = daily.mapValues { (_, c) -> TreeMap(FloorOrders.byDay(c, zone).headMap(to, true)) }
                summarize(floorOrders(days, syms, passes, broken, s, from, to, zone, charges), s.capital, from, to, syms.size)
            } else {
                val bars15 = HashMap<String, List<CandleData>>()
                for (sym in syms) candles(try { YahooFinanceClient.fetchCandles15m(sym, exch) } catch (e: Exception) { null })?.let { bars15[sym] = it }
                if (bars15.size * 2 < syms.size) return Result(false, "fifteen-minute prices came back for only ${bars15.size} of ${syms.size} stocks")
                val first = bars15.values.mapNotNull { it.minOfOrNull { b -> b.timestamp } }.minOrNull()
                    ?: return Result(false, "no fifteen-minute prices")
                val from = Instant.ofEpochSecond(first).atZone(zone).toLocalDate()
                summarize(macdTurns(bars15, syms, passes, broken, s, to, zone, smoothing, charges), s.capital, from, to, syms.size)
            }
        } catch (e: Exception) {
            Log.w(TAG, "[${a.id}] replay failed", e)
            return Result(false, e.message ?: "replay failed")
        } finally { busy.set(false) }
    }

    /** Replay and keep the result where the compare table and the card read it. */
    fun replayAndSave(ctx: Context, a: FloorAlgoStore.Algo): Result {
        val r = run(ctx, a)
        if (r.ok && r.from != null && r.to != null) try {
            val floor = a.kind == FloorAlgoStore.KIND_FLOOR
            val s = a.settings
            WhatIfStore.put(ctx, WhatIfStore.Summary(
                ns = a.ns, name = a.name, computedAt = System.currentTimeMillis(),
                layer = if (floor) "floor_daily" else "macd_15m", configSource = "built-in rules",
                slippagePct = (s.spreadPct ?: FloorAlgoStore.defaultSpreadPct(a.space)),
                entryTf = if (floor) "1D" else "15m", exitTf = if (floor) "1D" else "15m",
                interval = if (floor) "1d" else "15m",
                compoundedPct = r.compoundedPct, cagrPct = r.cagrPct, maxDrawdownPct = r.maxDrawdownPct,
                spanDays = ChronoUnit.DAYS.between(r.from, r.to).toDouble(),
                fromTs = r.from.atStartOfDay(ZoneOffset.UTC).toEpochSecond(),
                toTs = r.to.atStartOfDay(ZoneOffset.UTC).toEpochSecond(),
                closed = r.trips.size, symbols = r.symbols, seats = s.seats,
                note = (if (floor) "two years of daily prices" else "every fifteen-minute price there is — about two months") +
                    "; today's universe replayed backwards; ${r.wins} of ${r.trips.size} trades won"))
        } catch (e: Exception) { Log.w(TAG, "summary save failed", e) }
        return r
    }
}
