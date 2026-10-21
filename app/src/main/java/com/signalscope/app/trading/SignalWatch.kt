package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.signalscope.app.data.CandleData
import com.signalscope.app.data.SignalTrackStore
import com.signalscope.app.network.YahooFinanceClient
import com.signalscope.app.screener.DailyBar

/**
 * "TURNING UP AT A FLOOR" — and, beside it, plain "all five green" — watched on
 * every watchlist stock, recorded, and followed up.
 *
 * TURNING UP AT A FLOOR fires when, together:
 *   1. the month and the day MACD are rising — the bigger trend is up;
 *   2. the price is at the bottom of its 3-day range — the watcher's own floor;
 *   3. the 15-minute and hourly MACD have just turned up — the dip is ending.
 * It is an ALERT. Nothing is bought; the rule is new and has not been tested.
 *
 * ALL FIVE GREEN is logged, never alerted, so the two can be compared on the
 * same stocks over the same weeks.
 *
 * BUILT NOT TO DISTURB THE TRADING IT SITS BESIDE. Yahoo limits how much the
 * app may fetch, and the passes that manage real positions fetch from it too:
 *   • the slow dots are nearly free — the day is the watcher's own ranking, the
 *     month is fetched once a day per stock (and only a few new ones per pass);
 *   • the hourly and four-hour MACD come from the bars the watcher has just
 *     downloaded for this stock anyway;
 *   • only a stock that passes all of that costs one short 15-minute download,
 *     and there is a hard cap on those per pass.
 * Any failure leaves the stock unjudged for this pass. It never stops the pass.
 */
object SignalWatch {
    private const val TAG = "SignalWatch"

    /** Downloads this pass may still spend, and today's already-fired events —
     *  read from the record ONCE per pass, not twice for every stock. */
    class Budget(var shortFetches: Int = 8, var monthlyFetches: Int = 6) {
        var firedIds: MutableSet<String>? = null
    }

    fun newBudget() = Budget()

    /** Has the feed a bar from today's regular session for [symbol] — finished or not? */
    fun tradedToday(base: String, symbol: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val raw = StrategyLabRunner.lastRawNewest?.takeIf { it.first.equals(MacdLadder.bare(symbol), true) }
            ?: return false
        val zone = MarketHours.zoneOf(base) ?: return false
        val open = MarketHours.openOf(base) ?: return false
        val openSec = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            .atTime(open).atZone(zone).toEpochSecond()
        return raw.second >= openSec
    }

    private fun g(s: Int?) = when (s) { 1 -> "G"; -1 -> "R"; 0 -> "·"; else -> "·" }

    /**
     * Is this stock's market trading now, with prices from today? The session by
     * the exchange's clock, and the newest bar the watcher has just fetched for
     * this very stock being current. See [MarketHours].
     */
    fun liveDataFresh(base: String, symbol: String): Boolean {
        val nowMs = System.currentTimeMillis()
        if (!MarketHours.isRegularSession(base, nowMs)) return false
        // TRADING TODAY, FROM THE OPENING MINUTES. The newest FINISHED bar is
        // yesterday's for the first hour of every session, so judged on that alone
        // the near-low and entry alerts stayed silent until an hour after the bell.
        // A bar of today's session in the feed, even one still forming, is proof
        // enough that the market is open and this stock is trading — a holiday
        // has none.
        if (tradedToday(base, symbol, nowMs)) return true
        val lc = StrategyLabRunner.lastCandles?.takeIf { it.first.equals(MacdLadder.bare(symbol), true) }
            ?: return false
        val last = lc.third.lastOrNull() ?: return false
        return MarketHours.barIsCurrent(last.timestamp, lc.second, nowMs / 1000L, FeedDelay.secFor(base))
    }

    /**
     * Judge one watchlist stock. [atFloor] and [dataFresh] come from the
     * watcher's own floor check this pass; [px] is its current price.
     */
    fun check(ctx: Context, base: String, symbol: String, rank: Int, px: Double,
              atFloor: Boolean, dataFresh: Boolean, day: String, budget: Budget) {
        if (!dataFresh || px <= 0.0) return          // market shut or prices stale: judge nothing
        val sym = MacdLadder.bare(symbol)
        val floorId = SignalTrackStore.id(SignalTrackStore.RULE_FLOOR_TURN, base, sym, day)
        val greenId = SignalTrackStore.id(SignalTrackStore.RULE_ALL_GREEN, base, sym, day)
        val fired = budget.firedIds ?: SignalTrackStore.load(ctx).events
            .filter { it.day == day }.map { it.id }.toMutableSet().also { budget.firedIds = it }
        val needFloor = atFloor && floorId !in fired
        val needGreen = greenId !in fired
        if (!needFloor && !needGreen) return

        // ── 1. The slow trend: free, or one small download a day ──────────────
        val smoothing = MacdLadder.smoothingFor(ctx, base)
        val d1 = MacdSlopes.sign(MacdLadder.rankedDailySlope(ctx, base, sym))
        if (d1 != 1) return                          // both rules need the day rising
        val cachedMonthly = MacdLadder.monthlyBars(base, sym, allowFetch = false)
        val monthly = cachedMonthly ?: run {
            if (budget.monthlyFetches <= 0) return    // this stock waits for a later pass
            budget.monthlyFetches--
            MacdLadder.monthlyBars(base, sym, allowFetch = true)
        } ?: return
        val nowSec = System.currentTimeMillis() / 1000L
        val m1 = MacdSlopes.sign(MacdSlopes.latest(MacdSlopes.completedMonths(monthly, nowSec), smoothing))
        if (m1 != 1) return                          // and the month rising

        // ── 2. Hourly and 4-hour, from the bars the watcher just fetched ──────
        val lc = StrategyLabRunner.lastCandles?.takeIf { it.first.equals(sym, true) } ?: return
        if (lc.second > 3600L) return                // coarser than an hour: cannot build it
        val delay = FeedDelay.secFor(base)
        val s1h = MacdSlopes.series(MacdSlopes.resample(lc.third, 3600L, nowSec, delay), smoothing) ?: return
        val s4h = MacdSlopes.series(MacdSlopes.resample(lc.third, 4 * 3600L, nowSec, delay), smoothing)
        val hourUp = s1h.last() > 0.0
        val wantFloor = needFloor && hourUp
        val wantGreen = needGreen && hourUp && s4h != null && s4h.last() > 0.0
        if (!wantFloor && !wantGreen) return

        // ── 3. Only now, one short 15-minute download ─────────────────────────
        if (budget.shortFetches <= 0) return
        budget.shortFetches--
        Log.d(TAG, "[$base] $sym short-term check" + (if (wantFloor) " (at its floor)" else "") +
            " — ${budget.shortFetches} left this pass")
        val c15: List<CandleData> = try {
            (YahooFinanceClient.fetchCandles15mRange(sym, Spaces.exchangeOf(base), "10d")
                as? YahooFinanceClient.CandleResult.Success)?.candles
        } catch (e: Exception) { null } ?: return
        val s15 = MacdSlopes.series(MacdSlopes.resample(c15, 15 * 60L, nowSec, delay), smoothing) ?: return

        val dots = g(m1) + g(d1) + g(s4h?.let { MacdSlopes.sign(it.last()) }) +
            g(MacdSlopes.sign(s1h.last())) + g(MacdSlopes.sign(s15.last()))

        if (wantFloor && MacdSlopes.turnedUp(s15, s1h)) {
            val ev = SignalTrackStore.Event(id = floorId, rule = SignalTrackStore.RULE_FLOOR_TURN,
                space = base, symbol = sym, day = day, atMs = System.currentTimeMillis(),
                price = px, dots = dots)
            if (SignalTrackStore.record(ctx, ev)) {
                fired.add(floorId)
                Log.i(TAG, "[$base] $sym TURNING UP AT ITS FLOOR @ $px ($dots)")
                try { LabNotifier.turningUp(ctx, base, sym, rank, px) }
                catch (e: Exception) { Log.w(TAG, "[$base] $sym turning-up alert failed", e) }
            }
        }
        if (wantGreen && s4h != null && MacdSlopes.turnedAllGreen(s15, s1h, s4h)) {
            val ev = SignalTrackStore.Event(id = greenId, rule = SignalTrackStore.RULE_ALL_GREEN,
                space = base, symbol = sym, day = day, atMs = System.currentTimeMillis(),
                price = px, dots = dots)
            if (SignalTrackStore.record(ctx, ev)) {
                fired.add(greenId)
                Log.i(TAG, "[$base] $sym all five green @ $px (logged, not alerted)")
            }
        }
    }

    // ── WHAT HAPPENED NEXT ──────────────────────────────────────────────────
    //
    // Filled in once a day from completed daily bars, measured from the price at
    // the moment the signal fired. One, three and five TRADING days later — the
    // bars themselves, so weekends and holidays count for nothing.

    /** Percent returns 1, 3 and 5 bars after the event's own day; null where not yet known. */
    fun outcomes(bars: List<DailyBar>, day: String, price: Double): Triple<Double?, Double?, Double?> {
        if (price <= 0.0) return Triple(null, null, null)
        val idx = bars.indexOfFirst { it.date.toString() >= day }
        if (idx < 0 || bars[idx].date.toString() != day) return Triple(null, null, null)
        fun r(k: Int) = bars.getOrNull(idx + k)?.close?.takeIf { it > 0.0 }?.let { (it / price - 1.0) * 100.0 }
        return Triple(r(1), r(3), r(5))
    }

    /** Give up on an event whose prices never arrive after this many days. */
    private const val GIVE_UP_DAYS = 30L

    /**
     * Fill in returns for this market's open events. [barsFor] is the daily
     * pass's own set of complete bars; a stock no longer in it is read through
     * the shared daily cache, a few per day.
     */
    fun updateOutcomes(ctx: Context, base: String, barsFor: Map<String, List<DailyBar>>) {
        val open = SignalTrackStore.load(ctx).events.filter { it.space == base && !it.done }
        if (open.isEmpty()) return
        var extraReads = 10
        val cutoff = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
        val results = HashMap<String, SignalTrackStore.Event>()
        // Keyed without the market suffix on both sides, so "HEN.DE" and "HEN"
        // are the same stock here as they are everywhere else.
        val byBare = barsFor.mapKeys { MacdLadder.bare(it.key) }
        for (e in open) {
            val bars = byBare[MacdLadder.bare(e.symbol)] ?: run {
                if (extraReads-- <= 0) null
                else try {
                    com.signalscope.app.screener.ScreenerRunner
                        .fetchDailyBarsFor(Spaces.dataSpaceOf(base), e.symbol)
                        ?.filter { it.date.isBefore(cutoff) }
                } catch (ex: Exception) { null }
            }
            val (r1, r3, r5) = if (bars != null) outcomes(bars, e.day, e.price) else Triple(null, null, null)
            val age = try {
                java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(e.day), cutoff)
            } catch (ex: Exception) { 0L }
            val next = e.copy(ret1 = r1 ?: e.ret1, ret3 = r3 ?: e.ret3, ret5 = r5 ?: e.ret5,
                done = (r5 ?: e.ret5) != null || age > GIVE_UP_DAYS)
            if (next != e) results[e.id] = next
        }
        if (results.isNotEmpty()) {
            SignalTrackStore.update(ctx) { evs -> evs.map { results[it.id] ?: it } }
            Log.i(TAG, "[$base] signal outcomes updated for ${results.size} event(s)")
        }
    }
}
