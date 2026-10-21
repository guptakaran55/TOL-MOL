package com.signalscope.app.trading

import com.signalscope.app.data.CandleData
import java.time.Instant
import java.time.ZoneOffset

/**
 * WHICH WAY MACD IS TURNING, ON FIVE TIMESCALES AT ONCE — month, day, four
 * hours, hour and fifteen minutes — for the row of dots on a watcher alert.
 *
 * ONE DEFINITION OF "SLOPE", THE APP'S OWN: how far the MACD line (12/26 EMA)
 * moved over the last completed bar. It is exactly what the watcher's SLOPE
 * column shows for the day, so the 1D dot and that column can never disagree.
 *
 * NOT SMOOTHED BY DEFAULT. It used to be averaged over about five bars, and
 * that average lagged the line it described: a daily MACD that had fallen two
 * days running still showed a green dot because of the rise the week before
 * (Hensoldt, 8 Oct). The MACD is already an average, and reading it on five
 * timescales at once is what filters the noise. A strategy file can still ask
 * for smoothing, and crypto keeps its light one.
 *
 * COMPLETED BARS ONLY, on every timescale. A bar still forming would colour a
 * dot on a candle that has not closed — and on a German stock the feed's own
 * fifteen-minute delay counts too (see [FeedDelay]). The month in progress is
 * left out for the same reason.
 *
 * REGULAR SESSION ONLY, as every MACD the strategies reason about is.
 */
object MacdSlopes {

    /** Fewer closes than this and a 26-bar average has not settled: no verdict. */
    const val MIN_BARS = 30

    /** One dot: its label, and +1 rising, -1 falling, 0 flat, null unknown. */
    data class Reading(val label: String, val sign: Int?)

    val LABELS = listOf("1M", "1D", "4H", "1H", "15m")

    /** The slope smoothing when no strategy file sets one: none for stocks (1 =
     *  the last bar's move as it is), light for crypto. */
    fun defaultSmoothing(base: String): Int = if (base == "binance") 2 else 1

    /** Span 1 is no smoothing at all: the series comes back unchanged. */
    private fun ema(x: DoubleArray, span: Int): DoubleArray {
        val s = if (span < 1) 1 else span
        val a = 2.0 / (s + 1.0)
        val out = DoubleArray(x.size)
        if (x.isEmpty()) return out
        out[0] = x[0]
        for (i in 1 until x.size) out[i] = a * x[i] + (1 - a) * out[i - 1]
        return out
    }

    /** The smoothed MACD slope on the newest close, or null without enough history. */
    fun latest(closes: DoubleArray, smoothing: Int): Double? {
        if (closes.size < MIN_BARS) return null
        val e12 = ema(closes, 12); val e26 = ema(closes, 26)
        val macd = DoubleArray(closes.size) { e12[it] - e26[it] }
        val raw = DoubleArray(closes.size); raw[0] = 0.0
        for (i in 1 until macd.size) raw[i] = macd[i] - macd[i - 1]
        return ema(raw, smoothing).last()
    }

    /** The same slope from an already-computed MACD series (the stored daily curve). */
    fun latestFromMacd(macd: List<Double>, smoothing: Int): Double? {
        if (macd.size < 2) return null
        val raw = DoubleArray(macd.size); raw[0] = 0.0
        for (i in 1 until macd.size) raw[i] = macd[i] - macd[i - 1]
        return ema(raw, smoothing).last()
    }

    fun sign(slope: Double?): Int? = when {
        slope == null || !slope.isFinite() -> null
        slope > 0.0 -> 1
        slope < 0.0 -> -1
        else -> 0
    }

    /**
     * Closes of the COMPLETED [periodSec] bars built from fifteen-minute candles,
     * oldest first. Bars are aligned to the clock in UTC, which is how the engine
     * aligns its own two- and four-hour bars.
     */
    fun resample(c15: List<CandleData>, periodSec: Long, nowSec: Long, delaySec: Long): DoubleArray {
        val buckets = java.util.TreeMap<Long, Double>()
        for (c in c15) {
            if (!(c.close > 0.0) || !c.close.isFinite()) continue
            val start = (c.timestamp / periodSec) * periodSec
            buckets[start] = c.close          // candles arrive in time order: last one wins
        }
        return buckets.filterKeys { FeedDelay.isComplete(it, periodSec, nowSec, 0L, delaySec) }
            .values.toDoubleArray()
    }

    /** Monthly closes without the month still in progress. */
    fun completedMonths(monthly: List<CandleData>, nowSec: Long): DoubleArray {
        val now = Instant.ofEpochSecond(nowSec).atZone(ZoneOffset.UTC)
        return monthly.filter {
            val t = Instant.ofEpochSecond(it.timestamp).atZone(ZoneOffset.UTC)
            it.close > 0.0 && it.close.isFinite() &&
                !(t.year == now.year && t.monthValue == now.monthValue)
        }.map { it.close }.toDoubleArray()
    }

    /**
     * All five, longest timescale first. [dailySlope] comes from the watcher's
     * own ranking where it has one, so it is the very number its SLOPE column
     * shows; any input that is missing leaves its dot unknown, never guessed.
     */
    fun all(c15: List<CandleData>?, monthly: List<CandleData>?, dailySlope: Double?,
            nowSec: Long, delaySec: Long, smoothing: Int): List<Reading> {
        val q = c15.orEmpty()
        return listOf(
            Reading("1M", sign(monthly?.let { latest(completedMonths(it, nowSec), smoothing) })),
            Reading("1D", sign(dailySlope)),
            Reading("4H", sign(if (q.isEmpty()) null else latest(resample(q, 4 * 3600L, nowSec, delaySec), smoothing))),
            Reading("1H", sign(if (q.isEmpty()) null else latest(resample(q, 3600L, nowSec, delaySec), smoothing))),
            Reading("15m", sign(if (q.isEmpty()) null else latest(resample(q, 15 * 60L, nowSec, delaySec), smoothing))))
    }

    // ── WHEN DID IT TURN? ───────────────────────────────────────────────────
    // A dot says where the slope IS. An alert needs the moment it CHANGED —
    // otherwise a stock sitting all day with its short dots green would "turn up"
    // on every pass. Worked out from the history itself rather than remembered
    // between passes, so a restart, a skipped pass or a killed app can neither
    // invent a turn nor lose one.

    /** The smoothed slope on every close, oldest first; null without enough history. */
    fun series(closes: DoubleArray, smoothing: Int): DoubleArray? {
        if (closes.size < MIN_BARS) return null
        val e12 = ema(closes, 12); val e26 = ema(closes, 26)
        val macd = DoubleArray(closes.size) { e12[it] - e26[it] }
        val raw = DoubleArray(closes.size); raw[0] = 0.0
        for (i in 1 until macd.size) raw[i] = macd[i] - macd[i - 1]
        return ema(raw, smoothing)
    }

    private fun at(s: DoubleArray, back: Int): Double? =
        if (s.size > back) s[s.size - 1 - back] else null

    /**
     * THE DIP ENDING: the 15-minute and hourly slopes are both rising now, and
     * an hour ago they were not both rising. [back15] quarter-hours ago is "an
     * hour ago" on the 15-minute series; one bar back on the hourly one.
     */
    fun turnedUp(s15: DoubleArray, s1h: DoubleArray, back15: Int = 4): Boolean {
        val n15 = at(s15, 0) ?: return false
        val n1h = at(s1h, 0) ?: return false
        if (n15 <= 0.0 || n1h <= 0.0) return false
        val p15 = at(s15, back15) ?: return false
        val p1h = at(s1h, 1) ?: return false
        return !(p15 > 0.0 && p1h > 0.0)
    }

    /**
     * ALL FIVE NEWLY GREEN — for the comparison log. The month and day are
     * checked by the caller (they move slowly); this asks whether the three
     * faster ones are rising now and were not all rising about two hours ago.
     * The wider window matches how often each stock gets looked at.
     */
    fun turnedAllGreen(s15: DoubleArray, s1h: DoubleArray, s4h: DoubleArray): Boolean {
        val now = listOf(at(s15, 0), at(s1h, 0), at(s4h, 0))
        if (now.any { it == null || it <= 0.0 }) return false
        val then = listOf(at(s15, 8), at(s1h, 2), at(s4h, 1))
        if (then.any { it == null }) return false
        return !then.all { it!! > 0.0 }
    }

    /** Green rising, red falling, grey flat or unknown. */
    fun color(sign: Int?): Int = when (sign) {
        1 -> 0xFF059669.toInt()
        -1 -> 0xFFDC2626.toInt()
        else -> 0xFF94A3B8.toInt()
    }
}
