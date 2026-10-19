package com.signalscope.app.trading

import com.signalscope.app.data.CandleData
import com.signalscope.app.data.StrategyLabStore
import com.signalscope.app.screener.DailyBar
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * THE MONTHLY-LOW CHAIN, as rules — the user's decision chain, run on paper:
 *
 *   1. a stock trades in the bottom tenth of its last 21 trading days' range
 *      (the app's own "near its 1-month low", [IntradayLevels.floorsHit]);
 *   2. the news outlook is asked, and only BUY or STRONG BUY counts as yes;
 *   3. half a seat buys when the daily, 4-hour, hourly and 15-minute MACD slopes
 *      all turn rising together — the dots the watcher shows;
 *   4. the other half buys when the conservative champion's entry fires;
 *   5. each half sells when the champion's own exits fire, measured from its buy.
 *
 * WHY IT EXISTS. Backtested in the desktop lab (RESULTS_decision_chain.md) the
 * chain without step 2 made 1–6% a year against 19–41% for the momentum seats.
 * Step 2 cannot be backtested at all — the model knows what happened next — so
 * this runs it forward, and follows every stock the outlook turned down on the
 * side, so that "did the outlook help" is answered by the record itself.
 *
 * PURE: no Android, no network. The engine around it fetches and books.
 */
object ChainRules {

    /** Bottom tenth of the range counts as "at its low" — [IntradayLevels.SUPPORT_ZONE]. */
    const val ZONE = IntradayLevels.SUPPORT_ZONE
    const val MONTH_DAYS = 21
    const val MONTH_MIN_DAYS = 15

    /** The outlook verdicts that count as "positive". Everything else — WATCH,
     *  AVOID, STRONG AVOID, no verdict — is a no. */
    private val POSITIVE = setOf("BUY", "STRONG BUY")
    fun positive(verdict: String): Boolean = verdict.trim().uppercase() in POSITIVE

    /**
     * The conservative B_SLOW champion, exactly as the desktop lab ships it
     * (runs/lab_configs/champions/B_SLOW_240m-120m_STOCKS-conservative…json),
     * minus its dataset and results. Built in rather than read from the space's
     * active config, because the space may be running a different champion and
     * this test was asked with the conservative one.
     */
    const val CONSERVATIVE_JSON = """{"strategy":"macd","resample":"240min","exitBars":"120min","warmupBars":240,"notional":1000,"slippagePct":0.2,"macd":{"fast":12,"slow":26,"signal":9},"entry":{"mode":"ALL","slopeFlipUp":{"enabled":true,"smoothing":2,"persistent":true},"slopePositive":{"enabled":true},"macdCrossUp":{"enabled":false},"macdAboveSignal":{"enabled":false},"macdAboveZero":{"enabled":false},"accelPositive":{"enabled":true,"minSigma":0.7},"sustainSlope":{"enabled":true,"bars":20},"earlyRecovery":{"enabled":true,"pct":60,"lookback":22,"confirmVelocity":true},"velocity":{"enabled":false,"lookback":10,"threshold":0.5}},"gates":{"adx":{"enabled":false,"min":20,"period":14},"sma":{"enabled":true,"length":50,"kind":"ema"},"emaSlope":{"enabled":false,"length":16,"lookback":10,"min":0.1}},"exit":{"mode":"ANY","slopeFlipDown":{"enabled":true,"smoothing":2,"persistent":false},"fastMacdExit":{"enabled":false},"slopeRetrace":{"enabled":false,"pct":40,"confirmVelocity":true},"macdCrossDown":{"enabled":false},"macdBelowZero":{"enabled":false},"accelNegative":{"enabled":false},"velocity":{"enabled":false,"lookback":10,"threshold":0},"stopLoss":{"enabled":true,"pct":4},"takeProfit":{"enabled":false,"pct":1.6},"maxBars":{"enabled":false,"bars":100},"macdDistance":{"enabled":true,"pct":30},"minHoldBars":0,"session":{"enabled":false,"flattenAtClose":false,"noEntryAfter":"15:30"}}}"""

    /** The champion for [space], on that exchange's bar grid. Null only if the
     *  built-in text stopped parsing, which a test guards against. */
    fun championConfig(space: String): StrategyLabConfig? =
        (StrategyLabConfig.fromJson(CONSERVATIVE_JSON, "conservative champion (chain)")
            as? StrategyLabConfig.Companion.ParseResult.Ok)?.config
            ?.copy(barAnchorSec = StrategyLabStore.barAnchorFor(space))

    /**
     * Did the last of these days reach the bottom tenth of the last 21 trading
     * days' range (that day included)? Judged on the day's LOW — at some moment
     * of it, the price sat where the watcher's near-low alert would have fired.
     * Bars oldest first, the last one the day being judged.
     */
    fun touchedMonthlyLow(bars: List<DailyBar>): Boolean {
        if (bars.size < MONTH_MIN_DAYS) return false
        val w = bars.takeLast(MONTH_DAYS)
        val lo = w.minOf { it.low }
        val hi = w.maxOf { it.high }
        if (!(lo > 0.0) || !(hi > lo)) return false
        return (w.last().low - lo) / (hi - lo) <= ZONE
    }

    /** [n] trading days (Monday to Friday) after [from]. Holidays are not known
     *  here, so a watch may end a day early around one — never late. */
    fun addTradingDays(from: LocalDate, n: Int): LocalDate {
        var d = from
        var left = n
        while (left > 0) {
            d = d.plusDays(1)
            if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) left--
        }
        return d
    }

    /** The daily MACD slope in force on each day: the last COMPLETED day's, so
     *  never the day still trading. Null before 30 days of history. */
    fun dailySlopeBefore(bars: List<DailyBar>, smoothing: Int): (LocalDate) -> Double? {
        val s = MacdSlopes.series(bars.map { it.close }.toDoubleArray(), smoothing)
        val dates = bars.map { it.date }
        return { day ->
            val i = dates.indexOfLast { it.isBefore(day) }
            if (s == null || i < MacdSlopes.MIN_BARS - 1) null else s[i]
        }
    }

    data class Turn(val barSec: Long, val endSec: Long, val close: Double)

    /** Closes of UTC-aligned [periodSec] buckets, each with the time it completes. */
    private class Buckets(bars: List<CandleData>, periodSec: Long, smoothing: Int) {
        val done: LongArray
        val slope: DoubleArray?
        init {
            val keys = ArrayList<Long>(); val closes = ArrayList<Double>(); val ends = ArrayList<Long>()
            for (b in bars) {
                val k = b.timestamp / periodSec
                if (keys.isNotEmpty() && keys.last() == k) {
                    closes[closes.size - 1] = b.close
                    ends[ends.size - 1] = maxOf((k + 1) * periodSec, b.timestamp + 900L)
                } else {
                    keys.add(k); closes.add(b.close); ends.add(maxOf((k + 1) * periodSec, b.timestamp + 900L))
                }
            }
            done = ends.toLongArray()
            slope = MacdSlopes.series(closes.toDoubleArray(), smoothing)
        }

        /** Slope of the last bucket complete by [t]; null without 30 of them. */
        fun at(t: Long): Double? {
            val s = slope ?: return null
            var lo = 0; var hi = done.size
            while (lo < hi) { val m = (lo + hi) ushr 1; if (done[m] <= t) lo = m + 1 else hi = m }
            val i = lo - 1
            return if (i < MacdSlopes.MIN_BARS - 1) null else s[i]
        }
    }

    /**
     * The first finished fifteen-minute bar starting at or after [afterSec] on
     * which the daily, 4-hour, hourly and 15-minute MACD slopes are all rising
     * and were not all rising on the bar before — the moment the dots turn green.
     *
     * Same slope as [MacdSlopes] (the watcher's dots), on completed bars only:
     * each bar is judged with the 4-hour and hourly buckets finished by its end,
     * and the daily slope of the last finished day.
     */
    fun firstGreenTurn(
        c15: List<CandleData>, dailyBefore: (LocalDate) -> Double?, zone: ZoneId,
        afterSec: Long, nowSec: Long, delaySec: Long, smoothing: Int
    ): Turn? {
        val bars = c15.filter {
            it.timestamp % 900L == 0L && it.close > 0.0 && it.close.isFinite() &&
                FeedDelay.isComplete(it.timestamp, 900L, nowSec, 0L, delaySec)
        }.sortedBy { it.timestamp }
        if (bars.size < MacdSlopes.MIN_BARS + 1) return null
        val s15 = MacdSlopes.series(bars.map { it.close }.toDoubleArray(), smoothing) ?: return null
        val h1 = Buckets(bars, 3600L, smoothing)
        val h4 = Buckets(bars, 4 * 3600L, smoothing)
        fun green(k: Int): Boolean {
            if (k < MacdSlopes.MIN_BARS - 1) return false
            val end = bars[k].timestamp + 900L
            val day = Instant.ofEpochSecond(bars[k].timestamp).atZone(zone).toLocalDate()
            val d = dailyBefore(day) ?: return false
            val a = h4.at(end) ?: return false
            val b = h1.at(end) ?: return false
            return d > 0.0 && a > 0.0 && b > 0.0 && s15[k] > 0.0
        }
        var prev = green(0)
        for (k in 1 until bars.size) {
            val g = green(k)
            if (g && !prev && bars[k].timestamp >= afterSec)
                return Turn(bars[k].timestamp, bars[k].timestamp + 900L, bars[k].close)
            prev = g
        }
        return null
    }

    /** The open of the first fifteen-minute bar starting at or after [fromSec], on any day. */
    fun nextOpen(c15: List<CandleData>, fromSec: Long): FloorOrders.Fill? {
        val b = c15.asSequence().filter { it.timestamp >= fromSec && it.open > 0.0 && it.open.isFinite() }
            .minByOrNull { it.timestamp } ?: return null
        return FloorOrders.Fill(b.timestamp, b.open)
    }

    /**
     * The moment the newest finished [frameSec] bar became visible: its end on the
     * grid anchored at [anchorSec], plus the feed's delay and a minute's grace.
     * The champion can only change its mind when one of its bars finishes, so it
     * is asked again only once this moves past the last time it was asked.
     */
    fun frameVisibleAt(nowSec: Long, frameSec: Long, anchorSec: Long, delaySec: Long): Long {
        val lag = delaySec + 60L
        val end = Math.floorDiv(nowSec - lag - anchorSec, frameSec) * frameSec + anchorSec
        return end + lag
    }

    /** Rounded up to the next fifteen-minute boundary. */
    fun ceil15(sec: Long): Long = ((sec + 899L) / 900L) * 900L
}
