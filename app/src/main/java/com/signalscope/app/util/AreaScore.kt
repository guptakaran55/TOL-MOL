package com.signalscope.app.util

import com.signalscope.app.data.CandleData
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Area score: how far the closes of the last 12 months sit above (+) or below (−) today's close,
 * as a weighted average of the daily gaps, in % of today's close.
 *
 *   gap(k)  = (close[k] − close[today]) / close[today] × 100
 *   w(m)    = (m/12)^1.8333 × (1 − m/12)^3.6667      m = calendar months back, 0 < m < 12
 *   score   = Σ w·gap / Σ w
 *
 * The weight curve is the lopsided "binomial-style" curve tuned in the Area Score Explorer
 * (window 12 months, peak 4 months back, sharpness 7.5): it ignores the last few days, peaks
 * 4 months back and fades out at a year. Green / red are the weighted parts above / below today,
 * so score = green + red.
 *
 * rank is today's score placed against this stock's own earlier scores (up to 2 years back,
 * ties count half), 0–100: 100 = highest it has been, 50 = typical for this stock. It needs
 * enough history for at least [RANK_MIN_SCORES] earlier scores, otherwise it is null.
 * No logarithms and no volatility adjustment, to match the explorer.
 */
object AreaScore {
    const val WINDOW_MONTHS = 12.0
    private const val EXP_A = 1.8333
    private const val EXP_B = 3.6667
    private const val MONTH_DAYS = 365.25 / 12
    private const val RANK_LOOKBACK_DAYS = 730.0
    private const val RANK_MIN_SCORES = 120

    data class Result(val score: Double, val green: Double, val red: Double, val rank: Double?)

    /** Weight of a close [monthsBack] calendar months before the scored day. */
    fun weight(monthsBack: Double): Double {
        if (monthsBack <= 0.0 || monthsBack >= WINDOW_MONTHS) return 0.0
        val x = monthsBack / WINDOW_MONTHS
        return x.pow(EXP_A) * (1 - x).pow(EXP_B)
    }

    // Daily bars sit a whole number of days apart, so weights are looked up by day gap.
    private val weightByDay = DoubleArray((WINDOW_MONTHS * MONTH_DAYS).toInt() + 2) { weight(it / MONTH_DAYS) }

    fun compute(candles: List<CandleData>): Result? {
        val n = candles.size
        if (n < 2) return null
        val days = DoubleArray(n) { dayOf(candles[it].timestamp) }
        val close = DoubleArray(n) { candles[it].close }
        val window = WINDOW_MONTHS * MONTH_DAYS
        val last = n - 1
        if (days[last] - days[0] < window) return null
        val today = scoreAt(close, days, last) ?: return null

        var lower = 0; var equal = 0; var count = 0
        for (t in 0 until last) {
            if (days[last] - days[t] > RANK_LOOKBACK_DAYS || days[t] - days[0] < window) continue
            val s = scoreAt(close, days, t)?.score ?: continue
            count++
            if (s < today.score) lower++ else if (s == today.score) equal++
        }
        val rank = if (count >= RANK_MIN_SCORES) 100.0 * (lower + 0.5 * equal) / count else null
        return Result(today.score, today.green, today.red, rank)
    }

    private data class Point(val score: Double, val green: Double, val red: Double)

    private fun scoreAt(close: DoubleArray, days: DoubleArray, t: Int): Point? {
        val p = close[t]
        if (!(p > 0.0)) return null
        var sw = 0.0; var sg = 0.0; var sr = 0.0
        var k = t - 1
        while (k >= 0) {
            val d = (days[t] - days[k]).roundToInt()
            if (d >= weightByDay.size) break
            val w = if (d >= 0) weightByDay[d] else 0.0
            if (w > 0.0 && close[k] > 0.0) {
                val gap = (close[k] - p) / p * 100.0
                sw += w
                if (gap > 0.0) sg += w * gap else sr += w * gap
            }
            k--
        }
        if (sw <= 0.0) return null
        return Point((sg + sr) / sw, sg / sw, sr / sw)
    }

    // Yahoo timestamps are in seconds; some feeds use milliseconds.
    private fun dayOf(ts: Long): Double = if (ts > 100_000_000_000L) ts / 86_400_000.0 else ts / 86_400.0
}
