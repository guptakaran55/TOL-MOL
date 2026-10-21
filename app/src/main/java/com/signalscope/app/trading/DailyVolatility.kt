package com.signalscope.app.trading

import android.util.Log
import com.signalscope.app.network.YahooFinanceClient
import java.util.concurrent.ConcurrentHashMap

/**
 * A STOCK'S OWN DAILY RANGE — the yardsticks a trail can be measured in instead
 * of a flat percentage.
 *
 *  • 14-day ATR: the average size of a day's move, gaps included — the same
 *    measure the order ladders already size their steps with.
 *  • Half the 20-day Bollinger width, (upper − lower) / 2: two standard
 *    deviations of the last twenty daily closes.
 *
 * Both come from daily bars, which change once a day, so each is worked out
 * once per stock per day and kept in memory. The bars themselves come from the
 * shared daily-candle cache, so this usually costs no download at all.
 */
object DailyVolatility {
    private const val TAG = "DailyVolatility"

    data class Vol(val atr: Double, val bbHalf: Double)

    private val cache = ConcurrentHashMap<String, Pair<String, Vol>>()

    /** Null when no daily bars could be had — the caller then holds its last level. */
    fun of(symbol: String, exchange: String): Vol? {
        val day = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()
        val key = "${symbol.uppercase()}|$exchange"
        cache[key]?.let { (d, v) -> if (d == day) return v }
        return try {
            val res = YahooFinanceClient.fetchCandlesCached(symbol, exchange)
            val cs = (res as? YahooFinanceClient.CandleResult.Success)?.candles ?: return null
            val v = compute(cs.map { it.high }, cs.map { it.low }, cs.map { it.close }) ?: return null
            cache[key] = day to v
            v
        } catch (e: Exception) {
            Log.w(TAG, "$symbol daily range failed: ${e.message}"); null
        }
    }

    fun compute(highs: List<Double>, lows: List<Double>, closes: List<Double>): Vol? {
        if (closes.size < 20) return null
        val atr = try {
            com.signalscope.app.util.Indicators.atr(highs, lows, closes, 14)
                .lastOrNull { !it.isNaN() && it > 0.0 } ?: 0.0
        } catch (e: Exception) { 0.0 }
        val bb = ManualTrailingStop.bbHalfWidth(closes)
        if (atr <= 0.0 && bb <= 0.0) return null
        return Vol(atr, bb)
    }
}
