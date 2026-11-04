package com.signalscope.app.trading

import com.signalscope.app.data.CandleData
import kotlin.math.max
import kotlin.math.min

// ════════════════════════════════════════════════════════════════════════════
// BULL FLAG — breakout on a volume surge. BACKTEST ONLY: nothing here places
// or queues an order, and no live engine calls it.
// ════════════════════════════════════════════════════════════════════════════
// The shape, read off completed DAILY bars:
//   Pole — a sharp run (>= poleMinGainPct inside poleMaxDays) on volume well
//          above the stock's own normal.
//   Flag — the days after the pole's top: a shallow drift (gives back at most
//          flagMaxRetracePct of the pole) on volume that has dried up.
//   Line — the flag's highest high. Clearing it is the breakout.
//
// The trigger, walked bar by bar through the NEXT day (hourly data, or one bar
// per day for daily-only data):
//   price clears the line AND the day's volume so far is running at
//   volPaceMult × what this stock usually trades BY THE SAME POINT OF THE DAY.
// Same-point-of-day matters: the open is always heavy, so comparing an hour of
// trading to a whole-day average would call every morning a surge.
//
// Fills are deliberately conservative — the buy lands at the CLOSE of the bar
// where both conditions first hold (the moment the volume is actually known),
// not at the line. A buy that would already sit more than maxChasePct above the
// line is skipped as chasing. Inside one bar a stop and a target both touched
// counts as the stop.
// ════════════════════════════════════════════════════════════════════════════

object BullFlag {

    data class Params(
        // pole
        val poleMinDays: Int = 2,
        val poleMaxDays: Int = 15,
        val poleMinGainPct: Double = 15.0,
        val poleVolMult: Double = 1.3,          // pole's avg volume vs the 50 days before it
        // flag
        val flagMinDays: Int = 3,
        val flagMaxDays: Int = 15,
        val flagMaxRetracePct: Double = 50.0,   // deepest flag low, as % of the pole's height
        val flagVolDryUp: Double = 0.8,         // flag's avg volume vs the pole's
        val requireAboveSma50: Boolean = true,
        // breakout
        val requireVolume: Boolean = true,      // false = buy on price alone (the comparison run)
        val volPaceMult: Double = 1.5,
        val paceLookbackDays: Int = 20,
        val maxChasePct: Double = 2.0,
        // exits
        val stopBufferPct: Double = 0.5,        // stop sits this far under the flag's low
        val targetPoleMult: Double = 1.0,       // 1.0 = the classic measured move (pole height)
        val breakevenAtR: Double = 1.0,         // stop moves to entry once up this many risks; 0 = off
        val maxHoldDays: Int = 20,
        val costPct: Double = 0.476             // round trip, taken off every trade
    )

    /** One trading day built from the bars of one UTC calendar day. */
    data class Day(
        val epochDay: Long,
        val open: Double, val high: Double, val low: Double, val close: Double,
        val volume: Double,
        val firstBar: Int,                      // index into the candle list
        val barCount: Int
    )

    data class Setup(
        val asOfDay: Long,                      // epoch day of the last completed day it was read from
        val poleStartDay: Long, val poleTopDay: Long,
        val poleLow: Double, val poleHigh: Double,
        val trigger: Double,                    // the flag's highest high
        val flagLow: Double,
        val flagDays: Int,
        val poleGainPct: Double,
        val retracePct: Double,
        val poleVolRatio: Double,
        val flagVolRatio: Double
    )

    data class Trade(
        val symbol: String,
        val setup: Setup,
        val entryTs: Long, val entryPrice: Double,
        val volPace: Double,                    // day-so-far volume vs normal at the buy; NaN if unknown
        val stop: Double, val target: Double,  // stop as first placed, before any move to entry
        val exitTs: Long?, val exitPrice: Double?,
        val exitReason: String,                 // STOP | BREAKEVEN | TARGET | TIME | OPEN
        val pctReturn: Double,                  // net of costPct; an OPEN trade is marked at the last close
        val holdDays: Int
    )

    data class Result(
        val symbol: String,
        val trades: List<Trade>,
        val setupDays: Int,                     // days that started with a valid flag on the board
        val breaksWithoutVolume: Int,           // price cleared the line but no bar closed above it on a surge
        val chased: Int,                        // confirmed, but too far above the line to buy
        val marketBlocked: Int = 0              // flag on the board, but the market wasn't in an uptrend
    )

    // ── market trend ────────────────────────────────────────────────────────

    enum class MarketRule(val label: String) {
        ABOVE_SMA50("index above its 50-day average"),
        ABOVE_SMA200("index above its 200-day average"),
        SMA50_OVER_SMA200("index 50-day average above its 200-day")
    }

    /**
     * Is the market in an uptrend on [epochDay]? Judged from the index's last
     * close BEFORE that day — a breakout at 10:00 can't know today's close.
     * Days before the index has enough history read as "no".
     */
    fun marketTrend(index: List<CandleData>, rule: MarketRule): (Long) -> Boolean {
        val days = toDays(index)
        val closes = DoubleArray(days.size) { days[it].close }
        val s50 = sma(closes, 50)
        val s200 = sma(closes, 200)
        val up = BooleanArray(days.size) { i ->
            when (rule) {
                MarketRule.ABOVE_SMA50 -> !s50[i].isNaN() && closes[i] > s50[i]
                MarketRule.ABOVE_SMA200 -> !s200[i].isNaN() && closes[i] > s200[i]
                MarketRule.SMA50_OVER_SMA200 -> !s200[i].isNaN() && s50[i] > s200[i]
            }
        }
        val epochs = LongArray(days.size) { days[it].epochDay }
        return { day ->
            // last index day strictly before [day]
            var lo = 0; var hi = epochs.size - 1; var ans = -1
            while (lo <= hi) { val m = (lo + hi) / 2; if (epochs[m] < day) { ans = m; lo = m + 1 } else hi = m - 1 }
            ans >= 0 && up[ans]
        }
    }

    // ── daily view ──────────────────────────────────────────────────────────

    fun toDays(candles: List<CandleData>): List<Day> {
        val out = ArrayList<Day>()
        var i = 0
        while (i < candles.size) {
            val day = candles[i].timestamp / 86_400L
            var j = i
            var hi = candles[i].high; var lo = candles[i].low; var vol = 0.0
            while (j < candles.size && candles[j].timestamp / 86_400L == day) {
                hi = max(hi, candles[j].high); lo = min(lo, candles[j].low); vol += candles[j].volume
                j++
            }
            out.add(Day(day, candles[i].open, hi, lo, candles[j - 1].close, vol, i, j - i))
            i = j
        }
        return out
    }

    /**
     * Split-repair prices (as the Strategy Lab does) AND scale volume the other
     * way. A 1:2 split doubles the share count, so unrepaired volume would read
     * the months before it as half as busy — a fake "pole on heavy volume".
     */
    fun backAdjustWithVolume(raw: List<CandleData>): List<CandleData> {
        val adj = StrategyLabEngine.backAdjust(raw)
        if (adj === raw) return raw
        return adj.mapIndexed { i, c ->
            val f = if (raw[i].close > 0.0) c.close / raw[i].close else 1.0
            if (f == 1.0 || f <= 0.0) c else c.copy(volume = (c.volume / f).toLong())
        }
    }

    private fun sma(closes: DoubleArray, len: Int): DoubleArray {
        val out = DoubleArray(closes.size) { Double.NaN }
        var sum = 0.0
        for (i in closes.indices) {
            sum += closes[i]
            if (i >= len) sum -= closes[i - len]
            if (i >= len - 1) out[i] = sum / len
        }
        return out
    }

    private fun avgVolume(days: List<Day>, from: Int, to: Int): Double {
        if (to < from) return 0.0
        var s = 0.0
        for (i in from..to) s += days[i].volume
        return s / (to - from + 1)
    }

    /**
     * The flag on the board after day [d] closed, or null. Reads days 0..d only.
     */
    fun detect(days: List<Day>, d: Int, p: Params, sma50: DoubleArray? = null): Setup? {
        if (d < 0 || d >= days.size) return null
        // Pole top: the latest highest high inside the flag's longest allowed span.
        val from = max(0, d - p.flagMaxDays)
        var top = from
        for (i in from..d) if (days[i].high >= days[top].high) top = i
        val flagDays = d - top
        if (flagDays < p.flagMinDays) return null

        // Pole start: the latest lowest low poleMinDays..poleMaxDays before the top.
        val sFrom = top - p.poleMaxDays
        val sTo = top - p.poleMinDays
        if (sTo < 0) return null
        var s = -1
        for (i in max(0, sFrom)..sTo) if (s < 0 || days[i].low <= days[s].low) s = i
        // Need a baseline of the stock's normal volume before the run began.
        if (s < 20) return null

        val poleLow = days[s].low
        val poleHigh = days[top].high
        if (poleLow <= 0.0 || poleHigh <= poleLow) return null
        val poleGainPct = (poleHigh / poleLow - 1.0) * 100.0
        if (poleGainPct < p.poleMinGainPct) return null

        val baseVol = avgVolume(days, max(0, s - 50), s - 1)
        val poleVol = avgVolume(days, s, top)
        if (baseVol <= 0.0 || poleVol <= 0.0) return null
        val poleVolRatio = poleVol / baseVol
        if (poleVolRatio < p.poleVolMult) return null

        var flagHigh = days[top + 1].high
        var flagLow = days[top + 1].low
        for (i in top + 1..d) { flagHigh = max(flagHigh, days[i].high); flagLow = min(flagLow, days[i].low) }
        val retracePct = (poleHigh - flagLow) / (poleHigh - poleLow) * 100.0
        if (retracePct > p.flagMaxRetracePct) return null
        val flagVolRatio = avgVolume(days, top + 1, d) / poleVol
        if (flagVolRatio > p.flagVolDryUp) return null

        if (p.requireAboveSma50) {
            val m = (sma50 ?: sma(DoubleArray(days.size) { days[it].close }, 50))[d]
            if (m.isNaN() || days[d].close <= m) return null
        }

        return Setup(
            asOfDay = days[d].epochDay,
            poleStartDay = days[s].epochDay, poleTopDay = days[top].epochDay,
            poleLow = poleLow, poleHigh = poleHigh,
            trigger = flagHigh, flagLow = flagLow, flagDays = flagDays,
            poleGainPct = poleGainPct, retracePct = retracePct,
            poleVolRatio = poleVolRatio, flagVolRatio = flagVolRatio
        )
    }

    // ── the walk ────────────────────────────────────────────────────────────

    private class Open(
        val setup: Setup, val entryTs: Long, val entryPrice: Double, val volPace: Double,
        var stop: Double, val target: Double, val risk: Double, val entryDayIdx: Int,
        val initialStop: Double = stop,
        var stopRaised: Boolean = false
    )

    /** [marketUp], when given, must say yes for a breakout day before any buy (see [marketTrend]). */
    fun run(symbol: String, candles: List<CandleData>, p: Params = Params(),
            marketUp: ((Long) -> Boolean)? = null): Result {
        val days = toDays(candles)
        val sma50 = sma(DoubleArray(days.size) { days[it].close }, 50)
        // cum[d][k] = volume traded on day d through its k-th bar.
        val cum = Array(days.size) { d ->
            val day = days[d]
            val a = DoubleArray(day.barCount)
            var s = 0.0
            for (k in 0 until day.barCount) { s += candles[day.firstBar + k].volume; a[k] = s }
            a
        }
        fun normalCum(d: Int, k: Int): Double {
            var s = 0.0; var n = 0
            var i = d - 1
            while (i >= 0 && n < p.paceLookbackDays) {
                if (cum[i].size > k) { s += cum[i][k]; n++ }
                i--
            }
            return if (n >= 5) s / n else Double.NaN
        }

        val trades = ArrayList<Trade>()
        var setupDays = 0; var noVolume = 0; var chased = 0; var blocked = 0
        var pos: Open? = null

        fun close(o: Open, ts: Long, price: Double, reason: String, dayIdx: Int) {
            trades.add(Trade(symbol, o.setup, o.entryTs, o.entryPrice, o.volPace, o.initialStop, o.target,
                ts, price, reason, (price / o.entryPrice - 1.0) * 100.0 - p.costPct, dayIdx - o.entryDayIdx))
        }

        /** Walk bars [from, until) of day [e] for exits. True when the position closed. */
        fun walkExits(o: Open, e: Int, from: Int): Boolean {
            val day = days[e]
            for (k in from until day.barCount) {
                val b = candles[day.firstBar + k]
                if (b.open <= o.stop || b.low <= o.stop) {
                    close(o, b.timestamp, min(o.stop, b.open), if (o.stopRaised) "BREAKEVEN" else "STOP", e)
                    return true
                }
                if (b.open >= o.target || b.high >= o.target) {
                    close(o, b.timestamp, max(o.target, b.open), "TARGET", e)
                    return true
                }
                if (p.breakevenAtR > 0.0 && !o.stopRaised && b.high >= o.entryPrice + o.risk * p.breakevenAtR) {
                    o.stop = o.entryPrice; o.stopRaised = true
                }
            }
            if (e - o.entryDayIdx >= p.maxHoldDays) {
                val last = candles[day.firstBar + day.barCount - 1]
                close(o, last.timestamp, last.close, "TIME", e)
                return true
            }
            return false
        }

        for (e in 1 until days.size) {
            val held = pos
            if (held != null) {
                if (walkExits(held, e, 0)) pos = null
                continue                                    // no fresh buy on a day a position was held
            }
            val setup = detect(days, e - 1, p, sma50) ?: continue
            setupDays++
            val day = days[e]
            if (marketUp != null && !marketUp(day.epochDay)) { blocked++; continue }
            var unconfirmed = false
            for (k in 0 until day.barCount) {
                val b = candles[day.firstBar + k]
                if (b.high <= setup.trigger) continue
                unconfirmed = true
                val normal = normalCum(e, k)
                val pace = if (normal.isNaN() || normal <= 0.0) Double.NaN else cum[e][k] / normal
                val volumeOk = !p.requireVolume || (!pace.isNaN() && pace >= p.volPaceMult)
                if (!volumeOk || b.close <= setup.trigger) continue
                unconfirmed = false
                if (b.close > setup.trigger * (1.0 + p.maxChasePct / 100.0)) { chased++; break }
                val stop = setup.flagLow * (1.0 - p.stopBufferPct / 100.0)
                if (stop >= b.close) break
                val o = Open(setup, b.timestamp, b.close, pace, stop,
                    b.close + (setup.poleHigh - setup.poleLow) * p.targetPoleMult, b.close - stop, e)
                if (!walkExits(o, e, k + 1)) pos = o
                break
            }
            if (unconfirmed) noVolume++
        }

        pos?.let { o ->
            val last = candles.last()
            trades.add(Trade(symbol, o.setup, o.entryTs, o.entryPrice, o.volPace, o.initialStop, o.target,
                null, null, "OPEN", (last.close / o.entryPrice - 1.0) * 100.0 - p.costPct, days.size - 1 - o.entryDayIdx))
        }
        return Result(symbol, trades, setupDays, noVolume, chased, blocked)
    }

    // ── reading the results ─────────────────────────────────────────────────

    data class Stats(
        val trades: Int, val wins: Int, val winRatePct: Double,
        val avgPct: Double, val medianPct: Double,
        val avgWinPct: Double, val avgLossPct: Double,
        val profitFactor: Double,              // gross wins / gross losses
        val avgHoldDays: Double,
        val byExit: Map<String, Int>
    )

    fun stats(trades: List<Trade>): Stats {
        val closed = trades.filter { it.exitReason != "OPEN" }
        if (closed.isEmpty()) return Stats(0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, emptyMap())
        val r = closed.map { it.pctReturn }.sorted()
        val wins = r.filter { it > 0 }
        val losses = r.filter { it <= 0 }
        val gl = -losses.sum()
        return Stats(
            trades = closed.size, wins = wins.size, winRatePct = wins.size * 100.0 / closed.size,
            avgPct = r.average(),
            medianPct = if (r.size % 2 == 1) r[r.size / 2] else (r[r.size / 2 - 1] + r[r.size / 2]) / 2,
            avgWinPct = if (wins.isEmpty()) 0.0 else wins.average(),
            avgLossPct = if (losses.isEmpty()) 0.0 else losses.average(),
            profitFactor = if (gl > 0) wins.sum() / gl else Double.POSITIVE_INFINITY,
            avgHoldDays = closed.map { it.holdDays }.average(),
            byExit = closed.groupingBy { it.exitReason }.eachCount()
        )
    }

    data class Portfolio(
        val seats: Int,
        val taken: Int,
        val skippedFull: Int,                  // breakouts that came while every seat was busy
        val startValue: Double, val finalValue: Double,
        val compoundedPct: Double, val cagrPct: Double,
        val maxDrawdownPct: Double,            // on realised equity, at each exit
        val spanDays: Double
    )

    /**
     * Fixed seats, each compounding its own money; breakouts are taken in time
     * order while a seat is free. Ties at the same hour go alphabetically, so a
     * rerun is identical. OPEN trades count at their last mark.
     */
    fun portfolio(trades: List<Trade>, seats: Int, perSeat: Double = 1000.0,
                  firstTs: Long, lastTs: Long): Portfolio {
        val caps = DoubleArray(seats) { perSeat }
        val busyUntil = LongArray(seats) { Long.MIN_VALUE }
        data class Exit(val ts: Long, val seat: Int, val pct: Double)
        val pending = ArrayList<Exit>()
        var taken = 0; var skipped = 0
        var peak = perSeat * seats; var maxDd = 0.0
        fun settle(upTo: Long) {
            pending.sortBy { it.ts }
            val it = pending.iterator()
            while (it.hasNext()) {
                val x = it.next()
                if (x.ts > upTo) break
                caps[x.seat] *= 1.0 + x.pct / 100.0
                val v = caps.sum()
                peak = max(peak, v)
                maxDd = max(maxDd, (peak - v) / peak * 100.0)
                it.remove()
            }
        }
        for (t in trades.sortedWith(compareBy({ it.entryTs }, { it.symbol }))) {
            settle(t.entryTs)
            val seat = (0 until seats).firstOrNull { busyUntil[it] <= t.entryTs }
            if (seat == null) { skipped++; continue }
            val exitTs = t.exitTs ?: Long.MAX_VALUE
            busyUntil[seat] = exitTs
            pending.add(Exit(if (t.exitTs == null) lastTs else exitTs, seat, t.pctReturn))
            taken++
        }
        settle(Long.MAX_VALUE)
        val start = perSeat * seats
        val final = caps.sum()
        val compounded = (final / start - 1.0) * 100.0
        val span = (lastTs - firstTs) / 86_400.0
        return Portfolio(seats, taken, skipped, start, final, compounded,
            StrategyLabBacktest.cagr(compounded, span), maxDd, span)
    }
}
