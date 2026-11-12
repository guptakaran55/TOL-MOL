package com.signalscope.app.trading

import android.util.Log
import com.signalscope.app.network.YahooFinanceClient
import java.time.Instant
import java.time.LocalDate

/**
 * THE MARKET GATE (the user, 12 Nov 2026): a MACD-turns algo buys only on days the
 * market's index had its daily MACD rising — Nifty 50 for India, DAX for Germany,
 * S&P 500 for the US. Judged on the index's closes BEFORE today, so it is one answer
 * for the whole day and nothing in it is from the future.
 *
 * Why: over ten years of NSE 500 (RESULTS_MATRIX_india_macd_market_gate.md) India's
 * Macd swing lost 4% a year in 2015–20 and made 12% a year in 2021–26; gated on the
 * Nifty's daily MACD it made +11% and +5%. Unproven — it was the best of six tries —
 * so it runs as a paper copy beside the plain one, to see which holds up.
 *
 * Only new buys are gated. Stop, target and the time limit sell as before.
 */
object MarketGate {
    private const val TAG = "MarketGate"
    const val INDEX_DMACD = "index_dmacd"
    private val INDEX = mapOf("in" to "^NSEI", "de" to "^GDAXI", "us" to "^GSPC")
    private val NAME = mapOf("in" to "Nifty 50", "de" to "DAX", "us" to "S&P 500")
    /** An unreadable answer is asked again after this long. */
    private const val RETRY_MS = 15 * 60_000L

    private data class Known(val day: LocalDate, val up: Boolean?, val at: Long)
    private val known = HashMap<String, Known>()

    fun indexName(base: String): String = NAME[Spaces.baseOf(base.lowercase())] ?: "the market"

    /** Pure: closes oldest first, all before today. Is the MACD(12,26) line higher than
     *  the session before? Null with too little history. */
    fun dailyMacdRising(closes: List<Double>): Boolean? {
        if (closes.size < 35) return null
        val k12 = 2.0 / 13.0; val k26 = 2.0 / 27.0
        var e12 = closes[0]; var e26 = closes[0]
        var prev = 0.0; var line = 0.0
        for ((i, c) in closes.withIndex()) {
            e12 += k12 * (c - e12); e26 += k26 * (c - e26)
            if (i == closes.size - 1) prev = line
            line = e12 - e26
        }
        return line > prev
    }

    /** Is [base]'s index rising today, by its daily MACD? Null when it could not be read —
     *  the caller buys nothing then (asked again in a quarter of an hour). */
    fun indexRising(base0: String, today: LocalDate, nowMs: Long = System.currentTimeMillis()): Boolean? {
        val base = Spaces.baseOf(base0.lowercase())
        val zone = MarketHours.zoneOf(base) ?: return true
        synchronized(known) {
            known[base]?.let { k -> if (k.day == today && (k.up != null || nowMs - k.at < RETRY_MS)) return k.up }
        }
        val sym = INDEX[base] ?: return true
        val up = try {
            YahooFinanceClient.fetchDailyCloses(sym)
                ?.filter { (t, _) -> Instant.ofEpochSecond(t).atZone(zone).toLocalDate().isBefore(today) }
                ?.map { it.second }
                ?.let { dailyMacdRising(it) }
        } catch (e: Exception) { Log.w(TAG, "[$base] index read failed: ${e.message}"); null }
        synchronized(known) { known[base] = Known(today, up, nowMs) }
        return up
    }
}
